// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.HexFormat

class SppV2CodecTest {
    companion object {
        // Version/configuration/ACK bytes only, from the 2026-09-24 official reconnect capture.
        // These contain no device identity, nonces, proofs, credentials or health data.
        private val SESSION_REQUEST = hex(
            "a5a5020016001d4d0101030001000002020000fc03020020000402001027",
        )
        private val SESSION_RESPONSE = hex(
            "a5a50200160084330201030003020f020200008003020003000402007017",
        )
        private val ACK_ONE = hex("a5a5010100000000")

        private fun assertFrames(frames: List<SppV2Codec.Frame>) {
            assertEquals(3, frames.size)
            val response = frames[0]
            assertEquals(SppV2Codec.TYPE_SESSION_CONFIG, response.type)
            assertEquals(0, response.sequence)
            assertArrayEquals(
                hex("0201030003020f020200008003020003000402007017"),
                response.payload(),
            )
            val config = SppV2Codec.parseSessionConfig(response)
            assertEquals(2, config.opcode)
            assertArrayEquals(hex("03020f"), config.version)
            assertEquals(32768, config.maxPacketSize)
            assertEquals(3, config.txWindow)
            assertEquals(6000, config.sendTimeoutMillis)
            assertTrue(config.unknownParameters.isEmpty())
            assertEquals(SppV2Codec.TYPE_ACK, frames[1].type)
            assertEquals(1, frames[1].sequence)
            assertArrayEquals(ByteArray(0), frames[1].payload())
            assertEquals(SppV2Codec.TYPE_SESSION_CONFIG, frames[2].type)
            assertArrayEquals(
                hex("0101030001000002020000fc03020020000402001027"),
                frames[2].payload(),
            )
        }

        private fun join(vararg packets: ByteArray): ByteArray {
            val stream = ByteArray(packets.sumOf { it.size })
            var offset = 0
            for (packet in packets) {
                System.arraycopy(packet, 0, stream, offset, packet.size)
                offset += packet.size
            }
            return stream
        }

        private fun hex(value: String): ByteArray = HexFormat.of().parseHex(value)
    }

    @Test
    fun capturedFramesSurviveEverySplitAndCoalescingBoundary() {
        val stream = join(SESSION_RESPONSE, ACK_ONE, SESSION_REQUEST)
        for (split in 0..stream.size) {
            val decoder = SppV2Codec.Decoder()
            val frames = ArrayList(decoder.feed(stream, 0, split))
            frames.addAll(decoder.feed(stream, split, stream.size - split))
            assertFrames(frames)
        }
        val decoder = SppV2Codec.Decoder()
        val frames = ArrayList<SppV2Codec.Frame>()
        for (i in stream.indices) frames.addAll(decoder.feed(stream, i, 1))
        assertFrames(frames)
    }

    @Test
    fun checksumFailureCannotBeSkippedBeforeAValidFrame() {
        val damaged = SESSION_RESPONSE.clone().also {
            it[it.lastIndex] = (it[it.lastIndex].toInt() xor 1).toByte()
        }
        val stream = join(damaged, ACK_ONE)
        val decoder = SppV2Codec.Decoder()
        assertThrows(IllegalArgumentException::class.java) {
            decoder.feed(stream, 0, stream.size)
        }
        assertThrows(IllegalStateException::class.java) {
            decoder.feed(ACK_ONE, 0, ACK_ONE.size)
        }
        decoder.reset()
        val frames = decoder.feed(ACK_ONE, 0, ACK_ONE.size)
        assertEquals(1, frames.size)
        assertEquals(1, frames[0].sequence)
    }

    @Test
    fun declaredPayloadLimitIsEnforcedAtTheHeaderBoundary() {
        val decoder = SppV2Codec.Decoder(21)
        assertTrue(decoder.feed(SESSION_RESPONSE, 0, 7).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            decoder.feed(SESSION_RESPONSE, 7, 1)
        }
    }

    @Test
    fun capturedConfigurationRequestHasTheExactWireChecksum() {
        assertArrayEquals(SESSION_REQUEST, SppV2Codec.encode(SppNegotiation.startSessionRequest()))
    }

    @Test
    fun frxAndNakUseTheTypeNibble() {
        val frx = hex("a5a51300020000000102")
        // checksum of {1, 2}
        var crc = 0
        for (value in byteArrayOf(1, 2)) {
            crc = (crc xor (value.toInt() and 0xff)) and 0xffff
            for (bit in 0 until 8) {
                crc = if ((crc and 1) == 0) crc ushr 1 else (crc ushr 1) xor 0xa001
            }
        }
        frx[6] = crc.toByte()
        frx[7] = (crc ushr 8).toByte()
        val frame = SppV2Codec.Decoder().feed(frx, 0, frx.size)[0]
        assertEquals(SppV2Codec.TYPE_DATA, frame.type)
        assertTrue(frame.frx)
        assertEquals(0, frame.sequence)
        val nak = hex("a5a5000400000000")
        val nakFrame = SppV2Codec.Decoder().feed(nak, 0, nak.size)[0]
        assertEquals(SppV2Codec.TYPE_NAK, nakFrame.type)
        assertEquals(4, nakFrame.sequence)
        assertFalse(nakFrame.frx)
    }

    @Test
    fun aheadPacketIsNakedOnceThenAccepted() {
        val cursor = SppV2Codec.ReceiveCursor()
        assertEquals(SppV2Codec.ReceiveCursor.Decision.TAKE, cursor.offer(0, false, 1))
        assertEquals(SppV2Codec.ReceiveCursor.Decision.NAK, cursor.offer(2, false, 1))
        assertEquals(1, cursor.expected())
        assertEquals(SppV2Codec.ReceiveCursor.Decision.TAKE, cursor.offer(2, false, 1))
        assertEquals(3, cursor.expected())
        assertEquals(SppV2Codec.ReceiveCursor.Decision.DROP, cursor.offer(9, false, 7))
        assertEquals(3, cursor.expected())
        assertEquals(SppV2Codec.ReceiveCursor.Decision.TAKE, cursor.offer(0, false, 1))
        assertEquals(1, cursor.expected())
        assertEquals(SppV2Codec.ReceiveCursor.Decision.TAKE, cursor.offer(7, true, 1))
        assertEquals(8, cursor.expected())
    }
}
