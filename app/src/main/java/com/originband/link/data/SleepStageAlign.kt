// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

/** The newest file with an analysis owns the interval. An empty analysis leaves the interval for an older file. */
class SleepStageAlign private constructor() {
    data class Interval(
        @JvmField val startMs: Long,
        @JvmField val endMs: Long,
    ) {
        fun startMs(): Long = startMs

        fun endMs(): Long = endMs

        fun overlaps(start: Long, end: Long): Boolean = start < endMs && end > startMs
    }

    companion object {
        /** Intervals in this file that a newer file has not already taken. */
        @JvmStatic
        fun claim(fileIntervals: List<Interval?>?, newer: List<Interval?>?): List<Interval> {
            val owned = ArrayList<Interval>()
            if (fileIntervals == null) return owned
            for (interval in fileIntervals) {
                if (interval == null || interval.endMs <= interval.startMs) continue
                if (overlapsAny(interval.startMs, interval.endMs, newer)) continue
                owned.add(interval)
            }
            return owned
        }

        /** An owned interval with no stage stays free so an older file can keep its chart. */
        @JvmStatic
        fun withStages(owned: List<Interval?>?, stages: List<Interval?>?): List<Interval> {
            val covered = ArrayList<Interval>()
            if (owned == null) return covered
            for (interval in owned) {
                if (interval != null && overlapsAny(interval.startMs, interval.endMs, stages)) {
                    covered.add(interval)
                }
            }
            return covered
        }

        @JvmStatic
        fun overlapsAny(startMs: Long, endMs: Long, others: List<Interval?>?): Boolean {
            if (others == null || endMs <= startMs) return false
            for (other in others) {
                if (other != null && other.overlaps(startMs, endMs)) return true
            }
            return false
        }
    }
}
