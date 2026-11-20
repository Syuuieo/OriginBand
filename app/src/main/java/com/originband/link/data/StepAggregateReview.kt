// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Comparator
import java.util.HexFormat

/** Complete historical evidence for a user-confirmed aggregate review. Authorization belongs to the caller. */
data class StepAggregateReview(
    @JvmField val fingerprint: String,
    @JvmField val accountHash: String,
    @JvmField val queuedDevice: String,
    @JvmField val nativeDevice: String,
    @JvmField val date: Int,
    @JvmField val expectedSteps: Int,
    @JvmField val combinedSteps: Int,
    @JvmField val phoneSteps: Int,
) {
    /** Source calories are whole kilocalories; every minute, including a zero minute, is evidence. */
    data class SourceMinute(
        @JvmField val device: String?,
        @JvmField val startMs: Long,
        @JvmField val endMs: Long,
        @JvmField val steps: Long,
        @JvmField val calories: Long,
        @JvmField val distance: Long,
        @JvmField val timezone: String?,
    ) {
        fun device(): String? = device

        fun startMs(): Long = startMs

        fun endMs(): Long = endMs

        fun steps(): Long = steps

        fun calories(): Long = calories

        fun distance(): Long = distance

        fun timezone(): String? = timezone
    }

    /** Saved calories use the host's millicalorie scale. */
    data class SavedMinute(
        @JvmField val account: String?,
        @JvmField val device: String?,
        @JvmField val startMs: Long,
        @JvmField val endMs: Long,
        @JvmField val steps: Long,
        @JvmField val calories: Long,
        @JvmField val distance: Long,
        @JvmField val mode: Int,
        @JvmField val display: Int,
        @JvmField val timezone: String?,
    ) {
        fun account(): String? = account

        fun device(): String? = device

        fun startMs(): Long = startMs

        fun endMs(): Long = endMs

        fun steps(): Long = steps

        fun calories(): Long = calories

        fun distance(): Long = distance

        fun mode(): Int = mode

        fun display(): Int = display

        fun timezone(): String? = timezone
    }

    /** Daily calories use the host's millicalorie scale. */
    data class Daily(
        @JvmField val account: String?,
        @JvmField val device: String?,
        @JvmField val date: Int,
        @JvmField val mode: Int,
        @JvmField val steps: Long,
        @JvmField val calories: Long,
        @JvmField val distance: Long,
    ) {
        fun account(): String? = account

        fun device(): String? = device

        fun date(): Int = date

        fun mode(): Int = mode

        fun steps(): Long = steps

        fun calories(): Long = calories

        fun distance(): Long = distance
    }

    init {
        if (!hash(fingerprint) || !hash(accountHash) || !identity(queuedDevice) || !identity(nativeDevice) ||
            expectedSteps <= 0 || combinedSteps < 0 || phoneSteps < 0 ||
            (combinedSteps <= expectedSteps && phoneSteps <= expectedSteps)
        ) {
            throw invalid()
        }
        localDate(date)
    }

    fun fingerprint(): String = fingerprint

    fun accountHash(): String = accountHash

    fun queuedDevice(): String = queuedDevice

    fun nativeDevice(): String = nativeDevice

    fun date(): Int = date

    fun expectedSteps(): Int = expectedSteps

    fun combinedSteps(): Int = combinedSteps

    fun phoneSteps(): Int = phoneSteps

    fun toJson(): JSONObject {
        return try {
            JSONObject().put("fingerprint", fingerprint).put("accountHash", accountHash)
                .put("queuedDevice", queuedDevice).put("nativeDevice", nativeDevice).put("date", date)
                .put("expectedSteps", expectedSteps).put("combinedSteps", combinedSteps).put("phoneSteps", phoneSteps)
        } catch (failure: JSONException) {
            throw IllegalStateException("STEP_REVIEW_SERIALIZATION_FAILED", failure)
        }
    }

    companion object {
        private const val MINUTE_MS = 60_000L
        private const val DAY_MS = 86_400_000L
        private const val MINUTES = 1_440
        private val HEX64_REGEX = Regex("[0-9a-f]{64}")
        private val FIELDS = setOf(
            "fingerprint", "accountHash", "queuedDevice",
            "nativeDevice", "date", "expectedSteps", "combinedSteps", "phoneSteps",
        )

        /** Returns a report only after complete source, saved-minute and device-day agreement. */
        @JvmStatic
        fun assess(
            account: String?,
            queuedDevice: String?,
            nativeDevice: String?,
            date: Int,
            dayStart: Long,
            timezone: String?,
            daySteps: Long,
            minuteMode: Int,
            nowMs: Long,
            sources: List<SourceMinute?>?,
            saved: List<SavedMinute?>?,
            days: List<Daily?>?,
        ): StepAggregateReview? {
            try {
                if (!identity(account) || !identity(queuedDevice) || !identity(nativeDevice) ||
                    timezone == null || !positiveInt(daySteps) || (minuteMode != 1 && minuteMode != -1) || dayStart < 0 ||
                    sources == null || sources.size != MINUTES ||
                    saved == null || saved.size > MINUTES || days == null ||
                    days.size < 3 || days.size > 4
                ) {
                    return null
                }
                val nonNullAccount = checkNotNull(account)
                val nonNullQueued = checkNotNull(queuedDevice)
                val nonNullNative = checkNotNull(nativeDevice)
                val day = localDate(date)
                val zone = ZoneId.of(timezone)
                val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                if (day.atStartOfDay(zone).toLocalTime() != LocalTime.MIDNIGHT ||
                    day.atStartOfDay(zone).toInstant().toEpochMilli() != dayStart ||
                    Math.subtractExact(end, dayStart) != DAY_MS || nowMs < end
                ) {
                    return null
                }
                val sourceCopy = sources.map { it ?: return null }
                val savedCopy = saved.map { it ?: return null }
                val dayCopy = days.map { it ?: return null }
                val sourceByMinute = arrayOfNulls<SourceMinute>(MINUTES)
                var steps = 0L
                var calories = 0L
                var distance = 0L
                for (source in sourceCopy) {
                    val index = minuteIndex(source.startMs, source.endMs, dayStart, end)
                    if (index < 0 || sourceByMinute[index] != null || nonNullQueued != source.device ||
                        !sameOffset(source.timezone, zone, source.startMs) ||
                        source.steps < 0 || source.steps > 350 ||
                        source.calories < 0 || source.calories > 65 ||
                        source.distance < 0 || source.distance > 1_050
                    ) {
                        return null
                    }
                    sourceByMinute[index] = source
                    steps = Math.addExact(steps, source.steps)
                    calories = Math.addExact(calories, Math.multiplyExact(source.calories, 1_000L))
                    distance = Math.addExact(distance, source.distance)
                }
                if (steps != daySteps || !positiveInt(steps) || !nonnegativeInt(calories) ||
                    !nonnegativeInt(distance)
                ) {
                    return null
                }
                val savedByMinute = arrayOfNulls<SavedMinute>(MINUTES)
                for (row in savedCopy) {
                    val index = minuteIndex(row.startMs, row.endMs, dayStart, end)
                    if (index < 0 || savedByMinute[index] != null || nonNullAccount != row.account ||
                        nonNullNative != row.device || row.mode != minuteMode || row.display != 1 ||
                        !sameOffset(row.timezone, zone, row.startMs)
                    ) {
                        return null
                    }
                    val source = sourceByMinute[index] ?: return null
                    if (row.steps != source.steps || row.calories != source.calories * 1_000L ||
                        row.distance != source.distance
                    ) {
                        return null
                    }
                    savedByMinute[index] = row
                }
                val verifiedSources = Array(MINUTES) { i ->
                    val source = sourceByMinute[i] ?: return null
                    if (savedByMinute[i] == null &&
                        (source.steps != 0L || source.calories != 0L || source.distance != 0L)
                    ) {
                        return null
                    }
                    source
                }
                var deviceDay: Daily? = null
                var deviceMinutes: Daily? = null
                var combined: Daily? = null
                var phone: Daily? = null
                for (row in dayCopy) {
                    if (nonNullAccount != row.account || row.date != date || !nonnegativeInt(row.steps) ||
                        !nonnegativeInt(row.calories) || !nonnegativeInt(row.distance)
                    ) {
                        return null
                    }
                    if (row.device.isNullOrEmpty()) {
                        if (row.mode == -2 && combined == null) combined = row
                        else if (row.mode == -4 && phone == null) phone = row
                        else return null
                    } else {
                        if (nonNullNative != row.device || row.steps != steps || row.calories != calories ||
                            row.distance != distance
                        ) {
                            return null
                        }
                        if (row.mode == -3 && deviceDay == null) deviceDay = row
                        else if (row.mode == minuteMode && deviceMinutes == null) deviceMinutes = row
                        else return null
                    }
                }
                if (deviceDay == null || combined == null || phone == null ||
                    (combined.steps <= steps && phone.steps <= steps)
                ) {
                    return null
                }
                val accountHash = digest(nonNullAccount.toByteArray(StandardCharsets.UTF_8))
                val fingerprint = fingerprint(
                    accountHash, nonNullQueued, nonNullNative, date, dayStart,
                    timezone, minuteMode, verifiedSources, savedByMinute, dayCopy,
                )
                return StepAggregateReview(
                    fingerprint, accountHash, nonNullQueued, nonNullNative, date,
                    steps.toInt(), combined.steps.toInt(), phone.steps.toInt(),
                )
            } catch (_: RuntimeException) {
                return null
            }
        }

        @JvmStatic
        @Throws(JSONException::class)
        fun fromJson(json: JSONObject?): StepAggregateReview {
            if (json == null || json.length() != FIELDS.size) throw invalid()
            val keys = json.keys()
            while (keys.hasNext()) {
                if (!FIELDS.contains(keys.next())) throw invalid()
            }
            return StepAggregateReview(
                string(json, "fingerprint"), string(json, "accountHash"),
                string(json, "queuedDevice"), string(json, "nativeDevice"), integer(json, "date"),
                integer(json, "expectedSteps"), integer(json, "combinedSteps"), integer(json, "phoneSteps"),
            )
        }

        private fun minuteIndex(start: Long, end: Long, dayStart: Long, dayEnd: Long): Int {
            if (start < dayStart || start >= dayEnd || end != Math.addExact(start, MINUTE_MS) ||
                end > dayEnd || (start - dayStart) % MINUTE_MS != 0L
            ) {
                return -1
            }
            return ((start - dayStart) / MINUTE_MS).toInt()
        }

        private fun sameOffset(timezone: String?, zone: ZoneId, at: Long): Boolean {
            if (timezone == null) return false
            val instant = Instant.ofEpochMilli(at)
            return ZoneId.of(timezone).rules.getOffset(instant) == zone.rules.getOffset(instant)
        }

        private fun localDate(date: Int): LocalDate {
            if (date < 19700101 || date > 99991231) throw invalid()
            return try {
                LocalDate.of(date / 10_000, date / 100 % 100, date % 100)
            } catch (_: DateTimeException) {
                throw invalid()
            }
        }

        private fun identity(value: String?): Boolean = !value.isNullOrBlank() && value.length <= 512

        private fun hash(value: String?): Boolean = value != null && HEX64_REGEX.matches(value)

        private fun nonnegativeInt(value: Long): Boolean = value in 0..Int.MAX_VALUE

        private fun positiveInt(value: Long): Boolean = value in 1..Int.MAX_VALUE

        private fun invalid(): IllegalArgumentException = IllegalArgumentException("STEP_REVIEW_INVALID")

        @Throws(JSONException::class)
        private fun string(json: JSONObject, name: String): String {
            val value = json.get(name)
            if (value !is String) throw invalid()
            return value
        }

        @Throws(JSONException::class)
        private fun integer(json: JSONObject, name: String): Int {
            val value = json.get(name)
            if ((value !is Int && value !is Long) || !nonnegativeInt((value as Number).toLong())) {
                throw invalid()
            }
            return value.toInt()
        }

        private fun digest(value: ByteArray): String {
            return try {
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value))
            } catch (unavailable: NoSuchAlgorithmException) {
                throw IllegalStateException(unavailable)
            }
        }

        @Throws(IOException::class)
        private fun text(output: DataOutputStream, value: String?) {
            if (value == null) {
                output.writeInt(-1)
                return
            }
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            output.writeInt(bytes.size)
            output.write(bytes)
        }

        private fun fingerprint(
            accountHash: String,
            queued: String,
            nativeDevice: String,
            date: Int,
            dayStart: Long,
            timezone: String,
            minuteMode: Int,
            sources: Array<SourceMinute>,
            saved: Array<SavedMinute?>,
            days: List<Daily>,
        ): String {
            return try {
                val bytes = ByteArrayOutputStream()
                val out = DataOutputStream(bytes)
                text(out, "step-aggregate-review-v1")
                text(out, accountHash)
                text(out, queued)
                text(out, nativeDevice)
                out.writeInt(date)
                out.writeLong(dayStart)
                text(out, timezone)
                out.writeInt(minuteMode)
                for (row in sources) {
                    text(out, row.device)
                    out.writeLong(row.startMs)
                    out.writeLong(row.endMs)
                    out.writeLong(row.steps)
                    out.writeLong(row.calories)
                    out.writeLong(row.distance)
                    text(out, row.timezone)
                }
                for (row in saved) {
                    out.writeBoolean(row != null)
                    if (row == null) continue
                    text(out, accountHash)
                    text(out, row.device)
                    out.writeLong(row.startMs)
                    out.writeLong(row.endMs)
                    out.writeLong(row.steps)
                    out.writeLong(row.calories)
                    out.writeLong(row.distance)
                    out.writeInt(row.mode)
                    out.writeInt(row.display)
                    text(out, row.timezone)
                }
                val ordered = ArrayList(days)
                ordered.sortWith(Comparator.comparingInt { it.mode })
                out.writeInt(ordered.size)
                for (row in ordered) {
                    text(out, accountHash)
                    text(out, row.device)
                    out.writeInt(row.date)
                    out.writeInt(row.mode)
                    out.writeLong(row.steps)
                    out.writeLong(row.calories)
                    out.writeLong(row.distance)
                }
                out.flush()
                digest(bytes.toByteArray())
            } catch (impossible: IOException) {
                throw IllegalStateException("STEP_REVIEW_FINGERPRINT_FAILED", impossible)
            }
        }
    }
}
