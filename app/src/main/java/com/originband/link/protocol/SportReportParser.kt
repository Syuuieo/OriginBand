// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

/**
 * Decodes one Xiaomi sport summary. Detail and GPS files stay unsupported:
 * OPPO health only needs the summary that becomes a single workout row.
 */
object SportReportParser {
    @JvmStatic
    fun parse(bytes: ByteArray): Session {
        if (bytes.size < 12) throw unsupported("UNSUPPORTED_SPORT_FILE")
        val descriptor = bytes[6].toInt() and 0xff
        if ((descriptor and 0x80) == 0 || (descriptor and 3) != 1) throw unsupported("UNSUPPORTED_SPORT_FILE")
        val activity = (descriptor and 0x7f) ushr 2
        val version = bytes[5].toInt() and 0xff
        val schemaActivity = if (activity == 10) 9 else activity
        val layout = SportReportLayouts.layout(schemaActivity, version)
            ?: throw unsupported("UNSUPPORTED_SPORT_FILE")
        if (8 + layout.validityBytes > bytes.size - 4) throw unsupported("UNSUPPORTED_SPORT_FILE")
        val values = read(bytes, layout)
        return session(activity, bytes, values)
    }

    private fun read(bytes: ByteArray, layout: SportReportLayouts.Layout): Map<String, Long> {
        val present = BooleanArray(layout.flags.size) { i ->
            val index = 8 + i / 8
            (bytes[index].toInt() and (1 shl (7 - (i % 8)))) != 0
        }
        val flagAt = HashMap<String, Int>(layout.flags.size)
        for (i in layout.flags.indices) {
            flagAt[layout.flags[i]] = i
        }
        val values = HashMap<String, Long>()
        var cursor = 8 + layout.validityBytes
        val end = bytes.size - 4
        for (field in layout.fields) {
            if (!included(field, present, flagAt, values)) continue
            if (cursor > end - field.bytes) throw unsupported("UNSUPPORTED_SPORT_FILE")
            values[field.name] = if (field.floating) 0L else unsigned(bytes, cursor, field.bytes)
            cursor += field.bytes
        }
        return values
    }

    private fun included(
        field: SportReportLayouts.Field,
        present: BooleanArray,
        flagAt: Map<String, Int>,
        values: Map<String, Long>,
    ): Boolean {
        val gate = field.gate
        if (gate != null) {
            val at = flagAt[gate]
            if (at == null || !present[at]) return false
        }
        val whenField = field.`when` ?: return true
        val actual = values[whenField] ?: return false
        val allowed = field.values ?: return false
        return allowed.any { actual == it.toLong() }
    }

    private fun unsigned(bytes: ByteArray, offset: Int, width: Int): Long {
        var value = 0L
        for (i in 0 until width) {
            value = value or ((bytes[offset + i].toLong() and 0xffL) shl (8 * i))
        }
        return value
    }

    private fun session(activity: Int, bytes: ByteArray, values: Map<String, Long>): Session {
        val headerSeconds = unsigned(bytes, 0, 4)
        val startSeconds = values["startTime"]
        val start = (startSeconds ?: headerSeconds) * 1000L
        val endSeconds = values["endTime"]
        val duration = values["totalDuration"]
        val end = when {
            endSeconds != null -> endSeconds * 1000L
            duration == null -> start
            else -> start + duration * 1000L
        }
        if (start < 0L || end <= start || end - start > 48L * 3600_000L) {
            throw unsupported("INVALID_SPORT_TIMESTAMP")
        }
        val calories = first(values, "totalCalories", "activeCalories")
        val distance = narrow(values["totalDistance"])
        val steps = narrow(values["totalSteps"])
        val jumps = narrow(values["totalJumps"])
        var heart = narrow(values["avgHeartRate"])
        if (heart != null && (heart < 1 || heart > 250)) heart = null
        var moving = narrow(duration)
        if (moving != null && (moving < 1 || moving > 48 * 3600)) moving = null
        var bestPace = narrow(values["bestPace"])
        if (bestPace != null && (bestPace < 30 || bestPace > 7200)) bestPace = null
        val mapped = map(activity, narrow(values["subSportType"]))
        if (mapped.mode != 3) bestPace = null
        val reps = if (mapped.repsAreJumps) jumps else steps
        return Session(
            mapped.mode,
            mapped.name,
            start,
            end,
            calories,
            distance,
            reps,
            heart,
            moving,
            bestPace,
        )
    }

    private fun first(values: Map<String, Long>, primary: String, fallback: String): Int? =
        narrow(values[primary]) ?: narrow(values[fallback])

    private fun narrow(value: Long?): Int? {
        if (value == null || value < 0L || value > Int.MAX_VALUE.toLong()) return null
        return value.toInt()
    }

    private fun map(activity: Int, subSport: Int?): Mapped = when (activity) {
        22 -> foot(subSport)
        23 -> if (subSport == null || subSport == 6) {
            Mapped(3, "户外骑行", false)
        } else {
            unsupportedMapped()
        }
        3 -> indoor(subSport)
        8 -> Mapped(35, "自由活动", false)
        14 -> Mapped(908, "跳绳", true)
        9 -> Mapped(7, "泳池游泳", false)
        10 -> Mapped(809, "开放水域游泳", false)
        else -> unsupportedMapped()
    }

    private fun foot(subSport: Int?): Mapped = when (subSport) {
        1 -> Mapped(2, "户外跑步", false)
        2, 207 -> Mapped(1, "健走", false)
        4 -> Mapped(36, "登山", false)
        5 -> Mapped(37, "越野跑", false)
        15 -> Mapped(506, "徒步", false)
        else -> unsupportedMapped()
    }

    private fun indoor(subSport: Int?): Mapped = when (subSport) {
        3 -> Mapped(10, "室内跑步", false)
        333 -> Mapped(19, "室内健走", false)
        else -> unsupportedMapped()
    }

    private fun unsupportedMapped(): Mapped {
        throw unsupported("UNSUPPORTED_SPORT_TYPE")
    }

    private fun unsupported(status: String): IllegalArgumentException =
        IllegalArgumentException(status)

    @JvmStatic
    fun name(mode: Int): String = when (mode) {
        2 -> "户外跑步"
        1 -> "健走"
        506 -> "徒步"
        3 -> "户外骑行"
        10 -> "室内跑步"
        35 -> "自由活动"
        908 -> "跳绳"
        7 -> "泳池游泳"
        809 -> "开放水域游泳"
        36 -> "登山"
        37 -> "越野跑"
        19 -> "室内健走"
        else -> "运动"
    }

    class Session(
        @JvmField val mode: Int,
        @JvmField val name: String,
        @JvmField val startMs: Long,
        @JvmField val endMs: Long,
        @JvmField val calories: Int?,
        @JvmField val distance: Int?,
        @JvmField val reps: Int?,
        @JvmField val heart: Int?,
        /** Moving time in seconds. Null when the summary did not carry one. */
        @JvmField val duration: Int?,
        /** Fastest pace in seconds per kilometre. Null when it is missing or implausible. */
        @JvmField val bestPace: Int?,
    )

    private class Mapped(
        val mode: Int,
        val name: String,
        val repsAreJumps: Boolean,
    )
}
