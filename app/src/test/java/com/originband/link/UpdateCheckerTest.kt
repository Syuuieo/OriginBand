// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun parseLsposedTag() {
        val parsed = UpdateChecker.parseTag("2-0.2.0", "ignored")
        assertEquals(2, parsed.code)
        assertEquals("0.2.0", parsed.name)
    }

    @Test
    fun parseVPrefix() {
        val parsed = UpdateChecker.parseTag("v0.2.0", "fallback")
        assertEquals(0, parsed.code)
        assertEquals("0.2.0", parsed.name)
    }
}
