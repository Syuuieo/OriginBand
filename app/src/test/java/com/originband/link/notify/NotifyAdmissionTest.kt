// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

import com.originband.link.protocol.BandNotificationCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class NotifyAdmissionTest {
    @Test
    fun emptySelectionAllowsEveryPackageAndASavedSelectionDoesNot() {
        assertTrue(NotifyAdmission.packageAllowed(emptySet(), "com.tencent.mm"))
        assertFalse(NotifyAdmission.packageAllowed(setOf("com.tencent.mm"), "com.android.mms"))
        assertTrue(NotifyAdmission.packageAllowed(setOf("com.tencent.mm"), "com.tencent.mm"))
        assertFalse(NotifyAdmission.packageAllowed(emptySet(), " "))
    }

    @Test
    fun screenOnPushBlocksOnlyAnUnlockedLitScreen() {
        assertFalse(NotifyAdmission.screenBlocks(true, true, false))
        assertTrue(NotifyAdmission.screenBlocks(false, true, false))
        assertFalse(NotifyAdmission.screenBlocks(false, true, true))
        assertFalse(NotifyAdmission.screenBlocks(false, false, false))
    }

    @Test
    fun holdSurvivesUntilTheSessionAndDoesNotReplayAfterDrain() {
        val hold = NotifyHold()
        val first = event("old")
        hold.put(first)
        hold.put(event("new"))
        assertTrue(hold.contains("old"))
        assertEquals("new", hold.drain()[1].key())
        assertFalse(hold.contains("old"))
        hold.put(event("gone"))
        hold.remove("gone")
        assertTrue(hold.drain().isEmpty())
    }

    @Test
    fun holdDropsTheOldestWhenFull() {
        val hold = NotifyHold()
        for (i in 0..NotifyHold.CAPACITY) hold.put(event("k$i"))
        assertFalse(hold.contains("k0"))
        assertTrue(hold.contains("k${NotifyHold.CAPACITY}"))
    }

    private fun event(key: String): NotificationRelay.Event =
        NotificationRelay.Event(
            "pkg", "App", key, "Title", "body",
            null, null, Instant.EPOCH.toEpochMilli(), 1, false, false, false, 3,
        )

    @Test
    fun samePostTwiceIsOneDeliveryAndAnEditIsAnother() {
        val dedupe = NotifyDedupe()
        val first = BandNotificationCommand.post(
            "com.tencent.mm", "微信", "key", 1, "标题", "正文", Instant.EPOCH, ZoneId.of("UTC"),
        )
        val edit = BandNotificationCommand.post(
            "com.tencent.mm", "微信", "key", 1, "标题", "新正文", Instant.EPOCH, ZoneId.of("UTC"),
        )
        assertTrue(dedupe.first(NotifyDedupe.identity(first), 0))
        assertFalse(dedupe.first(NotifyDedupe.identity(first), 500))
        assertTrue(dedupe.first(NotifyDedupe.identity(edit), 500))
        assertTrue(dedupe.first(NotifyDedupe.identity(first), NotifyDedupe.WINDOW_MS + 1))
    }
}
