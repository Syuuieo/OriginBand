// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

class DozeWhitelistTest {
    @Test
    fun matchesThePackageFieldAfterSystemEntries() {
        val list = StringBuilder()
        for (i in 0 until 40) {
            list.append("system-excidle,com.android.pkg").append(i).append(",1000\n")
        }
        assertTrue(list.length > 256)
        assertFalse(list.substring(0, 256).contains(BRIDGE))
        list.append("user,").append(BRIDGE).append(",10321\n")
        assertTrue(OwnershipController.whitelistContains(list.toString(), BRIDGE))
    }

    @Test
    fun rejectsALongerPackageThatContainsTheName() {
        val list = "user,$BRIDGE.extra,10321\n"
        assertFalse(OwnershipController.whitelistContains(list, BRIDGE))
    }

    @Test
    @Throws(Exception::class)
    fun readsTheWholeStreamUpToTheLimit() {
        val body = "user,com.example,1\n".toByteArray(StandardCharsets.UTF_8)
        assertEquals(
            "user,com.example,1\n",
            OwnershipController.readLimited(ByteArrayInputStream(body), body.size)
        )
    }

    @Test
    @Throws(Exception::class)
    fun refusesATruncatedStream() {
        val body = "system-excidle,com.android.settings,1000\n".toByteArray(StandardCharsets.UTF_8)
        try {
            OwnershipController.readLimited(ByteArrayInputStream(body), 8)
            fail("truncated output must not look like a complete whitelist")
        } catch (limited: IOException) {
            assertEquals("OUTPUT_LIMIT", limited.message)
        }
    }

    companion object {
        private const val BRIDGE = "com.originband.link"
    }
}
