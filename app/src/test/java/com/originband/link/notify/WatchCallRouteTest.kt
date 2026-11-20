// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

import com.originband.link.protocol.BandNotificationCommand.CALL_ACTIVE
import com.originband.link.protocol.BandNotificationCommand.CALL_INCOMING
import com.originband.link.protocol.BandNotificationCommand.CALL_OUTGOING
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchCallRouteTest {
    @Test
    fun bandsKeepTheNotificationCallScreen() {
        assertEquals(WatchCallRoute.Link.NONE, WatchCallRoute.link(false, CALL_INCOMING))
        assertEquals(WatchCallRoute.Link.NONE, WatchCallRoute.link(false, CALL_ACTIVE))
        assertEquals(WatchCallRoute.Link.NONE, WatchCallRoute.link(false, 0))
    }

    @Test
    fun watchesOpenHfpForARingAndMoveAudioWhenAnswered() {
        assertEquals(WatchCallRoute.Link.PROFILE, WatchCallRoute.link(true, CALL_INCOMING))
        assertEquals(WatchCallRoute.Link.PROFILE, WatchCallRoute.link(true, CALL_OUTGOING))
        assertEquals(WatchCallRoute.Link.HANDSET, WatchCallRoute.link(true, CALL_ACTIVE))
        assertEquals(WatchCallRoute.Link.NONE, WatchCallRoute.link(true, 0))
    }
}
