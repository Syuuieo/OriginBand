/*  Copyright (C) 2023-2024 José Rebelo, Yoran Vulker

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

/** Only the observed version exchange uses the legacy envelope; all subsequent frames use V2. */
object SppNegotiation {
    const val VERSION_RESPONSE_LENGTH = 14

    /** The caller owns the serial counter. A response may carry a different value. */
    @JvmStatic
    fun versionRequest(opcodeSerial: Int): ByteArray {
        if (opcodeSerial < 0 || opcodeSerial > 0xff) {
            throw IllegalArgumentException("Startup opcode serial must be an unsigned byte")
        }
        return byteArrayOf(
            0xba.toByte(), 0xdc.toByte(), 0xfe.toByte(), 0, 0xc0.toByte(),
            3, 0, 0, opcodeSerial.toByte(), 0, 0xef.toByte(),
        )
    }

    data class VersionResponse(val opcodeSerial: Int, val major: Int, val minor: Int, val patch: Int) {
        fun opcodeSerial(): Int = opcodeSerial
        fun major(): Int = major
        fun minor(): Int = minor
        fun patch(): Int = patch
        fun apiVersion(): Int = (major shl 16) or (minor shl 8) or patch
        fun versionName(): String = "$major.$minor.$patch"
    }

    /** Read exactly VERSION_RESPONSE_LENGTH bytes, retaining later socket bytes for the V2 decoder. */
    @JvmStatic
    fun decodeVersionResponse(bytes: ByteArray): VersionResponse {
        if (bytes.size != VERSION_RESPONSE_LENGTH ||
            bytes[0] != 0xba.toByte() || bytes[1] != 0xdc.toByte() || bytes[2] != 0xfe.toByte() ||
            bytes[3] != 0.toByte() || bytes[4] != 0.toByte() || bytes[5] != 6.toByte() || bytes[6] != 0.toByte() ||
            bytes[7] != 1.toByte() || bytes[9] != 0.toByte() || bytes[13] != 0xef.toByte()
        ) {
            throw IllegalArgumentException("Unsupported SPP startup version envelope")
        }
        return VersionResponse(
            opcodeSerial = bytes[8].toInt() and 0xff,
            major = bytes[10].toInt() and 0xff,
            minor = bytes[11].toInt() and 0xff,
            patch = bytes[12].toInt() and 0xff,
        )
    }

    /** Matches the phone's observed request, including its independent session sequence zero. */
    @JvmStatic
    fun startSessionRequest(): SppV2Codec.Frame =
        SppV2Codec.Frame(
            SppV2Codec.TYPE_SESSION_CONFIG,
            0,
            byteArrayOf(
                1,
                1, 3, 0, 1, 0, 0,
                2, 2, 0, 0, 0xfc.toByte(),
                3, 2, 0, 0x20, 0,
                4, 2, 0, 0x10, 0x27,
            ),
        )
}
