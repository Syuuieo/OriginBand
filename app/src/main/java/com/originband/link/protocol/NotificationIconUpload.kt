// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import com.google.protobuf.ByteString
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.zip.CRC32

/**
 * Type 22 upload used after the band accepts a notification icon.
 * The envelope is type, MD5, size, pixels, then CRC-32, split into numbered parts.
 */
object NotificationIconUpload {
    const val COMMAND_TYPE = 22
    const val ICON_TYPE = 50
    const val DEFAULT_CHUNK = 2048

    @JvmStatic
    fun request(pixels: ByteArray?): XiaomiProto.Command {
        if (pixels == null || pixels.isEmpty()) throw IllegalArgumentException("ICON_PAYLOAD")
        return XiaomiProto.Command.newBuilder()
            .setType(COMMAND_TYPE)
            .setSubtype(0)
            .setDataUpload(
                XiaomiProto.DataUpload.newBuilder().setDataUploadRequest(
                    XiaomiProto.DataUploadRequest.newBuilder()
                        .setType(ICON_TYPE)
                        .setMd5Sum(ByteString.copyFrom(md5(pixels)))
                        .setSize(pixels.size),
                ),
            )
            .build()
    }

    /** Bytes carried inside each data-channel part, before the part header. */
    @JvmStatic
    fun envelope(pixels: ByteArray): ByteArray {
        val digest = md5(pixels)
        val body = ByteBuffer.allocate(2 + digest.size + 4 + pixels.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        body.put(0.toByte())
        body.put(ICON_TYPE.toByte())
        body.put(digest)
        body.putInt(pixels.size)
        body.put(pixels)
        val raw = body.array()
        val crc = CRC32()
        crc.update(raw)
        val framed = ByteBuffer.allocate(raw.size + 4).order(ByteOrder.LITTLE_ENDIAN)
        framed.put(raw)
        framed.putInt(crc.value.toInt())
        return framed.array()
    }

    @JvmStatic
    fun acceptedChunk(ack: XiaomiProto.Command?, wireLimit: Int): Int {
        if (ack == null || !ack.hasDataUpload() || !ack.dataUpload.hasDataUploadAck()) {
            throw IllegalArgumentException("ICON_UPLOAD_REJECTED")
        }
        val reply = ack.dataUpload.dataUploadAck
        if (reply.unknown2 != 0 || reply.resumePosition != 0) {
            throw IllegalArgumentException("ICON_UPLOAD_REJECTED")
        }
        var size = if (reply.hasChunkSize() && reply.chunkSize > 0) reply.chunkSize else DEFAULT_CHUNK
        if (wireLimit > 0) size = minOf(size, wireLimit)
        if (size < 8) throw IllegalArgumentException("ICON_CHUNK")
        return size
    }

    @JvmStatic
    fun chunks(envelope: ByteArray?, chunkSize: Int): List<ByteArray> {
        if (envelope == null || envelope.isEmpty() || chunkSize < 8) {
            throw IllegalArgumentException("ICON_CHUNK")
        }
        val partSize = chunkSize - 4
        val total = (envelope.size + partSize - 1) / partSize
        if (total > 0xffff) throw IllegalArgumentException("ICON_CHUNK")
        val parts = ArrayList<ByteArray>(total)
        for (index in 0 until total) {
            val start = index * partSize
            val length = minOf(partSize, envelope.size - start)
            val part = ByteArray(4 + length)
            val number = index + 1
            part[0] = total.toByte()
            part[1] = (total shr 8).toByte()
            part[2] = number.toByte()
            part[3] = (number shr 8).toByte()
            System.arraycopy(envelope, start, part, 4, length)
            parts.add(part)
        }
        return parts
    }

    @JvmStatic
    fun md5(pixels: ByteArray): ByteArray =
        try {
            MessageDigest.getInstance("MD5").digest(pixels)
        } catch (_: NoSuchAlgorithmException) {
            throw IllegalStateException("ICON_MD5")
        }
}
