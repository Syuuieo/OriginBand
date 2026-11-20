// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

import com.originband.link.protocol.BandNotificationCommand
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import java.util.concurrent.CompletableFuture

/** Notifications that arrived before the band session could send. Oldest past the deadline are dropped. */
class NotifyReplay {
    companion object {
        const val LIMIT = 16
        const val NOTIFICATION_MS = 60_000L
        const val CALL_MS = 15_000L

        private fun dismiss(command: XiaomiProto.Command): Boolean =
            command.subtype == 1 && command.hasNotification() &&
                command.notification.hasNotificationDismiss()

        private fun shortLived(command: XiaomiProto.Command): Boolean {
            if (dismiss(command)) return packageKey(command).split('\n', limit = 2)[0] == "phone"
            return command.hasNotification() && command.notification.hasNotification2() &&
                BandNotificationCommand.isCall(command.notification.notification2.notification3)
        }

        /** Package and key. A call and its end share phone plus an empty key. */
        private fun packageKey(command: XiaomiProto.Command): String {
            if (dismiss(command) && command.notification.notificationDismiss.notificationIdCount > 0) {
                val id = command.notification.notificationDismiss.getNotificationId(0)
                return "${id.`package`}\n${id.key}"
            }
            if (command.hasNotification() && command.notification.hasNotification2() &&
                command.notification.notification2.hasNotification3()
            ) {
                val item = command.notification.notification2.notification3
                return "${item.`package`}\n${item.key}"
            }
            return "\n"
        }
    }

    class Held(
        @JvmField val command: XiaomiProto.Command,
        waiter: CompletableFuture<Void?>,
        private var atMs: Long,
        private val call: Boolean,
    ) {
        private val waiters = ArrayList<CompletableFuture<Void?>>().apply { add(waiter) }

        fun addWaiter(waiter: CompletableFuture<Void?>, nowMs: Long) {
            waiters.add(waiter)
            atMs = nowMs
        }

        fun isExpired(nowMs: Long): Boolean {
            val age = if (call) CALL_MS else NOTIFICATION_MS
            return nowMs - atMs >= age
        }

        fun succeed() {
            for (waiter in waiters) waiter.complete(null)
        }

        fun fail(error: Throwable) {
            for (waiter in waiters) waiter.completeExceptionally(error)
        }
    }

    private val pending = ArrayList<Held>()

    @Synchronized
    fun add(command: XiaomiProto.Command, nowMs: Long): CompletableFuture<Void?> {
        expire(nowMs)
        val waiter = CompletableFuture<Void?>()
        if (dismiss(command)) {
            val target = packageKey(command)
            var cleared = false
            val items = pending.iterator()
            while (items.hasNext()) {
                val held = items.next()
                if (target == packageKey(held.command) && !dismiss(held.command)) {
                    items.remove()
                    held.succeed()
                    cleared = true
                }
            }
            if (cleared) {
                waiter.complete(null)
                return waiter
            }
        }
        val identity = NotifyDedupe.identity(command)
        if (identity.isNotEmpty()) {
            for (held in pending) {
                if (identity == NotifyDedupe.identity(held.command)) {
                    held.addWaiter(waiter, nowMs)
                    return waiter
                }
            }
        }
        while (pending.size >= LIMIT) {
            pending.removeAt(0).fail(IllegalStateException("NOTIFY_HOLD_DROPPED"))
        }
        pending.add(Held(command, waiter, nowMs, shortLived(command)))
        return waiter
    }

    @Synchronized
    fun expire(nowMs: Long): Int {
        var expired = 0
        val items = pending.iterator()
        while (items.hasNext()) {
            val held = items.next()
            if (held.isExpired(nowMs)) {
                items.remove()
                held.fail(IllegalStateException("NOTIFY_HOLD_EXPIRED"))
                expired++
            }
        }
        return expired
    }

    @Synchronized
    fun poll(nowMs: Long): List<Held> {
        expire(nowMs)
        val ready = ArrayList(pending)
        pending.clear()
        return ready
    }

    @Synchronized
    fun failAll(error: Throwable): Int {
        val count = pending.size
        for (held in pending) held.fail(error)
        pending.clear()
        return count
    }

    @Synchronized
    fun size(): Int = pending.size
}
