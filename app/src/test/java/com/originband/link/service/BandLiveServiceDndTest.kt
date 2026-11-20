// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.service

import com.originband.link.notify.PhoneDnd
import com.originband.link.protocol.BandNotificationCommand
import java.time.Instant
import java.time.ZoneId
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BandLiveServiceDndTest {
    private fun ordinary(): XiaomiProto.Command =
        BandNotificationCommand.post("pkg", "App", "key", 1, "t", "b", Instant.EPOCH, ZoneId.of("UTC"))

    @Test
    fun suppressForDndFollowsCommandKind() {
        assertTrue(BandLiveService.suppressForDnd(ordinary(), PhoneDnd.NONE))
        val call = BandNotificationCommand.incomingCall("a", Instant.EPOCH, ZoneId.of("UTC"))
        val dismiss = BandNotificationCommand.dismiss("pkg", "key", 1)
        assertFalse(BandLiveService.suppressForDnd(call, PhoneDnd.NONE))
        assertFalse(BandLiveService.suppressForDnd(dismiss, PhoneDnd.NONE))
        assertFalse(BandLiveService.suppressForDnd(ordinary(), PhoneDnd.ALL))
    }
}
