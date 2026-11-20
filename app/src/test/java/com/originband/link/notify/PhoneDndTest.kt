// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneDndTest {
    @Test
    fun onlyRealDndFiltersBlockPushes() {
        assertFalse(PhoneDnd.blocksNotifications(PhoneDnd.UNKNOWN))
        assertFalse(PhoneDnd.blocksNotifications(PhoneDnd.ALL))
        assertTrue(PhoneDnd.blocksNotifications(PhoneDnd.PRIORITY))
        assertTrue(PhoneDnd.blocksNotifications(PhoneDnd.NONE))
        assertTrue(PhoneDnd.blocksNotifications(PhoneDnd.ALARMS))
    }

    @Test
    fun bandManualAppliesOnlyWhenItDisagreesAndTheWriteHasSettled() {
        assertFalse(PhoneDnd.acceptBandManual(true, PhoneDnd.PRIORITY, 0, 5_000_000_000L))
        assertFalse(PhoneDnd.acceptBandManual(false, PhoneDnd.ALL, 0, 5_000_000_000L))
        assertFalse(PhoneDnd.acceptBandManual(true, PhoneDnd.UNKNOWN, 0, 5_000_000_000L))
        assertFalse(PhoneDnd.acceptBandManual(true, PhoneDnd.ALL, 1_000_000_000L, 2_000_000_000L))
        assertTrue(PhoneDnd.acceptBandManual(true, PhoneDnd.ALL, 1_000_000_000L, 5_000_000_000L))
        assertTrue(PhoneDnd.acceptBandManual(false, PhoneDnd.NONE, 0, 1L))
    }

    @Test
    fun oneDndChangeDoesNotSyncTwiceInsideASecond() {
        assertFalse(PhoneDnd.repeatSync(Int.MIN_VALUE, -1, PhoneDnd.PRIORITY))
        assertTrue(PhoneDnd.repeatSync(PhoneDnd.PRIORITY, 504_000_000L, PhoneDnd.PRIORITY))
        assertFalse(PhoneDnd.repeatSync(PhoneDnd.PRIORITY, 1_000_000_000L, PhoneDnd.PRIORITY))
        assertFalse(PhoneDnd.repeatSync(PhoneDnd.PRIORITY, 100_000_000L, PhoneDnd.ALL))
        assertFalse(PhoneDnd.repeatSync(PhoneDnd.ALL, -1, PhoneDnd.ALL))
    }

    @Test
    fun vendorFocusOrBreathTurnsDndOnWhileZenStaysOff() {
        assertEquals(PhoneDnd.PRIORITY, PhoneDnd.resolve(0, -1, -1, 1, PhoneDnd.ALL))
        assertEquals(PhoneDnd.PRIORITY, PhoneDnd.resolve(0, 1, -1, -1, PhoneDnd.ALL))
        assertEquals(PhoneDnd.PRIORITY, PhoneDnd.resolve(0, 0, 1, 0, PhoneDnd.ALL))
        assertEquals(PhoneDnd.ALL, PhoneDnd.resolve(0, 0, 0, 0, PhoneDnd.PRIORITY))
        assertEquals(PhoneDnd.ALL, PhoneDnd.resolve(0, -1, -1, -1, PhoneDnd.PRIORITY))
        assertEquals(PhoneDnd.NONE, PhoneDnd.resolve(2, 0, 0, 0, PhoneDnd.ALL))
        assertEquals(PhoneDnd.ALARMS, PhoneDnd.resolve(3, -1, -1, -1, PhoneDnd.ALL))
    }

    @Test
    fun zenModeUsesTheValuesThisPhoneReports() {
        assertEquals(0, PhoneDnd.zenMode(false))
        assertEquals(1, PhoneDnd.zenMode(true))
    }

    @Test
    fun unchangedPhoneDoesNotOverwriteADifferentBand() {
        assertFalse(PhoneDnd.sendPhoneRule(false, false, false, true))
        assertFalse(PhoneDnd.sendPhoneRule(false, false, true, true))
        assertTrue(PhoneDnd.sendPhoneRule(false, false, true, false))
        assertTrue(PhoneDnd.sendPhoneRule(true, false, true, true))
        assertTrue(PhoneDnd.sendPhoneRule(true, true, false, false))
    }
}
