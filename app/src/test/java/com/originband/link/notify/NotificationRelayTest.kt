// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.function.IntSupplier

class NotificationRelayTest {
    private class Harness {
        val commands = ArrayList<XiaomiProto.Command>()
        val receipts = ArrayList<CompletableFuture<Void?>>()
        val relay = NotificationRelay(
            NotificationRelay.Sender { command ->
                commands.add(command)
                val receipt = CompletableFuture<Void?>()
                receipts.add(receipt)
                receipt
            },
            IntSupplier { 512 },
            Executor { it.run() },
        )

        init {
            relay.configure(true, setOf("allowed"), true)
            relay.connected(emptyMap())
        }

        fun ack(index: Int) {
            receipts[index].complete(null)
        }

        fun data(index: Int): XiaomiProto.Notification3 =
            commands[index].notification.notification2.notification3
    }

    private fun event(key: String, body: String, time: Long): NotificationRelay.Event =
        NotificationRelay.Event(
            "allowed", "App", key, "Title", body,
            null, null, time, 1, false, false, false, 3,
        )

    @Test
    fun onlyAllowedAlertingNonSecretContentIsForwarded() {
        val h = Harness()
        h.relay.posted(NotificationRelay.Event("other", "App", "a", "Title", "secret", null, null, 1, 1, false, false, false, 3))
        h.relay.posted(NotificationRelay.Event("allowed", "App", "b", "Title", "secret", null, null, 1, -1, false, false, false, 3))
        h.relay.posted(NotificationRelay.Event("allowed", "App", "c", "Title", "low", null, null, 1, 1, false, false, false, 2))
        h.relay.posted(NotificationRelay.Event("allowed", "App", "d", "Title", "fgs", null, null, 1, 1, false, true, false, 3))
        h.relay.posted(NotificationRelay.Event("allowed", "App", "e", "Title", "summary", null, null, 1, 1, false, false, true, 3))
        assertTrue(h.commands.isEmpty())
        h.relay.posted(event("f", "visible", 1))
        assertEquals("visible", h.data(0).body)
    }

    @Test
    fun emptyPackageSelectionForwardsAnAlert() {
        val h = Harness()
        h.relay.configure(true, emptySet(), true)
        h.relay.connected(emptyMap())
        h.relay.posted(event("open", "visible", 1))
        assertEquals("visible", h.data(0).body)
    }

    @Test
    fun baselineIsNotReplayedButLaterUpdateCanBeDelivered() {
        val h = Harness()
        h.relay.connected(mapOf("key" to 1L))
        h.relay.posted(event("key", "old", 1))
        h.relay.removed("key")
        assertTrue(h.commands.isEmpty())
        h.relay.posted(event("key", "new", 2))
        assertEquals("new", h.data(0).body)
    }

    @Test
    fun updatesCoalesceWhileInFlightAndKeepTheHandle() {
        val h = Harness()
        h.relay.posted(event("key", "A", 1))
        h.relay.posted(event("key", "B", 2))
        h.relay.posted(event("key", "C", 3))
        assertEquals(1, h.commands.size)
        h.ack(0)
        assertEquals("C", h.data(1).body)
        assertEquals(h.data(0).id, h.data(1).id)
        h.ack(1)
        h.relay.posted(event("key", "C", 4))
        assertEquals(2, h.commands.size)
    }

    @Test
    fun removeBeforeAckClearsOnlyAfterSuccessfulDelivery() {
        val h = Harness()
        h.relay.posted(event("key", "A", 1))
        h.relay.removed("key")
        assertEquals(1, h.commands.size)
        h.ack(0)
        val clear = h.commands[1].notification.notificationDismiss.getNotificationId(0)
        assertEquals(h.data(0).id, clear.id)
        assertEquals("key", clear.key)
        h.ack(1)
        h.relay.removed("key")
        assertEquals(2, h.commands.size)
    }

    @Test
    fun failedPostAndUnknownRemovalNeverClearUndeliveredNotification() {
        val h = Harness()
        h.relay.removed("unknown")
        h.relay.posted(event("key", "A", 1))
        h.relay.removed("key")
        h.receipts[0].completeExceptionally(IllegalStateException())
        assertEquals(1, h.commands.size)
        assertEquals("NOTIFICATION_TRANSPORT_FAILED", h.relay.lastFailureCode())
    }

    @Test
    fun disconnectDiscardsUpdatesAndStaleAcknowledgements() {
        val h = Harness()
        h.relay.posted(event("key", "A", 1))
        h.relay.posted(event("key", "B", 2))
        h.relay.disconnected()
        h.relay.posted(event("offline", "private", 3))
        h.relay.connected(mapOf("offline" to 3L))
        h.ack(0)
        h.relay.posted(event("offline", "private", 3))
        assertEquals(1, h.commands.size)
        h.relay.posted(event("fresh", "C", 4))
        assertEquals("C", h.data(1).body)
    }

    @Test
    fun lockedPrivateUsesPublicContentOrTitleOnly() {
        val h = Harness()
        h.relay.posted(
            NotificationRelay.Event(
                "allowed", "App", "a", "Private title", "Private body",
                "Public title", "Public body", 1, 0, true, false, false, 3,
            ),
        )
        assertEquals("Public title", h.data(0).title)
        assertEquals("Public body", h.data(0).body)
        h.relay.posted(
            NotificationRelay.Event(
                "allowed", "App", "b", "Title only", "Private body",
                null, null, 1, 0, true, false, false, 3,
            ),
        )
        assertEquals("Title only", h.data(1).title)
        assertFalse(h.data(1).hasBody())
        h.relay.configure(true, setOf("allowed"), false)
        h.relay.posted(event("c", "hidden by setting", 2))
        assertFalse(h.data(2).hasBody())
    }

    @Test
    fun revokedSettingsDropPendingUpdatesEvenAfterAck() {
        val h = Harness()
        h.relay.posted(event("key", "A", 1))
        h.relay.posted(event("key", "B", 2))
        h.relay.configure(false, setOf("allowed"), true)
        h.ack(0)
        h.relay.posted(event("new", "C", 3))
        assertEquals(1, h.commands.size)
    }

    @Test
    fun repostDuringClearWaitsAndRestoresLatestContent() {
        val h = Harness()
        h.relay.posted(event("key", "A", 1))
        h.ack(0)
        h.relay.removed("key")
        h.relay.posted(event("key", "B", 2))
        assertEquals(2, h.commands.size)
        h.ack(1)
        assertEquals("B", h.data(2).body)
        assertEquals(h.data(0).id, h.data(2).id)
    }

    @Test
    fun lockingDropsQueuedContentButRetainsDeliveredRemovalHandle() {
        val h = Harness()
        h.relay.posted(event("key", "already delivered", 1))
        h.ack(0)
        h.relay.posted(event("key", "queued private update", 2))
        h.relay.posted(event("key", "newer private update", 3))
        h.relay.cancelPending()
        h.receipts[1].completeExceptionally(IllegalStateException())
        assertEquals(2, h.commands.size)
        h.relay.removed("key")
        val removed = h.commands[2].notification.notificationDismiss.getNotificationId(0)
        assertEquals(h.data(0).id, removed.id)
        assertEquals("key", removed.key)
    }

    @Test
    fun fullMemoryDoesNotEvictDeliveredHandlesNeededForRemoval() {
        val h = Harness()
        for (i in 0 until NotificationRelay.CAPACITY) {
            h.relay.posted(event("key$i", "body", (i + 1).toLong()))
            h.ack(i)
        }
        h.relay.posted(event("overflow", "body", 1000))
        assertEquals(NotificationRelay.CAPACITY, h.commands.size)
        h.relay.removed("key0")
        assertEquals(
            "key0",
            h.commands[NotificationRelay.CAPACITY].notification.notificationDismiss.getNotificationId(0).key,
        )
    }
}
