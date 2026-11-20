// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

class XiaomiMassReceiveTest {
    companion object {
        private fun metadata(name: String): ByteArray {
            val bytes = name.toByteArray(StandardCharsets.UTF_8)
            val out = ByteArrayOutputStream()
            out.write(bytes.size)
            out.writeBytes(bytes)
            out.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
            return out.toByteArray()
        }

        private fun packets(
            name: String,
            metadata: ByteArray,
            fragments: List<ByteArray>,
            crc: Int,
        ): List<ByteArray> {
            val packets = ArrayList<ByteArray>()
            for (index in fragments.indices) {
                val current = index + 1
                val packet = ByteArrayOutputStream()
                packet.write(0)
                packet.write(0)
                le16(packet, fragments.size)
                le16(packet, current)
                if (index == 0) packet.writeBytes(metadata)
                packet.writeBytes(fragments[index])
                if (current == fragments.size) {
                    packet.write(crc and 0xff)
                    packet.write((crc ushr 8) and 0xff)
                    packet.write((crc ushr 16) and 0xff)
                    packet.write((crc ushr 24) and 0xff)
                }
                packets.add(packet.toByteArray())
            }
            return packets
        }

        private fun le16(out: ByteArrayOutputStream, value: Int) {
            out.write(value and 0xff)
            out.write((value ushr 8) and 0xff)
        }
    }

    @Test
    @Throws(Exception::class)
    fun assemblesAndChecksCompleteReverseMassFile() {
        val dir = Files.createTempDirectory("mass-log-test").toFile()
        val first = "hello ".toByteArray(StandardCharsets.UTF_8)
        val second = "world\n".toByteArray(StandardCharsets.UTF_8)
        val metadata = metadata("device-log.tar.gz")
        val crc = CRC32()
        crc.update(metadata)
        crc.update(first)
        crc.update(second)
        val packets = packets("device-log.tar.gz", metadata, listOf(first, second), crc.value.toInt())
        val progress = ArrayList<XiaomiMassReceive.Progress>()
        val receiver = XiaomiMassReceive(dir) { progress.add(it) }
        try {
            packets.forEach { receiver.accept(it) }
            val result = receiver.await(2, TimeUnit.SECONDS)
            assertEquals("device-log.tar.gz", result.fileName())
            assertArrayEquals(
                "hello world\n".toByteArray(StandardCharsets.UTF_8),
                Files.readAllBytes(result.file().toPath()),
            )
            assertEquals(2, progress.size)
            assertEquals(2, progress[1].currentPart())
            assertEquals(2, progress[1].totalParts())
        } finally {
            receiver.close()
            dir.listFiles()?.forEach { Files.deleteIfExists(it.toPath()) }
            Files.deleteIfExists(dir.toPath())
        }
    }

    @Test
    @Throws(Exception::class)
    fun rejectsChecksumMismatch() {
        val dir = Files.createTempDirectory("mass-crc-test").toFile()
        val metadata = metadata("device-log.bin")
        val data = byteArrayOf(1, 2, 3)
        val packets = packets("device-log.bin", metadata, listOf(data), 0x12345678)
        val receiver = XiaomiMassReceive(dir, null)
        try {
            packets.forEach { receiver.accept(it) }
            val failure = assertThrows(ExecutionException::class.java) {
                receiver.await(2, TimeUnit.SECONDS)
            }
            assertEquals("MASS_CRC_MISMATCH", failure.cause?.message)
        } finally {
            receiver.close()
            dir.listFiles()?.forEach { Files.deleteIfExists(it.toPath()) }
            Files.deleteIfExists(dir.toPath())
        }
    }
}
