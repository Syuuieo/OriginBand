// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

/** Whether OPPO Health's own notification listener is connected in this process. */
object HealthListenerState {
    @Volatile
    private var isConnected = false

    @JvmStatic
    fun connected(): Boolean = isConnected

    @JvmStatic
    fun connected(value: Boolean) {
        isConnected = value
    }
}
