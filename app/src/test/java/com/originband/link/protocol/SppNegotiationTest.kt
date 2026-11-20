// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.HexFormat

class SppNegotiationTest {
    companion object {
        // The first two official reconnect frames contain only envelope fields and protocol version.
        private val REQUEST = HexFormat.of().parseHex("badcfe00c00300000300ef")
        private val RESPONSE = HexFormat.of().parseHex("badcfe0000060001020003020fef")
    }

    @Test
    fun capturedStartupRequestAndResponseHaveIndependentSerials() {
        assertArrayEquals(REQUEST, SppNegotiation.versionRequest(3))
        val response = SppNegotiation.decodeVersionResponse(RESPONSE)
        assertEquals(2, response.opcodeSerial)
        assertEquals("3.2.15", response.versionName())
        assertEquals(197135, response.apiVersion())
    }

    @Test
    fun startupEnvelopeMustBeCompleteAndTerminated() {
        for (size in RESPONSE.indices) {
            val truncated = RESPONSE.copyOf(size)
            assertThrows(IllegalArgumentException::class.java) {
                SppNegotiation.decodeVersionResponse(truncated)
            }
        }
        val damaged = RESPONSE.clone().also {
            it[it.lastIndex] = (it[it.lastIndex].toInt() xor 1).toByte()
        }
        assertThrows(IllegalArgumentException::class.java) {
            SppNegotiation.decodeVersionResponse(damaged)
        }
    }
}
