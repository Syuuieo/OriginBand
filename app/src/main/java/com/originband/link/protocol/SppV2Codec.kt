/*  Copyright (C) 2024 Yoran Vulker

    This file is adapted from Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program. If not, see <https://www.gnu.org/licenses/>. */
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import java.util.Objects

/** SPP V2 framing only; payloads remain encrypted where the data opcode requires it. */
object SppV2Codec {
    const val TYPE_NAK = 0
    const val TYPE_ACK = 1
    const val TYPE_SESSION_CONFIG = 2
    const val TYPE_DATA = 3
    const val HEADER_LENGTH = 8
    const val MAX_PAYLOAD_LENGTH = 0xffff
    const val CHANNEL_PROTOBUF = 1

    /** Plaintext bulk channel. Notification icons travel here after the type 22 accept. */
    const val CHANNEL_DATA = 2
    const val CHANNEL_ACTIVITY = 5
    const val OPCODE_PLAINTEXT = 1
    const val OPCODE_ENCRYPTED = 2

    class Frame @JvmOverloads constructor(
        val type: Int,
        val sequence: Int,
        payload: ByteArray,
        val frx: Boolean = false,
    ) {
        private val rawPayload: ByteArray

        init {
            requireType(type)
            requireByte(sequence, "Sequence must be an unsigned byte")
            if (payload.size > MAX_PAYLOAD_LENGTH || ((type == TYPE_ACK || type == TYPE_NAK) && payload.isNotEmpty())) {
                throw IllegalArgumentException("Invalid SPP V2 payload length")
            }
            rawPayload = payload.copyOf()
        }

        fun type(): Int = type

        fun sequence(): Int = sequence

        /** Bit 4 of the type byte. Official TransportL1 accepts these without an equality check. */
        fun frx(): Boolean = frx

        fun payload(): ByteArray = rawPayload.copyOf()
    }

    @JvmStatic
    fun encode(frame: Frame): ByteArray {
        val payload = frame.payload()
        val bytes = ByteArray(HEADER_LENGTH + payload.size)
        bytes[0] = 0xa5.toByte()
        bytes[1] = 0xa5.toByte()
        bytes[2] = (frame.type or if (frame.frx) 0x10 else 0).toByte()
        bytes[3] = frame.sequence.toByte()
        putU16(bytes, 4, payload.size)
        putU16(bytes, 6, crc16Arc(payload, 0, payload.size))
        System.arraycopy(payload, 0, bytes, HEADER_LENGTH, payload.size)
        return bytes
    }

    /** A malformed frame poisons this stream until reset. Recovery is a new session, not a skip inside the stream. */
    class Decoder @JvmOverloads constructor(private val maxPayloadLength: Int = MAX_PAYLOAD_LENGTH) {
        private var pending = ByteArray(HEADER_LENGTH)
        private var used = 0
        private var expected = HEADER_LENGTH
        private var failed = false

        init {
            if (maxPayloadLength < 0 || maxPayloadLength > MAX_PAYLOAD_LENGTH) {
                throw IllegalArgumentException("Invalid SPP V2 payload limit")
            }
        }

        fun feed(chunk: ByteArray, offset: Int, length: Int): List<Frame> {
            Objects.checkFromIndexSize(offset, length, chunk.size)
            if (failed) throw IllegalStateException("SPP V2 decoder requires reset")
            val frames = ArrayList<Frame>()
            var pos = offset
            val end = offset + length
            while (pos < end) {
                val count = minOf(end - pos, expected - used)
                System.arraycopy(chunk, pos, pending, used, count)
                used += count
                pos += count
                if (used < expected) continue
                if (expected == HEADER_LENGTH) {
                    if (pending[0] != 0xa5.toByte() || pending[1] != 0xa5.toByte()) {
                        throw reject("Invalid SPP V2 preamble")
                    }
                    val header = pending[2].toInt() and 0xff
                    val type = header and 0x0f
                    if ((header and 0xe0) != 0 || type > TYPE_DATA) {
                        throw reject("Unsupported SPP V2 type or header flags")
                    }
                    val payloadLength = u16(pending, 4)
                    if (payloadLength > maxPayloadLength ||
                        ((type == TYPE_ACK || type == TYPE_NAK) && payloadLength != 0)
                    ) {
                        throw reject("Invalid SPP V2 payload length")
                    }
                    expected = HEADER_LENGTH + payloadLength
                    if (pending.size < expected) pending = pending.copyOf(expected)
                    if (used < expected) continue
                }
                if (u16(pending, 6) != crc16Arc(pending, HEADER_LENGTH, expected - HEADER_LENGTH)) {
                    throw reject("SPP V2 payload checksum mismatch")
                }
                val header = pending[2].toInt() and 0xff
                frames.add(
                    Frame(
                        header and 0x0f,
                        pending[3].toInt() and 0xff,
                        pending.copyOfRange(HEADER_LENGTH, expected),
                        (header and 0x10) != 0,
                    ),
                )
                pending.fill(0, 0, used)
                used = 0
                expected = HEADER_LENGTH
            }
            return frames
        }

        fun reset() {
            pending.fill(0)
            used = 0
            expected = HEADER_LENGTH
            failed = false
        }

        private fun reject(reason: String): IllegalArgumentException {
            pending.fill(0, 0, used)
            failed = true
            return IllegalArgumentException(reason)
        }
    }

    class Data(bytes: ByteArray) {
        val flags: Int = (bytes[0].toInt() and 0xff) ushr 4
        val channel: Int = bytes[0].toInt() and 0x0f
        val opcode: Int = bytes[1].toInt() and 0xff
        private val rawPayload: ByteArray = bytes.copyOfRange(2, bytes.size)

        fun flags(): Int = flags

        fun channel(): Int = channel

        fun opcode(): Int = opcode

        fun payload(): ByteArray = rawPayload.copyOf()
    }

    /** Preserves unknown inner flags, channels and opcodes for the caller to reject explicitly. */
    @JvmStatic
    fun parseData(frame: Frame): Data {
        val payload = frame.payload()
        if (frame.type != TYPE_DATA || payload.size < 2) {
            throw IllegalArgumentException("Invalid SPP V2 data envelope")
        }
        return Data(payload)
    }

    /** The payload must already be encrypted for opcode 2. */
    @JvmStatic
    fun dataFrame(sequence: Int, channel: Int, opcode: Int, payload: ByteArray): Frame {
        if (channel < 0 || channel > 0x0f || payload.size > MAX_PAYLOAD_LENGTH - 2) {
            throw IllegalArgumentException("Invalid SPP V2 data envelope")
        }
        requireByte(opcode, "Opcode must be an unsigned byte")
        val bytes = ByteArray(2 + payload.size)
        bytes[0] = channel.toByte()
        bytes[1] = opcode.toByte()
        System.arraycopy(payload, 0, bytes, 2, payload.size)
        return Frame(TYPE_DATA, sequence, bytes, false)
    }

    /**
     * An ahead packet is NAK'd once. The same sequence again, or one more than
     * half a window behind, is accepted and the cursor resyncs. Channels 7 and 10
     * are outside this counter.
     */
    class ReceiveCursor {
        enum class Decision { TAKE, DROP, NAK }

        private var receivedCount = 0
        private var aheadSequence = -1

        fun expected(): Int = receivedCount and 255

        fun count(): Int = receivedCount

        fun offer(sequence: Int, frx: Boolean, channel: Int): Decision {
            if (channel == 7 || channel == 10) return Decision.DROP
            val expected = expected()
            if (frx || sequence == expected) {
                advanceTo(sequence + 1)
                return Decision.TAKE
            }
            val ahead = (sequence - expected) and 255
            if (ahead >= 128 || aheadSequence == sequence) {
                advanceTo(sequence + 1)
                return Decision.TAKE
            }
            aheadSequence = sequence
            return Decision.NAK
        }

        private fun advanceTo(next: Int) {
            receivedCount += (next - (receivedCount and 255)) and 255
            aheadSequence = -1
        }
    }

    class Parameter(val key: Int, value: ByteArray) {
        private val rawValue: ByteArray = value.copyOf()
        val value: ByteArray get() = rawValue.copyOf()

        fun key(): Int = key

        fun value(): ByteArray = rawValue.copyOf()

        operator fun component1(): Int = key

        operator fun component2(): ByteArray = rawValue.copyOf()

        fun copy(key: Int = this.key, value: ByteArray = this.rawValue): Parameter =
            Parameter(key, value)

        override fun equals(other: Any?): Boolean =
            this === other || (other is Parameter && key == other.key && rawValue.contentEquals(other.rawValue))

        override fun hashCode(): Int = 31 * key + rawValue.contentHashCode()

        override fun toString(): String = "Parameter(key=$key, value=${rawValue.contentToString()})"
    }

    /** Missing TLVs remain null; unknown TLVs remain available without assigning them a meaning. */
    class SessionConfig(
        val opcode: Int,
        version: ByteArray?,
        val maxPacketSize: Int?,
        val txWindow: Int?,
        val sendTimeoutMillis: Int?,
        unknownParameters: List<Parameter>,
    ) {
        private val rawVersion: ByteArray? = version?.copyOf()
        val version: ByteArray? get() = rawVersion?.copyOf()
        val unknownParameters: List<Parameter> = java.util.List.copyOf(unknownParameters)

        fun opcode(): Int = opcode

        fun version(): ByteArray? = rawVersion?.copyOf()

        fun maxPacketSize(): Int? = maxPacketSize

        fun txWindow(): Int? = txWindow

        fun sendTimeoutMillis(): Int? = sendTimeoutMillis

        fun unknownParameters(): List<Parameter> = unknownParameters

        operator fun component1(): Int = opcode

        operator fun component2(): ByteArray? = rawVersion?.copyOf()

        operator fun component3(): Int? = maxPacketSize

        operator fun component4(): Int? = txWindow

        operator fun component5(): Int? = sendTimeoutMillis

        operator fun component6(): List<Parameter> = unknownParameters

        fun copy(
            opcode: Int = this.opcode,
            version: ByteArray? = this.rawVersion,
            maxPacketSize: Int? = this.maxPacketSize,
            txWindow: Int? = this.txWindow,
            sendTimeoutMillis: Int? = this.sendTimeoutMillis,
            unknownParameters: List<Parameter> = this.unknownParameters,
        ): SessionConfig = SessionConfig(opcode, version, maxPacketSize, txWindow, sendTimeoutMillis, unknownParameters)

        override fun equals(other: Any?): Boolean =
            this === other || (
                other is SessionConfig &&
                    opcode == other.opcode &&
                    (if (rawVersion == null) other.rawVersion == null else other.rawVersion != null && rawVersion.contentEquals(other.rawVersion)) &&
                    maxPacketSize == other.maxPacketSize &&
                    txWindow == other.txWindow &&
                    sendTimeoutMillis == other.sendTimeoutMillis &&
                    unknownParameters == other.unknownParameters
                )

        override fun hashCode(): Int {
            var result = opcode
            result = 31 * result + (rawVersion?.contentHashCode() ?: 0)
            result = 31 * result + (maxPacketSize ?: 0)
            result = 31 * result + (txWindow ?: 0)
            result = 31 * result + (sendTimeoutMillis ?: 0)
            result = 31 * result + unknownParameters.hashCode()
            return result
        }

        override fun toString(): String =
            "SessionConfig(opcode=$opcode, version=${rawVersion?.contentToString()}, maxPacketSize=$maxPacketSize, txWindow=$txWindow, sendTimeoutMillis=$sendTimeoutMillis, unknownParameters=$unknownParameters)"
    }

    @JvmStatic
    fun parseSessionConfig(frame: Frame): SessionConfig {
        val payload = frame.payload()
        if (frame.type != TYPE_SESSION_CONFIG || payload.isEmpty()) {
            throw IllegalArgumentException("Invalid SPP V2 session envelope")
        }
        val opcode = payload[0].toInt() and 0xff
        if (opcode < 1 || opcode > 4 || (opcode >= 3 && payload.size != 1)) {
            throw IllegalArgumentException("Unsupported SPP V2 session opcode or payload")
        }
        var version: ByteArray? = null
        var maxPacketSize: Int? = null
        var txWindow: Int? = null
        var sendTimeoutMillis: Int? = null
        val seen = BooleanArray(256)
        val unknown = ArrayList<Parameter>()
        var offset = 1
        while (offset < payload.size) {
            if (payload.size - offset < 3) {
                throw IllegalArgumentException("Truncated SPP V2 session TLV header")
            }
            val key = payload[offset].toInt() and 0xff
            val size = u16(payload, offset + 1)
            offset += 3
            if (seen[key] || size > payload.size - offset) {
                throw IllegalArgumentException("Duplicate or truncated SPP V2 session TLV")
            }
            seen[key] = true
            if ((key == 1 && size != 3) || (key in 2..4 && size != 2)) {
                throw IllegalArgumentException("Invalid SPP V2 session TLV size")
            }
            when (key) {
                1 -> version = payload.copyOfRange(offset, offset + size)
                2 -> maxPacketSize = u16(payload, offset)
                3 -> txWindow = u16(payload, offset)
                4 -> sendTimeoutMillis = u16(payload, offset)
                else -> unknown.add(Parameter(key, payload.copyOfRange(offset, offset + size)))
            }
            offset += size
        }
        return SessionConfig(
            opcode,
            version,
            maxPacketSize,
            txWindow,
            sendTimeoutMillis,
            java.util.List.copyOf(unknown),
        )
    }

    private fun crc16Arc(bytes: ByteArray, offset: Int, length: Int): Int {
        var crc = 0
        for (i in offset until offset + length) {
            crc = crc xor (bytes[i].toInt() and 0xff)
            for (bit in 0 until 8) {
                crc = (crc ushr 1) xor if ((crc and 1) != 0) 0xa001 else 0
            }
        }
        return crc
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun requireType(type: Int) {
        if (type < TYPE_NAK || type > TYPE_DATA) {
            throw IllegalArgumentException("Unsupported SPP V2 packet type")
        }
    }

    private fun requireByte(value: Int, reason: String) {
        if (value < 0 || value > 0xff) throw IllegalArgumentException(reason)
    }
}
