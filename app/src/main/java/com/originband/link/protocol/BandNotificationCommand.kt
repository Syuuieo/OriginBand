/* Copyright (C) 2023-2024 Andreas Shimokawa, José Rebelo.
 * Notification wire semantics adapted from Gadgetbridge XiaomiNotificationService.java
 * at commit 75f923904f8504b03fabdee0987fd1c269a92278.
 */
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Wire encoder only. Real Band 11 ordinary-notification/call display remains unverified. */
object BandNotificationCommand {
    private val TIMESTAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT)
    private val NUMBER_SEPARATORS = Regex("[\\s()-]")
    private val DIGITS_PATTERN = Regex("\\+?\\d+")

    /** Field 8. Mi Fitness uses 1 for a ringing call and 3 for a call this phone placed. */
    const val CALL_INCOMING = 1

    /** Answered call. The band keeps a hang-up screen instead of the incoming buttons. */
    const val CALL_ACTIVE = 2
    const val CALL_OUTGOING = 3

    @JvmStatic
    fun post(
        packageName: String?,
        appName: String?,
        key: String?,
        id: Int,
        title: String?,
        body: String?,
        `when`: Instant?,
        zone: ZoneId?,
    ): XiaomiProto.Command {
        if (packageName.isNullOrBlank() || appName.isNullOrBlank() ||
            key.isNullOrBlank() || `when` == null || zone == null
        ) {
            throw IllegalArgumentException("NOTIFICATION_IDENTITY_REQUIRED")
        }
        val notification = XiaomiProto.Notification3.newBuilder()
            .setPackage(packageName)
            .setAppName(appName)
            .setUnknown4("")
            .setId(id)
            .setKey(key)
            .setOpenOnPhone(false)
            .setTimestamp(TIMESTAMP.format(`when`.atZone(zone)))
        if (!title.isNullOrBlank()) notification.setTitle(title)
        if (!body.isNullOrBlank()) notification.setBody(body)
        return XiaomiProto.Command.newBuilder()
            .setType(7)
            .setSubtype(0)
            .setNotification(
                XiaomiProto.Notification.newBuilder().setNotification2(
                    XiaomiProto.Notification2.newBuilder().setNotification3(notification),
                ),
            )
            .build()
    }

    @JvmStatic
    fun dismiss(packageName: String?, key: String?, id: Int): XiaomiProto.Command {
        if (packageName.isNullOrBlank() || key.isNullOrBlank()) {
            throw IllegalArgumentException("NOTIFICATION_IDENTITY_REQUIRED")
        }
        val target = XiaomiProto.NotificationId.newBuilder()
            .setId(id)
            .setPackage(packageName)
            .setKey(key)
        return XiaomiProto.Command.newBuilder()
            .setType(7)
            .setSubtype(1)
            .setNotification(
                XiaomiProto.Notification.newBuilder().setNotificationDismiss(
                    XiaomiProto.NotificationDismiss.newBuilder().addNotificationId(target),
                ),
            )
            .build()
    }

    @JvmStatic
    fun incomingCall(displayName: String?, `when`: Instant?, zone: ZoneId?): XiaomiProto.Command =
        incomingCall(displayName, null, `when`, zone, false)

    @JvmStatic
    fun incomingCall(
        displayName: String?,
        number: String?,
        `when`: Instant?,
        zone: ZoneId?,
        repliesAllowed: Boolean,
    ): XiaomiProto.Command = call(displayName, number, CALL_INCOMING, `when`, zone, repliesAllowed)

    @JvmStatic
    fun call(
        displayName: String?,
        number: String?,
        callState: Int,
        `when`: Instant?,
        zone: ZoneId?,
        repliesAllowed: Boolean,
    ): XiaomiProto.Command {
        if (`when` == null || zone == null) throw IllegalArgumentException("CALL_TIME_REQUIRED")
        if (callState != CALL_INCOMING && callState != CALL_ACTIVE && callState != CALL_OUTGOING) {
            throw IllegalArgumentException("CALL_STATE")
        }
        val replyNumber = if (callState == CALL_INCOMING && repliesAllowed && usableNumber(number)) {
            number?.trim()
        } else {
            null
        }
        val reply = replyNumber != null
        val title = if (!displayName.isNullOrBlank()) displayName else "?"
        val body = replyNumber ?: when (callState) {
            CALL_ACTIVE -> "通话中"
            CALL_OUTGOING -> "去电"
            else -> "?"
        }
        val notification = XiaomiProto.Notification3.newBuilder()
            .setPackage("phone")
            .setAppName("phone")
            .setId(0)
            .setUnknown4("")
            .setCallState(callState)
            .setRepliesAllowed(reply)
            .setTimestamp(TIMESTAMP.format(`when`.atZone(zone)))
            .setTitle(title)
            .setBody(body)
        return XiaomiProto.Command.newBuilder()
            .setType(7)
            .setSubtype(0)
            .setNotification(
                XiaomiProto.Notification.newBuilder().setNotification2(
                    XiaomiProto.Notification2.newBuilder().setNotification3(notification),
                ),
            )
            .build()
    }

    @JvmStatic
    fun isCall(data: XiaomiProto.Notification3?): Boolean = data != null && data.callState != 0

    @JvmStatic
    fun usableNumber(number: String?): Boolean {
        if (number == null) return false
        val compact = number.replace(NUMBER_SEPARATORS, "")
        if (compact.length < 3 || compact.length > 20 || !DIGITS_PATTERN.matches(compact)) return false
        return when (compact) {
            "110", "119", "120", "112", "911" -> false
            else -> true
        }
    }

    @JvmStatic
    fun smsReplyAck(success: Boolean): XiaomiProto.Command =
        XiaomiProto.Command.newBuilder()
            .setType(7)
            .setSubtype(14)
            .setNotification(
                XiaomiProto.Notification.newBuilder().setNotificationReplyStatus(if (success) 0 else 1),
            )
            .build()

    @JvmStatic
    fun iconQueryReply(packageName: String?): XiaomiProto.Command {
        if (packageName.isNullOrBlank()) {
            throw IllegalArgumentException("NOTIFICATION_IDENTITY_REQUIRED")
        }
        return XiaomiProto.Command.newBuilder()
            .setType(7)
            .setSubtype(15)
            .setNotification(
                XiaomiProto.Notification.newBuilder().setNotificationIconReply(
                    XiaomiProto.NotificationIconPackage.newBuilder().setPackage(packageName),
                ),
            )
            .build()
    }

    @JvmStatic
    fun endCall(): XiaomiProto.Command =
        // The pinned upstream sends id 0/package phone for a call-end dismissal.
        XiaomiProto.Command.newBuilder()
            .setType(7)
            .setSubtype(1)
            .setNotification(
                XiaomiProto.Notification.newBuilder().setNotificationDismiss(
                    XiaomiProto.NotificationDismiss.newBuilder().addNotificationId(
                        XiaomiProto.NotificationId.newBuilder().setId(0).setPackage("phone"),
                    ),
                ),
            )
            .build()

    /** The limit is the negotiated plaintext budget, including the entire command envelope. */
    @JvmStatic
    fun fitToPayload(command: XiaomiProto.Command, limit: Int): XiaomiProto.Command {
        if (limit <= 0) throw IllegalArgumentException("NOTIFICATION_PAYLOAD_LIMIT")
        if (command.serializedSize <= limit) return command
        if (command.type != 7 || command.subtype != 0 ||
            !command.notification.notification2.hasNotification3()
        ) {
            throw IllegalArgumentException("NOTIFICATION_IDENTITY_TOO_LARGE")
        }
        val original = command.notification.notification2.notification3
        val data = original.toBuilder().clearTitle().clearBody()
        if (replaceContent(command, data.build()).serializedSize > limit) {
            throw IllegalArgumentException("NOTIFICATION_IDENTITY_TOO_LARGE")
        }
        fitField(command, data, original.title, title = true, limit = limit)
        fitField(command, data, original.body, title = false, limit = limit)
        return replaceContent(command, data.build())
    }

    private fun fitField(
        command: XiaomiProto.Command,
        data: XiaomiProto.Notification3.Builder,
        text: String,
        title: Boolean,
        limit: Int,
    ) {
        var low = 0
        var high = text.codePointCount(0, text.length)
        while (low < high) {
            val count = low + (high - low + 1) / 2
            val prefix = text.substring(0, text.offsetByCodePoints(0, count))
            if (title) data.setTitle(prefix) else data.setBody(prefix)
            if (replaceContent(command, data.build()).serializedSize <= limit) {
                low = count
            } else {
                high = count - 1
            }
        }
        val prefix = text.substring(0, text.offsetByCodePoints(0, low))
        if (title) {
            if (low == 0) data.clearTitle() else data.setTitle(prefix)
        } else {
            if (low == 0) data.clearBody() else data.setBody(prefix)
        }
    }

    private fun replaceContent(
        command: XiaomiProto.Command,
        data: XiaomiProto.Notification3,
    ): XiaomiProto.Command =
        command.toBuilder().setNotification(
            command.notification.toBuilder().setNotification2(
                command.notification.notification2.toBuilder().setNotification3(data),
            ),
        ).build()
}
