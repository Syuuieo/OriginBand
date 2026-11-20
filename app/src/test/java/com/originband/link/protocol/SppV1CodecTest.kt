// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.HexFormat

class SppV1CodecTest {
    companion object {
        private val VERSION_REQUEST = HexFormat.of().parseHex("badcfe00c00300000300ef")
        private val VERSION_RESPONSE = HexFormat.of().parseHex("badcfe0000060001020003020fef")
        private val V1_VERSION = HexFormat.of().parseHex("badcfe00000600010000010032ef")
    }

    @Test
    fun capturedBand11VersionEnvelopeRoundTrips() {
        assertArrayEquals(VERSION_REQUEST, SppNegotiation.versionRequest(3))
        val request = SppV1Codec.decode(VERSION_REQUEST)
        assertEquals(0, request.channel)
        assertTrue(request.needsResponse)
        assertEquals(0, request.opcode)
        val response = SppV1Codec.decode(VERSION_RESPONSE)
        assertEquals(0, response.channel)
        assertArrayEquals(byteArrayOf(3, 2, 15), response.payload)
        assertTrue((response.payload[0].toInt() and 0xff) >= 2)
    }

    @Test
    fun band8ProStyleVersionStaysOnV1() {
        val packet = SppV1Codec.decode(V1_VERSION)
        assertEquals(0, packet.channel)
        assertEquals(1, packet.payload[0].toInt() and 0xff)
        assertArrayEquals(V1_VERSION, SppV1Codec.encode(packet))
    }

    @Test
    fun encryptedProtobufCarriesLittleEndianCounter() {
        val cipher = byteArrayOf(10, 11, 12)
        assertArrayEquals(byteArrayOf(1, 0, 10, 11, 12), SppV1Codec.sealEncrypted(1, cipher))
        assertArrayEquals(
            byteArrayOf(0x34, 0x12, 10, 11, 12),
            SppV1Codec.sealEncrypted(0x1234, cipher),
        )
    }

    @Test
    fun protobufPacketsSurviveByteSplits() {
        val payload = byteArrayOf(8, 1, 16, 26)
        val encoded = SppV1Codec.encode(SppV1Codec.protobuf(4, SppV1Codec.DATA_AUTH, payload))
        val decoder = SppV1Codec.Decoder()
        var packets = emptyList<SppV1Codec.Packet>()
        for (i in encoded.indices) {
            val part = decoder.feed(encoded, i, 1)
            if (part.isNotEmpty()) packets = part
        }
        assertEquals(1, packets.size)
        assertEquals(SppV1Codec.CHANNEL_PROTO_TX, packets[0].channel)
        assertEquals(SppV1Codec.DATA_AUTH, packets[0].dataType)
        assertArrayEquals(payload, packets[0].payload)
    }
}
