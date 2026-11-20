// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.host.vivo

import com.originband.link.data.HealthRecord
import java.time.Instant
import java.time.ZoneId
import java.util.Collections
import java.util.EnumMap
import java.util.regex.Pattern
import kotlin.math.abs

/**
 * Pure translation from OriginBand [HealthRecord] batches to com.vivo.health 7.1.3.72
 * model values and unit conventions.
 */
object VivoHealthMapper {
    const val HOUR_MS = 3_600_000L
    private const val SLEEP_CLUSTER_GAP_MS = 2L * HOUR_MS
    private const val SLEEP_STAGE_DEEP = 2
    private const val SLEEP_STAGE_REM = 3
    private const val SLEEP_STAGE_LIGHT = 4
    private const val SLEEP_STAGE_AWAKE = 5

    private val COMMA_SPLIT = Pattern.compile(",")
    private val SANITIZE_UUID_REGEX = Regex("[^A-Za-z0-9._-]")

    enum class Category {
        HEART_RATE,
        RESTING_HR,
        WALKING_HR,
        SPO2,
        STRESS,
        STEPS_INTERVAL,
        STEPS_DAY,
        SLEEP,
        SPORT_SESSION,
        SPORT_TRACK,
        SLEEP_BREATH,
    }

    @JvmRecord
    data class Receipt(val recordId: String, val revision: Int)

    @JvmRecord
    data class DataPoint(val timestampS: Long, val timestampMs: Long, val value: Int)

    @JvmRecord
    data class DailyHeartRate(val dayStartMs: Long, val sampleMs: Long, val bpm: Int)

    @JvmRecord
    data class TodayStepSlice(val uuid: String, val startMs: Long, val endMs: Long, val steps: Int)

    @JvmRecord
    data class TodayCalorieSlice(val uuid: String, val startMs: Long, val endMs: Long, val smallCalories: Int)

    @JvmRecord
    data class TodayDistanceSlice(val uuid: String, val startMs: Long, val endMs: Long, val meters: Int)

    @JvmRecord
    data class TodayStandSlice(val uuid: String, val hourStartMs: Long, val hourEndMs: Long, val value: Int)

    @JvmRecord
    data class DailyTotals(
        val dayStartMs: Long,
        val steps: Int,
        val caloriesKcal: Float?,
        val distanceMeters: Float?,
    )

    @JvmRecord
    data class SleepRange(val enterMs: Long, val exitMs: Long)

    @JvmRecord
    data class SleepNight(
        val uuid: String,
        val enterTimeMs: Long,
        val exitTimeMs: Long,
        val deep: List<SleepRange>,
        val light: List<SleepRange>,
        val rem: List<SleepRange>,
        val awake: List<SleepRange>,
        val deepMin: Int,
        val lightMin: Int,
        val remMin: Int,
        val awakeMin: Int,
        val zoneOffsetMin: Int,
    )

    @JvmRecord
    data class SportSession(
        val eid: String,
        val tag: Int,
        val vivoSportType: Int,
        val rtcStartTimeMs: Long,
        val rtcEndTimeMs: Long,
        val durationMs: Long,
        val activeDurationSec: Int,
        val calorieKcal: Int,
        val distanceMeters: Int,
        val steps: Int,
        val avgHeartRate: Int,
        val averagePaceSecPerKm: Int,
        val averageSpeedMps: Float,
    )

    @JvmRecord
    data class GpsPoint(
        val tag: Int,
        val rtcTimeMs: Long,
        val latitude: Double,
        val longitude: Double,
        val speedMps: Float,
    )

    class MappedBatch(
        heartRates: List<DataPoint>,
        restingRates: List<DailyHeartRate>,
        walkingRates: List<DailyHeartRate>,
        oxygens: List<DataPoint>,
        stresses: List<DataPoint>,
        stepSlices: List<TodayStepSlice>,
        calorieSlices: List<TodayCalorieSlice>,
        distanceSlices: List<TodayDistanceSlice>,
        standSlices: List<TodayStandSlice>,
        dailyTotals: List<DailyTotals>,
        sleepNights: List<SleepNight>,
        sportSessions: List<SportSession>,
        gpsPoints: List<GpsPoint>,
        handled: List<Receipt>,
        unsupported: List<Receipt>,
        receiptsByCategory: Map<Category, List<Receipt>>,
    ) {
        @JvmField val heartRates: List<DataPoint> = java.util.List.copyOf(heartRates)
        @JvmField val restingRates: List<DailyHeartRate> = java.util.List.copyOf(restingRates)
        @JvmField val walkingRates: List<DailyHeartRate> = java.util.List.copyOf(walkingRates)
        @JvmField val oxygens: List<DataPoint> = java.util.List.copyOf(oxygens)
        @JvmField val stresses: List<DataPoint> = java.util.List.copyOf(stresses)
        @JvmField val stepSlices: List<TodayStepSlice> = java.util.List.copyOf(stepSlices)
        @JvmField val calorieSlices: List<TodayCalorieSlice> = java.util.List.copyOf(calorieSlices)
        @JvmField val distanceSlices: List<TodayDistanceSlice> = java.util.List.copyOf(distanceSlices)
        @JvmField val standSlices: List<TodayStandSlice> = java.util.List.copyOf(standSlices)
        @JvmField val dailyTotals: List<DailyTotals> = java.util.List.copyOf(dailyTotals)
        @JvmField val sleepNights: List<SleepNight> = java.util.List.copyOf(sleepNights)
        @JvmField val sportSessions: List<SportSession> = java.util.List.copyOf(sportSessions)
        @JvmField val gpsPoints: List<GpsPoint> = java.util.List.copyOf(gpsPoints)
        @JvmField val handled: List<Receipt> = java.util.List.copyOf(handled)
        @JvmField val unsupported: List<Receipt> = java.util.List.copyOf(unsupported)
        private val receiptsByCategory: Map<Category, List<Receipt>>

        init {
            val copy = EnumMap<Category, List<Receipt>>(Category::class.java)
            for ((key, value) in receiptsByCategory) {
                copy[key] = java.util.List.copyOf(value)
            }
            this.receiptsByCategory = Collections.unmodifiableMap(copy)
        }

        fun receiptsFor(category: Category): List<Receipt> = receiptsByCategory[category] ?: emptyList()
    }

    @JvmStatic
    fun mapBatch(records: List<HealthRecord?>?): MappedBatch {
        val heartRates = ArrayList<DataPoint>()
        val restingRates = ArrayList<DailyHeartRate>()
        val walkingRates = ArrayList<DailyHeartRate>()
        val oxygens = ArrayList<DataPoint>()
        val stresses = ArrayList<DataPoint>()
        val stepSlices = ArrayList<TodayStepSlice>()
        val calorieSlices = ArrayList<TodayCalorieSlice>()
        val distanceSlices = ArrayList<TodayDistanceSlice>()
        val standSlices = ArrayList<TodayStandSlice>()
        val dailyTotals = ArrayList<DailyTotals>()
        val sleepIntervals = ArrayList<HealthRecord>()
        val sleepStages = ArrayList<HealthRecord>()
        val sportSessions = ArrayList<SportSession>()
        val trackRecords = ArrayList<HealthRecord>()
        val gpsPoints = ArrayList<GpsPoint>()
        val handled = ArrayList<Receipt>()
        val unsupported = ArrayList<Receipt>()
        val byCategory = EnumMap<Category, MutableList<Receipt>>(Category::class.java)

        if (records != null) {
            for (record in records) {
                if (record == null) continue
                val receipt = Receipt(record.recordId, record.revision)
                when (record.kind) {
                    "heart_rate" -> {
                        val value = record.value
                        if (!record.hostAccepts() || value == null) {
                            unsupported.add(receipt)
                            continue
                        }
                        val bpm = value.toInt()
                        when (record.measurementMode) {
                            "resting" -> {
                                restingRates.add(
                                    DailyHeartRate(dayStartMs(record.startMs, record.timezone), record.startMs, bpm)
                                )
                                addReceipt(byCategory, handled, Category.RESTING_HR, receipt)
                            }
                            "walking" -> {
                                walkingRates.add(
                                    DailyHeartRate(dayStartMs(record.startMs, record.timezone), record.startMs, bpm)
                                )
                                addReceipt(byCategory, handled, Category.WALKING_HR, receipt)
                            }
                            else -> {
                                heartRates.add(DataPoint(record.startMs / 1000L, record.startMs, bpm))
                                addReceipt(byCategory, handled, Category.HEART_RATE, receipt)
                            }
                        }
                    }
                    "spo2" -> {
                        val value = record.value
                        if (!record.hostAccepts() || value == null) {
                            unsupported.add(receipt)
                            continue
                        }
                        oxygens.add(DataPoint(record.startMs / 1000L, record.startMs, value.toInt()))
                        addReceipt(byCategory, handled, Category.SPO2, receipt)
                    }
                    "stress" -> {
                        val value = record.value
                        if (!record.hostAccepts() || value == null) {
                            unsupported.add(receipt)
                            continue
                        }
                        stresses.add(DataPoint(record.startMs / 1000L, record.startMs, value.toInt()))
                        addReceipt(byCategory, handled, Category.STRESS, receipt)
                    }
                    "steps_interval" -> {
                        val steps = record.value?.toLong()?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: 0
                        stepSlices.add(
                            TodayStepSlice(
                                "mi_step_" + sanitizeUuidPart(record.recordId),
                                record.startMs,
                                record.endMs,
                                steps,
                            )
                        )
                        val calories = record.calories
                        if (calories != null && calories > 0) {
                            calorieSlices.add(
                                TodayCalorieSlice(
                                    "mi_cal_" + sanitizeUuidPart(record.recordId),
                                    record.startMs,
                                    record.endMs,
                                    kcalToSmallCalories(calories),
                                )
                            )
                        }
                        val distance = record.distance
                        if (distance != null && distance > 0) {
                            distanceSlices.add(
                                TodayDistanceSlice(
                                    "mi_dist_" + sanitizeUuidPart(record.recordId),
                                    record.startMs,
                                    record.endMs,
                                    distance,
                                )
                            )
                        }
                        addReceipt(byCategory, handled, Category.STEPS_INTERVAL, receipt)
                    }
                    "steps_day" -> {
                        val dayStart = dayStartMs(record.startMs, record.timezone)
                        val steps = record.value?.toLong()?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: 0
                        val caloriesKcal = record.calories?.toFloat()
                        val distanceMeters = record.distance?.toFloat()
                        dailyTotals.add(DailyTotals(dayStart, steps, caloriesKcal, distanceMeters))
                        val moveAbout = record.moveAbout
                        if (moveAbout != null && moveAbout > 0) {
                            standSlices.addAll(standSlicesForDay(dayStart, record.endMs, moveAbout))
                        }
                        addReceipt(byCategory, handled, Category.STEPS_DAY, receipt)
                    }
                    "sleep_interval" -> {
                        sleepIntervals.add(record)
                        addReceipt(byCategory, handled, Category.SLEEP, receipt)
                    }
                    "sleep_stage" -> {
                        sleepStages.add(record)
                        addReceipt(byCategory, handled, Category.SLEEP, receipt)
                    }
                    "sport_session" -> {
                        sportSessions.add(mapSportSession(record))
                        addReceipt(byCategory, handled, Category.SPORT_SESSION, receipt)
                    }
                    "sport_track" -> {
                        trackRecords.add(record)
                        addReceipt(byCategory, handled, Category.SPORT_TRACK, receipt)
                    }
                    "sleep_breath" -> {
                        addReceipt(byCategory, handled, Category.SLEEP_BREATH, receipt)
                    }
                    else -> {
                        // Leave unknown record kinds untouched in the queue.
                    }
                }
            }
        }

        val sleepNights = buildSleepNights(sleepIntervals, sleepStages)
        for (trackRecord in trackRecords) {
            val tag = correlateTrackTag(trackRecord, sportSessions)
            gpsPoints.addAll(parseTrack(tag, trackRecord.track))
        }

        return MappedBatch(
            heartRates,
            restingRates,
            walkingRates,
            oxygens,
            stresses,
            stepSlices,
            calorieSlices,
            distanceSlices,
            standSlices,
            dailyTotals,
            sleepNights,
            sportSessions,
            gpsPoints,
            handled,
            unsupported,
            byCategory,
        )
    }

    /** Maps Xiaomi sport mode IDs into com.vivo.health.lib.router.syncdata.model.AppSportType. */
    @JvmStatic
    fun mapSportType(miMode: Int): Int = when (miMode) {
        2 -> 2     // AppSportType.RUNNING_OUTSIDE
        1 -> 12    // AppSportType.WALKING_OUTSIDE
        506 -> 16  // AppSportType.HIKE
        3 -> 4     // AppSportType.CYCLING_OUTSIDE
        10 -> 1    // AppSportType.RUNNING_INSIDE
        35 -> 10   // AppSportType.FREE_TRAINING
        908 -> 17  // AppSportType.SKIPPING_ROPE
        7 -> 5     // AppSportType.SWIMMING
        809 -> 15  // AppSportType.SWIMMING_OUTSIDE
        36 -> 6    // AppSportType.CLIMBING
        37 -> 7    // AppSportType.TRAIL_RUNNING
        19 -> 112  // AppSportType.Sport_Indoor_Walking
        else -> 10 // AppSportType.FREE_TRAINING
    }

    @JvmStatic
    fun sportTag(startMs: Long): Int {
        val tag = ((startMs / 1000L) and 0x7FFF_FFFFL).toInt()
        return if (tag == 0) 1 else tag
    }

    @JvmStatic
    fun kcalToSmallCalories(kcal: Int): Int {
        if (kcal <= 0) return 0
        return minOf(Int.MAX_VALUE.toLong(), kcal * 1000L).toInt()
    }

    @JvmStatic
    fun dayStartMs(timestampMs: Long, timezone: String?): Long {
        val zone = zoneOrDefault(timezone)
        return Instant.ofEpochMilli(timestampMs)
            .atZone(zone)
            .toLocalDate()
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
    }

    @JvmStatic
    fun zoneOffsetMinutes(timestampMs: Long, timezone: String?): Int {
        val zone = zoneOrDefault(timezone)
        return zone.rules.getOffset(Instant.ofEpochMilli(timestampMs)).totalSeconds / 60
    }

    /** Produces whole-hour stand slices for SportTodayActivity_STAND (% 3600000 == 0). */
    @JvmStatic
    fun standSlicesForDay(dayStartMs: Long, endMs: Long, moveAbout: Int): List<TodayStandSlice> {
        val count = moveAbout.coerceIn(0, 24)
        if (count == 0) return emptyList()
        val dayLastHour = dayStartMs + 23L * HOUR_MS
        val cappedEndHour = if (endMs >= dayStartMs) {
            minOf(dayLastHour, ((endMs - 1L) / HOUR_MS) * HOUR_MS)
        } else {
            dayLastHour
        }
        val availableHours = maxOf(0L, (cappedEndHour - dayStartMs) / HOUR_MS).toInt() + 1
        val actualCount = minOf(count, availableHours)
        if (actualCount <= 0) return emptyList()
        val preferredStartHour = dayStartMs + 8L * HOUR_MS
        val maxStartHour = cappedEndHour - (actualCount - 1).toLong() * HOUR_MS
        val firstHour = maxOf(dayStartMs, minOf(preferredStartHour, maxStartHour))
        val slices = ArrayList<TodayStandSlice>(actualCount)
        for (i in 0 until actualCount) {
            val hourStart = firstHour + i.toLong() * HOUR_MS
            val slot = ((hourStart - dayStartMs) / HOUR_MS).toInt()
            slices.add(
                TodayStandSlice(
                    "mi_stand_${dayStartMs}_$slot",
                    hourStart,
                    hourStart + HOUR_MS,
                    1,
                )
            )
        }
        return slices
    }

    /** Parses [com.originband.link.protocol.SportGpsParser] "5,0,0,0,0,0,t,lat,lon,0,spd,..." payloads. */
    @JvmStatic
    fun parseTrack(tag: Int, payload: String?): List<GpsPoint> {
        if (payload == null || !payload.startsWith("5,")) return emptyList()
        val parts = COMMA_SPLIT.split(payload)
        if (parts.size < 11) return emptyList()
        val points = ArrayList<GpsPoint>((parts.size - 6) / 5)
        for (i in 6 until parts.size - 4 step 5) {
            try {
                val tMs = parts[i].trim().toLong()
                val lat = parts[i + 1].trim().toDouble()
                val lon = parts[i + 2].trim().toDouble()
                var speed = parts[i + 4].trim().toFloat()
                if (!lat.isFinite() || !lon.isFinite()
                    || lat < -90.0 || lat > 90.0
                    || lon < -180.0 || lon > 180.0
                    || (lat == 0.0 && lon == 0.0)
                ) {
                    continue
                }
                if (!speed.isFinite() || speed < 0f) speed = 0f
                points.add(GpsPoint(tag, tMs, lat, lon, speed))
            } catch (_: NumberFormatException) {
                // Skip malformed point tuple.
            }
        }
        return points
    }

    private fun mapSportSession(record: HealthRecord): SportSession {
        val miMode = record.value?.toInt() ?: 35
        val vivoType = mapSportType(miMode)
        val rawDurationMs = record.duration?.let { it * 1000L } ?: (record.endMs - record.startMs)
        val durationMs = maxOf(1000L, rawDurationMs)
        val activeSec = (durationMs / 1000L).toInt()
        val kcal = record.calories?.coerceIn(0, 8000) ?: 0
        val distance = record.distance?.coerceIn(0, 1_000_000) ?: 0
        val steps = record.reps?.coerceAtLeast(0) ?: 0
        val avgHr = record.heart ?: 0
        val paceSecPerKm = if (distance > 0) {
            Math.round((activeSec * 1000.0) / distance).toInt()
        } else {
            record.bestPace ?: 0
        }
        val speedMps = if (distance > 0) (distance.toDouble() / activeSec).toFloat() else 0f
        return SportSession(
            record.recordId,
            sportTag(record.startMs),
            vivoType,
            record.startMs,
            record.endMs,
            durationMs,
            activeSec,
            kcal,
            distance,
            steps,
            avgHr,
            paceSecPerKm,
            speedMps,
        )
    }

    private fun correlateTrackTag(trackRecord: HealthRecord, sessions: List<SportSession>): Int {
        var closest: SportSession? = null
        var bestDelta = Long.MAX_VALUE
        for (session in sessions) {
            val delta = abs(session.rtcStartTimeMs - trackRecord.startMs)
            if (delta <= 60_000L && delta < bestDelta) {
                bestDelta = delta
                closest = session
            }
        }
        return closest?.tag ?: sportTag(trackRecord.startMs)
    }

    private fun buildSleepNights(
        intervals: List<HealthRecord>,
        stages: List<HealthRecord>,
    ): List<SleepNight> {
        if (intervals.isEmpty() && stages.isEmpty()) return emptyList()
        val groups = ArrayList<SleepGroup>()
        if (intervals.isNotEmpty()) {
            val sortedIntervals = intervals.sortedBy { it.startMs }
            for (interval in sortedIntervals) {
                if (groups.isEmpty()) {
                    groups.add(SleepGroup(interval.startMs, interval.endMs, interval.timezone))
                } else {
                    val last = groups[groups.size - 1]
                    if (interval.startMs <= last.endMs) {
                        last.startMs = minOf(last.startMs, interval.startMs)
                        last.endMs = maxOf(last.endMs, interval.endMs)
                    } else {
                        groups.add(SleepGroup(interval.startMs, interval.endMs, interval.timezone))
                    }
                }
            }
        }

        val sortedStages = stages.sortedBy { it.startMs }
        val unmatched = ArrayList<HealthRecord>()
        for (stage in sortedStages) {
            val owner = findGroupForStage(groups, stage)
            if (owner != null) {
                owner.stages.add(stage)
            } else {
                unmatched.add(stage)
            }
        }

        if (unmatched.isNotEmpty()) {
            var current: SleepGroup? = null
            for (stage in unmatched) {
                if (current == null || stage.startMs - current.endMs > SLEEP_CLUSTER_GAP_MS) {
                    current = SleepGroup(stage.startMs, stage.endMs, stage.timezone)
                    groups.add(current)
                } else {
                    current.endMs = maxOf(current.endMs, stage.endMs)
                }
                current.stages.add(stage)
            }
        }

        groups.sortBy { it.startMs }
        return groups.map(::toSleepNight)
    }

    private fun findGroupForStage(groups: List<SleepGroup>, stage: HealthRecord): SleepGroup? {
        var best: SleepGroup? = null
        var bestOverlap = 0L
        for (group in groups) {
            val overlap = minOf(group.endMs, stage.endMs) - maxOf(group.startMs, stage.startMs)
            if (overlap > bestOverlap) {
                bestOverlap = overlap
                best = group
            }
        }
        return best
    }

    private fun toSleepNight(group: SleepGroup): SleepNight {
        val deep = ArrayList<SleepRange>()
        val light = ArrayList<SleepRange>()
        val rem = ArrayList<SleepRange>()
        val awake = ArrayList<SleepRange>()
        var deepMs = 0L
        var lightMs = 0L
        var remMs = 0L
        var awakeMs = 0L

        for (stage in group.stages) {
            val stageCode = stage.stage ?: continue
            val dur = maxOf(0L, stage.endMs - stage.startMs)
            val range = SleepRange(stage.startMs, stage.endMs)
            when (stageCode) {
                SLEEP_STAGE_DEEP -> {
                    deep.add(range)
                    deepMs += dur
                }
                SLEEP_STAGE_REM -> {
                    rem.add(range)
                    remMs += dur
                }
                SLEEP_STAGE_LIGHT -> {
                    light.add(range)
                    lightMs += dur
                }
                SLEEP_STAGE_AWAKE -> {
                    awake.add(range)
                    awakeMs += dur
                }
                else -> {
                    // Stage 6 (UNSTAGED) has no WatchSleepBean list and is omitted.
                }
            }
        }

        if (deep.isEmpty() && light.isEmpty() && rem.isEmpty() && awake.isEmpty() && group.endMs > group.startMs) {
            light.add(SleepRange(group.startMs, group.endMs))
            lightMs = group.endMs - group.startMs
        }

        val enterMs = group.startMs
        val exitMs = maxOf(group.endMs, enterMs)
        val uuid = "mi_sleep_$enterMs"
        val zoneOffsetMin = zoneOffsetMinutes(exitMs, group.timezone)
        return SleepNight(
            uuid,
            enterMs,
            exitMs,
            deep,
            light,
            rem,
            awake,
            toMinutes(deepMs),
            toMinutes(lightMs),
            toMinutes(remMs),
            toMinutes(awakeMs),
            zoneOffsetMin,
        )
    }

    private fun toMinutes(durationMs: Long): Int {
        if (durationMs <= 0L) return 0
        return maxOf(1L, Math.round(durationMs / 60_000.0)).toInt()
    }

    private fun addReceipt(
        byCategory: MutableMap<Category, MutableList<Receipt>>,
        handled: MutableList<Receipt>,
        category: Category,
        receipt: Receipt,
    ) {
        handled.add(receipt)
        byCategory.getOrPut(category) { ArrayList() }.add(receipt)
    }

    private fun zoneOrDefault(timezone: String?): ZoneId {
        if (timezone == null || timezone.isBlank()) return ZoneId.systemDefault()
        return try {
            ZoneId.of(timezone.trim())
        } catch (_: Exception) {
            ZoneId.systemDefault()
        }
    }

    private fun sanitizeUuidPart(raw: String?): String {
        return raw?.replace(SANITIZE_UUID_REGEX, "_") ?: "0"
    }

    private class SleepGroup(
        var startMs: Long,
        var endMs: Long,
        val timezone: String?,
    ) {
        val stages = ArrayList<HealthRecord>()
    }
}
