// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import android.content.Context
import android.os.UserManager
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Credential-encrypted storage, no AndroidKeyStore.
 *
 * History: v1/v2 encrypted the binding with an AndroidKeyStore key. On ColorOS the keystore
 * alias silently disappears (OTA, system cleanup), which permanently bricked every saved
 * binding ("BINDING_KEY_UNAVAILABLE" loop). The binding now lives in a plaintext JSON file
 * inside the credential-encrypted no-backup directory: not readable by other apps, not
 * backed up, and immune to keystore loss. A legacy binding.enc is migrated when its key
 * still works, otherwise it is discarded and the user re-imports once.
 */
class BindingStore(private val context: Context) {
    private val file: AtomicFile
    private val legacyFile: File

    data class Identity(
        @JvmField val deviceId: String,
        @JvmField val did: String,
        @JvmField val address: String,
        @JvmField val model: String,
    ) {
        fun deviceId(): String = deviceId

        fun did(): String = did

        fun address(): String = address

        fun model(): String = model
    }

    private data class IdentitySnapshot(
        val path: String,
        val modified: Long,
        val length: Long,
        val identity: Identity,
    )

    init {
        if (context.isDeviceProtectedStorage) throw IllegalArgumentException("CE_STORAGE_REQUIRED")
        file = AtomicFile(File(context.noBackupFilesDir, "binding.json"))
        legacyFile = File(context.noBackupFilesDir, "binding.enc")
    }

    @Synchronized
    @Throws(Exception::class)
    fun save(value: JSONObject) {
        requireUnlocked()
        val plaintext = value.toString().toByteArray(StandardCharsets.UTF_8)
        try {
            write(plaintext)
            publicIdentity = null
            discardLegacy()
        } finally {
            Arrays.fill(plaintext, 0.toByte())
        }
    }

    @Synchronized
    @Throws(Exception::class)
    fun read(): JSONObject? {
        requireUnlocked()
        if (file.baseFile.exists()) return parse(readFile())
        if (!legacyFile.exists()) return null
        val legacy = migrateLegacy()
        if (legacy != null) {
            save(legacy)
            SessionLog.line(context, "bridge BINDING_MIGRATED_PLAINTEXT")
            return legacy
        }
        return null
    }

    /** An established session can check public identity without decrypting its token while locked. */
    @Throws(Exception::class)
    fun readIdentity(): Identity {
        requireUnlocked()
        var known = publicIdentity
        val source = file.baseFile
        if (known != null && known.path == source.absolutePath &&
            known.modified == source.lastModified() && known.length == source.length()
        ) {
            return known.identity
        }
        read() ?: throw IllegalStateException("UNPROVISIONED")
        known = publicIdentity
        return known?.identity ?: throw IllegalStateException("DEVICE_IDENTITY_UNCONFIRMED")
    }

    private fun cacheIdentity(binding: JSONObject) {
        publicIdentity = null
        try {
            val identity = Identity(
                BandStateRepository.deviceId(binding),
                binding.optString("did", ""),
                binding.optString("address", ""),
                binding.optString("model", ""),
            )
            val source = file.baseFile
            publicIdentity = IdentitySnapshot(source.absolutePath, source.lastModified(), source.length(), identity)
        } catch (_: Exception) {
            // Incomplete imports stay available to configuration. A live identity check waits for a complete import.
        }
    }

    private fun requireUnlocked() {
        val users = context.getSystemService(UserManager::class.java)
        if (users == null || !users.isUserUnlocked) {
            throw IllegalStateException("USER_LOCKED")
        }
    }

    @Throws(Exception::class)
    private fun parse(plaintext: ByteArray): JSONObject {
        try {
            val binding = JSONObject(String(plaintext, StandardCharsets.UTF_8))
            cacheIdentity(binding)
            return binding
        } finally {
            Arrays.fill(plaintext, 0.toByte())
        }
    }

    @Throws(Exception::class)
    private fun readFile(): ByteArray {
        if (file.baseFile.length() > 65536) throw IllegalStateException("INVALID_BINDING_SIZE")
        return file.readFully()
    }

    @Throws(Exception::class)
    private fun write(plaintext: ByteArray) {
        var output: FileOutputStream? = null
        try {
            output = file.startWrite()
            output.write(plaintext)
            file.finishWrite(output)
        } catch (failure: Exception) {
            if (output != null) file.failWrite(output)
            throw failure
        }
    }

    private fun discardLegacy() {
        if (legacyFile.exists() && legacyFile.delete()) {
            SessionLog.line(context, "bridge BINDING_KEY_RESET")
        }
    }

    /** Best-effort one-shot migration of the old keystore-encrypted binding. */
    private fun migrateLegacy(): JSONObject? {
        try {
            val plaintext = legacyDecrypt() ?: return null
            return JSONObject(String(plaintext, StandardCharsets.UTF_8))
        } catch (failed: Exception) {
            SessionLog.line(context, "bridge BINDING_MIGRATE_FAILED " + failed.javaClass.simpleName)
            return null
        } finally {
            // Key is unreadable or envelope broken: the binding is unrecoverable by design.
            discardLegacy()
        }
    }

    @Throws(Exception::class)
    private fun legacyDecrypt(): ByteArray? {
        if (legacyFile.length() > 65536) return null
        val envelope = AtomicFile(legacyFile).readFully()
        if (envelope.size < 29 || envelope[0] != 1.toByte()) return null
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        for (alias in arrayOf(LEGACY_ALIAS_V2, LEGACY_ALIAS_V1)) {
            if (!store.containsAlias(alias)) continue
            val key = store.getKey(alias, null) as? SecretKey ?: continue
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope, 1, 12))
            cipher.updateAAD(LEGACY_AAD)
            val plaintext = cipher.doFinal(envelope, 13, envelope.size - 13)
            store.deleteEntry(alias)
            return plaintext
        }
        return null
    }

    companion object {
        private const val LEGACY_ALIAS_V2 = "oplusband.binding.v2"
        private const val LEGACY_ALIAS_V1 = "oplusband.binding.v1"
        private val LEGACY_AAD = "com.originband.link.binding.v1".toByteArray(StandardCharsets.UTF_8)

        @Volatile
        private var publicIdentity: IdentitySnapshot? = null
    }
}
