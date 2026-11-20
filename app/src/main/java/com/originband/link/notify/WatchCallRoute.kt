// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

import com.originband.link.protocol.BandNotificationCommand

/**
 * Bands draw the call from the notification packet. Watches (Redmi Watch, Xiaomi Watch, and the
 * same split OPPO's own watches use) draw it from the phone's HFP audio gateway: the link has to
 * be up before the watch will show a call, and an answered call moves the SCO audio onto the watch.
 */
object WatchCallRoute {
    enum class Link { NONE, PROFILE, HANDSET }

    @JvmStatic
    fun link(watch: Boolean, callState: Int): Link {
        if (!watch || callState == 0) return Link.NONE
        if (callState == BandNotificationCommand.CALL_ACTIVE) return Link.HANDSET
        return Link.PROFILE
    }
}
