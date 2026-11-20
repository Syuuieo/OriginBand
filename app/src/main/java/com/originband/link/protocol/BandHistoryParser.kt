// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import com.originband.link.data.HealthRecord
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.util.HexFormat
import java.util.Locale
import java.util.TreeMap
import java.util.zip.CRC32
import kotlin.math.abs
import org.json.JSONException
import org.json.JSONObject

/** Decoder for complete Xiaomi fitness files on the SPP V2 activity channel. */
class BandHistoryParser(
    firmware: String?,
    deviceId: String?,
    identityMaterial: String?,
) {
    private val deviceId: String
    private val identityBytes: ByteArray
    private val digest: MessageDigest
    private val fragments = ArrayList<ByteArray>()
    private var totalFragments = 0
    private var receivedBytes = 0

    /** Identity material is the confirmed did or the same normalized MAC used for deviceId. */
    init {
        if (firmware.isNullOrBlank() || identityMaterial.isNullOrBlank()) {
            throw IllegalArgumentException("UNSUPPORTED_HISTORY_SOURCE")
        }
        digest = try {
            MessageDigest.getInstance("SHA-256")
        } catch (e: NoSuchAlgorithmException) {
            throw IllegalStateException("SHA256_UNAVAILABLE", e)
        }
        val expected = "miband11_" + HexFormat.of().formatHex(
            digest.digest(identityMaterial.toByteArray(StandardCharsets.UTF_8)),
        )
        if (deviceId == null || expected != deviceId) {
            throw IllegalArgumentException("HISTORY_IDENTITY_MISMATCH")
        }
        this.deviceId = deviceId
        this.identityBytes = deviceId.toByteArray(StandardCharsets.UTF_8)
    }

    /** Reset at every session boundary; no fragment from an earlier session is reusable. */
    @Synchronized
    fun reset() {
        fragments.clear()
        totalFragments = 0
        receivedBytes = 0
    }

    /**
     * Accepts the payload of Xiaomi raw channel 0x66, not a type-8 protobuf envelope.
     * Returns null until every fragment is present. A completed result retains the full
     * CRC-checked source file; its owner must durably save that file before any band confirm.
     */
    @Synchronized
    fun acceptFragment(fragment: ByteArray?): FileResult? {
        if (fragment == null || fragment.size < 5 || fragment.size > MAX_FILE_BYTES) {
            reset()
            throw IllegalArgumentException("INVALID_HISTORY_FRAGMENT")
        }
        val total = u16(fragment, 0)
        val sequence = u16(fragment, 2)
        if (total < 1 || total > MAX_FRAGMENTS || sequence < 1 || sequence > total ||
            (totalFragments != 0 && totalFragments != total) ||
            sequence != fragments.size + 1 ||
            receivedBytes > MAX_FILE_BYTES - (fragment.size - 4)
        ) {
            reset()
            throw IllegalArgumentException("INVALID_HISTORY_SEQUENCE")
        }
        totalFragments = total
        val part = fragment.copyOfRange(4, fragment.size)
        fragments.add(part)
        receivedBytes += part.size
        if (sequence != total) return null

        val fullFile = ByteArray(receivedBytes)
        var offset = 0
        for (next in fragments) {
            System.arraycopy(next, 0, fullFile, offset, next.size)
            offset += next.size
        }
        reset()
        return decodeFile(fullFile)
    }

    /** Archive replay uses exactly the same envelope and semantic validation as live receipt. */
    @Synchronized
    fun parseFile(raw: ByteArray?): FileResult {
        if (raw == null || raw.size < 12 || raw.size > MAX_FILE_BYTES) {
            throw IllegalArgumentException("INVALID_HISTORY_FILE_LENGTH")
        }
        return decodeFile(raw.clone())
    }

    private fun decodeFile(bytes: ByteArray): FileResult {
        if (bytes.size < 12 || bytes.size > MAX_FILE_BYTES) {
            throw IllegalArgumentException("INVALID_HISTORY_FILE_LENGTH")
        }
        val crc = CRC32()
        crc.update(bytes, 0, bytes.size - 4)
        if (crc.value != u32(bytes, bytes.size - 4)) {
            throw IllegalArgumentException("HISTORY_CRC_MISMATCH")
        }
        val descriptor = bytes[6].toInt() and 0xff
        val fileType = descriptor and 3
        val dailyType = (descriptor and 0x7f) ushr 2
        val version = bytes[5].toInt() and 0xff
        val quarterHours = bytes[4].toInt()
        val offsetSeconds = quarterHours * 15 * 60
        return try {
            if (offsetSeconds < -12 * 3600 || offsetSeconds > 14 * 3600) {
                throw SemanticException("INVALID_HISTORY_TIMEZONE")
            }
            if (bytes[7].toInt() != 0) throw SemanticException("UNSUPPORTED_HISTORY_ENCODING")
            val absMinutes = abs(quarterHours) * 15
            val timezone = String.format(
                Locale.ROOT,
                "%c%02d:%02d",
                if (quarterHours < 0) '-' else '+',
                absMinutes / 60,
                absMinutes % 60,
            )
            val measurements: List<Measurement> = when {
                (descriptor and 0x80) != 0 -> listOf(sportMeasurement(bytes, timezone))
                dailyType == 0 && fileType == 0 && version in 1..4 ->
                    decodeRecords(bytes, u32(bytes, 0), timezone, version)
                dailyType == 0 && fileType == 1 && version == 5 ->
                    decodeReport(bytes, u32(bytes, 0), offsetSeconds, timezone)
                dailyType == 6 && fileType == 0 && version == 2 ->
                    decodeManual(bytes, timezone)
                dailyType == 8 && fileType == 1 && version == 4 ->
                    decodeSleepV4(bytes, timezone)
                dailyType == 8 && fileType == 1 && (version == 5 || version == 6) ->
                    decodeSleep(bytes, version, timezone)
                dailyType == 9 && fileType == 0 && version == 2 ->
                    decodePulse(bytes, timezone)
                else -> throw SemanticException("UNSUPPORTED_HISTORY_DT${dailyType}_FT${fileType}_V$version")
            }
            FileResult(bytes, measurements, "PARSED")
        } catch (unsupported: SemanticException) {
            FileResult(bytes, emptyList(), unsupported.status)
        }
    }

    private fun decodeRecords(
        bytes: ByteArray,
        seconds: Long,
        timezone: String,
        version: Int,
    ): List<Measurement> {
        val headerBytes = if (version < 3) 4 else if (version == 3) 5 else 6
        val cursor = Cursor(bytes, 8)
        val validity = cursor.flags(headerBytes)
        val base = headerBytes * 8 - 1
        val records = ArrayList<Measurement>()
        val firstMinute = seconds / 60 * MINUTE_MS
        var tooManyRecords = false
        var index = 0
        while (cursor.remaining() > 0) {
            val recordIndex = index++
            val minute = firstMinute + recordIndex * MINUTE_MS
            val steps = if (flag(validity, base)) cursor.uint(2) else 0
            val minuteCalories = if (flag(validity, base - 4)) cursor.uint(1) else null
            if (flag(validity, base - 8)) cursor.skip(1)
            val minuteDistance = if (flag(validity, base - 12)) cursor.uint(2) else null
            val heart = if (flag(validity, base - 16)) cursor.uint(1) else -1
            if (flag(validity, base - 20)) cursor.skip(1)
            if (flag(validity, base - 24)) cursor.skip(2)
            var oxygen = -1
            var stress = -1
            if (version >= 3) {
                oxygen = if (flag(validity, base - 28)) cursor.uint(1) else -1
                stress = if (flag(validity, base - 32)) cursor.uint(1) else -1
            }
            if ((steps and 0x4000) != 0) cursor.skip(1)
            if (version >= 4) {
                if (flag(validity, base - 36)) cursor.skip(2)
                if (flag(validity, base - 40)) cursor.skip(2)
            }
            if (recordIndex >= 1440) {
                tooManyRecords = true
                continue
            }
            if (flag(validity, base) && flag(validity, base - 3)) {
                records.add(
                    measurement(
                        "steps_interval",
                        minute,
                        minute + MINUTE_MS,
                        steps and 0x3fff,
                        timezone,
                        "continuous",
                        false,
                        minuteCalories,
                        minuteDistance,
                        null,
                    ),
                )
            }
            if (flag(validity, base - 17)) addMetric(records, "heart_rate", minute, heart, timezone, "continuous")
            if (version >= 3 && flag(validity, base - 29)) {
                addMetric(records, "spo2", minute, oxygen, timezone, "continuous")
            }
            if (version >= 3 && flag(validity, base - 33)) {
                addMetric(records, "stress", minute, stress, timezone, "continuous")
            }
        }
        if (tooManyRecords) throw SemanticException("INVALID_ACTIVITY_RECORD_COUNT")
        return records
    }

    private fun decodeReport(
        bytes: ByteArray,
        seconds: Long,
        offsetSeconds: Int,
        timezone: String,
    ): List<Measurement> {
        val cursor = Cursor(bytes, 8)
        val validity = cursor.flags(4)
        cursor.require(REPORT_BYTES)
        if (cursor.remaining() != REPORT_BYTES) throw SemanticException("UNSUPPORTED_ACTIVITY_REPORT_LAYOUT")
        if (!bit(validity, 31)) return emptyList()
        val day = Math.floorDiv(seconds + offsetSeconds, 86_400L) * DAY_MS - offsetSeconds * 1000L
        val steps = cursor.unsigned32()
        val calories = cursor.uint(2)
        cursor.skip(1) // Reserved slot before the resting rate.
        // Validity bit 28 is slot 3: the day's resting heart rate. Max, min and average heart
        // rate, stress and their timestamps follow it and are not needed here.
        val resting = cursor.uint(1)
        cursor.skip(14)
        val moveAbout = Integer.bitCount(cursor.uint(3))
        val records = ArrayList<Measurement>(2)
        records.add(
            measurement(
                "steps_day",
                day,
                day + DAY_MS,
                steps,
                timezone,
                "continuous",
                false,
                calories,
                null,
                moveAbout,
            ),
        )
        // Noon keeps the value inside its own day and out of the night heart-rate window.
        val at = day + DAY_MS / 2
        if (bit(validity, 28) && resting in 40..220) {
            records.add(measurement("heart_rate", at, at + 1, resting, timezone, "resting", false))
        }
        return records
    }

    private fun decodeManual(bytes: ByteArray, timezone: String): List<Measurement> {
        val cursor = Cursor(bytes, 8)
        val records = ArrayList<Measurement>()
        var malformed = false
        while (cursor.remaining() > 0) {
            val time = cursor.unsigned32() * 1000L
            val descriptor = cursor.uint(1)
            val length = descriptor ushr 4
            val kind = descriptor and 15
            cursor.require(length)
            if (kind in 1..3) {
                if (length != 1) {
                    malformed = true
                } else {
                    addMetric(
                        records,
                        when (kind) {
                            1 -> "heart_rate"
                            2 -> "spo2"
                            else -> "stress"
                        },
                        time,
                        bytes[cursor.position].toInt() and 0xff,
                        timezone,
                        "manual",
                    )
                }
            }
            cursor.skip(length)
        }
        if (malformed) throw SemanticException("UNSUPPORTED_MANUAL_PAYLOAD_LENGTH")
        return records
    }

    private fun decodeSleep(bytes: ByteArray, version: Int, timezone: String): List<Measurement> {
        val cursor = Cursor(bytes, 8)
        val validity = cursor.flags(if (version == 5) 2 else 3)
        val full = cursor.uint(1)
        val start = cursor.unsigned32() * 1000L
        val end = cursor.unsigned32() * 1000L
        // Quality, efficiency, latency, bed duration and bed times are not total sleep.
        cursor.skip(18)
        if (version == 6) cursor.skip(24) // Ten uint16 HRV scalars and one uint32 timestamp.
        val records = ArrayList<Measurement>()
        var validSamples = true
        if (bit(validity, if (version == 5) 7 else 4)) {
            if (!decodeSleepSeries(cursor, records, "heart_rate", 1, timezone)) validSamples = false
        }
        if (bit(validity, if (version == 5) 6 else 3)) {
            if (!decodeSleepSeries(cursor, records, "spo2", 1, timezone)) validSamples = false
        }
        if (version == 6 && bit(validity, 2)) {
            if (!decodeSleepSeries(cursor, records, null, 2, timezone)) validSamples = false
        }
        if (bit(validity, if (version == 5) 5 else 1)) {
            if (!decodeSleepSeries(cursor, records, null, 4, timezone)) validSamples = false
        }
        decodeSleepStages(bytes, cursor, records, timezone, start, end)
        // Remaining unknown feature packets stay in the archived raw file.
        if (!validSamples) throw SemanticException("INVALID_SLEEP_SAMPLE_INTERVAL")
        if (full > 1) throw SemanticException("INVALID_SLEEP_COMPLETE_FLAG")
        if (bit(validity, if (version == 5) 15 else 23) && bit(validity, if (version == 5) 14 else 22)) {
            if (end <= start) throw SemanticException("INVALID_SLEEP_INTERVAL")
            records.add(0, measurement("sleep_interval", start, end, null, timezone, "sleep", full == 1))
        }
        return records
    }

    /**
     * Summary version 4. One validity byte, then the open flag, bed time and wake time.
     * Gadgetbridge index 3 (bit 4) is quality; 4/5/6 (bits 3/2/1) are heart, oxygen and snore.
     * Open flag 0 means the night is finished. 1 means it is still open.
     */
    private fun decodeSleepV4(bytes: ByteArray, timezone: String): List<Measurement> {
        val cursor = Cursor(bytes, 8)
        val validity = cursor.flags(1)
        val open = cursor.uint(1)
        val start = cursor.unsigned32() * 1000L
        val end = cursor.unsigned32() * 1000L
        if (bit(validity, 4)) cursor.skip(1)
        val records = ArrayList<Measurement>()
        var validSamples = true
        if (bit(validity, 3)) {
            if (!decodeSleepSeries(cursor, records, "heart_rate", 1, timezone)) validSamples = false
        }
        if (bit(validity, 2)) {
            if (!decodeSleepSeries(cursor, records, "spo2", 1, timezone)) validSamples = false
        }
        if (bit(validity, 1)) {
            if (!decodeSleepSeries(cursor, records, null, 4, timezone)) validSamples = false
        }
        decodeSleepStages(bytes, cursor, records, timezone, start, end)
        if (!validSamples) throw SemanticException("INVALID_SLEEP_SAMPLE_INTERVAL")
        if (open > 1) throw SemanticException("INVALID_SLEEP_COMPLETE_FLAG")
        if (end <= start) throw SemanticException("INVALID_SLEEP_INTERVAL")
        records.add(0, measurement("sleep_interval", start, end, null, timezone, "sleep", open == 0))
        return records
    }

    private fun decodeSleepSeries(
        cursor: Cursor,
        records: MutableList<Measurement>,
        kind: String?,
        width: Int,
        timezone: String,
    ): Boolean {
        val interval = cursor.uint(2)
        val count = cursor.uint(2)
        if (count == 0) return true
        // Current APK SleepAssistItemInfo computes firstRecordTime + sampleUnit * index
        // directly against Unix-second sleep bounds; both wire fields therefore use seconds.
        val first = cursor.unsigned32() * 1000L
        cursor.require(count * width)
        if (count > 1 && interval == 0) {
            cursor.skip(count * width)
            return false
        }
        for (i in 0 until count) {
            if (kind == null) {
                cursor.skip(width)
            } else {
                addMetric(records, kind, first + i * interval.toLong() * 1000L, cursor.uint(1), timezone, "sleep")
            }
        }
        return true
    }

    private fun decodeSleepStages(
        bytes: ByteArray,
        cursor: Cursor,
        records: MutableList<Measurement>,
        timezone: String,
        sleepStart: Long,
        sleepEnd: Long,
    ) {
        var pos = cursor.position
        val limit = cursor.end
        // Each sync appends another type-17 summary. The last one that still
        // covers this interval is the band's current analysis; earlier copies are history.
        var fullMatch: List<IntArray>? = null
        var bestPartial: List<IntArray>? = null
        var bestPartialMinutes = 0
        val t16Segments = ArrayList<LongArray>()
        val syncMarks = ArrayList<Long>()
        val spanMinutes = (sleepEnd - sleepStart) / 60_000L
        val breathSamples = TreeMap<Long, MutableList<Double>>()
        while (pos + 17 <= limit) {
            if (u32(bytes, pos) != 0xfffcfafbL) {
                pos++
                continue
            }
            if ((bytes[pos + 4].toInt() and 0xff) != 17) {
                pos++
                continue
            }
            val type = bytes[pos + 14].toInt() and 0xff
            val dataLen = ((bytes[pos + 15].toInt() and 0xff) shl 8) or (bytes[pos + 16].toInt() and 0xff)
            val noData = type == 2 || type == 3 || type == 9 || type == 0x0c ||
                type == 0x0d || type == 0x0e || type == 0x0f
            val payload = if (noData) 0 else dataLen
            if (payload < 0 || pos + 17 + payload > limit) break
            if (type == 17) {
                val runs = ArrayList<IntArray>()
                var totalMinutes = 0
                val off = pos + 17
                var i = 0
                while (i + 2 <= payload) {
                    val value = ((bytes[off + i].toInt() and 0xff) shl 8) or (bytes[off + i + 1].toInt() and 0xff)
                    val stage = xiaomiStage(value ushr 12)
                    val minutes = value and 0xfff
                    if (minutes > 0) {
                        totalMinutes += minutes
                        runs.add(intArrayOf(if (stage in 2..5) stage else 0, minutes))
                    }
                    i += 2
                }
                if (runs.isNotEmpty() && spanMinutes > 0L) {
                    if (abs(totalMinutes - spanMinutes) <= 2L) {
                        fullMatch = runs
                    } else if (totalMinutes <= spanMinutes + 2L && totalMinutes > bestPartialMinutes) {
                        bestPartial = runs
                        bestPartialMinutes = totalMinutes
                    }
                }
            } else if (type == 16) {
                decodeSleepSummaryPacket(bytes, pos, payload, t16Segments, syncMarks)
            } else if (type == 10) {
                decodeSleepBreathPacket(bytes, pos, payload, breathSamples)
            }
            pos += 17 + payload
        }
        cursor.position = limit
        var chosen = fullMatch ?: bestPartial
        var chosenEnd = sleepStart
        if (chosen != null) {
            chosen = absorbSyncAwake(chosen, sleepStart, syncMarks)
            var current = sleepStart
            for (run in chosen) {
                val next = current + run[1] * 60_000L
                if (run[0] in 2..5 && next > current) {
                    val a = maxOf(current, sleepStart)
                    val b = minOf(next, sleepEnd)
                    if (b > a) records.add(sleepStage(a, b, run[0], timezone))
                }
                current = next
            }
            chosenEnd = current
        }
        for (seg in t16Segments) {
            val a = seg[1]
            val b = seg[2]
            if (a >= chosenEnd && b <= sleepEnd + 60_000L && b > a) {
                val clampedA = maxOf(a, sleepStart)
                val clampedB = minOf(b, sleepEnd)
                if (clampedB > clampedA) {
                    records.add(sleepStage(clampedA, clampedB, seg[0].toInt(), timezone))
                }
            }
        }
        emitSleepBreathRecords(records, breathSamples, timezone)
    }

    private fun decodeSleepSummaryPacket(
        bytes: ByteArray,
        pos: Int,
        payload: Int,
        t16Segments: MutableList<LongArray>,
        syncMarks: MutableList<Long>,
    ) {
        val ts = u32(bytes, pos + 5) or (u32(bytes, pos + 9) shl 32)
        if (ts <= 0L) return
        val recTsSec = if (ts > 3_000_000_000L) ts / 1_000_000_000L else ts
        val t16StartMs = recTsSec * 1000L
        val off = pos + 17
        if (payload >= 13 && (bytes[off].toInt() and 0xff) == 0x10) {
            var unstaged = true
            for (i in 4..10) {
                if (bytes[off + i].toInt() != 0) {
                    unstaged = false
                    break
                }
            }
            if (unstaged) {
                val seg0Mins = (bytes[off + 2].toInt() and 0xff) or ((bytes[off + 3].toInt() and 0xff) shl 8)
                if (seg0Mins > 0) {
                    syncMarks.add(t16StartMs + seg0Mins * 60_000L)
                    // Stage 6 is a duration with no hypnogram. It is not light sleep.
                    t16Segments.clear()
                    t16Segments.add(longArrayOf(6, t16StartMs, t16StartMs + seg0Mins * 60_000L))
                    if (payload >= 26 && (bytes[off + 13].toInt() and 0xff) == 0x20) {
                        val seg1Mins = (bytes[off + 15].toInt() and 0xff) or
                            ((bytes[off + 16].toInt() and 0xff) shl 8)
                        val gapMins = bytes[off + 25].toInt() and 0xff
                        val gapStart = t16StartMs + seg0Mins * 60_000L
                        if (gapMins > 0) {
                            t16Segments.add(longArrayOf(5, gapStart, gapStart + gapMins * 60_000L))
                        }
                        if (seg1Mins > 0) {
                            val seg1Start = gapStart + gapMins * 60_000L
                            syncMarks.add(seg1Start + seg1Mins * 60_000L)
                            t16Segments.add(longArrayOf(6, seg1Start, seg1Start + seg1Mins * 60_000L))
                        }
                    }
                }
            }
        }
    }

    /** Type 9 version 2 is one heart-rate sample every second or two. Stored as minute averages. */
    private fun decodePulse(bytes: ByteArray, timezone: String): List<Measurement> {
        val end = bytes.size - 4
        var origin = -1
        var pos = 8
        while (pos + 10 <= end && pos < 8 + 32) {
            val first = u32(bytes, pos)
            val second = u32(bytes, pos + 5)
            val heart = bytes[pos + 4].toInt() and 0xff
            val nextHeart = bytes[pos + 9].toInt() and 0xff
            val gap = second - first
            if (first > 1_500_000_000L && gap in 1L..5L &&
                heart in 30..220 && nextHeart in 30..220
            ) {
                origin = pos
                break
            }
            pos++
        }
        if (origin < 0) return emptyList()
        val minutes = TreeMap<Long, LongArray>()
        pos = origin
        while (pos + 5 <= end) {
            val ts = u32(bytes, pos)
            val heart = bytes[pos + 4].toInt() and 0xff
            if (ts < 1_500_000_000L || ts > 2_100_000_000L) break
            if (heart in 30..220) {
                val minute = ts / 60 * 60_000L
                val bucket = minutes.getOrPut(minute) { LongArray(2) }
                bucket[0] += heart.toLong()
                bucket[1]++
            }
            pos += 5
        }
        val records = ArrayList<Measurement>()
        for ((minute, bucket) in minutes) {
            val average = Math.round(bucket[0] / bucket[1].toDouble()).toInt()
            addMetric(records, "heart_rate", minute, average, timezone, "continuous")
        }
        return records
    }

    private fun decodeSleepBreathPacket(
        bytes: ByteArray,
        pos: Int,
        payload: Int,
        breathSamples: MutableMap<Long, MutableList<Double>>,
    ) {
        val ts = u32(bytes, pos + 5) or (u32(bytes, pos + 9) shl 32)
        if (ts <= 0L) return
        val recTsSec = if (ts > 3_000_000_000L) ts / 1_000_000_000L else ts
        val windowStartSec = recTsSec - 600L
        if (windowStartSec < 0L) return
        val off = pos + 17
        val phases = Array(3) { ArrayList<Double>() }
        var i = 0
        while (i + 4 <= payload) {
            val w0 = (bytes[off + i].toInt() and 0xff) or ((bytes[off + i + 1].toInt() and 0xff) shl 8)
            val w1 = (bytes[off + i + 2].toInt() and 0xff) or ((bytes[off + i + 3].toInt() and 0xff) shl 8)
            val phase = w1 ushr 12
            val breath = w0 / 256.0
            if (phase in 0..2 && breath in 6.0..50.0) {
                phases[phase].add(breath)
            }
            i += 4
        }
        for (p in 0..2) {
            val pList = phases[p]
            val count = pList.size
            if (count == 0) continue
            val phaseStartSec = windowStartSec + p * 200L
            for (idx in 0 until count) {
                val sampleSec = phaseStartSec + idx.toLong() * 200L / count
                val minuteSec = (sampleSec / 60L) * 60L
                breathSamples.getOrPut(minuteSec) { ArrayList() }.add(pList[idx])
            }
        }
    }

    private fun emitSleepBreathRecords(
        records: MutableList<Measurement>,
        breathSamples: Map<Long, List<Double>>,
        timezone: String,
    ) {
        for ((minuteSec, samples) in breathSamples) {
            if (samples.isEmpty()) continue
            var sum = 0.0
            for (s in samples) sum += s
            val avg = Math.round((sum / samples.size) * 10.0).toInt()
            if (avg in 60..500) {
                val startMs = minuteSec * 1000L
                records.add(measurement("sleep_breath", startMs, startMs + MINUTE_MS, avg, timezone, "sleep", false))
            }
        }
    }

    private fun sleepStage(start: Long, end: Long, stage: Int, timezone: String): Measurement {
        if (start < 0L || end <= start) throw SemanticException("INVALID_HISTORY_TIMESTAMP")
        return Measurement(
            recordId("sleep_stage", start) + ":sleep",
            deviceId,
            "sleep_stage",
            start,
            end,
            null,
            stage,
            timezone,
            "sleep",
            false,
            sourceFingerprint("sleep_stage", start, end, null, timezone, "sleep", false, stage),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
        )
    }

    private fun addMetric(
        records: MutableList<Measurement>,
        kind: String,
        start: Long,
        value: Int,
        timezone: String,
        mode: String,
    ) {
        val min = if (kind == "stress" && mode == "manual") 0 else 1
        val max = if (kind == "heart_rate") 250 else 100
        if (value in min..max) {
            records.add(
                measurement(
                    kind,
                    start,
                    start + (if (mode == "continuous") MINUTE_MS else 1L),
                    value,
                    timezone,
                    mode,
                    false,
                ),
            )
        }
    }

    private fun measurement(
        kind: String,
        start: Long,
        end: Long,
        value: Number?,
        timezone: String,
        mode: String,
        complete: Boolean,
    ): Measurement = measurement(kind, start, end, value, timezone, mode, complete, null, null, null)

    private fun measurement(
        kind: String,
        start: Long,
        end: Long,
        value: Number?,
        timezone: String,
        mode: String,
        complete: Boolean,
        calories: Int?,
        distance: Int?,
        moveAbout: Int?,
    ): Measurement {
        if (start < 0L || end <= start) throw SemanticException("INVALID_HISTORY_TIMESTAMP")
        return Measurement(
            recordId(kind, start) + ":" + mode,
            deviceId,
            kind,
            start,
            end,
            value,
            null,
            timezone,
            mode,
            complete,
            sourceFingerprint(
                kind, start, end, value, timezone, mode, complete, null,
                calories, distance, moveAbout, null, null, null, null, null,
            ),
            calories,
            distance,
            moveAbout,
            null,
            null,
            null,
            null,
            null,
        )
    }

    /**
     * One daily heart value that is not a minute sample. Resting comes from the band's daily
     * report; walking is derived from that day's step minutes and is stored under the same
     * identity, so a recomputed average only replaces its own row.
     */
    fun dailyHeart(mode: String?, atMs: Long, value: Int, timezone: String): Measurement {
        if (!(mode == "resting" || mode == "walking")) {
            throw IllegalArgumentException("UNSUPPORTED_DAILY_HEART_MODE")
        }
        if (value < 40 || value > 220) throw IllegalArgumentException("UNSUPPORTED_DAILY_HEART_VALUE")
        return measurement("heart_rate", atMs, atMs + 1, value, timezone, mode, false)
    }

    private fun sportMeasurement(bytes: ByteArray, timezone: String): Measurement {
        try {
            if ((bytes[6].toInt() and 3) == 2) {
                val track = SportGpsParser.parse(bytes)
                val kind = "sport_track"
                return Measurement(
                    recordId(kind, track.startMs) + ":continuous",
                    deviceId,
                    kind,
                    track.startMs,
                    track.endMs,
                    track.points,
                    null,
                    timezone,
                    "continuous",
                    false,
                    sourceFingerprint(
                        kind, track.startMs, track.endMs, 1, timezone, "continuous",
                        false, null, null, null, null, null, null, null, null, track.payload,
                    ),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    track.payload,
                )
            }
            val session = SportReportParser.parse(bytes)
            val kind = "sport_session"
            return Measurement(
                recordId(kind, session.startMs) + ":continuous",
                deviceId,
                kind,
                session.startMs,
                session.endMs,
                session.mode,
                null,
                timezone,
                "continuous",
                false,
                sourceFingerprint(
                    kind, session.startMs, session.endMs, session.mode, timezone,
                    "continuous", false, null, session.calories, session.distance, null,
                    session.reps, session.heart, session.duration, session.bestPace, null,
                ),
                session.calories,
                session.distance,
                null,
                session.reps,
                session.heart,
                session.duration,
                session.bestPace,
                null,
            )
        } catch (failure: IllegalArgumentException) {
            val status = failure.message
            if (status != null && SPORT_STATUS_REGEX.matches(status)) throw SemanticException(status)
            throw failure
        }
    }

    private class SemanticException(val status: String) : IllegalArgumentException(status)

    private class Cursor(val bytes: ByteArray, var position: Int) {
        val end: Int = bytes.size - 4

        fun remaining(): Int = end - position

        fun require(length: Int) {
            if (length < 0 || length > remaining()) throw IllegalArgumentException("TRUNCATED_HISTORY_BODY")
        }

        fun skip(length: Int) {
            require(length)
            position += length
        }

        fun uint(length: Int): Int {
            require(length)
            var value = 0
            for (i in 0 until length) {
                value = value or ((bytes[position++].toInt() and 0xff) shl (8 * i))
            }
            return value
        }

        fun unsigned32(): Long {
            require(4)
            val value = u32(bytes, position)
            position += 4
            return value
        }

        fun flags(length: Int): Long {
            require(length)
            var value = 0L
            for (i in 0 until length) {
                value = (value shl 8) or (bytes[position++].toLong() and 0xffL)
            }
            return value
        }
    }

    private fun recordId(kind: String, start: Long): String {
        digest.reset()
        digest.update(identityBytes)
        digest.update(':'.code.toByte())
        digest.update(kindBytes(kind))
        for (shift in 56 downTo 0 step 8) {
            digest.update((start ushr shift).toByte())
        }
        return "xiaomi_" + HexFormat.of().formatHex(digest.digest())
    }

    private fun sourceFingerprint(
        kind: String,
        start: Long,
        end: Long,
        value: Number?,
        timezone: String,
        mode: String,
        complete: Boolean,
        stage: Int?,
    ): String = sourceFingerprint(
        kind, start, end, value, timezone, mode, complete, stage,
        null, null, null, null, null, null, null, null,
    )

    private fun sourceFingerprint(
        kind: String,
        start: Long,
        end: Long,
        value: Number?,
        timezone: String,
        mode: String,
        complete: Boolean,
        stage: Int?,
        calories: Int?,
        distance: Int?,
        moveAbout: Int?,
        reps: Int?,
        heart: Int?,
        duration: Int?,
        bestPace: Int?,
        track: String?,
    ): String {
        digest.reset()
        digest.update(identityBytes)
        digest.update(0.toByte())
        digest.update(kindBytes(kind))
        digest.update(0.toByte())
        updateLong(start)
        updateLong(end)
        digest.update((if (value == null) 0 else 1).toByte())
        if (value != null) updateLong(value.toLong())
        updateAscii(timezone)
        digest.update(0.toByte())
        updateAscii(mode)
        digest.update((if (complete) 1 else 0).toByte())
        if (stage != null) {
            digest.update(1.toByte())
            digest.update(stage.toByte())
        }
        if (calories != null || distance != null || moveAbout != null) {
            updateLong(calories?.toLong() ?: -1L)
            updateLong(distance?.toLong() ?: -1L)
            updateLong(moveAbout?.toLong() ?: -1L)
        }
        if (reps != null || heart != null || duration != null || bestPace != null) {
            updateLong(reps?.toLong() ?: -1L)
            updateLong(heart?.toLong() ?: -1L)
            updateLong(duration?.toLong() ?: -1L)
            updateLong(bestPace?.toLong() ?: -1L)
        }
        if (track != null) updateAscii(track)
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun updateAscii(text: String) {
        for (i in 0 until text.length) {
            digest.update(text[i].code.toByte())
        }
    }

    private fun updateLong(number: Long) {
        for (shift in 56 downTo 0 step 8) {
            digest.update((number ushr shift).toByte())
        }
    }

    class FileResult(
        private val rawFile: ByteArray,
        measurements: List<Measurement>,
        @JvmField val parseStatus: String,
    ) {
        @JvmField
        val measurements: List<Measurement> = java.util.List.copyOf(measurements)

        /** Persist this entire CRC-checked file before acknowledging it to the band. */
        fun copyRawFile(): ByteArray = rawFile.clone()

        /** Seven-byte Xiaomi file identifier for a later, separately gated confirm. */
        fun copyFileId(): ByteArray = rawFile.copyOf(FILE_ID_BYTES)
    }

    class DailySteps(
        @JvmField val steps: Long,
        @JvmField val measuredAtMs: Long,
        @JvmField val authoritative: Boolean,
    )

    class StepDay(
        @JvmField val date: Int,
        @JvmField val steps: Long,
        @JvmField val startMs: Long,
        @JvmField val timezone: String,
        /** -1 when the daily report has not been parsed. */
        @JvmField val calories: Long,
        @JvmField val distance: Long,
        @JvmField val moveAbout: Long,
    )

    /** A decoded fact without a revision; the durable queue assigns that monotonically. */
    class Measurement(
        @JvmField val recordId: String,
        @JvmField val deviceId: String,
        @JvmField val kind: String,
        @JvmField val startMs: Long,
        @JvmField val endMs: Long,
        @JvmField val value: Number?,
        @JvmField val stage: Int?,
        @JvmField val timezone: String?,
        @JvmField val measurementMode: String,
        @JvmField val complete: Boolean,
        @JvmField val sourceFingerprint: String,
        @JvmField val calories: Int?,
        @JvmField val distance: Int?,
        @JvmField val moveAbout: Int?,
        /** Steps, or jump count for rope skipping. Null when the summary did not carry one. */
        @JvmField val reps: Int?,
        /** Average heart rate. Null when the summary did not carry one. */
        @JvmField val heart: Int?,
        /** Moving time in seconds. Null when the summary did not carry one. */
        @JvmField val duration: Int?,
        /** Fastest pace in seconds per kilometre. Null except on a ride summary. */
        @JvmField val bestPace: Int?,
        /** OPPO band track payload. Null except on a GPS file. */
        @JvmField val track: String?,
    ) {
        init {
            if (sourceFingerprint.length != 64) {
                throw IllegalArgumentException("INVALID_SOURCE_FINGERPRINT")
            }
            for (i in 0 until sourceFingerprint.length) {
                val digit = sourceFingerprint[i]
                if (!(digit in '0'..'9' || digit in 'a'..'f')) {
                    throw IllegalArgumentException("INVALID_SOURCE_FINGERPRINT")
                }
            }
        }

        fun toRecord(revision: Int): HealthRecord = HealthRecord(
            recordId, deviceId, kind, startMs, endMs, value, stage,
            revision, timezone, measurementMode, complete, calories, distance, moveAbout,
            reps, heart, duration, bestPace, track,
        )

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
                if (timezone != null) json.put("timezone", timezone)
                json.put("sourceFingerprint", sourceFingerprint)
                json.put("measurementMode", measurementMode)
                json.put("complete", complete)
                if (calories != null) json.put("calories", calories)
                if (distance != null) json.put("distance", distance)
                if (moveAbout != null) json.put("moveAbout", moveAbout)
                if (reps != null) json.put("reps", reps)
                if (heart != null) json.put("heart", heart)
                if (duration != null) json.put("duration", duration)
                if (bestPace != null) json.put("bestPace", bestPace)
                if (track != null) json.put("track", track)
            } catch (e: JSONException) {
                throw IllegalStateException("MEASUREMENT_SERIALIZATION_FAILED", e)
            }
            return json
        }

        companion object {
            private val FIELDS = setOf(
                "recordId", "deviceId", "kind", "startMs",
                "endMs", "value", "stage", "timezone", "measurementMode", "complete", "sourceFingerprint",
                "calories", "distance", "moveAbout", "reps", "heart", "duration", "bestPace", "track",
            )

            @JvmStatic
            @Throws(JSONException::class)
            fun fromJson(json: JSONObject?): Measurement {
                if (json == null) throw IllegalArgumentException("INVALID_MEASUREMENT")
                val it = json.keys()
                while (it.hasNext()) {
                    if (!FIELDS.contains(it.next())) throw IllegalArgumentException("UNKNOWN_MEASUREMENT_FIELD")
                }
                val value = json.get("value")
                val stage = json.get("stage")
                if (json.get("measurementMode") !is String || json.get("complete") !is Boolean) {
                    throw IllegalArgumentException("INVALID_MEASUREMENT_VALUE")
                }
                if ((value !== JSONObject.NULL && value !is Number) ||
                    (stage !== JSONObject.NULL && stage !is Int)
                ) {
                    throw IllegalArgumentException("INVALID_MEASUREMENT_VALUE")
                }
                val result = Measurement(
                    json.getString("recordId"),
                    json.getString("deviceId"),
                    json.getString("kind"),
                    json.getLong("startMs"),
                    json.getLong("endMs"),
                    if (value === JSONObject.NULL) null else value as Number,
                    if (stage === JSONObject.NULL) null else stage as Int,
                    if (json.has("timezone")) json.getString("timezone") else null,
                    json.getString("measurementMode"),
                    json.getBoolean("complete"),
                    json.getString("sourceFingerprint"),
                    optional(json, "calories"),
                    optional(json, "distance"),
                    optional(json, "moveAbout"),
                    optional(json, "reps"),
                    optional(json, "heart"),
                    optional(json, "duration"),
                    optional(json, "bestPace"),
                    if (json.has("track") && !json.isNull("track")) json.getString("track") else null,
                )
                result.toRecord(1) // Apply the same measurement validity rules as persisted records.
                return result
            }

            @Throws(JSONException::class)
            private fun optional(json: JSONObject, name: String): Int? {
                if (!json.has(name) || json.isNull(name)) return null
                val value = json.get(name)
                if (value !is Number || value is Double || value is Float) {
                    throw IllegalArgumentException("INVALID_MEASUREMENT_VALUE")
                }
                val number = value.toLong()
                if (number < 0 || number > Int.MAX_VALUE.toLong()) {
                    throw IllegalArgumentException("INVALID_MEASUREMENT_VALUE")
                }
                return number.toInt()
            }
        }
    }

    companion object {
        private const val MAX_FRAGMENTS = 256
        private const val MAX_FILE_BYTES = 1024 * 1024
        private const val FILE_ID_BYTES = 7
        private const val REPORT_BYTES = 53
        private const val MINUTE_MS = 60_000L
        private const val DAY_MS = 86_400_000L

        private val STEPS_INTERVAL = "steps_interval".toByteArray(StandardCharsets.US_ASCII)
        private val STEPS_DAY = "steps_day".toByteArray(StandardCharsets.US_ASCII)
        private val HEART_RATE = "heart_rate".toByteArray(StandardCharsets.US_ASCII)
        private val SPO2 = "spo2".toByteArray(StandardCharsets.US_ASCII)
        private val STRESS = "stress".toByteArray(StandardCharsets.US_ASCII)
        private val SLEEP_INTERVAL = "sleep_interval".toByteArray(StandardCharsets.US_ASCII)
        private val SLEEP_STAGE = "sleep_stage".toByteArray(StandardCharsets.US_ASCII)
        private val SLEEP_BREATH = "sleep_breath".toByteArray(StandardCharsets.US_ASCII)
        private val SPORT_SESSION = "sport_session".toByteArray(StandardCharsets.US_ASCII)
        private val SPORT_TRACK = "sport_track".toByteArray(StandardCharsets.US_ASCII)

        @Suppress("unused")
        private val PRESENT_FIELDS = intArrayOf(47, 43, 39, 35, 31, 27, 23, 19, 15, 11, 7)

        @Suppress("unused")
        private val FIELD_WIDTHS = intArrayOf(2, 1, 1, 2, 1, 1, 2, 1, 1, 2, 2)

        private val SPORT_STATUS_REGEX = Regex("[A-Z][A-Z0-9_]{0,80}")

        /**
         * Each sync closes the open sleep file, and the later hypnogram keeps a short awake run
         * at that minute. Five minutes is the longest such run in captured nights. A longer awake
         * that happens to start on a sync, and a short awake that does not, stay awake.
         */
        private fun absorbSyncAwake(
            runs: List<IntArray>,
            sleepStart: Long,
            syncMarks: List<Long>,
        ): List<IntArray> {
            if (syncMarks.isEmpty()) return runs
            val folded = ArrayList<IntArray>()
            var cursor = sleepStart
            var prepend = 0
            for (run in runs) {
                val stage = run[0]
                val minutes = run[1]
                val syncAwake = stage == 5 && minutes in 1..5 && nearSync(cursor, syncMarks)
                if (syncAwake) {
                    if (folded.isNotEmpty()) {
                        folded[folded.size - 1][1] += minutes
                    } else {
                        prepend += minutes
                    }
                } else {
                    folded.add(intArrayOf(stage, minutes + prepend))
                    prepend = 0
                }
                cursor += minutes * 60_000L
            }
            return folded
        }

        private fun nearSync(startMs: Long, syncMarks: List<Long>): Boolean {
            for (mark in syncMarks) {
                if (abs(startMs - mark) <= 60_000L) return true
            }
            return false
        }

        private fun xiaomiStage(raw: Int): Int = when (raw) {
            0 -> 5
            1 -> 3
            2 -> 2
            3 -> 4
            else -> 0
        }

        /** True when every measurement from `index` on is a daily heart value, never a sample. */
        @JvmStatic
        fun onlyDailyHeartFrom(measurements: List<Measurement?>?, index: Int): Boolean {
            if (measurements == null) return false
            for (at in maxOf(0, index) until measurements.size) {
                val measurement = measurements[at] ?: return false
                if (measurement.kind != "heart_rate") return false
                if (measurement.measurementMode != "resting" && measurement.measurementMode != "walking") {
                    return false
                }
            }
            return true
        }

        private fun bit(flags: Long, bit: Int): Boolean = (flags and (1L shl bit)) != 0L

        private fun flag(flags: Long, bit: Int): Boolean = bit in 0..63 && bit(flags, bit)

        private fun kindBytes(kind: String): ByteArray = when (kind) {
            "steps_interval" -> STEPS_INTERVAL
            "steps_day" -> STEPS_DAY
            "heart_rate" -> HEART_RATE
            "spo2" -> SPO2
            "stress" -> STRESS
            "sleep_interval" -> SLEEP_INTERVAL
            "sleep_stage" -> SLEEP_STAGE
            "sleep_breath" -> SLEEP_BREATH
            "sport_session" -> SPORT_SESSION
            "sport_track" -> SPORT_TRACK
            else -> throw IllegalArgumentException("UNSUPPORTED_HEALTH_KIND")
        }

        private fun u16(bytes: ByteArray, index: Int): Int =
            (bytes[index].toInt() and 0xff) or ((bytes[index + 1].toInt() and 0xff) shl 8)

        private fun u32(bytes: ByteArray, index: Int): Long =
            (bytes[index].toLong() and 0xffL) or
                ((bytes[index + 1].toLong() and 0xffL) shl 8) or
                ((bytes[index + 2].toLong() and 0xffL) shl 16) or
                ((bytes[index + 3].toLong() and 0xffL) shl 24)

        /** Daily report wins. Otherwise today's minute deltas are the total; other days are ignored. */
        @JvmStatic
        fun todaySteps(measurements: List<Measurement?>?, dayStart: Long, dayEnd: Long): DailySteps? {
            if (measurements == null || dayStart < 0L || dayEnd <= dayStart) {
                throw IllegalArgumentException("INVALID_STEP_WINDOW")
            }
            var daily = -1L
            var dailyAt = -1L
            var interval = 0L
            var intervalAt = -1L
            var intervals = false
            for (measurement in measurements) {
                val measuredValue = measurement?.value ?: continue
                val value = measuredValue.toLong()
                if (value < 0L) continue
                if (measurement.kind == "steps_day" &&
                    measurement.startMs < dayEnd &&
                    measurement.endMs > dayStart
                ) {
                    daily = value
                    dailyAt = measurement.startMs
                } else if (measurement.kind == "steps_interval" &&
                    measurement.startMs >= dayStart &&
                    measurement.startMs < dayEnd
                ) {
                    if (value > 200_000L || interval > 200_000L - value) return null
                    intervals = true
                    interval += value
                    if (measurement.startMs > intervalAt) intervalAt = measurement.startMs
                }
            }
            if (dailyAt >= 0L) {
                if (daily > 200_000L) return null
                // A report is a snapshot. Minutes recorded after it are absent from that total,
                // and a later partial report must not replace a larger minute sum.
                if (intervals && interval > daily) return DailySteps(interval, intervalAt, true)
                return DailySteps(daily, dailyAt, true)
            }
            return if (intervals) DailySteps(interval, intervalAt, false) else null
        }

        /** One native day row. A daily report replaces minute sums for that timezone and date. */
        @JvmStatic
        fun stepDays(records: List<HealthRecord?>?): List<StepDay> {
            if (records == null) throw IllegalArgumentException("INVALID_STEP_WINDOW")
            val days = LinkedHashMap<String, LongArray>()
            for (record in records) {
                val measuredValue = record?.value ?: continue
                val timezone = record.timezone ?: continue
                val value = measuredValue.toLong()
                if (value < 0L || value > 200_000L) continue
                val report = record.kind == "steps_day"
                val interval = record.kind == "steps_interval"
                if (!report && !interval) continue
                val zone = try {
                    ZoneId.of(timezone)
                } catch (_: DateTimeException) {
                    continue
                }
                val date = Instant.ofEpochMilli(record.startMs).atZone(zone).toLocalDate()
                val numeric = date.year * 10_000 + date.monthValue * 100 + date.dayOfMonth
                val key = "$timezone|$numeric"
                val acc = days.getOrPut(key) {
                    longArrayOf(
                        numeric.toLong(),
                        -1L,
                        0L,
                        0L,
                        date.atStartOfDay(zone).toInstant().toEpochMilli(),
                        -1L,
                        -1L,
                        -1L,
                    )
                }
                if (report) {
                    if (acc[1] < value) {
                        acc[1] = value
                        acc[5] = record.calories?.toLong() ?: -1L
                        acc[6] = record.moveAbout?.toLong() ?: -1L
                    } else if (acc[1] == value) {
                        if (record.calories != null) acc[5] = maxOf(acc[5], record.calories.toLong())
                        if (record.moveAbout != null) acc[6] = maxOf(acc[6], record.moveAbout.toLong())
                    }
                } else if (acc[2] <= 200_000L - value) {
                    acc[2] += value
                    acc[3] = 1L
                    val dist = record.distance
                    if (dist != null && (acc[7] < 0L || acc[7] <= 1_000_000L - dist)) {
                        acc[7] = (if (acc[7] < 0L) 0L else acc[7]) + dist
                    }
                } else {
                    acc[3] = -1L
                }
            }
            val result = ArrayList<StepDay>()
            for ((key, acc) in days) {
                val steps = when {
                    acc[1] >= 0L -> acc[1]
                    acc[3] == 1L -> acc[2]
                    else -> -1L
                }
                if (steps < 0L || steps > 200_000L) continue
                val split = key.lastIndexOf('|')
                result.add(
                    StepDay(
                        acc[0].toInt(),
                        steps,
                        acc[4],
                        key.substring(0, split),
                        acc[5],
                        acc[7],
                        acc[6],
                    ),
                )
            }
            return result
        }

        /**
         * The band's passive step total is today in `zone`. A larger saved day stays.
         * The caller passes the zone. The system default would put the same millisecond on a different
         * date in UTC than in Shanghai.
         */
        @JvmStatic
        fun preferLiveTotal(
            days: List<StepDay>?,
            steps: Long,
            atMs: Long,
            zone: ZoneId?,
        ): List<StepDay> {
            if (days == null || zone == null) throw IllegalArgumentException("INVALID_STEP_WINDOW")
            if (steps < 0L || steps > 200_000L || atMs <= 0L) return java.util.List.copyOf(days)
            val local = Instant.ofEpochMilli(atMs).atZone(zone)
            val numeric = local.year * 10_000 + local.monthValue * 100 + local.dayOfMonth
            val start = local.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
            val result = ArrayList<StepDay>()
            var seen = false
            for (day in days) {
                if (day.date != numeric) {
                    result.add(day)
                    continue
                }
                seen = true
                result.add(
                    StepDay(
                        day.date,
                        maxOf(day.steps, steps),
                        day.startMs,
                        day.timezone,
                        day.calories,
                        day.distance,
                        day.moveAbout,
                    ),
                )
            }
            if (!seen) result.add(StepDay(numeric, steps, start, zone.id, -1L, -1L, -1L))
            return result
        }
    }
}
