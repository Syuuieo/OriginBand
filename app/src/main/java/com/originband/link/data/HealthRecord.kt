// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import org.json.JSONException
import org.json.JSONObject
import java.time.DateTimeException
import java.time.ZoneId

/** A normalized measurement. Diagnostic logs carry the kind and the time, and leave source ids and values in the record. */
class HealthRecord(
    recordId: String?,
    deviceId: String?,
    kind: String?,
    @JvmField val startMs: Long,
    @JvmField val endMs: Long,
    @JvmField val value: Number?,
    @JvmField val stage: Int?,
    @JvmField val revision: Int,
    @JvmField val timezone: String?,
    measurementMode: String?,
    @JvmField val complete: Boolean,
    /** Activity kilocalories. Null means this record has no calorie field. */
    @JvmField val calories: Int?,
    /** Meters. Null means this record has no distance field. */
    @JvmField val distance: Int?,
    /** Stand-hours in the daily report. Null except on a parsed steps_day. */
    @JvmField val moveAbout: Int?,
    /** Steps, or jumps for rope skipping. Null except on a sport summary. */
    @JvmField val reps: Int?,
    /** Average heart rate from a sport summary. */
    @JvmField val heart: Int?,
    /** Moving time in seconds. Null when the summary did not carry one. */
    @JvmField val duration: Int?,
    /** Fastest pace in seconds per kilometre. Null except on a sport summary. */
    @JvmField val bestPace: Int?,
    /** OPPO band track payload. Null except on a GPS file. */
    @JvmField val track: String?,
) {
    @JvmField val recordId: String
    @JvmField val deviceId: String
    @JvmField val kind: String
    @JvmField val measurementMode: String

    constructor(
        recordId: String?,
        deviceId: String?,
        kind: String?,
        startMs: Long,
        endMs: Long,
        value: Number?,
        stage: Int?,
        revision: Int,
        timezone: String?,
        measurementMode: String?,
        complete: Boolean,
    ) : this(
        recordId, deviceId, kind, startMs, endMs, value, stage, revision, timezone,
        measurementMode, complete, null, null, null, null, null, null, null, null,
    )

    constructor(
        recordId: String?,
        deviceId: String?,
        kind: String?,
        startMs: Long,
        endMs: Long,
        value: Number?,
        stage: Int?,
        revision: Int,
        timezone: String?,
        measurementMode: String?,
        complete: Boolean,
        calories: Int?,
        distance: Int?,
        moveAbout: Int?,
    ) : this(
        recordId, deviceId, kind, startMs, endMs, value, stage, revision, timezone,
        measurementMode, complete, calories, distance, moveAbout, null, null, null, null, null,
    )

    constructor(
        recordId: String?,
        deviceId: String?,
        kind: String?,
        startMs: Long,
        endMs: Long,
        value: Number?,
        stage: Int?,
        revision: Int,
        timezone: String?,
        measurementMode: String?,
        complete: Boolean,
        calories: Int?,
        distance: Int?,
        moveAbout: Int?,
        reps: Int?,
        heart: Int?,
    ) : this(
        recordId, deviceId, kind, startMs, endMs, value, stage, revision, timezone,
        measurementMode, complete, calories, distance, moveAbout, reps, heart, null, null, null,
    )

    init {
        if (recordId.isNullOrBlank() || deviceId.isNullOrBlank() ||
            startMs < 0 || endMs <= startMs || revision <= 0
        ) {
            throw IllegalArgumentException("INVALID_HEALTH_RECORD")
        }
        val sleepStage = "sleep_stage" == kind
        val sleepInterval = "sleep_interval" == kind
        val sleepBreath = "sleep_breath" == kind
        val steps = "steps_interval" == kind || "steps_day" == kind
        val heartRate = "heart_rate" == kind
        val oxygen = "spo2" == kind
        val stress = "stress" == kind
        val sport = "sport_session" == kind
        val trackFile = "sport_track" == kind
        if (kind == null || !(sleepStage || sleepInterval || sleepBreath || steps || heartRate || oxygen || stress || sport || trackFile)) {
            throw IllegalArgumentException("UNSUPPORTED_HEALTH_KIND")
        }
        // Resting and walking heart rate are one daily value each. Health only derives them from
        // points typed 1 and 4, so they stay their own modes instead of a continuous minute.
        val dailyHeart = heartRate && ("resting" == measurementMode || "walking" == measurementMode)
        // Database v2 migrates every old source, including sleep_stage, to continuous.
        if (measurementMode == null ||
            !("continuous" == measurementMode || "manual" == measurementMode ||
                "sleep" == measurementMode || dailyHeart) ||
            (steps && "continuous" != measurementMode) ||
            (sport && "continuous" != measurementMode) ||
            (trackFile && "continuous" != measurementMode) ||
            (sleepInterval && "sleep" != measurementMode) ||
            (sleepStage && "manual" == measurementMode) ||
            (sleepBreath && "sleep" != measurementMode) ||
            (complete && !sleepInterval) ||
            ((calories != null || distance != null || moveAbout != null) && !steps && !sport) ||
            ((reps != null || heart != null || duration != null || bestPace != null) && !sport) ||
            (duration != null && (duration < 1 || duration > 48 * 3600)) ||
            (bestPace != null && (bestPace < 30 || bestPace > 7200)) ||
            (track != null && !trackFile) ||
            (trackFile && (track == null || !track.startsWith("5,") || track.length > 200_000)) ||
            (calories != null && (calories < 0 || calories > 100_000)) ||
            (distance != null && (distance < 0 || distance > 1_000_000)) ||
            (reps != null && reps > 1_000_000) ||
            (heart != null && (heart < 1 || heart > 250)) ||
            (moveAbout != null && (moveAbout < 0 || moveAbout > 24 || !reportDay(kind)))
        ) {
            throw IllegalArgumentException("INVALID_MEASUREMENT_MODE")
        }
        if (sleepStage) {
            if (stage == null || value != null) throw IllegalArgumentException("INVALID_HEALTH_MEASUREMENT")
            // 6 is a sleep duration the band did not classify. It is not light sleep.
            if (stage < 2 || stage > 6) throw IllegalArgumentException("INVALID_SLEEP_STAGE")
        } else if (stage != null || (!sleepInterval && value == null)) {
            throw IllegalArgumentException("INVALID_HEALTH_MEASUREMENT")
        }
        if (value != null) {
            val measured = value.toDouble()
            if (!measured.isFinite() || measured < 0 || measured != Math.rint(measured) ||
                (heartRate && (measured < 1 || measured > 250)) ||
                (sport && (measured > Int.MAX_VALUE || !SPORT_MODES.contains(value.toInt()))) ||
                (trackFile && (measured < 2 || measured > 1500)) ||
                (oxygen && (measured < 1 || measured > 100)) ||
                (stress && measured > 100) ||
                (steps && measured > 0xffff_ffffL) ||
                (sleepInterval && measured > endMs - startMs) ||
                (sleepBreath && (measured < 60 || measured > 500))
            ) {
                throw IllegalArgumentException("INVALID_HEALTH_MEASUREMENT")
            }
        }
        if ((heartRate || oxygen || stress) &&
            endMs - startMs != (if ("continuous" == measurementMode) 60_000L else 1L)
        ) {
            throw IllegalArgumentException("INVALID_MEASUREMENT_INTERVAL")
        }
        if ((sport || trackFile) && endMs - startMs > 48L * 3_600_000L) {
            throw IllegalArgumentException("INVALID_MEASUREMENT_INTERVAL")
        }
        if (sleepBreath && endMs - startMs != 60_000L) {
            throw IllegalArgumentException("INVALID_MEASUREMENT_INTERVAL")
        }
        if (timezone != null) {
            try {
                ZoneId.of(timezone)
            } catch (e: DateTimeException) {
                throw IllegalArgumentException("INVALID_HEALTH_TIMEZONE", e)
            }
        }
        this.recordId = recordId
        this.deviceId = deviceId
        this.kind = kind
        this.measurementMode = measurementMode
    }

    /** OHealth 6.9.37 drops these values and still reports insert success. */
    fun hostAccepts(): Boolean {
        val current = value ?: return false
        val measured = current.toInt()
        return when (kind) {
            "heart_rate" -> measured in 40..220
            "spo2" -> measured in 60..100
            "stress" -> measured in 1..100
            else -> false
        }
    }

    fun toJson(): JSONObject {
        val json = JSONObject()
        try {
            json.put("recordId", recordId)
            json.put("deviceId", deviceId)
            json.put("kind", kind)
            json.put("startMs", startMs)
            json.put("endMs", endMs)
            json.put("value", value ?: JSONObject.NULL)
            json.put("stage", stage ?: JSONObject.NULL)
            json.put("revision", revision)
            json.put("measurementMode", measurementMode)
            json.put("complete", complete)
            if (timezone != null) json.put("timezone", timezone)
            if (calories != null) json.put("calories", calories)
            if (distance != null) json.put("distance", distance)
            if (moveAbout != null) json.put("moveAbout", moveAbout)
            if (reps != null) json.put("reps", reps)
            if (heart != null) json.put("heart", heart)
            if (duration != null) json.put("duration", duration)
            if (bestPace != null) json.put("bestPace", bestPace)
            if (track != null) json.put("track", track)
        } catch (e: JSONException) {
            throw IllegalStateException("HEALTH_RECORD_SERIALIZATION_FAILED", e)
        }
        return json
    }

    companion object {
        private val FIELDS = setOf(
            "recordId", "deviceId", "kind", "startMs",
            "endMs", "value", "stage", "revision", "timezone", "measurementMode", "complete",
            "calories", "distance", "moveAbout", "reps", "heart", "duration", "bestPace", "track",
        )
        private val SPORT_MODES = setOf(1, 2, 3, 7, 10, 19, 35, 36, 37, 506, 809, 908)

        @JvmStatic
        @Throws(JSONException::class)
        fun fromJson(json: JSONObject?): HealthRecord {
            if (json == null) throw IllegalArgumentException("INVALID_HEALTH_RECORD")
            val it = json.keys()
            while (it.hasNext()) {
                if (!FIELDS.contains(it.next())) throw IllegalArgumentException("UNKNOWN_HEALTH_FIELD")
            }
            val value = json.get("value")
            val stage = json.get("stage")
            if (json.get("measurementMode") !is String || json.get("complete") !is Boolean) {
                throw IllegalArgumentException("INVALID_HEALTH_MEASUREMENT")
            }
            if ((value !== JSONObject.NULL && value !is Number) ||
                (stage !== JSONObject.NULL && stage !is Int)
            ) {
                throw IllegalArgumentException("INVALID_HEALTH_MEASUREMENT")
            }
            return HealthRecord(
                json.getString("recordId"),
                json.getString("deviceId"),
                json.getString("kind"),
                json.getLong("startMs"),
                json.getLong("endMs"),
                if (value === JSONObject.NULL) null else value as Number,
                if (stage === JSONObject.NULL) null else stage as Int,
                json.getInt("revision"),
                if (json.has("timezone")) json.getString("timezone") else null,
                json.getString("measurementMode"),
                json.getBoolean("complete"),
                optional(json, "calories"),
                optional(json, "distance"),
                optional(json, "moveAbout"),
                optional(json, "reps"),
                optional(json, "heart"),
                optional(json, "duration"),
                optional(json, "bestPace"),
                if (json.has("track") && !json.isNull("track")) json.getString("track") else null,
            )
        }

        private fun reportDay(kind: String): Boolean = "steps_day" == kind

        @Throws(JSONException::class)
        private fun optional(json: JSONObject, name: String): Int? {
            if (!json.has(name) || json.isNull(name)) return null
            val value = json.get(name)
            if (value !is Number || value is Double || value is Float) {
                throw IllegalArgumentException("INVALID_HEALTH_MEASUREMENT")
            }
            val number = value.toLong()
            if (number < 0 || number > Int.MAX_VALUE) {
                throw IllegalArgumentException("INVALID_HEALTH_MEASUREMENT")
            }
            return number.toInt()
        }
    }
}
