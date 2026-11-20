// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import java.util.TreeMap
import java.util.TreeSet

/**
 * The band does not report a walking average. Health only shows one when it holds a heart-rate
 * point typed for walking, so this derives the day's value from the band's own minute rows:
 * minutes that look like walking and carry a nearby continuous heart rate.
 */
object WalkingHeartRate {
    /** Below this a minute is idle shuffling, above it the minute is a run. */
    const val MIN_STEPS_PER_MINUTE = 60
    const val MAX_STEPS_PER_MINUTE = 140

    /**
     * The band samples a heart rate about every ten minutes, so a walking minute borrows the
     * nearest reading inside half that interval instead of demanding one in the same minute.
     */
    const val MATCH_WINDOW_MS = 5 * 60_000L

    /** Fewer readings than this is not an average worth showing. */
    const val MIN_SAMPLES = 10

    /** Null when the day does not hold enough walking readings to average. */
    @JvmStatic
    fun average(
        steps: List<HealthRecord?>?,
        heart: List<HealthRecord?>?,
        sport: List<HealthRecord?>?,
    ): Int? {
        if (steps == null || heart == null) return null
        val beats = TreeMap<Long, Int>()
        for (sample in heart) {
            if (sample == null || sample.kind != "heart_rate" ||
                sample.measurementMode != "continuous"
            ) {
                continue
            }
            val rawValue = sample.value ?: continue
            val value = rawValue.toInt()
            if (value < 40 || value > 220) continue
            beats.putIfAbsent(sample.startMs, value)
        }
        if (beats.isEmpty()) return null
        val workouts = sport ?: emptyList()
        val used = TreeSet<Long>()
        var total = 0L
        for (minute in steps) {
            if (minute == null || !walking(minute) || duringWorkout(workouts, minute.startMs)) continue
            val beat = nearest(beats, minute.startMs)
            // One reading is one measurement, however many walking minutes borrow it.
            if (beat != null && used.add(beat.key)) total += beat.value
        }
        if (used.size < MIN_SAMPLES) return null
        return Math.round(total.toDouble() / used.size).toInt()
    }

    private fun walking(minute: HealthRecord): Boolean {
        if (minute.kind != "steps_interval") return false
        val rawValue = minute.value ?: return false
        val count = rawValue.toInt()
        return count in MIN_STEPS_PER_MINUTE..MAX_STEPS_PER_MINUTE
    }

    private fun nearest(beats: TreeMap<Long, Int>, atMs: Long): Map.Entry<Long, Int>? {
        var before = beats.floorEntry(atMs)
        var after = beats.ceilingEntry(atMs)
        if (before != null && atMs - before.key > MATCH_WINDOW_MS) before = null
        if (after != null && after.key - atMs > MATCH_WINDOW_MS) after = null
        if (before == null) return after
        if (after == null) return before
        return if (after.key - atMs < atMs - before.key) after else before
    }

    private fun duringWorkout(workouts: List<HealthRecord?>, atMs: Long): Boolean {
        for (workout in workouts) {
            if (workout == null || workout.kind != "sport_session") continue
            if (atMs >= workout.startMs && atMs < workout.endMs) return true
        }
        return false
    }
}
