// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream

class DeviceLogPublisherTest {
    @Rule
    @JvmField
    val folder = TemporaryFolder()

    @Test
    fun absoluteHandsetLogPathsAreMadeRelativeWithoutEscapingDownloads() {
        assertEquals("data/log/tmp.log", DeviceLogPublisher.safeEntryName("/data/log/tmp.log"))
        assertEquals("data/log/log1.gz", DeviceLogPublisher.safeEntryName("\\data\\log\\log1.gz"))
        assertNull(DeviceLogPublisher.safeEntryName("/data/log/../../secret"))
        assertNull(DeviceLogPublisher.safeEntryName("../../secret"))
    }

    @Test
    fun parsesUstarGnuLongNamePaxAndSkipsSymlinks() {
        val raw = ByteArrayOutputStream()
        // 1. GNU LongName entry followed by regular file
        val longName = "very/long/path/inside/band/logs/" + "sub".repeat(35) + ".txt"
        val longNameBytes = (longName + "\u0000").toByteArray(StandardCharsets.UTF_8)
        raw.write(tarHeader("././@LongLink", 'L', longNameBytes.size.toLong()))
        raw.write(padded512(longNameBytes))
        val body1 = "hello-long".toByteArray(StandardCharsets.UTF_8)
        raw.write(tarHeader("truncated", '0', body1.size.toLong()))
        raw.write(padded512(body1))

        // 2. PAX extended header overriding path
        val paxPath = "pax/custom/name.log"
        val paxLineBody = " path=$paxPath\n"
        val totalLen = paxLineBody.length + 2 // "23 path=..."
        val paxBytes = "$totalLen$paxLineBody".toByteArray(StandardCharsets.UTF_8)
        raw.write(tarHeader("PaxHeader", 'x', paxBytes.size.toLong()))
        raw.write(padded512(paxBytes))
        val body2 = "hello-pax".toByteArray(StandardCharsets.UTF_8)
        raw.write(tarHeader("fallback.log", '0', body2.size.toLong()))
        raw.write(padded512(body2))

        // 3. Symlink entry (should report isSymbolicLink = true)
        raw.write(tarHeader("bad/symlink", '2', 0L))

        // 4. USTAR prefix + name entry
        val body3 = "hello-ustar".toByteArray(StandardCharsets.UTF_8)
        raw.write(tarHeader("file.txt", '0', body3.size.toLong(), prefix = "var/log"))
        raw.write(padded512(body3))

        // End of archive (two zero blocks)
        raw.write(ByteArray(1024))

        val tarBytes = raw.toByteArray()
        assertEquals(DeviceLogPublisher.ArchiveType.TAR, DeviceLogPublisher.detectHeader(tarBytes, tarBytes.size))

        val gzFile = folder.newFile("band.tar.gz")
        GZIPOutputStream(gzFile.outputStream()).use { it.write(tarBytes) }
        assertEquals(DeviceLogPublisher.ArchiveType.GZIP_TAR, DeviceLogPublisher.detect(gzFile))

        val entries = mutableListOf<Pair<String, String>>()
        var symlinkSeen = false
        DeviceLogPublisher.openArchive(gzFile.inputStream(), DeviceLogPublisher.ArchiveType.GZIP_TAR).use { src ->
            while (true) {
                val item = src.nextItem() ?: break
                if (item.isSymbolicLink) {
                    symlinkSeen = true
                    continue
                }
                val buf = ByteArray(1024)
                val out = ByteArrayOutputStream()
                while (true) {
                    val n = src.readChunk(buf, buf.size)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
                entries += item.name to out.toString(StandardCharsets.UTF_8)
            }
        }

        assertTrue(symlinkSeen)
        assertFalse(entries.any { it.first == "bad/symlink" })
        assertEquals(
            listOf(
                longName to "hello-long",
                paxPath to "hello-pax",
                "var/log/file.txt" to "hello-ustar",
            ),
            entries,
        )
    }

    private fun tarHeader(name: String, type: Char, size: Long, prefix: String = ""): ByteArray {
        val h = ByteArray(512)
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        System.arraycopy(nameBytes, 0, h, 0, minOf(nameBytes.size, 100))
        writeAscii(h, 100, "000644 \u0000")
        writeAscii(h, 108, "000000 \u0000")
        writeAscii(h, 116, "000000 \u0000")
        writeAscii(h, 124, java.lang.Long.toOctalString(size).padStart(11, '0') + " ")
        writeAscii(h, 136, "00000000000 ")
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        h[156] = type.code.toByte()
        writeAscii(h, 257, "ustar\u0000")
        writeAscii(h, 263, "00")
        if (prefix.isNotEmpty()) {
            val pb = prefix.toByteArray(StandardCharsets.UTF_8)
            System.arraycopy(pb, 0, h, 345, minOf(pb.size, 155))
        }
        val sum = h.sumOf { it.toInt() and 0xff }
        writeAscii(h, 148, Integer.toOctalString(sum).padStart(6, '0') + "\u0000 ")
        return h
    }

    private fun writeAscii(dest: ByteArray, offset: Int, text: String) {
        val bytes = text.toByteArray(StandardCharsets.US_ASCII)
        System.arraycopy(bytes, 0, dest, offset, bytes.size)
    }

    private fun padded512(data: ByteArray): ByteArray {
        val mod = data.size % 512
        if (mod == 0) return data
        return data + ByteArray(512 - mod)
    }
}
