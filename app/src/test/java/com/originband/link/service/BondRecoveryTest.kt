// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A removed bond used to leave the session in a two minute loop with no repair. */
class BondRecoveryTest {
    @Test
    fun lostBondWakesTheWorkerAndRestoredBondWakesIt() {
        val recovery = BondRecovery()
        assertTrue("a removed bond is news", recovery.note(BondRecovery.NONE))
        assertTrue("a restored bond is news", recovery.note(BondRecovery.BONDED))
        assertFalse("pairing in progress changes nothing yet", recovery.note(BondRecovery.BONDING))
    }

    @Test
    fun asksForTheBondOncePerLoss() {
        val recovery = BondRecovery()
        val now = 1_000L
        assertEquals(BondRecovery.ASK, recovery.decide(BondRecovery.NONE, now))
        recovery.asked(now)
        // The band confirm prompt lives on the user's wrist, so never nag automatically.
        assertEquals(
            "no second automatic ask, however long the gap",
            BondRecovery.GAVE_UP,
            recovery.decide(BondRecovery.NONE, now + BondRecovery.ASK_GAP_MS)
        )
        assertEquals(BondRecovery.GAVE_UP, recovery.decide(BondRecovery.NONE, now + 60 * 60_000L))
        assertEquals("one request per loss", BondRecovery.MAX_ASKS, recovery.asks())
        assertTrue(recovery.gaveUp())
    }

    @Test
    fun pairingInProgressAndRestoredBondSkipTheRequest() {
        val recovery = BondRecovery()
        assertEquals(BondRecovery.WAIT, recovery.decide(BondRecovery.BONDING, 5_000L))
        assertEquals(BondRecovery.READY, recovery.decide(BondRecovery.BONDED, 5_000L))
        assertEquals("a restored bond spends no request", 0, recovery.asks())
        assertEquals(BondRecovery.ASK, recovery.decide(BondRecovery.NONE, 5_000L))
        recovery.asked(5_000L)
        // With a single ask the gap branch is unreachable: one request already spent the loss.
        assertEquals(BondRecovery.GAVE_UP, recovery.decide(BondRecovery.NONE, 5_001L))
    }

    @Test
    fun manualRetryAuthAndRestoredBondRefillTheBudget() {
        val recovery = BondRecovery()
        recovery.asked(1_000L)
        assertEquals(BondRecovery.GAVE_UP, recovery.decide(BondRecovery.NONE, 100_000L))
        recovery.manualRetry()
        assertEquals(
            "the 重新配对 tap expects the next wrist prompt",
            BondRecovery.ASK,
            recovery.decide(BondRecovery.NONE, 100_000L)
        )
        recovery.asked(100_000L)
        assertEquals(BondRecovery.GAVE_UP, recovery.decide(BondRecovery.NONE, 200_000L))
        recovery.connected()
        assertEquals(
            "an authenticated session is a fresh start",
            BondRecovery.ASK,
            recovery.decide(BondRecovery.NONE, 200_000L)
        )
        recovery.asked(200_000L)
        recovery.note(BondRecovery.BONDED)
        assertEquals("the official app re-pairing is a fresh start", 0, recovery.asks())
    }

    @Test
    fun noticeAsksForATapAfterTheOneAsk() {
        assertEquals("正在重新配对", BondRecovery.notice(false))
        assertEquals("手环需要重新配对，点通知重试", BondRecovery.notice(true))
    }
}
