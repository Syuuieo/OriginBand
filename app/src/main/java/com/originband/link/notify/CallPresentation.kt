// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

import android.app.Notification
import android.os.Bundle
import com.originband.link.protocol.BandNotificationCommand
import java.util.regex.Pattern

/**
 * ColorOS posts one CATEGORY_CALL notification for dialing, ringing, and the connected call.
 * Telephony OFFHOOK covers both dialing and answered, and the call switch may be off:
 * the notification itself has to choose incoming, outgoing, or active.
 */
object CallPresentation {
    enum class Kind { UNKNOWN, INCOMING, OUTGOING, ACTIVE }

    private val ELAPSED = Pattern.compile("(?:^|\\D)\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\D|$)")

    /** CallStyle extra. 1 incoming, 2 ongoing (dialing or active), 3 screening. */
    private const val EXTRA_CALL_TYPE = "android.callType"
    private const val LIVE_ALERT_CARD = "oplus.livealert.card"
    private const val LIVE_ALERT_CAPSULE = "oplus.livealert.capsule"
    private const val CHRONOMETER_NODE = "\"desc\":\"Chronometer\""

    @JvmStatic
    fun connected(notification: Notification?): Boolean = kind(notification) == Kind.ACTIVE

    /** A running timer or an explicit in-call label. Dialing copy such as 「正在呼叫」(calling) stays outgoing. */
    @JvmStatic
    fun connected(showChronometer: Boolean, text: String?, title: String?): Boolean =
        kind(showChronometer, false, 0, text, title) == Kind.ACTIVE

    @JvmStatic
    fun kind(notification: Notification?): Kind {
        if (notification == null) return Kind.UNKNOWN
        val extras = notification.extras
        val chronometer = extras != null && extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        val callType = extras?.getInt(EXTRA_CALL_TYPE, 0) ?: 0
        return kind(
            chronometer,
            liveAlertChronometer(extras),
            callType,
            text(extras, Notification.EXTRA_TEXT),
            text(extras, Notification.EXTRA_TITLE),
            text(extras, Notification.EXTRA_SUB_TEXT),
            text(extras, Notification.EXTRA_BIG_TEXT),
            text(extras, Notification.EXTRA_SUMMARY_TEXT),
            NotificationRelay.sanitize(notification.tickerText),
            extras?.getString(LIVE_ALERT_CARD) ?: "",
            extras?.getString(LIVE_ALERT_CAPSULE) ?: "",
        )
    }

    @JvmStatic
    fun kind(
        showChronometer: Boolean,
        liveAlertChronometer: Boolean,
        callType: Int,
        vararg fields: String?,
    ): Kind {
        if (showChronometer || liveAlertChronometer) return Kind.ACTIVE
        var outgoing = false
        var incoming = false
        for (field in fields) {
            val text = field?.trim() ?: ""
            if (text.isEmpty()) continue
            if (active(text)) return Kind.ACTIVE
            if (dialing(text)) outgoing = true
            else if (ringing(text)) incoming = true
        }
        if (incoming || callType == 1 || callType == 3) return Kind.INCOMING
        if (outgoing || callType == 2) return Kind.OUTGOING
        return Kind.UNKNOWN
    }

    @JvmStatic
    fun wire(notification: Notification?): Int = wire(kind(notification))

    @JvmStatic
    fun wire(kind: Kind?): Int =
        when (kind ?: Kind.UNKNOWN) {
            Kind.OUTGOING -> BandNotificationCommand.CALL_OUTGOING
            Kind.ACTIVE -> BandNotificationCommand.CALL_ACTIVE
            Kind.INCOMING -> BandNotificationCommand.CALL_INCOMING
            Kind.UNKNOWN -> 0
        }

    @JvmStatic
    fun kind(wire: Int): Kind =
        when (wire) {
            BandNotificationCommand.CALL_OUTGOING -> Kind.OUTGOING
            BandNotificationCommand.CALL_ACTIVE -> Kind.ACTIVE
            else -> Kind.INCOMING
        }

    /**
     * ColorOS has already rejected this call. The shade notice stays a normal notification,
     * even when its copy contains 「来电」.
     */
    @JvmStatic
    fun alreadyBlocked(vararg fields: String?): Boolean = fields.any(::blockedCopy)

    @JvmStatic
    fun alreadyBlocked(notification: Notification?): Boolean {
        if (notification == null) return false
        val extras = notification.extras
        return alreadyBlocked(
            text(extras, Notification.EXTRA_TITLE),
            text(extras, Notification.EXTRA_TEXT),
            text(extras, Notification.EXTRA_BIG_TEXT),
            text(extras, Notification.EXTRA_SUB_TEXT),
            NotificationRelay.sanitize(notification.tickerText),
        )
    }

    /** A state label is not the person being called. */
    @JvmStatic
    fun displayName(title: String?, kind: Kind?): String {
        val clean = title?.trim() ?: ""
        val shown = kind ?: Kind.UNKNOWN
        if (clean.isEmpty() || stateCopy(clean)) {
            return when (shown) {
                Kind.OUTGOING -> "去电"
                Kind.ACTIVE -> "通话中"
                Kind.INCOMING, Kind.UNKNOWN -> "来电"
            }
        }
        return clean
    }

    private fun stateCopy(text: String): Boolean = active(text) || dialing(text) || ringing(text)

    private fun liveAlertChronometer(extras: Bundle?): Boolean {
        if (extras == null) return false
        return chronometerCard(extras.getString(LIVE_ALERT_CARD)) ||
            chronometerCard(extras.getString(LIVE_ALERT_CAPSULE))
    }

    @JvmStatic
    fun chronometerCard(json: String?): Boolean = json != null && json.contains(CHRONOMETER_NODE)

    private fun text(extras: Bundle?, key: String): String =
        if (extras == null) "" else NotificationRelay.sanitize(extras.getCharSequence(key))

    private fun active(text: String): Boolean {
        if (text.contains("通话中") || text.contains("正在通话") || text.contains("正在通話") ||
            text.contains("通话保持") || text.contains("保持通话") || text.contains("保持通話") ||
            text.contains("正在保持") || text.contains("保持中") ||
            text.contains("高音质通话") || text.contains("高音質通話")
        ) {
            return true
        }
        val lower = text.lowercase()
        if (lower.contains("ongoing call") || lower.contains("on hold")) return true
        return ELAPSED.matcher(text).find()
    }

    private fun dialing(text: String): Boolean {
        if (text.contains("正在呼叫") || text.contains("呼叫中") ||
            text.contains("正在拨号") || text.contains("正在拨打") || text.contains("正在重拨") ||
            text.contains("正在撥號") || text.contains("正在撥打") || text.contains("正在重撥") ||
            text.contains("等待接听") || text.contains("等待接聽")
        ) {
            return true
        }
        val lower = text.lowercase()
        return lower.contains("dialing") || lower.contains("dialling") || lower.contains("calling")
    }

    private fun ringing(text: String): Boolean {
        if (text.contains("来电") || text.contains("來電")) return true
        return text.lowercase().contains("incoming")
    }

    /** Titles and bodies of the InCallUI notices posted after a call was rejected. */
    private fun blockedCopy(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return text.contains("新拦截") || text.contains("新攔截") || text.contains("新封鎖") ||
            text.contains("已自动拦截") || text.contains("已自動攔截") ||
            text.contains("已自动挂断") || text.contains("已自動掛斷") ||
            text.contains("为您拦截了") || text.contains("為您攔截了") || text.contains("為您封鎖了") ||
            text.contains("命中拦截规则") || text.contains("命中攔截規則") || text.contains("符合攔截規則")
    }
}
