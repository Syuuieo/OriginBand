// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class BandClockCommandTest {
    @Test
    fun sameInstantUsesLocalDateAndExactDstOffset() {
        val instant = Instant.parse("2026-07-01T23:59:59.321Z")
        val shanghai = BandClockCommand.at(instant, ZoneId.of("Asia/Shanghai"), true)
        val china = shanghai.system.clock
        assertEquals(2, shanghai.type)
        assertEquals(3, shanghai.subtype)
        assertEquals(2026, china.date.year)
        assertEquals(7, china.date.month)
        assertEquals(2, china.date.day)
        assertEquals(7, china.time.hour)
        assertEquals(59, china.time.minute)
        assertEquals(321, china.time.millisecond)
        assertEquals(32, china.timezone.zoneOffset)
        assertEquals(0, china.timezone.dstOffset)
        assertFalse(china.isNot24Hour)

        val newYork = BandClockCommand.at(instant, ZoneId.of("America/New_York"), false)
            .system.clock
        assertEquals(19, newYork.time.hour)
        assertEquals(-20, newYork.timezone.zoneOffset)
        assertEquals(4, newYork.timezone.dstOffset)
        assertTrue(newYork.isNot24Hour)
    }
}
