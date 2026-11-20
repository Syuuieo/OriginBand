// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Comparator
import java.util.LinkedHashMap

/**
 * Groups band sleep intervals and stages into per-night sessions and sleep days.
 */
class SleepPlan private constructor() {
    data class Segment(
        @JvmField val startMs: Long,
        @JvmField val endMs: Long,
        @JvmField val sleepState: Int,
        @JvmField val syntheticAwake: Boolean,
    ) {
        constructor(startMs: Long, endMs: Long, sleepState: Int) : this(startMs, endMs, sleepState, false)

        fun startMs(): Long = startMs

        fun endMs(): Long = endMs

        fun sleepState(): Int = sleepState

        fun syntheticAwake(): Boolean = syntheticAwake
    }

    data class Night(
        @JvmField val date: Int,
        @JvmField val zone: ZoneId,
        @JvmField val fallAsleepMs: Long,
        @JvmField val wakeMs: Long,
        @JvmField val sleepMinutes: Long,
        @JvmField val deepMinutes: Long,
        @JvmField val lightMinutes: Long,
        @JvmField val remMinutes: Long,
        @JvmField val wakeMinutes: Long,
        @JvmField val segments: List<Segment>,
        @JvmField val dayStartMs: Long,
        @JvmField val dayEndMs: Long,
        @JvmField val snapshots: Int,
        @JvmField val syntheticAwakeMinutes: Long,
    ) {
        constructor(
            date: Int,
            zone: ZoneId,
            fallAsleepMs: Long,
            wakeMs: Long,
            sleepMinutes: Long,
            deepMinutes: Long,
            lightMinutes: Long,
            remMinutes: Long,
            wakeMinutes: Long,
            segments: List<Segment>,
            dayStartMs: Long,
            dayEndMs: Long,
        ) : this(
            date, zone, fallAsleepMs, wakeMs, sleepMinutes, deepMinutes, lightMinutes,
            remMinutes, wakeMinutes, segments, dayStartMs, dayEndMs, 0, 0L,
        )

        constructor(
            date: Int,
            fallAsleepMs: Long,
            wakeMs: Long,
            sleepMinutes: Long,
            deepMinutes: Long,
            lightMinutes: Long,
            remMinutes: Long,
            wakeMinutes: Long,
            segments: List<Segment>,
            dayStartMs: Long,
            dayEndMs: Long,
        ) : this(
            date, ZoneId.systemDefault(), fallAsleepMs, wakeMs, sleepMinutes, deepMinutes,
            lightMinutes, remMinutes, wakeMinutes, segments, dayStartMs, dayEndMs, 0, 0L,
        )

        fun date(): Int = date

        fun zone(): ZoneId = zone

        fun fallAsleepMs(): Long = fallAsleepMs

        fun wakeMs(): Long = wakeMs

        fun sleepMinutes(): Long = sleepMinutes

        fun deepMinutes(): Long = deepMinutes

        fun lightMinutes(): Long = lightMinutes

        fun remMinutes(): Long = remMinutes

        fun wakeMinutes(): Long = wakeMinutes

        fun segments(): List<Segment> = segments

        fun dayStartMs(): Long = dayStartMs

        fun dayEndMs(): Long = dayEndMs

        fun snapshots(): Int = snapshots

        fun syntheticAwakeMinutes(): Long = syntheticAwakeMinutes
    }

    data class Day(
        @JvmField val date: Int,
        @JvmField val zone: ZoneId,
        @JvmField val fallAsleepMs: Long,
        @JvmField val wakeMs: Long,
        @JvmField val sleepMinutes: Long,
        @JvmField val deepMinutes: Long,
        @JvmField val lightMinutes: Long,
        @JvmField val remMinutes: Long,
        @JvmField val wakeMinutes: Long,
        @JvmField val nights: List<Night>,
        @JvmField val mainSession: Night?,
        @JvmField val dayStartMs: Long,
        @JvmField val dayEndMs: Long,
    ) {
        fun date(): Int = date

        fun zone(): ZoneId = zone

        fun fallAsleepMs(): Long = fallAsleepMs

        fun wakeMs(): Long = wakeMs

        fun sleepMinutes(): Long = sleepMinutes

        fun deepMinutes(): Long = deepMinutes

        fun lightMinutes(): Long = lightMinutes

        fun remMinutes(): Long = remMinutes

        fun wakeMinutes(): Long = wakeMinutes

        fun nights(): List<Night> = nights

        fun mainSession(): Night? = mainSession

        fun dayStartMs(): Long = dayStartMs

        fun dayEndMs(): Long = dayEndMs
    }

    private data class IntervalGroup(val record: HealthRecord, val snapshots: Int)

    private data class Count(
        val sleep: Long,
        val deep: Long,
        val light: Long,
        val rem: Long,
        val awake: Long,
        val syntheticAwake: Long,
    )

    private class Session(
        val date: Int,
        val zone: ZoneId,
        val fall: Long,
        val wake: Long,
        val sleep: Long,
        val deep: Long,
        val light: Long,
        val rem: Long,
        val awake: Long,
        val snapshots: Int,
        val syntheticAwake: Long,
        val segments: List<Segment>,
    )

    companion object {
        const val DEEP = 2
        const val REM = 3
        const val LIGHT = 4
        const val AWAKE = 5
        const val UNSTAGED = 6

        /** The calendar date whose 20:00-to-20:00 window contains this wake time. */
        @JvmStatic
        fun sleepDate(endMs: Long, zone: ZoneId): Int {
            val local = Instant.ofEpochMilli(endMs).atZone(zone).toLocalDateTime()
            val day = if (local.hour >= 20) local.toLocalDate().plusDays(1) else local.toLocalDate()
            return day.year * 10000 + day.monthValue * 100 + day.dayOfMonth
        }

        @JvmStatic
        fun sleepDayMinutes(epochMs: Long, zone: ZoneId): Long {
            val local = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDateTime()
            var hour = local.hour
            if (hour < 20) hour += 24
            return hour * 60L + local.minute
        }

        @JvmStatic
        fun hostState(bandStage: Int): Int = when (bandStage) {
            2 -> DEEP
            3 -> LIGHT
            4 -> REM
            5 -> AWAKE
            6 -> UNSTAGED
            else -> throw IllegalArgumentException("UNSUPPORTED_SLEEP_STAGE")
        }

        @JvmStatic
        fun nights(records: List<HealthRecord>): List<Night> = nights(records, ZoneId.systemDefault())

        private fun unionOverlapping(intervals: List<HealthRecord>): List<IntervalGroup> {
            if (intervals.size < 2) {
                return intervals.map { IntervalGroup(it, 1) }
            }
            val sorted = ArrayList(intervals)
            sorted.sortWith(Comparator.comparingLong { it.startMs })
            val merged = ArrayList<IntervalGroup>()
            var current = sorted[0]
            var groupStartMs = current.startMs
            var snapshots = 1
            for (i in 1 until sorted.size) {
                val next = sorted[i]
                if (next.startMs <= current.endMs) {
                    if (next.startMs == groupStartMs) snapshots++
                    if (next.endMs > current.endMs) {
                        current = HealthRecord(
                            current.recordId, current.deviceId, current.kind,
                            current.startMs, next.endMs, null, null, maxOf(1, current.revision),
                            current.timezone, "sleep", current.complete || next.complete,
                        )
                    }
                    continue
                }
                merged.add(IntervalGroup(current, snapshots))
                current = next
                groupStartMs = current.startMs
                snapshots = 1
            }
            merged.add(IntervalGroup(current, snapshots))
            return merged
        }

        @JvmStatic
        fun nights(records: List<HealthRecord>, zone: ZoneId?): List<Night> {
            if (zone == null) throw IllegalArgumentException("SLEEP_ZONE_MISSING")
            val rawIntervals = ArrayList<HealthRecord>()
            val stages = ArrayList<HealthRecord>()
            for (record in records) {
                if ("sleep_interval" == record.kind) rawIntervals.add(record)
                else if ("sleep_stage" == record.kind) stages.add(record)
            }
            val intervalGroups = unionOverlapping(rawIntervals)
            val intervals = intervalGroups.map { it.record }
            val used = BooleanArray(stages.size)
            val sessions = ArrayList<Session>()
            for (group in intervalGroups) {
                val interval = group.record
                var segments: MutableList<Segment> = ArrayList()
                for (i in stages.indices) {
                    if (used[i]) continue
                    val stage = stages[i]
                    val overlap = overlap(interval, stage)
                    if (overlap <= 0 || !bestInterval(interval, stage, intervals, overlap)) continue
                    used[i] = true
                    segments.add(Segment(stage.startMs, stage.endMs, hostState(stage.stage ?: -1)))
                }
                if (segments.isEmpty()) {
                    segments.add(Segment(interval.startMs, interval.endMs, UNSTAGED))
                }
                segments = flatten(segments)
                val clusters = ArrayList<MutableList<Segment>>()
                var currentCluster = ArrayList<Segment>()
                currentCluster.add(segments[0])
                for (i in 1 until segments.size) {
                    val seg = segments[i]
                    val prev = segments[i - 1]
                    if (seg.startMs - prev.endMs > 1_200_000L) {
                        clusters.add(currentCluster)
                        currentCluster = ArrayList()
                    }
                    currentCluster.add(seg)
                }
                clusters.add(currentCluster)

                for (cluster in clusters) {
                    val fall = cluster[0].startMs
                    val wake = cluster[cluster.size - 1].endMs
                    val date = sleepDate(wake, zone)
                    val counted = count(cluster)
                    sessions.add(
                        Session(
                            date, zone, fall, wake,
                            counted.sleep, counted.deep, counted.light, counted.rem, counted.awake,
                            group.snapshots, counted.syntheticAwake, cluster,
                        ),
                    )
                }
            }
            val nights = ArrayList<Night>()
            for (session in sessions) {
                val window = sleepDayWindow(session.date, session.zone)
                val purgeStart = minOf(window[0], session.fall)
                val flat = flatten(session.segments)
                val counted = count(flat)
                nights.add(
                    Night(
                        session.date, session.zone, session.fall, session.wake, counted.sleep, counted.deep,
                        counted.light, counted.rem, counted.awake, java.util.List.copyOf(flat),
                        purgeStart, window[1], session.snapshots, session.syntheticAwake,
                    ),
                )
            }
            nights.sortWith(Comparator.comparingInt<Night> { it.date }.thenComparingLong { it.fallAsleepMs })
            return nights
        }

        @JvmStatic
        fun days(nights: List<Night>?): List<Day> {
            if (nights.isNullOrEmpty()) return emptyList()
            val byDate = LinkedHashMap<Int, ArrayList<Night>>()
            for (night in nights) {
                byDate.getOrPut(night.date) { ArrayList() }.add(night)
            }
            val days = ArrayList<Day>()
            for ((date, dateNights) in byDate) {
                dateNights.sortWith(Comparator.comparingLong { it.fallAsleepMs })

                var minFall = Long.MAX_VALUE
                var maxWake = Long.MIN_VALUE
                var totalSleep = 0L
                var totalDeep = 0L
                var totalLight = 0L
                var totalRem = 0L
                var totalWake = 0L
                var dayStart = Long.MAX_VALUE
                var dayEnd = Long.MIN_VALUE
                var zone: ZoneId? = null
                var mainSession: Night? = null

                for (night in dateNights) {
                    minFall = minOf(minFall, night.fallAsleepMs)
                    maxWake = maxOf(maxWake, night.wakeMs)
                    totalSleep += night.sleepMinutes
                    totalDeep += night.deepMinutes
                    totalLight += night.lightMinutes
                    totalRem += night.remMinutes
                    totalWake += night.wakeMinutes
                    dayStart = minOf(dayStart, night.dayStartMs)
                    dayEnd = maxOf(dayEnd, night.dayEndMs)
                    if (zone == null) zone = night.zone
                    if (summary(nights, night)) {
                        mainSession = night
                    }
                }
                val resolvedZone = zone ?: ZoneId.systemDefault()

                days.add(
                    Day(
                        date, resolvedZone, minFall, maxWake, totalSleep, totalDeep, totalLight,
                        totalRem, totalWake, java.util.List.copyOf(dateNights), mainSession, dayStart, dayEnd,
                    ),
                )
            }
            days.sortWith(Comparator.comparingInt { it.date })
            return days
        }

        @JvmStatic
        fun summary(nights: List<Night>, night: Night): Boolean {
            if (night.sleepMinutes < 120) return false
            for (other in nights) {
                if (other.date != night.date || other === night) continue
                if (other.sleepMinutes > night.sleepMinutes) return false
                if (other.sleepMinutes == night.sleepMinutes &&
                    other.fallAsleepMs < night.fallAsleepMs
                ) {
                    return false
                }
            }
            return true
        }

        @JvmStatic
        fun sleepDayWindow(date: Int, zone: ZoneId): LongArray {
            val year = date / 10000
            val month = (date / 100) % 100
            val day = date % 100
            val end = LocalDate.of(year, month, day).atTime(20, 0).atZone(zone)
            return longArrayOf(end.minusDays(1).toInstant().toEpochMilli(), end.toInstant().toEpochMilli())
        }

        private fun bestInterval(
            interval: HealthRecord,
            stage: HealthRecord,
            intervals: List<HealthRecord>,
            overlap: Long,
        ): Boolean {
            for (other in intervals) {
                if (other === interval) continue
                val otherOverlap = overlap(other, stage)
                if (otherOverlap > overlap || (otherOverlap == overlap && other.startMs < interval.startMs)) {
                    return false
                }
            }
            return true
        }

        private fun overlap(interval: HealthRecord, stage: HealthRecord): Long =
            maxOf(0L, minOf(interval.endMs, stage.endMs) - maxOf(interval.startMs, stage.startMs))

        private fun merge(segments: MutableList<Segment>): MutableList<Segment> {
            if (segments.isEmpty()) return segments
            val sorted = ArrayList(segments)
            sorted.sortWith(Comparator.comparingLong<Segment> { it.startMs }.thenComparingInt { it.sleepState })
            val merged = ArrayList<Segment>()
            var current = sorted[0]
            for (i in 1 until sorted.size) {
                val next = sorted[i]
                if (next.sleepState == current.sleepState &&
                    next.syntheticAwake == current.syntheticAwake &&
                    next.startMs <= current.endMs
                ) {
                    current = Segment(
                        current.startMs, maxOf(current.endMs, next.endMs),
                        current.sleepState, current.syntheticAwake,
                    )
                } else {
                    merged.add(current)
                    current = next
                }
            }
            merged.add(current)
            return merged
        }

        private fun flatten(segments: List<Segment>): MutableList<Segment> {
            if (segments.size < 2) return ArrayList(segments)
            val sorted = ArrayList(segments)
            sorted.sortWith(
                compareBy<Segment> { it.startMs }
                    .then(
                        Comparator { left, right ->
                            java.lang.Long.compare(right.endMs - right.startMs, left.endMs - left.startMs)
                        },
                    )
                    .thenBy { it.sleepState },
            )
            val parted = ArrayList<Segment>()
            var current = sorted[0]
            for (i in 1 until sorted.size) {
                var next = sorted[i]
                if (next.startMs < current.endMs) {
                    if (next.endMs <= current.endMs) continue
                    next = Segment(current.endMs, next.endMs, next.sleepState, next.syntheticAwake)
                }
                parted.add(current)
                current = next
            }
            parted.add(current)
            return stitch(merge(parted))
        }

        private fun stitch(segments: MutableList<Segment>): MutableList<Segment> {
            if (segments.size < 2) return segments
            val stitched = ArrayList<Segment>()
            var current = segments[0]
            for (i in 1 until segments.size) {
                val next = segments[i]
                val gap = next.startMs - current.endMs
                if (gap in 1..1_200_000L) {
                    stitched.add(current)
                    if (gap >= 60_000L) {
                        stitched.add(Segment(current.endMs, next.startMs, AWAKE, true))
                    }
                } else {
                    stitched.add(current)
                }
                current = next
            }
            stitched.add(current)
            return stitched
        }

        private fun count(segments: List<Segment>): Count {
            var sleep = 0L
            var deep = 0L
            var light = 0L
            var rem = 0L
            var awake = 0L
            var syntheticAwake = 0L
            for (segment in segments) {
                val minutes = maxOf(0L, (segment.endMs - segment.startMs) / 60_000L)
                when (segment.sleepState) {
                    DEEP -> {
                        deep += minutes
                        sleep += minutes
                    }
                    LIGHT -> {
                        light += minutes
                        sleep += minutes
                    }
                    REM -> {
                        rem += minutes
                        sleep += minutes
                    }
                    UNSTAGED -> sleep += minutes
                    AWAKE -> {
                        awake += minutes
                        if (segment.syntheticAwake) syntheticAwake += minutes
                    }
                    else -> throw IllegalStateException("SLEEP_STATE_UNMAPPED")
                }
            }
            return Count(sleep, deep, light, rem, awake, syntheticAwake)
        }
    }
}
