// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.zip.CRC32

/**
 * Streaming receiver for Xiaomi's reverse MASS file format.
 *
 * <p>The first packet starts with {@code version, flags, total LE16, current LE16, nameLen},
 * followed by the UTF-8 name and a five-byte retained header. Every packet then carries its file
 * fragment. Only the final packet ends with the little-endian CRC32 of the retained header and
 * the complete file.
 */
class XiaomiMassReceive @JvmOverloads constructor(
    private val directory: File,
    progress: Consumer<Progress>? = null,
) : AutoCloseable {
    companion object {
        private const val PACKET_HEADER = 6
        private const val RETAINED_HEADER = 5
        private const val CRC_BYTES = 4
        private const val MAX_NAME_BYTES = 180
        private const val MAX_FILE_BYTES = 512L * 1024 * 1024
        private val CONTROL_REGEX = Regex("[\\p{Cntrl}]")

        private fun unique(directory: File, name: String): File {
            var candidate = File(directory, name)
            if (!candidate.exists()) return candidate
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            val suffix = if (dot > 0) name.substring(dot) else ""
            var index = 2
            while (true) {
                candidate = File(directory, "$stem-$index$suffix")
                if (!candidate.exists()) return candidate
                index++
            }
        }

        private fun safeFileName(raw: String?): String {
            var clean = raw?.replace('\\', '/') ?: ""
            clean = clean.substring(clean.lastIndexOf('/') + 1)
                .replace(CONTROL_REGEX, "")
                .trim()
            if (clean.isBlank() || clean == "." || clean == "..") return "device-log.bin"
            return if (clean.length > 120) clean.substring(clean.length - 120) else clean
        }

        private fun u16(data: ByteArray, offset: Int): Int =
            (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)

        private fun le32(data: ByteArray, offset: Int): Int =
            (data[offset].toInt() and 0xff) or
                ((data[offset + 1].toInt() and 0xff) shl 8) or
                ((data[offset + 2].toInt() and 0xff) shl 16) or
                ((data[offset + 3].toInt() and 0xff) shl 24)
    }

    data class Progress(
        @JvmField val currentPart: Int,
        @JvmField val totalParts: Int,
        @JvmField val fileName: String,
        @JvmField val bytes: Long,
    ) {
        fun currentPart(): Int = currentPart
        fun totalParts(): Int = totalParts
        fun fileName(): String = fileName
        fun bytes(): Long = bytes
    }

    data class Result(
        @JvmField val file: File,
        @JvmField val fileName: String,
        @JvmField val bytes: Long,
    ) {
        fun file(): File = file
        fun fileName(): String = fileName
        fun bytes(): Long = bytes
    }

    private val progress: Consumer<Progress> = progress ?: Consumer { }
    private val completion = CompletableFuture<Result>()
    private val crc = CRC32()
    private var part: File? = null
    private var output: OutputStream? = null
    private var fileName = ""
    private var totalParts = 0
    private var currentPart = 0
    private var bytes = 0L
    private var complete = false
    private var latestProgress = Progress(0, 0, "", 0)

    fun completion(): CompletionStage<Result> = completion

    /** Called by the transport reader in wire order, after MASS payload decryption. */
    @Synchronized
    fun accept(packet: ByteArray?) {
        if (complete || completion.isCompletedExceptionally) return
        try {
            if (packet == null || packet.size < PACKET_HEADER + CRC_BYTES) {
                throw IllegalArgumentException("MASS_PACKET_TOO_SHORT")
            }
            if (packet[0] != 0.toByte()) throw IllegalArgumentException("MASS_VERSION_UNSUPPORTED")
            val total = u16(packet, 2)
            val current = u16(packet, 4)
            if (total <= 0 || current <= 0 || current > total) {
                throw IllegalArgumentException("MASS_PART_RANGE_INVALID")
            }

            val out: OutputStream
            val from: Int
            val existingOut = output
            if (existingOut == null) {
                if (current != 1) throw IllegalArgumentException("MASS_FIRST_PART_MISSING")
                val nameLength = packet[6].toInt() and 0xff
                val firstData = 7 + nameLength + RETAINED_HEADER
                if (nameLength <= 0 || nameLength > MAX_NAME_BYTES || firstData + CRC_BYTES > packet.size) {
                    throw IllegalArgumentException("MASS_HEADER_INVALID")
                }
                fileName = safeFileName(String(packet, 7, nameLength, StandardCharsets.UTF_8))
                totalParts = total
                out = startFile(packet, 6, firstData)
                from = firstData
                crc.update(packet, 6, firstData - 6)
            } else {
                if (total != totalParts) throw IllegalArgumentException("MASS_TOTAL_CHANGED")
                if (current != currentPart + 1) throw IllegalArgumentException("MASS_PART_OUT_OF_ORDER")
                out = existingOut
                from = PACKET_HEADER
            }

            var to = packet.size
            if (current == totalParts) {
                if (to - from < CRC_BYTES) throw IllegalArgumentException("MASS_FRAGMENT_MISSING")
                val expected = le32(packet, to - CRC_BYTES)
                to -= CRC_BYTES
                out.write(packet, from, to - from)
                crc.update(packet, from, to - from)
                bytes += (to - from).toLong()
                synchronized(this) {
                    if (crc.value.toInt() != expected) {
                        throw IllegalArgumentException("MASS_CRC_MISMATCH")
                    }
                    currentPart = current
                    finishFile()
                    publishProgress(current, total)
                }
                return
            }

            out.write(packet, from, to - from)
            crc.update(packet, from, to - from)
            bytes += (to - from).toLong()
            currentPart = current
            if (bytes > MAX_FILE_BYTES) throw IOException("MASS_FILE_TOO_LARGE")
            publishProgress(current, total)
        } catch (failure: Exception) {
            fail(failure.message ?: failure.javaClass.simpleName)
        }
    }

    @Throws(IOException::class)
    private fun startFile(packet: ByteArray, headerFrom: Int, firstData: Int): OutputStream {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("MASS_DIRECTORY_FAILED")
        }
        val temp = File(directory, ".incoming-${System.nanoTime()}.part")
        part = temp
        val stream = BufferedOutputStream(FileOutputStream(temp), 64 * 1024)
        output = stream
        if (firstData > packet.size) throw IOException("MASS_HEADER_INVALID")
        return stream
    }

    @Throws(IOException::class)
    private fun finishFile() {
        val stream = output ?: return
        stream.flush()
        stream.close()
        output = null
        val destination = unique(directory, fileName)
        val sourcePart = checkNotNull(part)
        Files.move(sourcePart.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        part = null
        complete = true
        completion.complete(Result(destination, fileName, bytes))
    }

    @Synchronized
    fun fail(code: String?) {
        if (complete || completion.isDone) return
        try {
            output?.close()
        } catch (_: IOException) {
        }
        output = null
        part?.delete()
        part = null
        completion.completeExceptionally(
            IllegalStateException(if (code == null || code.isBlank()) "MASS_RECEIVE_FAILED" else code),
        )
    }

    @Synchronized
    fun progressSnapshot(): Progress = latestProgress

    @Throws(Exception::class)
    fun await(timeout: Long, unit: TimeUnit): Result = completion.get(timeout, unit)

    override fun close() = fail("MASS_RECEIVE_CANCELLED")

    private fun publishProgress(current: Int, total: Int) {
        val snapshot = Progress(current, total, fileName, bytes)
        latestProgress = snapshot
        progress.accept(snapshot)
    }
}
