// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class AuthTokenTest {
    companion object {
        private const val TOKEN = "0123456789abcdef0123456789abcdef"
        private const val ENCRYPT_KEY = "fedcba9876543210fedcba9876543210"
    }

    @Test
    fun firstHex32SkipsInvalidThenReturns32Hex() {
        assertEquals(TOKEN, AuthToken.firstHex32("", "nothex", TOKEN))
    }

    @Test
    fun emptyTokenThenEncryptKeyWinsAndAllBlankIsEmpty() {
        assertEquals(ENCRYPT_KEY, AuthToken.firstHex32("", ENCRYPT_KEY))
        assertEquals("", AuthToken.firstHex32("", null, "short", "not-hex"))
        assertEquals("", AuthToken.firstHex32())
        assertEquals("", AuthToken.firstHex32(null))
    }

    @Test
    fun fromKeyBytesRequiresExactly16Bytes() {
        val key = ByteArray(16) { 0x01 }
        assertEquals("01010101010101010101010101010101", AuthToken.fromKeyBytes(key))
        assertEquals("", AuthToken.fromKeyBytes(null))
        assertEquals("", AuthToken.fromKeyBytes(ByteArray(15)))
    }

    @Test
    fun normalizeAcceptsSpacedHexAndBase64Key() {
        assertEquals(TOKEN, AuthToken.normalize("0123456789ABCDEF 0123456789ABCDEF"))
        assertEquals(TOKEN, AuthToken.normalize("0x$TOKEN"))
        val key = ByteArray(16) { it.toByte() }
        val base64 = Base64.getEncoder().encodeToString(key)
        assertEquals(AuthToken.fromKeyBytes(key), AuthToken.normalize(base64))
        assertEquals("", AuthToken.normalize("not-a-key"))
        assertEquals(AuthToken.fromKeyBytes(key), AuthToken.firstKey("not-a-key", base64))
    }

    @Test
    fun hex32RejectsWrongLengthAndNonHex() {
        assertTrue(AuthToken.hex32(TOKEN))
        assertFalse(AuthToken.hex32(TOKEN.substring(1)))
        assertFalse(AuthToken.hex32("0123456789abcdef0123456789abcdeg"))
        assertFalse(AuthToken.hex32(null))
    }
}
