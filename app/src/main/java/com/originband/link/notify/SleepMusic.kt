// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

import android.content.Context
import com.originband.link.data.LocalPrefs
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

/**
 * Type 2 subtype 78 is one baseline read ({@code isUserAsleep}).
 * Subtype 79 is the pushed change ({@code sleepState} 1 asleep, 2 awake).
 * The first sample after {@link #reset()} is only a baseline.
 * A sleep file that started after the switch was armed pauses once per night.
 * An asleep baseline, or a later awake report, holds that night until the next one.
 */
class SleepMusic {
    enum class Effect { BASELINE, UNCHANGED, PAUSE, WOKE }

    data class Report(
        @JvmField val effect: Effect,
        @JvmField val generation: Int,
    ) {
        fun effect(): Effect = effect

        fun generation(): Int = generation
    }

    private var phase = UNKNOWN
    private var generation = 0
    private var holdFile = false
    private var baselineAtMs = Long.MAX_VALUE
    private var pausedStart = Long.MIN_VALUE

    @JvmOverloads
    @Synchronized
    fun observe(asleep: Boolean, nowMs: Long = Long.MAX_VALUE): Report {
        val next = if (asleep) ASLEEP else AWAKE
        if (phase == UNKNOWN) {
            phase = next
            if (asleep) holdNight(nowMs)
            return Report(Effect.BASELINE, generation)
        }
        if (phase == ASLEEP && !asleep) holdNight(nowMs)
        return transition(next)
    }

    /** Subtype 79. The first sample is still only a baseline. */
    @JvmOverloads
    @Synchronized
    fun push(asleep: Boolean, nowMs: Long = Long.MIN_VALUE): Report {
        val next = if (asleep) ASLEEP else AWAKE
        if (phase == UNKNOWN) {
            phase = next
            return Report(Effect.BASELINE, generation)
        }
        if (phase == ASLEEP && !asleep) holdNight(nowMs)
        return transition(next)
    }

    /**
     * A parsed sleep interval. Skipped when it began before the switch was armed,
     * before a held night, or its end is no longer current.
     */
    @Synchronized
    fun currentNight(startMs: Long, endMs: Long, armedAtMs: Long, nowMs: Long): Report {
        if (startMs < armedAtMs || endMs <= startMs ||
            endMs < nowMs - FILE_WINDOW_MS || endMs > nowMs + 5 * 60_000L
        ) {
            return Report(Effect.UNCHANGED, generation)
        }
        if (holdFile && startMs <= baselineAtMs) return Report(Effect.UNCHANGED, generation)
        holdFile = false
        if (startMs == pausedStart) return Report(Effect.UNCHANGED, generation)
        pausedStart = startMs
        phase = ASLEEP
        return Report(Effect.PAUSE, generation)
    }

    private fun holdNight(nowMs: Long) {
        holdFile = true
        baselineAtMs = nowMs
    }

    @Synchronized
    fun asleep(): Boolean = phase == ASLEEP

    private fun transition(next: Int): Report {
        val effect = when {
            phase == next -> Effect.UNCHANGED
            next == ASLEEP -> Effect.PAUSE
            else -> Effect.WOKE
        }
        phase = next
        return Report(effect, generation)
    }

    @Synchronized
    fun reset(): Int {
        phase = UNKNOWN
        holdFile = false
        baselineAtMs = Long.MAX_VALUE
        pausedStart = Long.MIN_VALUE
        return ++generation
    }

    @Synchronized
    fun generation(): Int = generation

    companion object {
        const val PREFS = "features"
        const val KEY = "sleepPauseMusic"
        const val ARMED_KEY = "sleepPauseArmedAt"

        /** A file whose end is older than this is a previous night, not a reason to pause. */
        const val FILE_WINDOW_MS = 20 * 60_000L

        private const val UNKNOWN = -1
        private const val AWAKE = 0
        private const val ASLEEP = 1

        @JvmStatic
        fun enabled(context: Context): Boolean =
            LocalPrefs.open(context, PREFS).getBoolean(KEY, false)

        /** Switch-on time. A restart keeps it; a missing value is not yet armed. */
        @JvmStatic
        fun armedAt(context: Context): Long {
            if (!enabled(context)) return Long.MAX_VALUE
            return LocalPrefs.open(context, PREFS).getLong(ARMED_KEY, Long.MAX_VALUE)
        }

        /** Move the cutoff forward. Nights that started before it stay playing after a restart. */
        @JvmStatic
        fun rememberCutoff(context: Context, cutoffMs: Long): Boolean {
            if (!enabled(context) || cutoffMs <= 0 || cutoffMs == Long.MAX_VALUE) return false
            val current = LocalPrefs.open(context, PREFS).getLong(ARMED_KEY, Long.MIN_VALUE)
            if (current != Long.MIN_VALUE && cutoffMs <= current) return true
            return LocalPrefs.open(context, PREFS).edit().putLong(ARMED_KEY, cutoffMs).commit()
        }

        @JvmStatic
        fun setEnabled(context: Context, enabled: Boolean): Boolean {
            val edit = LocalPrefs.open(context, PREFS).edit().putBoolean(KEY, enabled)
            if (enabled) edit.putLong(ARMED_KEY, System.currentTimeMillis()) else edit.remove(ARMED_KEY)
            return edit.commit()
        }

        @JvmStatic
        fun query(): XiaomiProto.Command =
            XiaomiProto.Command.newBuilder().setType(2).setSubtype(78).build()

        /** @return null when {@code command} is not a sleep report */
        @JvmStatic
        fun asleep(command: XiaomiProto.Command?): Boolean? {
            if (command == null || command.type != 2 || !command.hasSystem() ||
                (command.hasStatus() && command.status != 0)
            ) {
                return null
            }
            if (command.subtype == 78 && command.system.hasBasicDeviceState()) {
                return command.system.basicDeviceState.isUserAsleep
            }
            if (command.subtype == 79 && command.system.hasDeviceState() &&
                command.system.deviceState.hasSleepState()
            ) {
                return when (command.system.deviceState.sleepState) {
                    1 -> true
                    2 -> false
                    else -> null
                }
            }
            return null
        }
    }
}
