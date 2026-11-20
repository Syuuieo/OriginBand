// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.UserManager
import com.originband.link.integration.DeviceCardProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Downloads the Mi Fitness body picture and keeps the last good file. */
object DeviceImageStore {
    private const val FRESH_MS = 30L * 24 * 60 * 60 * 1000
    private const val RETRY_MS = 6L * 60 * 60 * 1000
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val MAX_EDGE = 4096
    private val EXECUTOR: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "band-device-image").apply { isDaemon = true }
    }

    @JvmStatic
    fun requestRefresh(context: Context) {
        val target = context.applicationContext ?: context
        EXECUTOR.execute {
            try {
                if (refreshIfNeeded(target)) BandStateRepository(target).noteDeviceImage()
            } catch (failure: RuntimeException) {
                SessionLog.line(target, "bridge DEVICE_IMAGE_UNAVAILABLE ${failure.javaClass.simpleName}")
            }
        }
    }

    /** Content URI of the current body picture, or empty when this registration has none. */
    @JvmStatic
    fun uri(context: Context): String {
        return try {
            val state = LocalPrefs.open(context, "band-state")
            if (!state.getBoolean("registered", false)) return ""
            val image = LocalPrefs.open(context, "device-image")
            if (state.getString("modelId", "") != image.getString("model", "")) return ""
            val file = File(image.getString("file", ""))
            if (!file.isFile || file.length() == 0L) return ""
            val version = image.getLong("version", 0)
            "content://${DeviceCardProvider.AUTHORITY}/device_image/miband-body-$version.png"
        } catch (_: RuntimeException) {
            ""
        }
    }

    @JvmStatic
    fun file(context: Context): File? {
        if (uri(context).isEmpty()) return null
        return try {
            val stored = File(LocalPrefs.open(context, "device-image").getString("file", ""))
            if (stored.isFile) stored else null
        } catch (_: RuntimeException) {
            null
        }
    }

    @JvmStatic
    fun refreshIfNeeded(context: Context): Boolean {
        val users = context.getSystemService(UserManager::class.java)
        if (users == null || !users.isUserUnlocked) return false
        val state = LocalPrefs.open(context, "band-state")
        if (!state.getBoolean("registered", false)) return false
        val model = state.getString("modelId", "")
        val sku = state.getString("sku", "")
        if (model.isBlank()) return false
        val image = LocalPrefs.open(context, "device-image")
        val same = model == image.getString("model", "") && sku == image.getString("sku", "")
        val samePick = MiProductImage.PICK == image.getString("pick", "")
        val current = File(image.getString("file", ""))
        val fresh = same && samePick && current.isFile && current.length() > 0L &&
            System.currentTimeMillis() - image.getLong("fetchedAtMs", 0) < FRESH_MS
        if (fresh) return false
        val lastAttempt = image.getLong("lastAttemptMs", 0)
        val attempted = model == image.getString("attemptModel", "") &&
            sku == image.getString("attemptSku", "") &&
            samePick
        if (attempted && lastAttempt > 0 && System.currentTimeMillis() - lastAttempt < RETRY_MS) return false
        image.edit()
            .putLong("lastAttemptMs", System.currentTimeMillis())
            .putString("attemptModel", model)
            .putString("attemptSku", sku)
            .commit()
        return try {
            val config = MiFitnessDatabase.productConfig(context)
            val choice = MiProductImage.choose(config, model, sku)
            if (choice == null) {
                SessionLog.line(context, "bridge DEVICE_IMAGE_UNAVAILABLE MI_PRODUCT_CONFIG_MISSING")
                return false
            }
            val encoded = download(choice.url)
            val version = image.getLong("version", 0) + 1
            val dir = File(context.filesDir, "device-image")
            if (!dir.isDirectory && !dir.mkdirs()) {
                SessionLog.line(context, "bridge DEVICE_IMAGE_UNAVAILABLE DEVICE_IMAGE_STORE")
                return false
            }
            val next = File(dir, "body-$version.png")
            val temp = File(dir, "body-$version.tmp")
            FileOutputStream(temp).use { out ->
                out.write(encoded)
            }
            if (!temp.renameTo(next)) {
                temp.delete()
                SessionLog.line(context, "bridge DEVICE_IMAGE_UNAVAILABLE DEVICE_IMAGE_STORE")
                return false
            }
            val previous = image.getString("file", "")
            if (!image.edit()
                    .putString("model", model)
                    .putString("sku", sku)
                    .putString("pick", MiProductImage.PICK)
                    .putString("url", choice.url)
                    .putString("source", choice.source)
                    .putString("file", next.absolutePath)
                    .putLong("fetchedAtMs", System.currentTimeMillis())
                    .putLong("version", version)
                    .commit()
            ) {
                next.delete()
                SessionLog.line(context, "bridge DEVICE_IMAGE_UNAVAILABLE DEVICE_IMAGE_STORE")
                return false
            }
            if (previous.isNotEmpty() && previous != next.absolutePath) File(previous).delete()
            SessionLog.line(context, "bridge DEVICE_IMAGE source=${choice.source} version=$version")
            true
        } catch (failure: MiFitnessDatabase.Failure) {
            SessionLog.line(context, "bridge DEVICE_IMAGE_UNAVAILABLE ${failure.code}")
            false
        } catch (failure: Exception) {
            SessionLog.line(context, "bridge DEVICE_IMAGE_UNAVAILABLE ${failure.javaClass.simpleName}")
            false
        }
    }

    @Throws(Exception::class)
    private fun download(address: String): ByteArray {
        val url = URL(address)
        if (url.protocol != "https") throw IllegalStateException("DEVICE_IMAGE_URL")
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = true
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw IllegalStateException("DEVICE_IMAGE_HTTP")
            if (connection.url == null || connection.url.protocol != "https") {
                throw IllegalStateException("DEVICE_IMAGE_URL")
            }
            val type = connection.contentType
            if (type == null || !type.lowercase(Locale.ROOT).startsWith("image/")) {
                throw IllegalStateException("DEVICE_IMAGE_TYPE")
            }
            val raw = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                var total = 0
                while (total < MAX_BYTES) {
                    val read = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES - total))
                    if (read < 0) break
                    raw.write(buffer, 0, read)
                    total += read
                }
                if (input.read() >= 0) throw IllegalStateException("DEVICE_IMAGE_SIZE")
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val bytes = raw.toByteArray()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                bounds.outWidth > MAX_EDGE || bounds.outHeight > MAX_EDGE
            ) {
                throw IllegalStateException("DEVICE_IMAGE_BOUNDS")
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalStateException("DEVICE_IMAGE_DECODE")
            try {
                val png = ByteArrayOutputStream()
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)) {
                    throw IllegalStateException("DEVICE_IMAGE_DECODE")
                }
                return png.toByteArray()
            } finally {
                bitmap.recycle()
            }
        } finally {
            connection.disconnect()
        }
    }
}
