// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

import com.originband.link.notify.CallPresentation.Kind.ACTIVE
import com.originband.link.notify.CallPresentation.Kind.INCOMING
import com.originband.link.notify.CallPresentation.Kind.OUTGOING
import com.originband.link.notify.CallPresentation.Kind.UNKNOWN
import com.originband.link.protocol.BandNotificationCommand.CALL_ACTIVE
import com.originband.link.protocol.BandNotificationCommand.CALL_INCOMING
import com.originband.link.protocol.BandNotificationCommand.CALL_OUTGOING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallPresentationTest {
    @Test
    fun dialingCopyStaysOutgoingUntilTheCallIsConnected() {
        assertFalse(CallPresentation.connected(false, "正在呼叫", "张三"))
        assertFalse(CallPresentation.connected(false, "正在拨号", "10086"))
        assertTrue(CallPresentation.connected(true, "正在呼叫", "张三"))
        assertTrue(CallPresentation.connected(false, "00:12", "张三"))
        assertTrue(CallPresentation.connected(false, "通话中", "张三"))
        assertTrue(CallPresentation.connected(false, "", "1:02:03"))
    }

    @Test
    fun colorOsDialingIsOutgoingAndARunningTimerIsActive() {
        assertEquals(OUTGOING, CallPresentation.kind(false, false, 0, "正在呼叫", "张三"))
        assertEquals(OUTGOING, CallPresentation.kind(false, false, 0, "正在拨号", "10086"))
        assertEquals(OUTGOING, CallPresentation.kind(false, false, 0, "等待接听", "张三"))
        assertEquals(OUTGOING, CallPresentation.kind(false, false, 0, "Calling", "Ada"))
        assertEquals(INCOMING, CallPresentation.kind(false, false, 0, "来电", "张三"))
        assertEquals(INCOMING, CallPresentation.kind(false, false, 0, "Incoming call", "Ada"))
        assertEquals(ACTIVE, CallPresentation.kind(false, false, 0, "正在通话", "张三"))
        assertEquals(ACTIVE, CallPresentation.kind(false, false, 0, "保持通话", "张三"))
        assertEquals(ACTIVE, CallPresentation.kind(false, true, 0, "", "张三"))
        assertEquals(UNKNOWN, CallPresentation.kind(false, false, 0, "", "张三"))
        assertEquals(INCOMING, CallPresentation.kind(false, false, 0, "{\"text\":\"来电\"}", "张三"))
        assertEquals(0, CallPresentation.wire(UNKNOWN))
        assertEquals(OUTGOING, CallPresentation.kind(false, false, 2, "", "张三"))
        assertEquals(INCOMING, CallPresentation.kind(false, false, 1, "", "张三"))
        assertEquals(CALL_OUTGOING, CallPresentation.wire(OUTGOING))
        assertEquals(CALL_ACTIVE, CallPresentation.wire(ACTIVE))
        assertEquals(CALL_INCOMING, CallPresentation.wire(INCOMING))
    }

    @Test
    fun stateCopyIsNotUsedAsTheCallerName() {
        assertEquals("张三", CallPresentation.displayName("张三", OUTGOING))
        assertEquals("去电", CallPresentation.displayName("正在呼叫", OUTGOING))
        assertEquals("通话中", CallPresentation.displayName("", ACTIVE))
        assertEquals("来电", CallPresentation.displayName("来电", INCOMING))
    }

    @Test
    fun anInterceptedCallNoticeIsNotARingingCall() {
        assertTrue(CallPresentation.alreadyBlocked("新拦截", "为您拦截了 1 个来电"))
        assertTrue(
            CallPresentation.alreadyBlocked(
                "已自动拦截来电",
                "号码识别结果更新后命中拦截规则，已自动挂断 (13800138000)",
            ),
        )
        assertTrue(CallPresentation.alreadyBlocked("新攔截", "為您封鎖了 2 個來電"))
        assertEquals(INCOMING, CallPresentation.kind(false, false, 0, "为您拦截了 1 个来电", "新拦截"))
        assertFalse(CallPresentation.alreadyBlocked("骚扰电话", "13800138000"))
        assertFalse(CallPresentation.alreadyBlocked("张三", "来电"))
    }

    @Test
    fun seedlingChronometerNodeMarksTheCallConnected() {
        assertTrue(CallPresentation.chronometerCard("{\"desc\":\"Chronometer\",\"started\":true}"))
        assertFalse(CallPresentation.chronometerCard("{\"desc\":\"正在呼叫\"}"))
        assertFalse(CallPresentation.chronometerCard(null))
    }
}
