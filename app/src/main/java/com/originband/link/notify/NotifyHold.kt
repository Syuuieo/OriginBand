// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

/** Notifications that arrived before the band session was up. Not a replay of the shade. */
class NotifyHold {
    companion object {
        const val CAPACITY = 32
    }

    private val pending = LinkedHashMap<String, NotificationRelay.Event>()

    @Synchronized
    fun put(event: NotificationRelay.Event?) {
        val key = event?.key
        if (key == null || key.isBlank()) return
        pending.remove(key)
        while (pending.size >= CAPACITY) {
            val oldest = pending.keys.iterator()
            if (!oldest.hasNext()) break
            oldest.next()
            oldest.remove()
        }
        pending[key] = event
    }

    @Synchronized
    fun remove(key: String?) {
        if (key != null) pending.remove(key)
    }

    @Synchronized
    fun contains(key: String?): Boolean = key != null && pending.containsKey(key)

    @Synchronized
    fun clear() {
        pending.clear()
    }

    @Synchronized
    fun drain(): List<NotificationRelay.Event> {
        val copy = ArrayList(pending.values)
        pending.clear()
        return copy
    }
}
