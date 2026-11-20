/*  Copyright (C) 2023-2024 Andreas Shimokawa, José Rebelo
 *
 *  This file is adapted from Gadgetbridge XiaomiCharacteristicV1.
 *
 *  Gadgetbridge is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as published
 *  by the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

/** BLE V1 characteristic chunking used by Band 8 and other fe95 protobuf devices. */
object BleV1Codec {
    const val TYPE_CHUNK_START = 0
    const val TYPE_CHUNK_ACK = 1
    const val TYPE_SINGLE = 2
    const val TYPE_PAYLOAD_ACK = 3

    @JvmField
    val PAYLOAD_ACK: ByteArray = byteArrayOf(0, 0, 3, 0)

    @JvmField
    val CHUNK_START_ACK: ByteArray = byteArrayOf(0, 0, 1, 1)

    @JvmField
    val CHUNK_END_ACK: ByteArray = byteArrayOf(0, 0, 1, 0)

    const val DEFAULT_MTU_PAYLOAD = 244

    /**
     * Band 8 fe95 plaintext is a single frame only when it fits: chunk 0, type 2, encryption
     * flag 2, then the protobuf. Flag 2 is what an encrypted characteristic expects before
     * auth; omitting it makes the band drop the nonce. Encrypted single frames carry the
     * counter in the header. A chunked frame prepends that counter to the ciphertext.
     */
    @JvmStatic
    @JvmOverloads
    fun encodeOutgoing(
        payload: ByteArray?,
        maxWrite: Int,
        encrypted: Boolean,
        counter: Int = if (encrypted) 1 else 0,
    ): List<ByteArray> {
        if (payload == null) throw IllegalArgumentException("BLE payload required")
        if (maxWrite < 8) throw IllegalArgumentException("BLE write size too small")
        if (encrypted && (counter < 1 || counter > 0xffff)) {
            throw IllegalArgumentException("BLE V1 counter out of range")
        }
        val chunkPayload = maxWrite - 2
        val bodyLength = payload.size.toLong() + if (encrypted) 2 else 0
        if ((bodyLength + chunkPayload - 1) / chunkPayload > 0xffff) {
            throw IllegalArgumentException("BLE payload exceeds chunk count")
        }
        if (encrypted && 6 + payload.size <= maxWrite) {
            val single = ByteArray(6 + payload.size)
            single[2] = TYPE_SINGLE.toByte()
            single[3] = 1
            single[4] = counter.toByte()
            single[5] = (counter shr 8).toByte()
            System.arraycopy(payload, 0, single, 6, payload.size)
            return listOf(single)
        }
        val body = if (encrypted) {
            ByteArray(2 + payload.size).apply {
                this[0] = counter.toByte()
                this[1] = (counter shr 8).toByte()
                System.arraycopy(payload, 0, this, 2, payload.size)
            }
        } else {
            payload
        }
        if (!encrypted && 4 + body.size <= maxWrite) {
            val single = ByteArray(4 + body.size)
            single[2] = TYPE_SINGLE.toByte()
            single[3] = 2
            System.arraycopy(body, 0, single, 4, body.size)
            return listOf(single)
        }
        val chunks = (body.size + chunkPayload - 1) / chunkPayload
        val frames = ArrayList<ByteArray>(chunks + 1)
        val start = ByteArray(6)
        start[2] = TYPE_CHUNK_START.toByte()
        start[3] = if (encrypted) 1 else 0
        start[4] = chunks.toByte()
        start[5] = (chunks shr 8).toByte()
        frames.add(start)
        for (index in 0 until chunks) {
            val from = index * chunkPayload
            val to = minOf(from + chunkPayload, body.size)
            val chunk = ByteArray(2 + (to - from))
            val id = index + 1
            chunk[0] = id.toByte()
            chunk[1] = (id shr 8).toByte()
            System.arraycopy(body, from, chunk, 2, to - from)
            frames.add(chunk)
        }
        return frames
    }

    @JvmStatic
    fun control(value: ByteArray?, type: Int): Boolean =
        value != null && value.size >= 3 && value[0] == 0.toByte() && value[1] == 0.toByte() &&
            (value[2].toInt() and 0xff) == type

    @JvmStatic
    fun chunkAck(value: ByteArray?, subtype: Int): Boolean =
        value != null && value.size >= 4 && control(value, TYPE_CHUNK_ACK) &&
            (value[3].toInt() and 0xff) == subtype

    class Reassembler {
        private var expectedChunks = 0
        private val chunks = HashMap<Int, ByteArray>()

        fun accept(value: ByteArray?): ByteArray? {
            if (value == null || value.size < 3) throw IllegalArgumentException("Invalid BLE V1 frame")
            val chunk = (value[0].toInt() and 0xff) or ((value[1].toInt() and 0xff) shl 8)
            if (chunk != 0) {
                if (expectedChunks <= 0 || chunk > expectedChunks) {
                    throw IllegalArgumentException("Unexpected BLE V1 chunk")
                }
                chunks[chunk] = value.copyOfRange(2, value.size)
                if (chunks.size < expectedChunks) return null
                val payload = join()
                chunks.clear()
                expectedChunks = 0
                return payload
            }
            val type = value[2].toInt() and 0xff
            if (type == TYPE_SINGLE) {
                if (value.size < 4) throw IllegalArgumentException("Invalid BLE V1 single")
                val flag = value[3].toInt() and 0xff
                // Flag 1 stays so decrypt can tell ciphertext from plaintext. Flag 2 is the
                // Band 8 plaintext marker and is not part of the protobuf.
                return if (flag == 1) value.copyOfRange(3, value.size) else value.copyOfRange(4, value.size)
            }
            if (type == TYPE_CHUNK_START) {
                if (value.size < 6) throw IllegalArgumentException("Invalid BLE V1 start")
                expectedChunks = (value[4].toInt() and 0xff) or ((value[5].toInt() and 0xff) shl 8)
                chunks.clear()
                return null
            }
            if (type == TYPE_CHUNK_ACK || type == TYPE_PAYLOAD_ACK) return null
            throw IllegalArgumentException("Unsupported BLE V1 type")
        }

        fun awaitingAck(value: ByteArray?): Boolean =
            value != null && value.size >= 3 && value[0] == 0.toByte() && value[1] == 0.toByte() &&
                ((value[2].toInt() and 0xff) == TYPE_CHUNK_ACK || (value[2].toInt() and 0xff) == TYPE_PAYLOAD_ACK)

        private fun join(): ByteArray {
            var size = 0
            for (i in 1..expectedChunks) {
                val part = chunks[i] ?: throw IllegalArgumentException("Missing BLE V1 chunk")
                size += part.size
            }
            val payload = ByteArray(size)
            var offset = 0
            for (i in 1..expectedChunks) {
                val part = chunks[i]!!
                System.arraycopy(part, 0, payload, offset, part.size)
                offset += part.size
            }
            return payload
        }
    }
}
