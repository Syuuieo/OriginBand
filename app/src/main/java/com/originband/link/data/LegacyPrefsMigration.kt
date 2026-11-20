// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Moves SharedPreferences left by the old module out of the LSPosed preference directory.
 *
 * <p>Through 1.6.8 the module declared {@code xposedminversion=93}, so LSPosed pointed
 * {@code getPreferencesDir()} at {@code /data/misc/&lt;id&gt;/prefs/&lt;package&gt;}.
 * A libxposed API 102 module is not redirected, so the same names open an empty
 * {@code shared_prefs} in the data directory. Each old file is copied once, before
 * anything opens a preference. A file already in {@code shared_prefs} stays as it is.</p>
 */
object LegacyPrefsMigration {
    private const val TAG = "OriginBand"
    private const val MARKER = "legacy-prefs-migrated"
    private const val ROOT_TIMEOUT_SECONDS = 4L
    private val PACKAGE_REGEX = Regex("[A-Za-z0-9._]+")
    private val FILE_NAME_REGEX = Regex("[A-Za-z0-9._-]+\\.xml")

    @JvmStatic
    fun run(context: Context) {
        val marker = File(context.noBackupFilesDir, MARKER)
        if (marker.exists()) return
        val pkg = context.packageName
        if (!PACKAGE_REGEX.matches(pkg)) return
        val target = File(context.dataDir, "shared_prefs")
        try {
            val listing = root(
                "for f in /data/misc/*/prefs/$pkg/*.xml /data/misc/*/*/prefs/$pkg" +
                    "/*.xml; do [ -f \"\$f\" ] && echo \"\$f\"; done; true",
            )
            var copied = 0
            for (path in lines(listing)) {
                val name = path.substring(path.lastIndexOf('/') + 1)
                if (!FILE_NAME_REGEX.matches(name)) continue
                val destination = File(target, name)
                if (destination.exists()) continue
                val content = rootBytes("cat '${path.replace("'", "'\\''")}'")
                if (content.isEmpty()) continue
                write(target, destination, content)
                copied++
            }
            if (!marker.createNewFile() && !marker.exists()) throw IOException("MARKER_NOT_WRITTEN")
            Log.i(TAG, "LEGACY_PREFS_MIGRATED files=$copied")
        } catch (failure: IOException) {
            // No root yet, or root timed out. The next process start tries again.
            Log.i(TAG, "LEGACY_PREFS_MIGRATION_DEFERRED ${failure.message}")
        } catch (failure: RuntimeException) {
            // No root yet, or root timed out. The next process start tries again.
            Log.i(TAG, "LEGACY_PREFS_MIGRATION_DEFERRED ${failure.message}")
        }
    }

    @JvmStatic
    fun lines(listing: String?): List<String> {
        if (listing == null) return emptyList()
        val result = ArrayList<String>()
        for (line in listing.split('\n')) {
            val path = line.trim()
            if (path.startsWith("/data/misc/") && path.endsWith(".xml") && !path.contains("..")) {
                result.add(path)
            }
        }
        return result
    }

    @Throws(IOException::class)
    private fun write(directory: File, destination: File, content: ByteArray) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("PREFS_DIR_UNAVAILABLE")
        val temporary = File(directory, "${destination.name}.migrating")
        FileOutputStream(temporary).use { out ->
            out.write(content)
            out.fd.sync()
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            throw IOException("PREFS_RENAME_FAILED")
        }
    }

    @Throws(IOException::class)
    private fun root(command: String): String = String(rootBytes(command), StandardCharsets.UTF_8)

    @Throws(IOException::class)
    private fun rootBytes(command: String): ByteArray {
        val process = ProcessBuilder("su", "-c", command).start()
        try {
            process.outputStream.close()
            val output = ByteArrayOutputStream()
            val reader = Thread({ copy(process.inputStream, output) }, "OplusBandPrefsMigration")
            reader.start()
            if (!process.waitFor(ROOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw IOException("ROOT_TIMEOUT")
            reader.join(1_000L)
            if (process.exitValue() != 0) throw IOException("ROOT_DENIED")
            synchronized(output) {
                return output.toByteArray()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("ROOT_INTERRUPTED")
        } finally {
            process.destroy()
        }
    }

    private fun copy(input: InputStream, output: ByteArrayOutputStream) {
        val buffer = ByteArray(8192)
        try {
            input.use { stream ->
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    synchronized(output) {
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (_: IOException) {
            // The process ended or timed out.
        }
    }
}
