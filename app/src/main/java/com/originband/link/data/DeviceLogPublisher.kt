// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Publishes a completed device-log bundle to the public Downloads collection. */
object DeviceLogPublisher {
    private const val MAX_EXTRACTED_BYTES = 512L * 1024 * 1024
    private const val MAX_EXTRACTED_FILES = 50_000
    private val FOLDER_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)

    @JvmRecord
    data class Result(
        val browseFile: File?,
        val browseUri: Uri?,
        val archiveUri: Uri,
        val relativeDirectory: String,
        val archiveName: String,
        val extractedFiles: Int,
        val extractedBytes: Long,
    )

    internal enum class ArchiveType { ZIP, TAR, GZIP_TAR, UNKNOWN }

    internal data class ArchiveItem(
        val name: String,
        val isDirectory: Boolean,
        val isSymbolicLink: Boolean,
        val size: Long = -1L,
    )

    internal interface ArchiveSource : Closeable {
        fun nextItem(): ArchiveItem?
        fun readChunk(buffer: ByteArray, length: Int): Int
    }

    @JvmStatic
    @Throws(IOException::class)
    fun publish(context: Context, source: File?): Result {
        if (source == null || !source.isFile || source.length() <= 0L) {
            throw IOException("DEVICE_LOG_FILE_MISSING")
        }
        val type = detect(source)
        val archiveName = displayName(source, type)
        val relative = "Download/OriginBand/device-logs/" + FOLDER_TIME.format(LocalDateTime.now()) + "/"
        val archiveUri = copyToDownloads(context, source, relative, archiveName, mime(type))

        var files = 0
        var bytes = 0L
        var browseFile: File? = null
        var browseUri: Uri? = null
        try {
            if (type != ArchiveType.UNKNOWN) {
                browseFile = File(source.parentFile, browseName(source.name))
                val count = extract(context, source, type, relative, browseFile)
                files = count.files
                bytes = count.bytes
                if (files > 0 && browseFile.isFile && browseFile.length() > 0L) {
                    browseUri = copyToDownloads(context, browseFile, relative, browseFile.name, "application/zip")
                } else {
                    browseFile.delete()
                    browseFile = null
                }
            }
        } catch (_: Exception) {
            // The original archive is still complete and shareable; extraction is best effort.
            browseFile?.delete()
            browseFile = null
            browseUri = null
            files = 0
            bytes = 0L
        }
        return Result(browseFile, browseUri, archiveUri, relative, archiveName, files, bytes)
    }

    @Throws(Exception::class)
    private fun extract(
        context: Context,
        source: File,
        type: ArchiveType,
        relativeRoot: String,
        browseFile: File,
    ): ExtractCount {
        BufferedInputStream(FileInputStream(source), 64 * 1024).use { buffered ->
            openArchive(buffered, type).use { archive ->
                ZipOutputStream(BufferedOutputStream(FileOutputStream(browseFile), 64 * 1024)).use { normalized ->
                    var files = 0
                    var bytes = 0L
                    val normalizedNames = HashSet<String>()
                    while (true) {
                        val entry = archive.nextItem() ?: break
                        if (files >= MAX_EXTRACTED_FILES || bytes >= MAX_EXTRACTED_BYTES) break
                        if (entry.isDirectory || entry.isSymbolicLink) continue
                        val safe = safeEntryName(entry.name) ?: continue
                        val path = Paths.get(safe)
                        val parent = path.parent?.toString()?.replace('\\', '/')?.plus("/") ?: ""
                        val display = path.fileName.toString()
                        val normalizedName = uniqueEntry(safe, normalizedNames)
                        val uri = createDownload(context, relativeRoot + parent, display, mimeFromName(display))
                        try {
                            context.contentResolver.openOutputStream(uri, "w").use { out ->
                                if (out == null) throw IOException("DEVICE_LOG_STORAGE_FAILED")
                                normalized.putNextEntry(ZipEntry(normalizedName))
                                val copied = copyLimited(archive, out, normalized, MAX_EXTRACTED_BYTES - bytes)
                                normalized.closeEntry()
                                bytes += copied
                                files++
                            }
                        } catch (failure: Exception) {
                            context.contentResolver.delete(uri, null, null)
                            throw failure
                        }
                        finish(context, uri)
                    }
                    return ExtractCount(files, bytes)
                }
            }
        }
    }

    @Throws(IOException::class)
    internal fun openArchive(input: InputStream, type: ArchiveType): ArchiveSource {
        return when (type) {
            ArchiveType.ZIP -> ZipArchiveSource(ZipInputStream(input))
            ArchiveType.TAR -> TarArchiveSource(input)
            ArchiveType.GZIP_TAR -> TarArchiveSource(GZIPInputStream(input, 64 * 1024))
            ArchiveType.UNKNOWN -> throw IOException("DEVICE_LOG_ARCHIVE_UNKNOWN")
        }
    }

    internal fun detect(file: File): ArchiveType {
        val head = ByteArray(512)
        val read = FileInputStream(file).use { it.read(head) }
        return detectHeader(head, read)
    }

    internal fun detectHeader(head: ByteArray, read: Int): ArchiveType {
        if (read >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
            (head[2].toInt() == 3 || head[2].toInt() == 5 || head[2].toInt() == 7)
        ) {
            return ArchiveType.ZIP
        }
        if (read >= 2 && (head[0].toInt() and 0xff) == 0x1f && (head[1].toInt() and 0xff) == 0x8b) {
            return ArchiveType.GZIP_TAR
        }
        if (read >= 262 && head[257] == 'u'.code.toByte() && head[258] == 's'.code.toByte() &&
            head[259] == 't'.code.toByte() && head[260] == 'a'.code.toByte() && head[261] == 'r'.code.toByte()
        ) {
            return ArchiveType.TAR
        }
        return ArchiveType.UNKNOWN
    }

    @Throws(IOException::class)
    private fun copyToDownloads(
        context: Context,
        source: File,
        relative: String,
        fileName: String,
        mime: String,
    ): Uri {
        val uri = createDownload(context, relative, fileName, mime)
        try {
            FileInputStream(source).use { input ->
                context.contentResolver.openOutputStream(uri, "w").use { output ->
                    if (output == null) throw IOException("DEVICE_LOG_STORAGE_FAILED")
                    input.transferTo(output)
                }
            }
        } catch (failure: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw if (failure is IOException) failure else IOException("DEVICE_LOG_STORAGE_FAILED", failure)
        }
        finish(context, uri)
        return uri
    }

    private fun createDownload(context: Context, relative: String, name: String, mime: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return context.contentResolver.insert(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values,
        ) ?: throw IllegalStateException("DEVICE_LOG_STORAGE_FAILED")
    }

    private fun finish(context: Context, uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        context.contentResolver.update(uri, values, null, null)
    }

    @Throws(IOException::class)
    private fun copyLimited(
        archive: ArchiveSource,
        output: OutputStream,
        normalized: ZipOutputStream,
        limit: Long,
    ): Long {
        val buffer = ByteArray(64 * 1024)
        var remaining = limit
        var copied = 0L
        while (remaining > 0L) {
            val want = minOf(buffer.size.toLong(), remaining).toInt()
            val count = archive.readChunk(buffer, want)
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
            normalized.write(buffer, 0, count)
            copied += count
            remaining -= count
        }
        val probe = ByteArray(1)
        if (remaining == 0L && archive.readChunk(probe, 1) > 0) {
            throw IOException("DEVICE_LOG_EXTRACT_LIMIT_REACHED")
        }
        return copied
    }

    @JvmStatic
    fun safeEntryName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val value = raw.replace('\\', '/')
            .replace(Regex("[\\p{Cntrl}]"), "")
            .replaceFirst(Regex("^/+"), "")
        for (segment in value.split('/')) {
            if (".." == segment) return null
        }
        val path = Paths.get(value).normalize()
        if (path.isAbsolute || path.startsWith("..")) return null
        val clean = path.toString().replace('\\', '/')
        return if (clean.isBlank() || "." == clean) null else clean
    }

    private fun displayName(source: File, type: ArchiveType): String {
        val name = if (source.name.isBlank()) "device-log.bin" else source.name
        val lower = name.lowercase(Locale.ROOT)
        if (type == ArchiveType.GZIP_TAR && !(lower.endsWith(".gz") || lower.endsWith(".tgz"))) {
            return "$name.tar.gz"
        }
        if (type == ArchiveType.ZIP && !lower.endsWith(".zip")) return "$name.zip"
        return name
    }

    private fun browseName(source: String): String {
        val lower = source.lowercase(Locale.ROOT)
        val base = when {
            lower.endsWith(".tar.gz") -> source.substring(0, source.length - 7)
            lower.endsWith(".tgz") -> source.substring(0, source.length - 4)
            lower.endsWith(".gz") -> source.substring(0, source.length - 3)
            lower.endsWith(".tar") -> source.substring(0, source.length - 4)
            else -> source
        }
        return "$base-browse.zip"
    }

    private fun uniqueEntry(path: String, used: MutableSet<String>): String {
        if (used.add(path)) return path
        val slash = path.lastIndexOf('/')
        val dot = path.lastIndexOf('.')
        val parent = if (slash >= 0) path.substring(0, slash + 1) else ""
        val name = if (slash >= 0) path.substring(slash + 1) else path
        val suffix = if (dot > slash) dot - slash - 1 else name.length
        val stem = name.substring(0, suffix)
        val extension = name.substring(suffix)
        var index = 2
        while (true) {
            val candidate = "$parent$stem-$index$extension"
            if (used.add(candidate)) return candidate
            index++
        }
    }

    private fun mime(type: ArchiveType): String = when (type) {
        ArchiveType.ZIP -> "application/zip"
        ArchiveType.TAR -> "application/x-tar"
        ArchiveType.GZIP_TAR -> "application/gzip"
        ArchiveType.UNKNOWN -> "application/octet-stream"
    }

    private fun mimeFromName(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot >= 0 && dot + 1 < name.length) {
            val ext = name.substring(dot + 1).lowercase(Locale.ROOT)
            val found = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            if (found != null) return found
        }
        return "application/octet-stream"
    }

    private data class ExtractCount(val files: Int, val bytes: Long)

    private class ZipArchiveSource(private val zip: ZipInputStream) : ArchiveSource {
        override fun nextItem(): ArchiveItem? {
            val entry = zip.nextEntry ?: return null
            return ArchiveItem(
                name = entry.name,
                isDirectory = entry.isDirectory,
                isSymbolicLink = false,
                size = entry.size,
            )
        }

        override fun readChunk(buffer: ByteArray, length: Int): Int = zip.read(buffer, 0, length)
        override fun close() = zip.close()
    }

    /** Minimal USTAR / GNU LongName / PAX TAR reader over a streaming [InputStream]. */
    internal class TarArchiveSource(private val input: InputStream) : ArchiveSource {
        private val header = ByteArray(512)
        private var currentSize = 0L
        private var currentRemaining = 0L
        private var pendingLongName: String? = null
        private var pendingPaxPath: String? = null
        private var pendingPaxSize: Long? = null

        override fun nextItem(): ArchiveItem? {
            skipRemainingAndPadding()
            while (true) {
                val read = readFullBlock(header)
                if (read == 0 || isZeroBlock(header)) return null
                if (read < 512) throw EOFException("Truncated TAR header")

                val typeFlag = header[156].toInt().toChar()
                var size = parseSize(header, 124, 12)
                val paxSize = pendingPaxSize
                if (typeFlag != 'x' && typeFlag != 'g' && typeFlag != 'L' && typeFlag != 'K' && paxSize != null) {
                    size = paxSize
                }

                when (typeFlag) {
                    'L' -> {
                        val raw = readMetaBytes(size)
                        pendingLongName = String(raw, StandardCharsets.UTF_8).trimEnd('\u0000')
                        continue
                    }
                    'K' -> {
                        skipMetaBytes(size)
                        continue
                    }
                    'x', 'g' -> {
                        val raw = readMetaBytes(size)
                        if (typeFlag == 'x') {
                            parsePaxRecords(raw)
                        }
                        continue
                    }
                    'V' -> {
                        skipMetaBytes(size)
                        continue
                    }
                }

                val name = pendingPaxPath
                    ?: pendingLongName
                    ?: buildUstarName(header)
                pendingPaxPath = null
                pendingPaxSize = null
                pendingLongName = null

                val isDirOrLink = typeFlag == '5' || typeFlag == '2'
                currentSize = if (isDirOrLink) 0L else size
                currentRemaining = currentSize
                return ArchiveItem(
                    name = name,
                    isDirectory = typeFlag == '5',
                    isSymbolicLink = typeFlag == '2',
                    size = size,
                )
            }
        }

        override fun readChunk(buffer: ByteArray, length: Int): Int {
            if (currentRemaining <= 0L) return -1
            val want = minOf(length.toLong(), currentRemaining).toInt()
            val n = input.read(buffer, 0, want)
            if (n < 0) return -1
            currentRemaining -= n
            return n
        }

        override fun close() = input.close()

        private fun skipRemainingAndPadding() {
            if (currentRemaining > 0L) {
                skipExact(currentRemaining)
                currentRemaining = 0L
            }
            val mod = (currentSize % 512L).toInt()
            if (mod != 0) {
                skipExact((512 - mod).toLong())
            }
            currentSize = 0L
        }

        private fun readMetaBytes(size: Long): ByteArray {
            if (size < 0L || size > 1024L * 1024L) {
                throw IOException("Unsupported TAR metadata length=$size")
            }
            val n = size.toInt()
            val out = ByteArray(n)
            var offset = 0
            while (offset < n) {
                val read = input.read(out, offset, n - offset)
                if (read < 0) throw EOFException("Unexpected EOF in TAR metadata")
                offset += read
            }
            val mod = n % 512
            if (mod != 0) skipExact((512 - mod).toLong())
            return out
        }

        private fun skipMetaBytes(size: Long) {
            if (size <= 0L) return
            skipExact(size)
            val mod = (size % 512L).toInt()
            if (mod != 0) skipExact((512 - mod).toLong())
        }

        private fun parsePaxRecords(raw: ByteArray) {
            val text = String(raw, StandardCharsets.UTF_8)
            var pos = 0
            while (pos < text.length) {
                val space = text.indexOf(' ', pos)
                if (space <= pos) break
                val recordLen = text.substring(pos, space).toIntOrNull() ?: break
                if (recordLen <= 0 || pos + recordLen > text.length) break
                val record = text.substring(space + 1, pos + recordLen).trimEnd('\n')
                val eq = record.indexOf('=')
                if (eq > 0) {
                    val key = record.substring(0, eq)
                    val value = record.substring(eq + 1)
                    if (key == "path") pendingPaxPath = value
                    else if (key == "size") pendingPaxSize = value.toLongOrNull()
                }
                pos += recordLen
            }
        }

        private fun readFullBlock(dest: ByteArray): Int {
            var offset = 0
            while (offset < dest.size) {
                val n = input.read(dest, offset, dest.size - offset)
                if (n < 0) return offset
                offset += n
            }
            return offset
        }

        private fun skipExact(bytes: Long) {
            var remaining = bytes
            while (remaining > 0L) {
                val skipped = input.skip(remaining)
                if (skipped > 0L) {
                    remaining -= skipped
                } else {
                    if (input.read() < 0) throw EOFException("Unexpected EOF skipping TAR padding")
                    remaining--
                }
            }
        }

        companion object {
            private fun isZeroBlock(block: ByteArray): Boolean {
                for (b in block) if (b.toInt() != 0) return false
                return true
            }

            private fun parseCString(block: ByteArray, offset: Int, maxLen: Int): String {
                var end = offset
                val limit = offset + maxLen
                while (end < limit && block[end].toInt() != 0) end++
                return String(block, offset, end - offset, StandardCharsets.UTF_8)
            }

            private fun buildUstarName(block: ByteArray): String {
                val name = parseCString(block, 0, 100)
                val magic = parseCString(block, 257, 6)
                if (magic.startsWith("ustar")) {
                    val prefix = parseCString(block, 345, 155)
                    if (prefix.isNotEmpty()) return "$prefix/$name"
                }
                return name
            }

            private fun parseSize(block: ByteArray, offset: Int, len: Int): Long {
                val first = block[offset].toInt() and 0xFF
                if ((first and 0x80) != 0) {
                    var value = 0L
                    for (i in 1 until len) {
                        value = (value shl 8) or (block[offset + i].toInt() and 0xFF).toLong()
                    }
                    return value
                }
                var value = 0L
                var started = false
                for (i in offset until offset + len) {
                    val c = block[i].toInt()
                    if (c == 0 || c == ' '.code) {
                        if (started) break else continue
                    }
                    if (c < '0'.code || c > '7'.code) break
                    started = true
                    value = (value shl 3) or (c - '0'.code).toLong()
                }
                return value
            }
        }
    }
}
