// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

/** Collapses the health hook and the local listener posting one notification twice. */
class NotifyDedupe {
    companion object {
        const val WINDOW_MS = 2_000L

        @JvmStatic
        fun identity(command: XiaomiProto.Command?): String {
            if (command == null || !command.hasNotification()) return ""
            val notification = command.notification
            if (notification.hasNotification2() && notification.notification2.hasNotification3()) {
                val item = notification.notification2.notification3
                return "post\n${item.`package`}\n${item.key}\n${item.title}\n${item.body}"
            }
            if (notification.hasNotificationDismiss() && notification.notificationDismiss.notificationIdCount > 0) {
                val id = notification.notificationDismiss.getNotificationId(0)
                return "dismiss\n${id.`package`}\n${id.key}\n${id.id}"
            }
            return ""
        }
    }

    private val seen = HashMap<String, Long>()

    /** A different title or body is a new notification, even on the same key. */
    @Synchronized
    fun first(identity: String?, now: Long): Boolean {
        if (identity.isNullOrBlank()) return true
        val entries = seen.entries.iterator()
        while (entries.hasNext()) {
            if (now - entries.next().value > WINDOW_MS) entries.remove()
        }
        val previous = seen[identity]
        if (previous != null && now - previous <= WINDOW_MS) return false
        seen[identity] = now
        return true
    }
}
