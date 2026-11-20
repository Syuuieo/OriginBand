// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Local session trace. A line carries the event name. Tokens, MAC addresses, nonces, and ciphertext stay in the caller. */
object SessionLog {
    const val START_MARK = "--- module start "
    const val KEPT_STARTS = 3
    private const val MAX_BYTES = 256L * 1024
    private const val BUNDLE = "band-debug"
    private const val CAPTURE_FILES = 3
    private const val CAPTURE_BYTES = 24L * 1024 * 1024
    private val LOCK = Any()
    private val MAC_REGEX = Regex("(?i)[0-9a-f]{2}([:-][0-9a-f]{2}){5}")
    private val CNTRL_REGEX = Regex("[\\p{Cntrl}]+")

    @Volatile
    private var started = false

    /** One marker per process. Older module starts beyond the newest three are deleted. */
    @JvmStatic
    fun start(context: Context?) {
        if (context == null || started) return
        started = true
        synchronized(LOCK) {
            try {
                val file = logFile(context)
                var existing = if (file.isFile) {
                    String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                } else {
                    ""
                }
                if (existing.isNotBlank() && !existing.contains(START_MARK)) {
                    existing = "${START_MARK}legacy ---\n$existing"
                }
                val next = retain("$existing$START_MARK${Instant.now()} ---\n")
                Files.write(file.toPath(), next.toByteArray(StandardCharsets.UTF_8))
            } catch (_: IOException) {
            }
        }
    }

    /** Drops MAC-shaped addresses and control characters before a line is stored. */
    @JvmStatic
    fun redact(value: String?): String {
        if (value == null || value.isBlank()) return ""
        val clean = value.replace(MAC_REGEX, "mac").replace(CNTRL_REGEX, " ").trim()
        return if (clean.length > 160) clean.substring(0, 160) else clean
    }

    @JvmStatic
    fun line(context: Context?, message: String?) {
        if (context == null || message == null || message.isBlank()) return
        val row = "${Instant.now()} ${message.replace('\n', ' ').replace('\r', ' ')}\n"
        val bytes = row.toByteArray(StandardCharsets.UTF_8)
        synchronized(LOCK) {
            try {
                val file = logFile(context)
                if (file.length() + bytes.size > MAX_BYTES && file.isFile) {
                    val kept = retain(String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) + row)
                    FileOutputStream(file, false).use { out ->
                        out.write(kept.toByteArray(StandardCharsets.UTF_8))
                    }
                    return
                }
                FileOutputStream(file, true).use { out ->
                    out.write(bytes)
                }
            } catch (_: IOException) {
            }
        }
    }

    /**
     * One archive for analysis: the session trace, the checked transport profile, and the
     * newest protocol captures. The captures are the band frames the debug window recorded.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun export(context: Context): File {
        val dir = File(context.cacheDir, "debug")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("DEBUG_LOG_UNAVAILABLE")
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())
        val out = File(dir, "$BUNDLE-$stamp.zip")
        val text = StringBuilder()
        text.append("miband-oplusbridge debug\n")
        val live = File(context.noBackupFilesDir, "live-status.txt")
        if (live.isFile && live.length() < 512) {
            text.append("live-status ")
                .append(String(Files.readAllBytes(live.toPath()), StandardCharsets.UTF_8).trim())
                .append('\n')
        }
        val log = logFile(context)
        if (log.isFile) text.append(String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8))
        val trace = retain(text.toString()).toByteArray(StandardCharsets.UTF_8)
        try {
            ZipOutputStream(FileOutputStream(out)).use { zip ->
                put(zip, "band-debug.txt", trace)
                put(zip, "transport-observation.json", transport(context).toByteArray(StandardCharsets.UTF_8))
                put(zip, "README.txt", readme().toByteArray(StandardCharsets.UTF_8))
                for (capture in pickCaptures(captureFiles(context), CAPTURE_FILES, CAPTURE_BYTES)) {
                    put(zip, "captures/${capture.name}", capture)
                }
            }
        } catch (failed: IOException) {
            out.delete()
            throw failed
        }
        dir.listFiles { _, name -> name.startsWith("$BUNDLE-") && name.endsWith(".zip") }
            ?.forEach { bundle ->
                if (bundle != out) bundle.delete()
            }
        return out
    }

    private fun transport(context: Context): String {
        val observation: JSONObject = TransportObservation.read(context)
        return try {
            observation.toString(2)
        } catch (_: JSONException) {
            observation.toString()
        }
    }

    /** Newest captures first, bounded by both count and total bytes. */
    @JvmStatic
    fun pickCaptures(files: Array<File>?, maxFiles: Int, maxBytes: Long): List<File> {
        if (files == null || files.isEmpty() || maxFiles < 1 || maxBytes <= 0) return emptyList()
        val sorted = files.sortedByDescending { it.lastModified() }
        val picked = ArrayList<File>()
        var total = 0L
        for (file in sorted) {
            if (picked.size >= maxFiles) break
            val size = file.length()
            if (size <= 0L || total + size > maxBytes) continue
            picked.add(file)
            total += size
        }
        return picked
    }

    private fun captureFiles(context: Context): Array<File>? {
        val directory = File(context.noBackupFilesDir, "protocol-captures")
        return directory.listFiles { _, name -> name.startsWith("capture-") && name.endsWith(".jsonl") }
    }

    private fun readme(): String =
        "miband-oplusbridge 调试包\n" +
            "band-debug.txt：会话日志（已去除 MAC 与控制字符）\n" +
            "transport-observation.json：最近一次连接的传输参数（SPP/GATT、鉴权分支、RFCOMM）\n" +
            "captures/*.jsonl：协议消息采集，一行一条，payloadBase64 是原始帧；\n" +
            "  tx/rx 是方向，capturedAtMs/elapsedRealtimeNs 是宿主时间，receivedAtMs 是模块落盘时间。\n"

    @Throws(IOException::class)
    private fun put(zip: ZipOutputStream, name: String, content: ByteArray) {
        val entry = ZipEntry(name).apply { time = System.currentTimeMillis() }
        zip.putNextEntry(entry)
        zip.write(content)
        zip.closeEntry()
    }

    @Throws(IOException::class)
    private fun put(zip: ZipOutputStream, name: String, source: File) {
        val entry = ZipEntry(name).apply { time = source.lastModified() }
        zip.putNextEntry(entry)
        FileInputStream(source).use { input ->
            input.transferTo(zip)
        }
        zip.closeEntry()
    }

    /** Keeps the newest `keep` module starts. A single start that is still too long is cut from the tail. */
    @JvmStatic
    fun retain(text: String): String {
        var kept = keepLastStarts(text, KEPT_STARTS)
        var raw = kept.toByteArray(StandardCharsets.UTF_8)
        while (raw.size > MAX_BYTES && countStarts(kept) > 1) {
            kept = keepLastStarts(kept, countStarts(kept) - 1)
            raw = kept.toByteArray(StandardCharsets.UTF_8)
        }
        if (raw.size <= MAX_BYTES) return kept
        val from = raw.size - MAX_BYTES.toInt()
        var tail = String(raw, from, raw.size - from, StandardCharsets.UTF_8)
        val newline = tail.indexOf('\n')
        if (newline >= 0 && newline + 1 < tail.length) tail = tail.substring(newline + 1)
        val marker = kept.indexOf(START_MARK)
        if (marker >= 0 && !tail.startsWith(START_MARK)) {
            val end = kept.indexOf('\n', marker)
            val head = if (end < 0) kept.substring(marker) else kept.substring(marker, end + 1)
            return "$head… earlier lines trimmed\n$tail"
        }
        return "… earlier lines trimmed\n$tail"
    }

    @JvmStatic
    fun keepLastStarts(text: String?, keep: Int): String {
        if (text.isNullOrEmpty() || keep < 1) return ""
        val count = countStarts(text)
        if (count <= keep) return text
        var at = 0
        for (i in 0..(count - keep)) {
            at = text.indexOf(START_MARK, at)
            if (i < count - keep) at += START_MARK.length
        }
        return if (at < 0) text else text.substring(at)
    }

    private fun countStarts(text: String): Int {
        var count = 0
        var from = text.indexOf(START_MARK, 0)
        while (from >= 0) {
            count++
            from = text.indexOf(START_MARK, from + START_MARK.length)
        }
        return count
    }

    private fun logFile(context: Context): File = File(context.noBackupFilesDir, "session-log.txt")
}
