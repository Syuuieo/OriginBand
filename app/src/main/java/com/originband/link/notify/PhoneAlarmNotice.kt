// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

import android.content.Context
import android.service.notification.StatusBarNotification
import com.originband.link.data.SessionLog
import com.originband.link.host.HostIdentity
import com.originband.link.protocol.BandAlarmCommand
import com.originband.link.service.BandLiveService
import java.util.concurrent.ConcurrentHashMap

/** Clock ringing is a foreground notification. The band gets the phone-alarm packet, not a normal push. */
object PhoneAlarmNotice {
    const val CLOCK = HostIdentity.CLOCK_PACKAGE
    private const val DEDUPE_MS = 1500L
    private val ringing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** AlarmSnoozeService foreground id. A countdown, not a ringing alarm. */
    const val SNOOZE_NOTIFICATION_ID = -1018

    /** TimerService, the timer floating window, and the stopwatch service share the klaxon channel. */
    private const val TIMER_SERVICE_ID = -1014
    private const val TIMER_FLOATING_ID = -1015
    private const val STOPWATCH_SERVICE_ID = -1016

    private var lastSentAt = 0L
    private var lastSentOp = -1
    private var clockRingAt = 0L

    @JvmStatic
    fun ringing(pkg: String?, flags: Int, channel: String?, id: Int, fullScreen: Boolean): Boolean {
        if (CLOCK != pkg || channel == null || channel.contains("next.alarm")) return false
        if (id == SNOOZE_NOTIFICATION_ID || runningClockTool(id, channel)) return false
        val service = (flags and android.app.Notification.FLAG_FOREGROUND_SERVICE) != 0
        // ColorOS 17 posts the klaxon on this channel. Timer and stopwatch services use it too.
        if ("clock_foreground_service_channel_id" == channel) return service
        if (id == Int.MIN_VALUE || id == -1017) return true
        if (!channel.contains("alarmclock")) return false
        return fullScreen || service
    }

    /** A running timer or stopwatch. The band has no packet for either, so the clock alarm stays on the phone. */
    private fun runningClockTool(id: Int, channel: String): Boolean {
        if (id == TIMER_SERVICE_ID || id == TIMER_FLOATING_ID || id == STOPWATCH_SERVICE_ID) return true
        return channel.contains("timer") || channel.contains("stopwatch")
    }

    @JvmStatic
    fun ringing(item: StatusBarNotification?): Boolean {
        val notification = item?.notification ?: return false
        return ringing(
            item.packageName,
            notification.flags,
            notification.channelId,
            item.id,
            notification.fullScreenIntent != null,
        )
    }

    @JvmStatic
    fun posted(context: Context?, item: StatusBarNotification?) {
        if (item == null || !ringing(item)) return
        ringing.add(item.key)
        val title = item.notification.extras?.getString(android.app.Notification.EXTRA_TITLE, "") ?: ""
        send(context, 0, title)
    }

    @JvmStatic
    fun removed(context: Context?, item: StatusBarNotification?) {
        if (item == null || !ringing.remove(item.key)) return
        send(context, 1, "")
    }

    /** The clock klaxon just alerted the band. Later notification echoes keep that alarm id. */
    @JvmStatic
    fun noteClockRing() {
        clockRingAt = android.os.SystemClock.elapsedRealtime()
    }

    @JvmStatic
    fun claim(op: Int): Boolean {
        if (op != 0 && op != 1 && op != 2) return false
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(PhoneAlarmNotice::class.java) {
            if (op == lastSentOp && now - lastSentAt < DEDUPE_MS) return false
            lastSentOp = op
            lastSentAt = now
            return true
        }
    }

    @JvmStatic
    fun send(context: Context?, op: Int, label: String?) {
        if (context == null) return
        if (op == 0 && android.os.SystemClock.elapsedRealtime() - clockRingAt < 15_000L) return
        if (!claim(op)) return
        var shown = label?.replace('\n', ' ')?.replace('\r', ' ')?.trim().orEmpty()
        if (shown.length > 40) shown = shown.substring(0, 40)
        SessionLog.line(context, "ALARM_PHONE op=$op")
        try {
            BandLiveService.sendSessionCommand(
                BandAlarmCommand.operation(
                    op,
                    1,
                    if (op == 0) (System.currentTimeMillis() / 1000L).toInt() else -1,
                    shown,
                ),
            )
        } catch (failure: RuntimeException) {
            SessionLog.line(context, "ALARM_PHONE_FAILED ${failure.javaClass.simpleName}")
        }
    }
}
