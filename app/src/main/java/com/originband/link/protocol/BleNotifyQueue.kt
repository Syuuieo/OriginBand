// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

/** GATT accepts one descriptor write. The next CCCD starts only after that callback. */
class BleNotifyQueue(private val total: Int) {
    private var started = 0
    private var outstanding = false

    init {
        if (total < 1) throw IllegalArgumentException("total")
    }

    /** Index to write now, or -1 while a write is in flight or after the last one has started. */
    fun start(): Int {
        if (outstanding || started >= total) return -1
        outstanding = true
        return started++
    }

    /** @return false when no write was waiting for a callback. */
    fun confirmed(): Boolean {
        if (!outstanding) return false
        outstanding = false
        return true
    }

    fun complete(): Boolean = !outstanding && started >= total
}
