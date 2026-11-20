/*  Copyright (C) 2023-2024 Yoran Vulker
 *
 *  This file is adapted from Gadgetbridge XiaomiSppPacketV1 / XiaomiSppProtocolV1.
 *
 *  Gadgetbridge is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as published
 *  by the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import java.util.Objects

/** SPP V1 envelope used by Band 8 Pro and other SppTaskQueueV1 devices. */
object SppV1Codec {
    @JvmField
    val PREAMBLE: ByteArray = byteArrayOf(0xba.toByte(), 0xdc.toByte(), 0xfe.toByte())

    const val EPILOGUE: Byte = 0xef.toByte()
    const val CHANNEL_VERSION = 0
    const val CHANNEL_PROTO_RX = 1
    const val CHANNEL_PROTO_TX = 2
    const val CHANNEL_FITNESS = 3
    const val CHANNEL_MASS = 5
    const val DATA_PLAIN = 0
    const val DATA_ENCRYPTED = 1
    const val DATA_AUTH = 2
    const val OPCODE_READ = 0
    const val OPCODE_SEND = 2
    const val HEADER = 11
    const val MAX_PAYLOAD = 0xffff - 3

    data class Packet(
        val channel: Int,
        val flag: Boolean,
        val needsResponse: Boolean,
        val opcode: Int,
        val serial: Int,
        val dataType: Int,
        val payload: ByteArray,
    ) {
        fun channel(): Int = channel

        fun flag(): Boolean = flag

        fun needsResponse(): Boolean = needsResponse

        fun opcode(): Int = opcode

        fun serial(): Int = serial

        fun dataType(): Int = dataType

        fun payload(): ByteArray = payload

        override fun equals(other: Any?): Boolean =
            this === other || (
                other is Packet &&
                    channel == other.channel &&
                    flag == other.flag &&
                    needsResponse == other.needsResponse &&
                    opcode == other.opcode &&
                    serial == other.serial &&
                    dataType == other.dataType &&
                    payload.contentEquals(other.payload)
                )

        override fun hashCode(): Int {
            var result = channel
            result = 31 * result + flag.hashCode()
            result = 31 * result + needsResponse.hashCode()
            result = 31 * result + opcode
            result = 31 * result + serial
            result = 31 * result + dataType
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    @JvmStatic
    fun encode(packet: Packet): ByteArray {
        val payload = packet.payload
        if (payload.size > MAX_PAYLOAD) throw IllegalArgumentException("SPP V1 payload too large")
        val bytes = ByteArray(HEADER + payload.size)
        bytes[0] = PREAMBLE[0]
        bytes[1] = PREAMBLE[1]
        bytes[2] = PREAMBLE[2]
        bytes[3] = packet.channel.toByte()
        var flags = 0
        if (packet.flag) flags = flags or 0x80
        if (packet.needsResponse) flags = flags or 0x40
        bytes[4] = flags.toByte()
        val size = payload.size + 3
        bytes[5] = size.toByte()
        bytes[6] = (size shr 8).toByte()
        bytes[7] = packet.opcode.toByte()
        bytes[8] = packet.serial.toByte()
        bytes[9] = packet.dataType.toByte()
        System.arraycopy(payload, 0, bytes, 10, payload.size)
        bytes[bytes.size - 1] = EPILOGUE
        return bytes
    }

    @JvmStatic
    fun protobuf(serial: Int, dataType: Int, payload: ByteArray): Packet =
        Packet(CHANNEL_PROTO_TX, true, false, OPCODE_SEND, serial, dataType, payload)

    /** Phone-to-watch encrypted protobuf: little-endian counter, then CCM ciphertext. */
    @JvmStatic
    fun sealEncrypted(counter: Int, ciphertext: ByteArray): ByteArray {
        if (counter < 1 || counter > 0xffff) throw IllegalArgumentException("SPP V1 counter out of range")
        val sealed = ByteArray(2 + ciphertext.size)
        sealed[0] = counter.toByte()
        sealed[1] = (counter shr 8).toByte()
        System.arraycopy(ciphertext, 0, sealed, 2, ciphertext.size)
        return sealed
    }

    @JvmStatic
    fun decode(bytes: ByteArray): Packet {
        if (bytes.size < HEADER ||
            bytes[0] != PREAMBLE[0] || bytes[1] != PREAMBLE[1] || bytes[2] != PREAMBLE[2] ||
            bytes[bytes.size - 1] != EPILOGUE
        ) {
            throw IllegalArgumentException("Invalid SPP V1 envelope")
        }
        val size = (bytes[5].toInt() and 0xff) or ((bytes[6].toInt() and 0xff) shl 8)
        val payloadLength = size - 3
        if (payloadLength < 0 || HEADER + payloadLength != bytes.size) {
            throw IllegalArgumentException("Invalid SPP V1 payload length")
        }
        val payload = bytes.copyOfRange(10, 10 + payloadLength)
        val flags = bytes[4].toInt() and 0xff
        return Packet(
            bytes[3].toInt() and 0x0f,
            (flags and 0x80) != 0,
            (flags and 0x40) != 0,
            bytes[7].toInt() and 0xff,
            bytes[8].toInt() and 0xff,
            bytes[9].toInt() and 0xff,
            payload,
        )
    }

    class Decoder {
        private var pending = ByteArray(HEADER)
        private var used = 0
        private var expected = HEADER
        private var failed = false

        fun feed(chunk: ByteArray, offset: Int, length: Int): List<Packet> {
            Objects.checkFromIndexSize(offset, length, chunk.size)
            if (failed) throw IllegalStateException("SPP V1 decoder requires reset")
            val packets = ArrayList<Packet>()
            var pos = offset
            val end = offset + length
            while (pos < end) {
                val count = minOf(end - pos, expected - used)
                System.arraycopy(chunk, pos, pending, used, count)
                used += count
                pos += count
                if (used < expected) continue
                if (expected == HEADER) {
                    if (pending[0] != PREAMBLE[0] || pending[1] != PREAMBLE[1] || pending[2] != PREAMBLE[2]) {
                        throw reject("Invalid SPP V1 preamble")
                    }
                    val size = (pending[5].toInt() and 0xff) or ((pending[6].toInt() and 0xff) shl 8)
                    val payloadLength = size - 3
                    if (payloadLength < 0 || payloadLength > MAX_PAYLOAD) {
                        throw reject("Invalid SPP V1 payload length")
                    }
                    expected = HEADER + payloadLength
                    if (pending.size < expected) pending = pending.copyOf(expected)
                    if (used < expected) continue
                }
                packets.add(decode(pending.copyOf(expected)))
                pending.fill(0, 0, used)
                used = 0
                expected = HEADER
            }
            return packets
        }

        fun reset() {
            pending.fill(0)
            used = 0
            expected = HEADER
            failed = false
        }

        private fun reject(reason: String): IllegalArgumentException {
            pending.fill(0, 0, used)
            failed = true
            return IllegalArgumentException(reason)
        }
    }
}
