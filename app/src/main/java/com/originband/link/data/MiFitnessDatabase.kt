// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Copies com.mi.health device_db with root and reads it in this app. The copy is deleted. */
object MiFitnessDatabase {
    private const val SOURCE = "/data/data/com.mi.health/databases"
    private val SCRIPT_PATH_REGEX = Regex("[A-Za-z0-9_./-]+")
    private val LOCALE_TOKEN_REGEX = Regex("[A-Za-z0-9_-]{1,32}")
    private val USER_ID_REGEX = Regex("[0-9]{5,}")
    private val LINE_BREAK_REGEX = Regex("\\R")

    class Snapshot(
        @JvmField val userId: String,
        @JvmField val region: String,
        @JvmField val details: List<MiFitnessDeviceRows.Detail>,
    )

    class Failure(
        @JvmField val code: String,
    ) : Exception()

    /** Locale product cache written by Mi Fitness, or the newest `product_*.json`. */
    @JvmStatic
    @Throws(Failure::class)
    fun productConfig(context: Context): String {
        val dir = File(context.noBackupFilesDir, "mihealth-product")
        if (!dir.isDirectory && !dir.mkdirs()) throw Failure("MI_PRODUCT_CONFIG_MISSING")
        val copy = File(dir, "product.json")
        try {
            val script = File(dir, "product.sh")
            writeProductScript(script, dir.absolutePath)
            try {
                execute(script.absolutePath)
            } catch (failure: Failure) {
                if (failure.code == "ROOT_ACTION_TIMEOUT") throw failure
                throw Failure("MI_PRODUCT_CONFIG_MISSING")
            }
            if (!copy.isFile || copy.length() == 0L || copy.length() > 16L * 1024 * 1024) {
                throw Failure("MI_PRODUCT_CONFIG_MISSING")
            }
            val bytes = Files.readAllBytes(copy.toPath())
            return String(bytes, StandardCharsets.UTF_8)
        } catch (_: IOException) {
            throw Failure("MI_PRODUCT_CONFIG_MISSING")
        } finally {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    @JvmStatic
    @Throws(Failure::class)
    fun read(context: Context): Snapshot {
        val dir = File(context.noBackupFilesDir, "mihealth-read")
        if (!dir.isDirectory && !dir.mkdirs()) throw Failure("MI_DATABASE_UNREADABLE")
        return try {
            val userId = copy(dir)
            open(dir, userId)
        } finally {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    @Throws(Failure::class)
    private fun open(dir: File, userId: String): Snapshot {
        val device = File(dir, "device_db")
        if (!device.isFile) throw Failure("MI_DATABASE_MISSING")
        var database: SQLiteDatabase? = null
        var profile: SQLiteDatabase? = null
        try {
            // The copy is private. Read-write applies the WAL. The live shm is not copied:
            // it carries the official app's lock and the open would wait on that process.
            database = SQLiteDatabase.openDatabase(device.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            database.rawQuery("PRAGMA busy_timeout=2000", null).use { }
            val details = ArrayList<MiFitnessDeviceRows.Detail>()
            database.rawQuery("SELECT model, name, productId, did, detail FROM device", null).use { cursor ->
                while (cursor.moveToNext()) {
                    details.add(
                        MiFitnessDeviceRows.Detail(
                            cursor.getString(0),
                            cursor.getString(1),
                            if (cursor.isNull(2)) "" else cursor.getLong(2).toString(),
                            cursor.getString(3),
                            cursor.getString(4),
                        ),
                    )
                }
            }
            var region = ""
            val users = File(dir, "fitness_user")
            if (users.isFile) {
                profile = SQLiteDatabase.openDatabase(users.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
                profile.rawQuery("PRAGMA busy_timeout=2000", null).use { }
                profile.rawQuery("SELECT region FROM user_info_profile LIMIT 1", null).use { cursor ->
                    if (cursor.moveToFirst()) {
                        region = cursor.getString(0)?.trim() ?: ""
                    }
                }
            }
            if (region.length > 8) region = ""
            return Snapshot(userId, region, details)
        } catch (_: RuntimeException) {
            throw Failure("MI_DATABASE_UNREADABLE")
        } finally {
            database?.close()
            profile?.close()
        }
    }

    @Throws(Failure::class)
    private fun copy(dir: File): String {
        val script = File(dir, "copy.sh")
        // KernelSU runs su -c through another shell. A quoted command is split and exits 1.
        // The script path has no spaces, so su receives one plain argument.
        writeScript(script, dir.absolutePath)
        val text = execute(script.absolutePath)
        for (line in text.split(LINE_BREAK_REGEX)) {
            val trimmed = line.trim()
            if (USER_ID_REGEX.matches(trimmed)) return trimmed
        }
        return ""
    }

    @Throws(Failure::class)
    private fun writeProductScript(script: File, dest: String) {
        if (!SCRIPT_PATH_REGEX.matches(dest)) throw Failure("MI_PRODUCT_CONFIG_MISSING")
        val language = localeToken(Locale.getDefault().toLanguageTag())
        val underscored = localeToken(language.replace('-', '_'))
        var names = ""
        if (language.isNotEmpty()) names += " product_$language.json"
        if (underscored.isNotEmpty() && underscored != language) names += " product_$underscored.json"
        val body = "set -eu\n" +
            "cfg=/data/data/com.mi.health/files/config\n" +
            "test -d \"\$cfg\"\n" +
            "pick=\n" +
            "for name in$names; do\n" +
            "  if [ -f \"\$cfg/\$name\" ]; then pick=\"\$cfg/\$name\"; break; fi\n" +
            "done\n" +
            "if [ -z \"\$pick\" ]; then pick=\$(ls -1t \"\$cfg\"/product_*.json | head -n 1); fi\n" +
            "test -n \"\$pick\"\n" +
            "rm -f $dest/product.json\n" +
            "cp -a \"\$pick\" $dest/product.json\n" +
            "owner=\$(stat -c %u $dest)\n" +
            "group=\$(stat -c %g $dest)\n" +
            "chown \"\$owner:\$group\" $dest/product.json\n" +
            "chmod 600 $dest/product.json\n"
        try {
            FileOutputStream(script).use { out ->
                out.write(body.toByteArray(StandardCharsets.UTF_8))
            }
        } catch (_: IOException) {
            throw Failure("MI_PRODUCT_CONFIG_MISSING")
        }
    }

    private fun localeToken(raw: String?): String {
        if (raw == null) return ""
        val clean = raw.trim()
        return if (LOCALE_TOKEN_REGEX.matches(clean)) clean else ""
    }

    @Throws(Failure::class)
    private fun writeScript(script: File, dest: String) {
        if (!SCRIPT_PATH_REGEX.matches(dest)) throw Failure("MI_DATABASE_UNREADABLE")
        val source = SOURCE
        val body = "set -eu\n" +
            "test -f $source/device_db\n" +
            "rm -f $dest/device_db $dest/device_db-wal $dest/device_db-shm " +
            "$dest/fitness_user $dest/fitness_user-wal $dest/fitness_user-shm\n" +
            "cp -a $source/device_db $dest/device_db\n" +
            "if [ -f $source/device_db-wal ]; then cp -a $source/device_db-wal $dest/device_db-wal; fi\n" +
            "if [ -f $source/fitness_user ]; then cp -a $source/fitness_user $dest/fitness_user; fi\n" +
            "if [ -f $source/fitness_user-wal ]; then cp -a $source/fitness_user-wal $dest/fitness_user-wal; fi\n" +
            "owner=\$(stat -c %u $dest)\n" +
            "group=\$(stat -c %g $dest)\n" +
            "chown \"\$owner:\$group\" $dest/*\n" +
            "chmod 600 $dest/*\n" +
            "ls -1 $source | grep -E '^[0-9]{5,}\$' | head -n 1\n"
        try {
            FileOutputStream(script).use { out ->
                out.write(body.toByteArray(StandardCharsets.UTF_8))
            }
        } catch (_: IOException) {
            throw Failure("MI_DATABASE_UNREADABLE")
        }
    }

    @Throws(Failure::class)
    private fun execute(scriptPath: String): String {
        if (!SCRIPT_PATH_REGEX.matches(scriptPath)) throw Failure("MI_DATABASE_UNREADABLE")
        var process: Process? = null
        try {
            // -M leaves the app mount namespace, where other apps' /data/data is hidden.
            process = ProcessBuilder("su", "-M", "-c", "sh $scriptPath")
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw Failure("ROOT_ACTION_TIMEOUT")
            }
            val text = readLimited(process.inputStream, 4096)
            if (process.exitValue() != 0) {
                throw Failure(if (text.contains("No such file")) "MI_DATABASE_MISSING" else "ROOT_ACTION_FAILED")
            }
            return text
        } catch (_: IOException) {
            throw Failure("ROOT_ACTION_FAILED")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw Failure("ROOT_ACTION_FAILED")
        } finally {
            process?.destroy()
        }
    }

    @Throws(IOException::class)
    private fun readLimited(input: InputStream, maxOutput: Int): String {
        val buf = ByteArray(1024)
        val out = ByteArrayOutputStream()
        var total = 0
        while (total < maxOutput) {
            val n = input.read(buf, 0, minOf(buf.size, maxOutput - total))
            if (n < 0) return out.toString(StandardCharsets.UTF_8)
            out.write(buf, 0, n)
            total += n
        }
        return out.toString(StandardCharsets.UTF_8)
    }
}
