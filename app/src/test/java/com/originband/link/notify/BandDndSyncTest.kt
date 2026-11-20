// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BandDndSyncTest {
    @Test
    fun aFreshLinkAsksBeforeItPushesAnything() {
        val sync = BandDndSync()
        assertEquals(BandDndSync.Next.ASK, sync.before())
        assertTrue(sync.waiting())
        assertEquals(BandDndSync.Next.MIRROR, sync.before())
    }

    @Test
    fun theBandAnsweringOffStopsTheMirrorUntilTheUserTurnsItBackOn() {
        val sync = BandDndSync()
        assertEquals(BandDndSync.Next.ASK, sync.before())
        assertFalse(sync.answered(false))
        assertFalse(sync.on())
        assertFalse(sync.waiting())
        assertEquals(BandDndSync.Next.HOLD, sync.before())
        assertEquals(BandDndSync.Next.HOLD, sync.before())
        assertTrue(sync.answered(true))
        assertTrue(sync.on())
        assertEquals(BandDndSync.Next.MIRROR, sync.before())
    }

    @Test
    fun aQuietBandKeepsTheStoredSwitch() {
        val stored = BandDndSync()
        stored.seed(false)
        assertEquals(BandDndSync.Next.ASK, stored.before())
        assertFalse(stored.answered(null))
        assertFalse(stored.on())

        val stillOn = BandDndSync()
        assertEquals(BandDndSync.Next.ASK, stillOn.before())
        assertTrue(stillOn.answered(null))
        assertTrue(stillOn.on())
    }

    @Test
    fun anAnswerOnTheSecondAskDoesNotTurnSyncOffWithTheFirst() {
        val sync = BandDndSync()
        assertEquals(BandDndSync.Next.ASK, sync.before())
        assertTrue(sync.answered(true))
        // Pushed mid-link when the user moved the switch on the band, not as a reply.
        assertFalse(sync.answered(false))
        assertEquals(BandDndSync.Next.HOLD, sync.before())
        assertTrue(sync.answered(true))
    }

    @Test
    fun aNewLinkForgetsTheAnswerButNotTheSwitch() {
        val sync = BandDndSync()
        assertEquals(BandDndSync.Next.ASK, sync.before())
        assertFalse(sync.answered(false))
        sync.newLink()
        assertFalse(sync.waiting())
        assertFalse(sync.on())
        assertEquals(BandDndSync.Next.ASK, sync.before())
    }

    @Test
    fun theStoredSwitchSeedsTheNextLink() {
        val sync = BandDndSync()
        sync.seed(false)
        sync.newLink()
        assertEquals(BandDndSync.Next.ASK, sync.before())
        // Answered off again: no mirror, and nothing changes.
        assertFalse(sync.answered(false))
        assertEquals(BandDndSync.Next.HOLD, sync.before())
    }
}
