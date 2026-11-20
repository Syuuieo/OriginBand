// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

import android.app.NotificationManager
import android.content.Context

/** Phone DND. Values match NotificationManager interruption filters. */
object PhoneDnd {
    const val UNKNOWN = 0
    const val ALL = 1
    const val PRIORITY = 2
    const val NONE = 3
    const val ALARMS = 4

    data class Attempt(
        @JvmField val applied: Boolean,
        @JvmField val policy: Boolean,
        @JvmField val via: String,
    ) {
        fun applied(): Boolean = applied
        fun policy(): Boolean = policy
        fun via(): String = via
    }

    @JvmStatic
    fun currentFilter(context: Context): Int {
        val zen = global(context, "zen_mode")
        val focus = secure(context, "focusmode_switch")
        val focusNew = secure(context, "focusmode_switch_new")
        val breath = secure(context, "op_breath_mode_status")
        var interruption = UNKNOWN
        if (focusNew != 1 && focus != 1 && breath != 1 &&
            zen != 0 && zen != 1 && zen != 2 && zen != 3
        ) {
            interruption = interruptionFilter(context)
        }
        return resolve(zen, focus, focusNew, breath, interruption)
    }

    /** Health 6.9.40 ZenModeObserver: vendor switch 1 is on. Classic zen_mode still wins when those keys are absent. */
    @JvmStatic
    fun resolve(zen: Int, focus: Int, focusNew: Int, breath: Int, interruptionFilter: Int): Int {
        if (focusNew == 1 || focus == 1 || breath == 1) return PRIORITY
        if (zen == 1) return PRIORITY
        if (zen == 2) return NONE
        if (zen == 3) return ALARMS
        if (zen == 0) return ALL
        return interruptionFilter
    }

    @JvmStatic
    fun describe(context: Context): String {
        val zen = global(context, "zen_mode")
        val focus = secure(context, "focusmode_switch")
        val focusNew = secure(context, "focusmode_switch_new")
        val breath = secure(context, "op_breath_mode_status")
        val filter = currentFilter(context)
        return "filter=$filter on=${blocksNotifications(filter)}" +
            " zen=$zen focus=$focus focusNew=$focusNew breath=$breath"
    }

    private fun global(context: Context, key: String): Int =
        try {
            android.provider.Settings.Global.getInt(context.contentResolver, key, 0)
        } catch (_: RuntimeException) {
            0
        }

    private fun secure(context: Context, key: String): Int =
        try {
            android.provider.Settings.Secure.getInt(context.contentResolver, key, -1)
        } catch (_: RuntimeException) {
            -1
        }

    private fun interruptionFilter(context: Context): Int {
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager?.currentInterruptionFilter ?: UNKNOWN
    }

    /** Priority, alarms-only, and total silence all suppress ordinary notification pushes. */
    @JvmStatic
    fun blocksNotifications(filter: Int): Boolean =
        filter == PRIORITY || filter == NONE || filter == ALARMS

    /** ColorOS delivers one change as both a filter broadcast and a zen_mode write. */
    @JvmStatic
    fun repeatSync(previousFilter: Int, elapsedNanos: Long, filter: Int): Boolean =
        previousFilter == filter && elapsedNanos >= 0 && elapsedNanos < 1_000_000_000L

    @JvmStatic
    fun acceptBandManual(bandOn: Boolean, phoneFilter: Int, sentAtNanos: Long, nowNanos: Long): Boolean {
        if (phoneFilter == UNKNOWN) return false
        if (bandOn == blocksNotifications(phoneFilter)) return false
        return sentAtNanos == 0L || nowNanos - sentAtNanos >= 3_000_000_000L
    }

    /**
     * ColorOS keeps the status-bar switch in zen_mode. setInterruptionFilter can return
     * without writing it, and a normal app cannot write zen_mode. Callers that still
     * see applied=false retry from a system process.
     */
    @JvmStatic
    fun apply(context: Context, on: Boolean): Attempt {
        val policy = policyGranted(context)
        if (blocksNotifications(currentFilter(context)) == on) return Attempt(true, policy, "already")
        if (policy) {
            val manager = context.getSystemService(NotificationManager::class.java)
            try {
                manager?.setInterruptionFilter(if (on) PRIORITY else ALL)
            } catch (_: RuntimeException) {
            }
            if (blocksNotifications(currentFilter(context)) == on) return Attempt(true, true, "filter")
        }
        if (writeZen(context, on) && blocksNotifications(currentFilter(context)) == on) {
            return Attempt(true, policy, "zen")
        }
        return Attempt(false, policy, "none")
    }

    @JvmStatic
    fun policyGranted(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager != null && manager.isNotificationPolicyAccessGranted
    }

    /** 0 off, 1 priority. Matches the zen values this phone reports when the tile moves. */
    @JvmStatic
    fun zenMode(on: Boolean): Int = if (on) 1 else 0

    /**
     * A phone toggle may overwrite the band. A connect or a failed write leaves a band
     * that is already the other way on its own state.
     */
    @JvmStatic
    fun sendPhoneRule(
        phoneChanged: Boolean,
        phoneOn: Boolean,
        bandKnown: Boolean,
        bandOn: Boolean,
    ): Boolean {
        if (phoneChanged) return true
        if (!bandKnown) return false
        return bandOn == phoneOn
    }

    private fun writeZen(context: Context, on: Boolean): Boolean =
        try {
            android.provider.Settings.Global.putInt(
                context.contentResolver,
                "zen_mode",
                zenMode(on),
            )
        } catch (_: RuntimeException) {
            false
        }
}
