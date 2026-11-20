// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import android.content.Context
import android.util.Log
import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Ownership state lives in a json file under no_backup.
 *
 * <p>libxposed API 102 leaves {@link Context#getSharedPreferences} in the app data directory, not at
 * {@code /data/misc/apexdata}. Modules through 1.6.8 did, and the old OFFICIAL copy is still there.
 * An empty json file is imported once from {@code shared_prefs} in the app data directory.
 * {@code RawFitnessFileStore} still opens {@code oplusband-history} directly, because that step-metric
 * flag is already in the user's SharedPreferences.</p>
 */
class LocalPrefs private constructor(
    private val file: File,
    @Volatile private var json: JSONObject,
) {
    /** Present means a non-empty file is already on disk. A parse failure leaves that file in place, so open keeps it instead of the old SharedPreferences. */
    class Stored(
        @JvmField val json: JSONObject,
        @JvmField val present: Boolean,
    )

    private data class CacheEntry(
        val modifiedMs: Long,
        val length: Long,
        val raw: String,
        val snapshot: JSONObject,
    )

    companion object {
        private val cache = ConcurrentHashMap<String, CacheEntry>()

        @JvmStatic
        fun open(context: Context, name: String): LocalPrefs {
            val targetContext = context.applicationContext ?: context
            val file = File(targetContext.noBackupFilesDir, "$name.json")
            val stored = readCached(file)
            var json = stored.json
            if (!stored.present) {
                json = importShared(targetContext, name)
                val disk = importXml(File(targetContext.dataDir, "shared_prefs/$name.xml"))
                if (prefer(disk, json, name)) json = disk
                if (json.length() > 0) writeJson(file, json)
            }
            return LocalPrefs(file, json)
        }

        private fun readCached(file: File): Stored {
            val path = file.absolutePath
            if (!file.isFile) {
                cache.remove(path)
                return Stored(JSONObject(), false)
            }
            val len = file.length()
            if (len == 0L) {
                cache.remove(path)
                return Stored(JSONObject(), false)
            }
            val modified = file.lastModified()
            val hit = cache[path]
            if (hit != null && hit.modifiedMs == modified && hit.length == len) {
                return Stored(hit.snapshot, true)
            }
            return readStored(file)
        }

        @JvmStatic
        fun readStored(file: File): Stored {
            val path = file.absolutePath
            if (!file.isFile || file.length() == 0L) {
                cache.remove(path)
                return Stored(JSONObject(), false)
            }
            val len = file.length()
            val modified = file.lastModified()
            return try {
                val bytes = Files.readAllBytes(file.toPath())
                if (bytes.isEmpty()) {
                    cache.remove(path)
                    Stored(JSONObject(), false)
                } else {
                    val raw = String(bytes, StandardCharsets.UTF_8)
                    val parsed = JSONObject(raw)
                    cache[path] = CacheEntry(modified, len, raw, parsed)
                    Stored(parsed, true)
                }
            } catch (failed: Exception) {
                cache.remove(path)
                note("LOCAL_PREFS_READ_FAILED ${file.name}", failed)
                Stored(JSONObject(), true)
            }
        }

        @JvmStatic
        fun writeJson(file: File, json: JSONObject): Boolean {
            val dir = file.parentFile
            if (dir != null) dir.mkdirs()
            val tmp = File("${file.path}.tmp")
            return try {
                val raw = json.toString()
                Files.write(tmp.toPath(), raw.toByteArray(StandardCharsets.UTF_8))
                try {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                cache[file.absolutePath] = CacheEntry(file.lastModified(), file.length(), raw, JSONObject(raw))
                true
            } catch (failed: Exception) {
                tmp.delete()
                cache.remove(file.absolutePath)
                note("LOCAL_PREFS_WRITE_FAILED ${file.name}", failed)
                false
            }
        }

        private fun prefer(disk: JSONObject, current: JSONObject, name: String): Boolean {
            if (disk.length() == 0) return false
            if ("ownership" == name && "NATIVE" == disk.optString("mode") && "NATIVE" != current.optString("mode")) {
                return true
            }
            if ("band-state" == name && disk.optBoolean("registered") &&
                disk.optLong("revision", 0) >= current.optLong("revision", 0)
            ) {
                return true
            }
            return current.length() == 0
        }

        private fun note(line: String, failed: Exception) {
            try {
                Log.w("OriginBand", line, failed)
            } catch (_: RuntimeException) {
                // Unit tests run without a working android.util.Log.
            }
        }

        private fun importShared(context: Context, name: String): JSONObject {
            val json = JSONObject()
            try {
                val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                for ((key, value) in prefs.all) {
                    if (value != null) json.put(key, value)
                }
            } catch (_: Exception) {
            }
            return json
        }

        private fun importXml(file: File): JSONObject {
            val json = JSONObject()
            if (!file.isFile) return json
            try {
                FileInputStream(file).use { input ->
                    val parser = Xml.newPullParser()
                    parser.setInput(input, StandardCharsets.UTF_8.name())
                    var type: String? = null
                    var key: String? = null
                    var event = parser.eventType
                    while (event != XmlPullParser.END_DOCUMENT) {
                        when (event) {
                            XmlPullParser.START_TAG -> {
                                type = parser.name
                                key = parser.getAttributeValue(null, "name")
                                if ("boolean" == type && key != null) {
                                    json.put(key, parser.getAttributeValue(null, "value").toBoolean())
                                } else if ("int" == type && key != null) {
                                    json.put(key, parser.getAttributeValue(null, "value").toInt())
                                } else if ("long" == type && key != null) {
                                    json.put(key, parser.getAttributeValue(null, "value").toLong())
                                }
                            }
                            XmlPullParser.TEXT -> if ("string" == type && key != null) {
                                json.put(key, parser.text)
                            }
                            XmlPullParser.END_TAG -> {
                                type = null
                                key = null
                            }
                        }
                        event = parser.next()
                    }
                }
            } catch (_: Exception) {
            }
            return json
        }
    }

    fun getBoolean(key: String, fallback: Boolean): Boolean = json.optBoolean(key, fallback)

    fun getString(key: String, fallback: String): String {
        return if (json.has(key)) json.optString(key, fallback) else fallback
    }

    fun getInt(key: String, fallback: Int): Int = json.optInt(key, fallback)

    fun getLong(key: String, fallback: Long): Long = json.optLong(key, fallback)

    fun edit(): Editor = Editor()

    inner class Editor internal constructor() {
        private val next: JSONObject = try {
            val raw = cache[file.absolutePath]?.raw ?: json.toString()
            JSONObject(raw)
        } catch (failed: Exception) {
            throw IllegalStateException("STATE_COPY_FAILED", failed)
        }

        fun putBoolean(key: String, value: Boolean): Editor = put(key, value)
        fun putString(key: String, value: String): Editor = put(key, value)
        fun putInt(key: String, value: Int): Editor = put(key, value)
        fun putLong(key: String, value: Long): Editor = put(key, value)

        fun remove(key: String): Editor {
            next.remove(key)
            return this
        }

        private fun put(key: String, value: Any): Editor {
            return try {
                next.put(key, value)
                this
            } catch (failed: Exception) {
                throw IllegalStateException("STATE_WRITE_FAILED", failed)
            }
        }

        fun commit(): Boolean {
            synchronized(this@LocalPrefs) {
                if (!writeJson(file, next)) return false
                json = next
                return true
            }
        }
    }
}
