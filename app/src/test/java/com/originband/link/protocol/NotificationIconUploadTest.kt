// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class NotificationIconUploadTest {
    @Test
    fun rgb565PacksFiveSixFive() {
        val opaqueRed = 0xffff0000.toInt()
        val encoded = NotificationIconPixels.encode(
            intArrayOf(opaqueRed),
            1,
            1,
            NotificationIconPixels.RGB_565_LE,
        )
        assertArrayEquals(byteArrayOf(0x00, 0xf8.toByte()), encoded)
    }

    @Test
    fun uploadEnvelopeMatchesTypeMd5SizeAndCrc() {
        val pixels = NotificationIconPixels.encode(
            intArrayOf(0xff112233.toInt()),
            1,
            1,
            NotificationIconPixels.ARGB_8888_LE,
        )
        val request = NotificationIconUpload.request(pixels)
        assertEquals(22, request.type)
        assertEquals(50, request.dataUpload.dataUploadRequest.type)
        assertEquals(pixels.size, request.dataUpload.dataUploadRequest.size)
        assertArrayEquals(
            NotificationIconUpload.md5(pixels),
            request.dataUpload.dataUploadRequest.md5Sum.toByteArray(),
        )

        val envelope = NotificationIconUpload.envelope(pixels)
        assertEquals(0, envelope[0].toInt())
        assertEquals(50, envelope[1].toInt() and 0xff)
        val crc = CRC32()
        crc.update(envelope, 0, envelope.size - 4)
        val stored = ByteBuffer.wrap(envelope, envelope.size - 4, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .getInt()
        assertEquals(crc.value.toInt(), stored)
    }

    @Test
    fun chunksCarryPartNumbers() {
        val envelope = ByteArray(10) { 7 }
        val parts = NotificationIconUpload.chunks(envelope, 8)
        assertEquals(3, parts.size)
        assertEquals(3, parts[0][0].toInt() and 0xff)
        assertEquals(1, parts[0][2].toInt() and 0xff)
        assertEquals(4, parts[0].size - 4)
        assertEquals(3, parts[2][2].toInt() and 0xff)
        assertEquals(2, parts[2].size - 4)
    }

    @Test
    fun rejectedAckDoesNotStart() {
        val ack = XiaomiProto.Command.newBuilder()
            .setType(22)
            .setSubtype(0)
            .setDataUpload(
                XiaomiProto.DataUpload.newBuilder().setDataUploadAck(
                    XiaomiProto.DataUploadAck.newBuilder().setUnknown2(1).setChunkSize(2048),
                ),
            )
            .build()
        assertThrows(IllegalArgumentException::class.java) {
            NotificationIconUpload.acceptedChunk(ack, 2000)
        }
    }
}
