// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

import com.originband.link.protocol.BandNotificationCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class NotifyReplayTest {
    companion object {
        private val WHEN: Instant = Instant.parse("2026-10-01T00:00:00Z")
        private val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    }

    @Test
    fun sameNotificationWaitsOnceAndADismissBeforeSendDropsIt() {
        val hold = NotifyReplay()
        val first = hold.add(
            BandNotificationCommand.post("com.example", "Example", "k", 1, "Hi", "one", WHEN, ZONE),
            0,
        )
        val second = hold.add(
            BandNotificationCommand.post("com.example", "Example", "k", 1, "Hi", "one", WHEN, ZONE),
            1_000,
        )
        assertEquals(1, hold.size())
        val dismissed = hold.add(BandNotificationCommand.dismiss("com.example", "k", 1), 2_000)
        assertEquals(0, hold.size())
        assertTrue(first.isDone && !first.isCompletedExceptionally)
        assertTrue(second.isDone && !second.isCompletedExceptionally)
        assertTrue(dismissed.isDone && !dismissed.isCompletedExceptionally)
    }

    @Test
    fun dismissWithoutAHeldPostIsStillSent() {
        val hold = NotifyReplay()
        hold.add(BandNotificationCommand.dismiss("com.example", "k", 1), 0)
        assertEquals(1, hold.poll(0).size)
    }

    @Test
    fun callsExpireBeforeOrdinaryNotifications() {
        val hold = NotifyReplay()
        val call = hold.add(BandNotificationCommand.incomingCall("Ada", WHEN, ZONE), 0)
        val note = hold.add(
            BandNotificationCommand.post("com.example", "Example", "k", 1, "Hi", "one", WHEN, ZONE),
            0,
        )
        assertEquals(1, hold.expire(NotifyReplay.CALL_MS))
        assertTrue(call.isCompletedExceptionally)
        assertFalse(note.isDone)
        assertEquals(1, hold.expire(NotifyReplay.NOTIFICATION_MS))
        assertTrue(note.isCompletedExceptionally)
        assertTrue(hold.poll(NotifyReplay.NOTIFICATION_MS).isEmpty())
    }

    @Test
    fun endingAHeldCallDropsIt() {
        val hold = NotifyReplay()
        val call = hold.add(BandNotificationCommand.incomingCall("Ada", WHEN, ZONE), 0)
        val end = hold.add(BandNotificationCommand.endCall(), 1_000)
        assertEquals(0, hold.size())
        assertTrue(call.isDone && !call.isCompletedExceptionally)
        assertTrue(end.isDone && !end.isCompletedExceptionally)
    }

    @Test
    fun oldestIsDroppedWhenFull() {
        val hold = NotifyReplay()
        lateinit var oldest: CompletableFuture<Void?>
        for (i in 0 until NotifyReplay.LIMIT) {
            val added = hold.add(
                BandNotificationCommand.post("com.example", "Example", "k$i", i, "Hi", "n$i", WHEN, ZONE),
                0,
            )
            if (i == 0) oldest = added
        }
        hold.add(
            BandNotificationCommand.post("com.example", "Example", "fresh", 99, "Hi", "new", WHEN, ZONE),
            0,
        )
        assertEquals(NotifyReplay.LIMIT, hold.size())
        assertEquals("NOTIFY_HOLD_DROPPED", causeMessage(oldest))
    }

    private fun causeMessage(future: CompletableFuture<Void?>): String? =
        try {
            future.get(1, TimeUnit.SECONDS)
            ""
        } catch (error: Exception) {
            val cause = error.cause ?: error
            cause.message
        }
}
