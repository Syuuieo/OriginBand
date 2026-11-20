// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId

class BandNotificationCommandTest {
    @Test
    fun chineseNotificationAndDismissalShareTheExactHandle() {
        val key = "0|com.example.allowed|123|null|event"
        val post = BandNotificationCommand.post(
            "com.example.allowed",
            "测试应用",
            key,
            3281,
            "测试手环11：中文 ABC 123",
            "内容",
            Instant.parse("2026-09-23T18:32:06Z"),
            ZoneId.of("Asia/Shanghai"),
        )
        val data = post.notification.notification2.notification3
        assertEquals(7, post.type)
        assertEquals(0, post.subtype)
        assertEquals("测试手环11：中文 ABC 123", data.title)
        assertEquals("20260924T023206", data.timestamp)
        assertEquals(key, data.key)
        assertFalse(data.openOnPhone)
        val delete = BandNotificationCommand.dismiss("com.example.allowed", key, 3281)
            .notification.notificationDismiss.getNotificationId(0)
        assertEquals(data.id, delete.id)
        assertEquals(data.`package`, delete.`package`)
        assertEquals(data.key, delete.key)
    }

    @Test
    fun callWithoutNumberDoesNotInventIdentityOrReplyPermission() {
        val incoming = BandNotificationCommand.incomingCall(
            null,
            Instant.parse("2026-09-23T18:32:06Z"),
            ZoneId.of("Asia/Shanghai"),
        ).notification.notification2.notification3
        assertEquals(BandNotificationCommand.CALL_INCOMING, incoming.callState)
        assertFalse(incoming.repliesAllowed)
        assertEquals("?", incoming.title)
        assertEquals("?", incoming.body)
        assertEquals("", incoming.unknown4)
        val end = BandNotificationCommand.endCall()
            .notification.notificationDismiss.getNotificationId(0)
        assertEquals("phone", end.`package`)
        assertEquals(0, end.id)
    }

    @Test
    fun answeredAndOutgoingCallsUseDistinctStates() {
        val active = BandNotificationCommand.call(
            "张三",
            null,
            BandNotificationCommand.CALL_ACTIVE,
            Instant.parse("2026-09-23T18:32:06Z"),
            ZoneId.of("Asia/Shanghai"),
            false,
        ).notification.notification2.notification3
        assertEquals(BandNotificationCommand.CALL_ACTIVE, active.callState)
        assertEquals("张三", active.title)
        assertEquals("通话中", active.body)
        val outgoing = BandNotificationCommand.call(
            "去电",
            null,
            BandNotificationCommand.CALL_OUTGOING,
            Instant.EPOCH,
            ZoneId.of("UTC"),
            true,
        ).notification.notification2.notification3
        assertEquals(BandNotificationCommand.CALL_OUTGOING, outgoing.callState)
        assertFalse(outgoing.repliesAllowed)
        assertEquals("去电", outgoing.body)
    }

    @Test
    fun smsReplyRequiresUsableNumberAndDoesNotAllowEmergency() {
        val allowed = BandNotificationCommand.incomingCall(
            "张三",
            "+86 13800138000",
            Instant.parse("2026-09-23T18:32:06Z"),
            ZoneId.of("Asia/Shanghai"),
            true,
        ).notification.notification2.notification3
        assertTrue(allowed.repliesAllowed)
        assertEquals("+86 13800138000", allowed.body)
        val blocked = BandNotificationCommand.incomingCall(
            "来电",
            "110",
            Instant.EPOCH,
            ZoneId.of("UTC"),
            true,
        ).notification.notification2.notification3
        assertFalse(blocked.repliesAllowed)
        assertEquals("?", blocked.body)
        assertFalse(BandNotificationCommand.usableNumber("?"))
    }

    @Test
    fun missingStableNotificationKeyCannotBeForwarded() {
        assertThrows(IllegalArgumentException::class.java) {
            BandNotificationCommand.post(
                "com.example.allowed",
                "测试应用",
                "",
                1,
                "标题",
                "内容",
                Instant.EPOCH,
                ZoneId.of("UTC"),
            )
        }
    }

    @Test
    fun payloadFittingKeepsIdentityAndWholeUnicodeCodePoints() {
        val title = "来电\ud83d\udcf1".repeat(30)
        val body = "正文\ud83d\ude03".repeat(100)
        val original = BandNotificationCommand.post(
            "allowed",
            "App",
            "stable-key",
            33,
            title,
            body,
            Instant.EPOCH,
            ZoneId.of("UTC"),
        )
        val fitted = BandNotificationCommand.fitToPayload(original, 180)
        assertTrue(fitted.serializedSize <= 180)
        val data = fitted.notification.notification2.notification3
        assertEquals("stable-key", data.key)
        assertEquals(33, data.id)
        assertEquals("App", data.appName)
        assertTrue(title.startsWith(data.title))
        assertTrue(body.startsWith(data.body))
        assertFalse(data.title.isEmpty())
        assertFalse(data.title.last().isHighSurrogate())
        assertEquals(
            data.title,
            String(data.title.toByteArray(StandardCharsets.UTF_8), StandardCharsets.UTF_8),
        )
    }

    @Test
    fun identityCannotBeTruncatedToHideOversizeHandle() {
        val original = BandNotificationCommand.post(
            "allowed",
            "App",
            "key".repeat(200),
            1,
            "title",
            "body",
            Instant.EPOCH,
            ZoneId.of("UTC"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            BandNotificationCommand.fitToPayload(original, 100)
        }
    }

    @Test
    fun exactPayloadBoundaryPreservesContent() {
        val original = BandNotificationCommand.post(
            "allowed",
            "App",
            "key",
            1,
            "\ud83d\ude03",
            "\ud83d\ude03",
            Instant.EPOCH,
            ZoneId.of("UTC"),
        )
        assertEquals(original, BandNotificationCommand.fitToPayload(original, original.serializedSize))
        val fitted = BandNotificationCommand.fitToPayload(original, original.serializedSize - 1)
        assertEquals("\ud83d\ude03", fitted.notification.notification2.notification3.title)
        assertFalse(fitted.notification.notification2.notification3.hasBody())
    }
}
