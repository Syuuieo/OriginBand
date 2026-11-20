// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class BandAlarmCommandTest {
    @Test
    fun alertCarriesOpIdTimeAndLabel() {
        val command = BandAlarmCommand.operation(0, 7, 1_700_000_000, "起床")
        val alarm = command.schedule.phoneAlarmOperation.phoneAlarm
        assertEquals(17, command.type)
        assertEquals(16, command.subtype)
        assertEquals(0, command.schedule.phoneAlarmOperation.opCode)
        assertEquals(7, alarm.id)
        assertEquals(1_700_000_000, alarm.alertTime)
        assertEquals("起床", alarm.label)
    }

    @Test
    fun negativeTimeOmitsAlertTimeAndNullLabelIsEmpty() {
        val alarm = BandAlarmCommand.operation(1, 7, -1, null)
            .schedule.phoneAlarmOperation.phoneAlarm
        assertEquals("", alarm.label)
        assertFalse(alarm.hasAlertTime())
    }

    @Test
    fun unknownOpIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            BandAlarmCommand.operation(9, 1, 0, "")
        }
    }
}
