// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BleV1CodecTest {
    @Test
    fun smallPayloadUsesSingleFrame() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val frames = BleV1Codec.encodeOutgoing(payload, 20, false)
        assertEquals(1, frames.size)
        val reassembler = BleV1Codec.Reassembler()
        assertArrayEquals(payload, reassembler.accept(frames[0]))
    }

    @Test
    fun largePayloadReassemblesInOrder() {
        val payload = ByteArray(40) { it.toByte() }
        val frames = BleV1Codec.encodeOutgoing(payload, 10, false)
        assertTrue(frames.size > 2)
        assertEquals(BleV1Codec.TYPE_CHUNK_START, frames[0][2].toInt())
        val reassembler = BleV1Codec.Reassembler()
        var result: ByteArray? = null
        for (frame in frames) {
            val part = reassembler.accept(frame)
            if (part != null) result = part
        }
        assertArrayEquals(payload, result)
    }

    @Test
    fun encryptedSingleFrameCarriesCounter() {
        val payload = byteArrayOf(9, 8, 7)
        val frames = BleV1Codec.encodeOutgoing(payload, 20, true, 2)
        assertEquals(1, frames.size)
        assertEquals(1, frames[0][3].toInt())
        assertEquals(2, frames[0][4].toInt())
        assertEquals(0, frames[0][5].toInt())
        assertEquals(9, frames[0][6].toInt())
    }

    @Test
    fun encryptedChunkedFramePrefixesCounter() {
        val payload = ByteArray(40)
        val frames = BleV1Codec.encodeOutgoing(payload, 10, true, 1)
        assertEquals(1, frames[0][3].toInt())
        val reassembler = BleV1Codec.Reassembler()
        var result: ByteArray? = null
        for (frame in frames) {
            val part = reassembler.accept(frame)
            if (part != null) result = part
        }
        assertEquals(42, result!!.size)
        assertEquals(1, result[0].toInt())
        assertEquals(0, result[1].toInt())
    }

    @Test
    fun payloadAbove512BytesUsesMtuSizedChunks() {
        for (budget in intArrayOf(20, 244)) {
            val payload = ByteArray(1200) { it.toByte() }
            val frames = BleV1Codec.encodeOutgoing(payload, budget, true, 0x1234)
            val expectedChunks = (payload.size + 2 + budget - 3) / (budget - 2)
            assertEquals(expectedChunks + 1, frames.size)
            assertEquals(
                expectedChunks,
                (frames[0][4].toInt() and 255) or ((frames[0][5].toInt() and 255) shl 8),
            )
            val reassembler = BleV1Codec.Reassembler()
            var result: ByteArray? = null
            for (frame in frames) {
                assertTrue(frame.size <= budget)
                val part = reassembler.accept(frame)
                if (part != null) result = part
            }
            val expected = ByteArray(payload.size + 2)
            expected[0] = 0x34
            expected[1] = 0x12
            System.arraycopy(payload, 0, expected, 2, payload.size)
            assertArrayEquals(expected, result)
        }
    }

    @Test
    fun chunkCountOverflowIsRejectedBeforeEncoding() {
        val capacity = 6 * 0xffff
        assertThrows(IllegalArgumentException::class.java) {
            BleV1Codec.encodeOutgoing(ByteArray(capacity + 1), 8, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BleV1Codec.encodeOutgoing(ByteArray(capacity - 1), 8, true, 1)
        }
    }

    @Test
    fun payloadAckIsIgnored() {
        val reassembler = BleV1Codec.Reassembler()
        assertNull(reassembler.accept(BleV1Codec.PAYLOAD_ACK))
        assertTrue(reassembler.awaitingAck(BleV1Codec.PAYLOAD_ACK))
        assertFalse(BleV1Codec.control(BleV1Codec.PAYLOAD_ACK, BleV1Codec.TYPE_CHUNK_ACK))
        assertTrue(BleV1Codec.control(BleV1Codec.CHUNK_END_ACK, BleV1Codec.TYPE_CHUNK_ACK))
        assertTrue(BleV1Codec.control(BleV1Codec.CHUNK_START_ACK, BleV1Codec.TYPE_CHUNK_ACK))
    }

    @Test
    fun band8PlaintextSingleUsesEncryptionFlagTwo() {
        val payload = ByteArray(27)
        payload[0] = 8
        val frames = BleV1Codec.encodeOutgoing(payload, 244, false)
        assertEquals(1, frames.size)
        val frame = frames[0]
        assertEquals(31, frame.size)
        assertEquals(0, frame[0].toInt())
        assertEquals(0, frame[1].toInt())
        assertEquals(BleV1Codec.TYPE_SINGLE, frame[2].toInt())
        assertEquals(2, frame[3].toInt())
        assertEquals(8, frame[4].toInt())
        assertArrayEquals(payload, BleV1Codec.Reassembler().accept(frame))
    }

    @Test
    fun plaintextThatExceedsMtuIsChunked() {
        val payload = ByteArray(27)
        val frames = BleV1Codec.encodeOutgoing(payload, 20, false)
        assertTrue(frames.size > 1)
        assertEquals(BleV1Codec.TYPE_CHUNK_START, frames[0][2].toInt())
        assertEquals(0, frames[0][3].toInt())
        val reassembler = BleV1Codec.Reassembler()
        var result: ByteArray? = null
        for (frame in frames) {
            val part = reassembler.accept(frame)
            if (part != null) result = part
        }
        assertArrayEquals(payload, result)
    }

    @Test
    fun encryptedReplyKeepsFlagForDecrypt() {
        val frame = byteArrayOf(0, 0, 2, 1, 9, 8, 7)
        val got = BleV1Codec.Reassembler().accept(frame)!!
        assertEquals(1, got[0].toInt())
        assertEquals(9, got[1].toInt())
    }

    @Test
    fun chunkAckMatchesSubtype() {
        assertTrue(BleV1Codec.chunkAck(BleV1Codec.CHUNK_START_ACK, 1))
        assertTrue(BleV1Codec.chunkAck(BleV1Codec.CHUNK_END_ACK, 0))
        assertFalse(BleV1Codec.chunkAck(BleV1Codec.PAYLOAD_ACK, 0))
    }
}
