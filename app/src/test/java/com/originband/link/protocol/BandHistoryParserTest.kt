// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import com.originband.link.data.HealthRecord
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.HexFormat
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class BandHistoryParserTest {
    @Test
    fun stepsAreMinuteDeltasAndSummaryIsAnIndependentDailyTotal() {
        val parser = parser()
        val record = file(4, 0, recordBody(9, 5, 72, 0))
        val packet = fragment(1, 1, record)
        val result = parser.acceptFragment(packet)!!
        val entries = result.measurements
        assertEquals(3, entries.size)
        assertEquals("steps_interval", entries[0].kind)
        assertEquals(9, entries[0].value!!.toInt())
        assertEquals(5, entries[2].value!!.toInt())
        assertEquals("heart_rate", entries[1].kind)
        assertEquals(72, entries[1].value!!.toInt())
        assertEquals(entries[0].endMs, entries[2].startMs)
        assertEquals("+05:30", entries[0].timezone)
        assertNull(entries[0].stage)

        val daily = parser.acceptFragment(fragment(1, 1, file(5, 1, reportBody(20))))!!
            .measurements[0]
        assertEquals("steps_day", daily.kind)
        assertEquals(20, daily.value!!.toInt())
        assertEquals(86_400_000L, daily.endMs - daily.startMs)
        assertNotEquals(entries[0].recordId, daily.recordId)
        assertEquals(
            20,
            parser.acceptFragment(fragment(1, 1, file(5, 1, reportBody(20))))!!
                .measurements[0].value!!.toInt(),
        )
        val changedHeartRate = parser.acceptFragment(
            fragment(1, 1, file(4, 0, recordBody(9, 5, 75, 0))),
        )!!.measurements
        assertEquals(entries[0].sourceFingerprint, changedHeartRate[0].sourceFingerprint)
        assertNotEquals(entries[1].sourceFingerprint, changedHeartRate[1].sourceFingerprint)
    }

    @Test
    fun dailyReportCarriesCaloriesAndStandHours() {
        val body = reportBody(2052)
        put16(body, 9, 183)
        body[27] = 0x0c
        body[28] = 0x30
        val daily = parser().parseFile(file(5, 1, body)).measurements[0]
        assertEquals(2052, daily.value!!.toInt())
        assertEquals(183, daily.calories)
        assertEquals(4, daily.moveAbout)
        assertNull(daily.distance)
        val day = LocalDate.of(2026, 9, 29).atStartOfDay(ZoneId.of("+05:30"))
            .toInstant().toEpochMilli()
        val saved = BandHistoryParser.stepDays(
            listOf(
                HealthRecord(
                    "day", "device", "steps_day",
                    day, day + 86_400_000L, 2052, null, 1, "+05:30", "continuous", false, 183, null, 4,
                ),
                HealthRecord(
                    "minute", "device", "steps_interval",
                    day, day + 60_000L, 21, null, 1, "+05:30", "continuous", false, 1, 13, null,
                ),
            ),
        )
        assertEquals(1, saved.size)
        assertEquals(2052L, saved[0].steps)
        assertEquals(183L, saved[0].calories)
        assertEquals(4L, saved[0].moveAbout)
        assertEquals(13L, saved[0].distance)
    }

    @Test
    fun dailyReportCarriesTheRestingHeartRateAsItsOwnPoint() {
        val body = reportBody(2052)
        body[1] = (body[1].toInt() or 0x10).toByte() // Validity bit 28, slot 3: the resting heart rate is present.
        body[12] = 58
        val measurements = parser().parseFile(file(5, 1, body)).measurements
        assertEquals(2, measurements.size)
        val steps = measurements[0]
        assertEquals("steps_day", steps.kind)
        assertEquals(2052, steps.value!!.toInt())
        val resting = measurements[1]
        assertEquals("heart_rate", resting.kind)
        assertEquals("resting", resting.measurementMode)
        assertEquals(58, resting.value!!.toInt())
        assertEquals(1L, resting.endMs - resting.startMs)
        assertEquals(steps.startMs + 43_200_000L, resting.startMs)
        assertNotEquals(steps.recordId, resting.recordId)
        assertTrue(resting.recordId.endsWith(":resting"))

        // The same day at a different rate is a new revision of the same point.
        val changed = reportBody(2052)
        changed[1] = (changed[1].toInt() or 0x10).toByte()
        changed[12] = 61
        val changedResting = parser().parseFile(file(5, 1, changed)).measurements[1]
        assertEquals(resting.recordId, changedResting.recordId)
        assertNotEquals(resting.sourceFingerprint, changedResting.sourceFingerprint)
    }

    @Test
    fun dailyReportWithoutAUsableRestingRateKeepsOnlyTheSteps() {
        assertEquals(1, parser().parseFile(file(5, 1, reportBody(20))).measurements.size)
        for (unusable in intArrayOf(0, 255)) {
            val body = reportBody(20)
            body[1] = (body[1].toInt() or 0x10).toByte()
            body[12] = unusable.toByte()
            assertEquals(1, parser().parseFile(file(5, 1, body)).measurements.size)
        }
    }

    @Test
    fun onlyATrailingDailyHeartMayGrowAnArchivedFile() {
        val body = reportBody(20)
        body[1] = (body[1].toInt() or 0x10).toByte()
        body[12] = 58
        val grown = parser().parseFile(file(5, 1, body)).measurements
        assertTrue(BandHistoryParser.onlyDailyHeartFrom(grown, 1))
        assertFalse(BandHistoryParser.onlyDailyHeartFrom(grown, 0))
        assertTrue(BandHistoryParser.onlyDailyHeartFrom(grown, 2))
        val minutes = parser().parseFile(file(4, 0, recordBody(9, 5, 72, 0))).measurements
        assertFalse(BandHistoryParser.onlyDailyHeartFrom(minutes, minutes.size - 1))
        assertFalse(BandHistoryParser.onlyDailyHeartFrom(null, 0))
    }

    @Test
    fun derivedDailyHeartIsOnePointPerMode() {
        val noon = 1_700_000_000_000L
        val walking = parser().dailyHeart("walking", noon, 97, "+05:30")
        assertEquals("heart_rate", walking.kind)
        assertEquals("walking", walking.measurementMode)
        assertEquals(noon, walking.startMs)
        assertEquals(1L, walking.endMs - walking.startMs)
        assertTrue(walking.recordId.endsWith(":walking"))
        assertEquals(walking.recordId, parser().dailyHeart("walking", noon, 103, "+05:30").recordId)
        assertNotEquals(
            walking.sourceFingerprint,
            parser().dailyHeart("walking", noon, 103, "+05:30").sourceFingerprint,
        )
        assertNotEquals(walking.recordId, parser().dailyHeart("resting", noon, 97, "+05:30").recordId)
        assertThrows(IllegalArgumentException::class.java) {
            parser().dailyHeart("continuous", noon, 97, "+05:30")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parser().dailyHeart("walking", noon, 30, "+05:30")
        }
    }

    @Test
    fun minuteRecordCarriesCaloriesAndDistanceBesideSteps() {
        val body = recordBody(9, 5, 72, 0)
        body[9] = 4
        put16(body, 11, 13)
        val steps = parser().parseFile(file(4, 0, body)).measurements[0]
        assertEquals("steps_interval", steps.kind)
        assertEquals(9, steps.value!!.toInt())
        assertEquals(4, steps.calories)
        assertEquals(13, steps.distance)
        assertNull(steps.moveAbout)
    }

    @Test
    fun versionTwoMinuteRecordsStillExposeHeartRate() {
        val body = ByteArray(8)
        val base = 31
        for (bit in intArrayOf(base, base - 3, base - 16, base - 17)) {
            body[1 + (base - bit) / 8] = (body[1 + (base - bit) / 8].toInt() or (1 shl (bit % 8))).toByte()
        }
        put16(body, 5, 6)
        body[7] = 80
        val records = parser().parseFile(file(2, 0, body)).measurements
        assertEquals(2, records.size)
        assertEquals("steps_interval", records[0].kind)
        assertEquals(6, records[0].value!!.toInt())
        assertEquals("heart_rate", records[1].kind)
        assertEquals(80, records[1].value!!.toInt())
    }

    @Test
    fun todayStepsPreferTheDailyReportAndIgnoreOtherDays() {
        val day = 1_000_000L
        val minutes = listOf(
            step("steps_interval", day - 60_000L, day, 9),
            step("steps_interval", day, day + 60_000L, 4),
            step("steps_interval", day + 60_000L, day + 120_000L, 6),
        )
        val summed = BandHistoryParser.todaySteps(minutes, day, day + 86_400_000L)!!
        assertEquals(10L, summed.steps)
        assertEquals(day + 60_000L, summed.measuredAtMs)
        assertFalse(summed.authoritative)
        val withReport = ArrayList(minutes)
        withReport.add(step("steps_day", day, day + 86_400_000L, 20))
        val daily = BandHistoryParser.todaySteps(withReport, day, day + 86_400_000L)!!
        assertEquals(20L, daily.steps)
        assertEquals(day, daily.measuredAtMs)
        assertTrue(daily.authoritative)
        val later = ArrayList(withReport)
        later.add(step("steps_interval", day + 120_000L, day + 180_000L, 25))
        val raised = BandHistoryParser.todaySteps(later, day, day + 86_400_000L)!!
        assertEquals(35L, raised.steps)
        assertEquals(day + 120_000L, raised.measuredAtMs)
        assertTrue(raised.authoritative)
        assertNull(
            BandHistoryParser.todaySteps(
                listOf(step("steps_interval", day - 60_000L, day, 9)),
                day,
                day + 86_400_000L,
            ),
        )
    }

    @Test
    fun stepDaysUseTheDailyReportAndKeepEachTimezone() {
        val day = LocalDate.of(2026, 9, 24).atStartOfDay(ZoneId.of("Asia/Shanghai"))
            .toInstant().toEpochMilli()
        val days = BandHistoryParser.stepDays(
            listOf(
                stepRecord("steps_interval", day, day + 60_000, 4),
                stepRecord("steps_interval", day + 60_000, day + 120_000, 6),
                stepRecord("steps_day", day, day + 86_400_000L, 20),
                stepRecord("steps_interval", day + 3_600_000, day + 3_660_000, 9, "+05:30"),
            ),
        )
        assertEquals(2, days.size)
        assertEquals(20260924, days[0].date)
        assertEquals(20L, days[0].steps)
        assertEquals("Asia/Shanghai", days[0].timezone)
        assertEquals(9L, days[1].steps)
        assertEquals("+05:30", days[1].timezone)
    }

    @Test
    fun liveStepTotalRaisesTodayAndDoesNotShrinkIt() {
        val zone = ZoneId.of("Asia/Shanghai")
        val day = LocalDate.of(2026, 9, 24).atStartOfDay(zone)
            .toInstant().toEpochMilli()
        val saved = BandHistoryParser.stepDays(
            listOf(stepRecord("steps_day", day, day + 86_400_000L, 20)),
        )
        val raised = BandHistoryParser.preferLiveTotal(saved, 30, day + 3_600_000, zone)
        val kept = BandHistoryParser.preferLiveTotal(saved, 10, day + 3_600_000, zone)
        assertEquals(1, raised.size)
        assertEquals(1, kept.size)
        assertEquals(30L, raised[0].steps)
        assertEquals(20L, kept[0].steps)
        val utc = BandHistoryParser.preferLiveTotal(saved, 30, day + 3_600_000, ZoneId.of("UTC"))
        assertEquals(2, utc.size)
        assertEquals(20260923, utc[1].date)
        assertEquals(30L, utc[1].steps)
    }

    @Test
    fun fragmentedFilesRetainMeasurementIdentityAcrossRevisions() {
        val parser = parser()
        val first = file(5, 1, reportBody(20))
        val second = file(5, 1, reportBody(21))
        val firstHalf = first.copyOfRange(0, 17)
        val secondHalf = first.copyOfRange(17, first.size)
        assertNull(parser.acceptFragment(fragment(2, 1, firstHalf)))
        val accepted = parser.acceptFragment(fragment(2, 2, secondHalf))!!
        val repeated = parser.acceptFragment(fragment(1, 1, first))!!.measurements[0]
        val revised = parser.acceptFragment(fragment(1, 1, second))!!.measurements[0]
        assertEquals(accepted.measurements[0].recordId, repeated.recordId)
        assertEquals(repeated.sourceFingerprint, accepted.measurements[0].sourceFingerprint)
        assertEquals(repeated.recordId, revised.recordId)
        assertNotEquals(repeated.sourceFingerprint, revised.sourceFingerprint)
    }

    @Test
    fun invalidFragmentOrCrcCannotProducePartialMeasurements() {
        val parser = parser()
        val good = file(4, 0, recordBody(2, 3, 64, 77))
        assertNull(parser.acceptFragment(fragment(2, 1, good.copyOfRange(0, 16))))
        assertThrows(IllegalArgumentException::class.java) {
            parser.acceptFragment(fragment(2, 2, byteArrayOf(1, 2)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parser.acceptFragment(fragment(2, 2, good.copyOfRange(16, good.size)))
        }
        val corrupt = good.clone()
        corrupt[16] = (corrupt[16].toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) {
            parser.acceptFragment(fragment(1, 1, corrupt))
        }
        assertFalse(parser.acceptFragment(fragment(1, 1, good))!!.measurements.isEmpty())
    }

    @Test
    fun unsupportedFilesRemainArchivableButBadFramingFails() {
        assertThrows(IllegalArgumentException::class.java) {
            BandHistoryParser("", stableId(), DID)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BandHistoryParser("4.100.139", stableId(), "different-did")
        }
        BandHistoryParser("1.0.50", stableId(), DID)
        val parser = parser()
        for (type in intArrayOf(0, 2 shl 2, 3 shl 2, 7 shl 2)) {
            val raw = file(99, type, byteArrayOf(0))
            val result = parser.parseFile(raw)
            assertTrue(result.parseStatus.startsWith("UNSUPPORTED_HISTORY_"))
            assertTrue(result.measurements.isEmpty())
            assertArrayEquals(raw, result.copyRawFile())
        }
        val extraLayout = recordBody(1, 1, 0, 0)
        extraLayout[8] = (extraLayout[8].toInt() or 0x40).toByte() // Announced anomaly byte absent: the final record is truncated.
        assertThrows(IllegalArgumentException::class.java) {
            parser.parseFile(file(4, 0, extraLayout))
        }
    }

    @Test
    fun variableFieldsAndAnomalyByteKeepFollowingMinutesAligned() {
        val body = activity(
            intArrayOf(47, 44, 31, 30, 19, 18, 15, 14, 11, 7),
            byteArrayOf(
                3, 0x40, 72, 98, 0, 111, 4, 5, 6, 7,
                4, 0, 73, 99, 45, 8, 9, 10, 11,
            ),
        )
        val records = parser().parseFile(file(4, 0, body)).measurements
        assertEquals(7, records.size)
        assertEquals(4, records[3].value!!.toInt())
        assertEquals(73, records[4].value!!.toInt())
        assertEquals(99, records[5].value!!.toInt())
        assertEquals(45, records[6].value!!.toInt())
        assertEquals("stress", records[6].kind)
        assertEquals(records[0].startMs + 60_000L, records[3].startMs)
        assertEquals("continuous", records[1].measurementMode)
    }

    @Test
    fun presenceAndValidityAreIndependentAndOutOfRangeValuesAreAbsent() {
        var body = activity(intArrayOf(31, 19, 15), byteArrayOf(72, 98, 0))
        assertTrue(parser().parseFile(file(4, 0, body)).measurements.isEmpty())
        body = activity(
            intArrayOf(31, 30, 19, 18, 15, 14),
            byteArrayOf(0, 0, 0, 251.toByte(), 101, 101, 250.toByte(), 100, 100),
        )
        val records = parser().parseFile(file(4, 0, body)).measurements
        assertEquals(3, records.size)
        assertEquals("heart_rate", records[0].kind)
        assertEquals(250, records[0].value!!.toInt())
        assertEquals("spo2", records[1].kind)
        assertEquals(100, records[1].value!!.toInt())
        assertEquals("stress", records[2].kind)
        assertEquals(100, records[2].value!!.toInt())
    }

    @Test
    fun manualRecordsUseDeclaredPayloadAndKeepRealPointTimes() {
        val body = ByteArrayOutputStream()
        body.write(0)
        manual(body, START_SECONDS, 0x37, byteArrayOf(8, 9, 10))
        manual(body, START_SECONDS, 0x11, byteArrayOf(81))
        manual(body, START_SECONDS + 1, 0x12, byteArrayOf(98))
        manual(body, START_SECONDS + 2, 0x13, byteArrayOf(0))
        val records = parser().parseFile(file(2, 6 shl 2, body.toByteArray())).measurements
        assertEquals(3, records.size)
        for (i in records.indices) {
            assertEquals((START_SECONDS + i.toLong()) * 1000L, records[i].startMs)
            assertEquals(records[i].startMs + 1, records[i].endMs)
            assertEquals("manual", records[i].measurementMode)
        }
        assertEquals("heart_rate", records[0].kind)
        assertEquals("spo2", records[1].kind)
        assertEquals("stress", records[2].kind)
        assertEquals(0, records[2].value!!.toInt())
        body.reset()
        body.write(0)
        manual(body, START_SECONDS, 0x21, byteArrayOf(81, 99))
        assertEquals(
            "UNSUPPORTED_MANUAL_PAYLOAD_LENGTH",
            parser().parseFile(file(2, 6 shl 2, body.toByteArray())).parseStatus,
        )
        val truncated = byteArrayOf(0, 1, 2, 3, 4, 0x31, 81)
        assertThrows(IllegalArgumentException::class.java) {
            parser().parseFile(file(2, 6 shl 2, truncated))
        }
    }

    @Test
    fun recordIdentityPreservesLegacyBaseAndSeparatesModes() {
        val aligned = START_SECONDS / 60 * 60
        val continuous = parser().parseFile(
            file(4, 0, activity(intArrayOf(31, 30), byteArrayOf(81))),
        ).measurements[0]
        val body = ByteArrayOutputStream()
        body.write(0)
        manual(body, aligned, 0x11, byteArrayOf(81))
        val manual = parser().parseFile(file(2, 6 shl 2, body.toByteArray())).measurements[0]
        val base = "xiaomi_ff917cf1ba2363ca168d5483b327936ba68c3b6cd60f13cb49cbd81e4fa971cf"
        assertEquals("$base:continuous", continuous.recordId)
        assertEquals("$base:manual", manual.recordId)
        assertNotEquals(continuous.sourceFingerprint, manual.sourceFingerprint)
        val mac = "AA:BB:CC:DD:EE:FF"
        val id = "miband11_" + HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(mac.toByteArray(StandardCharsets.UTF_8)),
        )
        assertEquals(
            id,
            BandHistoryParser("4.100.139", id, mac).parseFile(
                file(4, 0, activity(intArrayOf(31, 30), byteArrayOf(81))),
            ).measurements[0].deviceId,
        )
    }

    @Test
    fun sleepReportsRetainUnknownDurationAndRealAncillaryValuesAcrossMidnight() {
        for (version in intArrayOf(5, 6)) {
            val body = sleepBody(version, true)
            val records = parser().parseFile(file(version, (8 shl 2) or 1, body)).measurements
            assertEquals(4, records.size)
            val sleep = records[0]
            assertEquals("sleep_interval", sleep.kind)
            assertNull(sleep.value)
            assertNull(sleep.stage)
            assertTrue(sleep.complete)
            assertEquals("sleep", sleep.measurementMode)
            assertEquals("+05:30", sleep.timezone)
            assertEquals((START_SECONDS + 18 * 3600L) * 1000L, sleep.startMs)
            assertEquals((START_SECONDS + 26 * 3600L) * 1000L, sleep.endMs)
            val zone = ZoneId.of(sleep.timezone)
            assertEquals(
                Instant.ofEpochMilli(sleep.startMs).atZone(zone).toLocalDate().plusDays(1),
                Instant.ofEpochMilli(sleep.endMs).atZone(zone).toLocalDate(),
            )
            assertEquals(70, records[1].value!!.toInt())
            assertEquals(71, records[2].value!!.toInt())
            assertEquals(records[1].startMs + 60_000, records[2].startMs)
            assertEquals(98, records[3].value!!.toInt())
            assertEquals(records[3].startMs + 1, records[3].endMs)
            assertFalse(records[1].complete)
            val incomplete = parser().parseFile(
                file(version, (8 shl 2) or 1, sleepBody(version, false)),
            ).measurements[0]
            assertFalse(incomplete.complete)
            assertEquals(sleep.recordId, incomplete.recordId)
            assertNotEquals(sleep.sourceFingerprint, incomplete.sourceFingerprint)
            val truncated = body.copyOf(if (version == 5) 12 else 40)
            assertThrows(IllegalArgumentException::class.java) {
                parser().parseFile(file(version, (8 shl 2) or 1, truncated))
            }
        }
    }

    @Test
    fun sleepWithoutValidBoundsNeverCreatesAnIntervalOrGuessedStages() {
        for (version in intArrayOf(5, 6)) {
            var body = sleepBody(version, true)
            body[1] = (body[1].toInt() and 0x3f).toByte()
            var result = parser().parseFile(file(version, (8 shl 2) or 1, body))
            assertEquals(3, result.measurements.size)
            for (measurement in result.measurements) {
                assertTrue(measurement.kind == "heart_rate" || measurement.kind == "spo2")
                assertNull(measurement.stage)
            }
            body = sleepBody(version, true)
            val fixedStart = if (version == 5) 3 else 4
            put32(body, fixedStart + 5, START_SECONDS - 1)
            result = parser().parseFile(file(version, (8 shl 2) or 1, body))
            assertEquals("INVALID_SLEEP_INTERVAL", result.parseStatus)
            assertTrue(result.measurements.isEmpty())
            body = sleepBody(version, true)
            body[fixedStart] = 2
            assertEquals(
                "INVALID_SLEEP_COMPLETE_FLAG",
                parser().parseFile(file(version, (8 shl 2) or 1, body)).parseStatus,
            )
        }
    }

    @Test
    fun sleepStagePacketsFillTheIntervalWithoutInventingACoveringBar() {
        val sleepStart = START_SECONDS + 18 * 3600
        val body = sleepBody(6, true)
        val packet = ByteArray(21)
        packet[0] = 0xfb.toByte()
        packet[1] = 0xfa.toByte()
        packet[2] = 0xfc.toByte()
        packet[3] = 0xff.toByte()
        packet[4] = 17
        put32(packet, 5, sleepStart)
        packet[14] = 17
        packet[16] = 4
        val deep = (2 shl 12) or 120
        val light = (1 shl 12) or 360
        packet[17] = (deep ushr 8).toByte()
        packet[18] = deep.toByte()
        packet[19] = (light ushr 8).toByte()
        packet[20] = light.toByte()
        val withStages = body.copyOf(body.size + packet.size)
        System.arraycopy(packet, 0, withStages, body.size, packet.size)
        val records = parser().parseFile(file(6, (8 shl 2) or 1, withStages)).measurements
        val stages = records.filter { it.kind == "sleep_stage" }
        assertEquals(2, stages.size)
        assertEquals(2, stages[0].stage)
        assertEquals(sleepStart * 1000L, stages[0].startMs)
        assertEquals((sleepStart + 120 * 60) * 1000L, stages[0].endMs)
        assertEquals(3, stages[1].stage)
        assertEquals(stages[0].endMs, stages[1].startMs)
        assertEquals((sleepStart + 480 * 60) * 1000L, stages[1].endMs)
        assertEquals("sleep", stages[0].measurementMode)
        assertFalse(stages[0].complete)
    }

    @Test
    fun latestMatchingSleepStagePacketReplacesEarlierSummaries() {
        val sleepStart = START_SECONDS + 18 * 3600
        val stale = stagePacket(sleepStart, intArrayOf((2 shl 12) or 120, (1 shl 12) or 120))
        val current = stagePacket(sleepStart, intArrayOf((2 shl 12) or 60, (1 shl 12) or 420))
        val body = sleepBody(6, true)
        val withStages = body.copyOf(body.size + stale.size + current.size)
        System.arraycopy(stale, 0, withStages, body.size, stale.size)
        System.arraycopy(current, 0, withStages, body.size + stale.size, current.size)
        val stages = parser().parseFile(file(6, (8 shl 2) or 1, withStages)).measurements
            .filter { it.kind == "sleep_stage" }
        assertEquals(2, stages.size)
        assertEquals(2, stages[0].stage)
        assertEquals(60 * 60_000L, stages[0].endMs - stages[0].startMs)
        assertEquals(3, stages[1].stage)
        assertEquals(420 * 60_000L, stages[1].endMs - stages[1].startMs)
        assertEquals(stages[0].endMs, stages[1].startMs)
    }

    @Test
    fun partialSleepStagePacketAndT16NapSummaryBothDecode() {
        val sleepStart = START_SECONDS + 18 * 3600
        val stage = stagePacket(
            sleepStart,
            intArrayOf((2 shl 12) or 73, (1 shl 12) or 98, (3 shl 12) or 58, (0 shl 12) or 4),
        )
        val napStart = sleepStart + 319 * 60
        val nap = summaryPacket(napStart, 35, 19, 88)
        val body = sleepBody(6, true)
        val withBoth = body.copyOf(body.size + stage.size + nap.size)
        System.arraycopy(stage, 0, withBoth, body.size, stage.size)
        System.arraycopy(nap, 0, withBoth, body.size + stage.size, nap.size)

        val stages = parser().parseFile(file(6, (8 shl 2) or 1, withBoth)).measurements
            .filter { it.kind == "sleep_stage" }
        assertEquals(7, stages.size)
        assertEquals(2, stages[0].stage)
        assertEquals(73 * 60_000L, stages[0].endMs - stages[0].startMs)
        assertEquals(3, stages[1].stage)
        assertEquals(98 * 60_000L, stages[1].endMs - stages[1].startMs)
        assertEquals(4, stages[2].stage)
        assertEquals(58 * 60_000L, stages[2].endMs - stages[2].startMs)
        assertEquals(5, stages[3].stage)
        assertEquals(4 * 60_000L, stages[3].endMs - stages[3].startMs)
        assertEquals(6, stages[4].stage)
        assertEquals(35 * 60_000L, stages[4].endMs - stages[4].startMs)
        assertEquals(napStart * 1000L, stages[4].startMs)
        assertEquals(6, stages[4].stage)
        assertEquals(5, stages[5].stage)
        assertEquals(19 * 60_000L, stages[5].endMs - stages[5].startMs)
        assertEquals(6, stages[6].stage)
        assertEquals(88 * 60_000L, stages[6].endMs - stages[6].startMs)
    }

    @Test
    fun shortAwakeOnASyncMarkJoinsTheStageBeforeIt() {
        val sleepStart = START_SECONDS + 18 * 3600
        val mark = summaryPacket(sleepStart, 120, 0, 0)
        val staged = stagePacket(sleepStart, intArrayOf((2 shl 12) or 120, (0 shl 12) or 1, (1 shl 12) or 359))
        val stages = stagesOf(concat(concat(sleepV4(0, true), mark), staged))
        assertEquals(2, stages.size)
        assertEquals(2, stages[0].stage)
        assertEquals(121 * 60_000L, stages[0].endMs - stages[0].startMs)
        assertEquals(3, stages[1].stage)
        assertEquals(359 * 60_000L, stages[1].endMs - stages[1].startMs)

        val early = summaryPacket(sleepStart, 30, 0, 0)
        val kept = stagesOf(concat(concat(sleepV4(0, true), early), staged))
        assertEquals(5, kept[1].stage)
        assertEquals(1 * 60_000L, kept[1].endMs - kept[1].startMs)

        val longAwake = stagePacket(sleepStart, intArrayOf((2 shl 12) or 100, (0 shl 12) or 54, (1 shl 12) or 326))
        val longMark = summaryPacket(sleepStart, 100, 0, 0)
        val stillAwake = stagesOf(concat(concat(sleepV4(0, true), longMark), longAwake))
        assertEquals(5, stillAwake[1].stage)
        assertEquals(54 * 60_000L, stillAwake[1].endMs - stillAwake[1].startMs)
    }

    @Test
    fun pulseFileBecomesMinuteHeartRates() {
        val start = START_SECONDS + 18 * 3600
        val body = ByteArray(1 + 10)
        put32(body, 1, start)
        body[5] = 70
        put32(body, 6, start + 1)
        body[10] = 80
        val hearts = parser().parseFile(file(2, 9 shl 2, body)).measurements
        assertEquals(1, hearts.size)
        assertEquals("heart_rate", hearts[0].kind)
        assertEquals(75, hearts[0].value!!.toInt())
        assertEquals("continuous", hearts[0].measurementMode)
    }

    @Test
    fun testForRealBandFile20261010() {
        val file = File("/tmp/band_files/6d20bb5b4795d4a577c0d9ff9df03af62f5d4b9a46685dc87714bacee2907b4e.dat")
        if (!file.exists()) return
        val bytes = Files.readAllBytes(file.toPath())
        val res = parser().parseFile(bytes)
        assertEquals("PARSED", res.parseStatus)
        val stages = res.measurements.filter { it.kind == "sleep_stage" }
        // 18 from T17 + 3 from T16 = 21 stages
        assertEquals(21, stages.size)

        var deepMins = 0L
        var remMins = 0L
        var lightMins = 0L
        var awakeMins = 0L
        for (stage in stages) {
            val mins = (stage.endMs - stage.startMs) / 60_000L
            when (stage.stage) {
                2 -> deepMins += mins
                3 -> lightMins += mins
                4 -> remMins += mins
                5 -> awakeMins += mins
            }
        }
        assertEquals(73L, deepMins)
        assertEquals(58L, remMins)
        assertEquals(221L, lightMins)
        assertEquals(23L, awakeMins)
        assertEquals(352L, deepMins + remMins + lightMins) // Total sleep: 352 mins (5h 52m)
    }

    @Test
    fun sleepSummaryVersion4ParsesIntervalSeriesAndStages() {
        val sleepStart = START_SECONDS + 18 * 3600
        val records = parser().parseFile(file(4, (8 shl 2) or 1, sleepV4(0, true))).measurements
        assertEquals(6, records.size)
        val sleep = records[0]
        assertEquals("sleep_interval", sleep.kind)
        assertTrue(sleep.complete)
        assertEquals("sleep", sleep.measurementMode)
        assertEquals(sleepStart * 1000L, sleep.startMs)
        assertEquals((sleepStart + 8 * 3600L) * 1000L, sleep.endMs)
        assertEquals(70, records[1].value!!.toInt())
        assertEquals(71, records[2].value!!.toInt())
        assertEquals("spo2", records[3].kind)
        assertEquals(98, records[3].value!!.toInt())
        val stages = records.filter { it.kind == "sleep_stage" }
        assertEquals(2, stages.size)
        assertEquals(2, stages[0].stage)
        assertEquals(3, stages[1].stage)
        assertEquals(sleep.startMs, stages[0].startMs)
        assertEquals(sleep.endMs, stages[1].endMs)
        val open = parser().parseFile(file(4, (8 shl 2) or 1, sleepV4(1, true))).measurements[0]
        assertFalse(open.complete)
        assertEquals(sleep.recordId, open.recordId)
        val shifted = parser().parseFile(file(4, (8 shl 2) or 1, sleepV4(0, false))).measurements
        assertEquals(70, shifted[1].value!!.toInt())
        assertEquals(sleepStart * 1000L, shifted[1].startMs)
        assertEquals(
            "INVALID_SLEEP_COMPLETE_FLAG",
            parser().parseFile(file(4, (8 shl 2) or 1, sleepV4(2, true))).parseStatus,
        )
        val inverted = sleepV4(0, true)
        put32(inverted, 7, sleepStart - 1)
        assertEquals(
            "INVALID_SLEEP_INTERVAL",
            parser().parseFile(file(4, (8 shl 2) or 1, inverted)).parseStatus,
        )
    }

    @Test
    fun sleepTlvType10DecodesAndAggregatesMinuteSleepBreath() {
        val sleepStart = START_SECONDS + 18 * 3600
        val base = sleepV4(0, true)
        val windowEndSec = (sleepStart + 600).toLong()
        val entries = listOf(
            // Phase 0: 6 entries
            intArrayOf(0, Math.round(14.0f * 256)), // offset 0 -> min 0
            intArrayOf(0, Math.round(15.0f * 256)), // offset 33 -> min 0 (avg: 14.5 -> 145)
            intArrayOf(0, Math.round(16.0f * 256)), // offset 66 -> min 1
            intArrayOf(0, Math.round(16.0f * 256)), // offset 100 -> min 1 (avg: 16.0 -> 160)
            intArrayOf(0, Math.round(17.0f * 256)), // offset 133 -> min 2
            intArrayOf(0, Math.round(17.0f * 256)), // offset 166 -> min 2 (avg: 17.0 -> 170)
            // Phase 1: 1 entry
            intArrayOf(1, Math.round(18.0f * 256)), // offset 200 -> min 3 (avg: 18.0 -> 180)
            // Phase 2: 1 invalid (> 50.0 bpm) dropped, 1 valid
            intArrayOf(2, Math.round(55.0f * 256)), // invalid bpm (> 50) dropped
            intArrayOf(2, Math.round(12.0f * 256)), // offset 400 -> min 6 (avg: 12.0 -> 120)
        )
        val packet = breathPacket(windowEndSec, entries)
        val combined = concat(base, packet)
        val measurements = parser().parseFile(file(4, (8 shl 2) or 1, combined)).measurements
        assertEquals(11, measurements.size)

        val breath = measurements.filter { it.kind == "sleep_breath" }
        assertEquals(5, breath.size)

        val alignedStart = (sleepStart / 60L) * 60L
        assertEquals(alignedStart * 1000L, breath[0].startMs)
        assertEquals((alignedStart + 60) * 1000L, breath[0].endMs)
        assertEquals(145, breath[0].value!!.toInt())
        assertEquals("sleep", breath[0].measurementMode)
        assertFalse(breath[0].complete)

        assertEquals((alignedStart + 60) * 1000L, breath[1].startMs)
        assertEquals((alignedStart + 120) * 1000L, breath[1].endMs)
        assertEquals(160, breath[1].value!!.toInt())

        assertEquals((alignedStart + 120) * 1000L, breath[2].startMs)
        assertEquals((alignedStart + 180) * 1000L, breath[2].endMs)
        assertEquals(165, breath[2].value!!.toInt())

        assertEquals((alignedStart + 180) * 1000L, breath[3].startMs)
        assertEquals((alignedStart + 240) * 1000L, breath[3].endMs)
        assertEquals(175, breath[3].value!!.toInt())

        assertEquals((alignedStart + 420) * 1000L, breath[4].startMs)
        assertEquals((alignedStart + 480) * 1000L, breath[4].endMs)
        assertEquals(120, breath[4].value!!.toInt())
    }

    @Test
    fun realRawFitnessFilesDecodeSleepBreathRecords() {
        val file = File("/tmp/opencode/allraw/90ae55ce8aec19b3b38dc16637dc4251deca9f20da4587bbe0c0207a5712e0fc.dat")
        if (!file.exists()) return
        val bytes = Files.readAllBytes(file.toPath())
        val result = parser().parseFile(bytes)
        assertEquals("PARSED", result.parseStatus)
        val breath = result.measurements.filter { it.kind == "sleep_breath" }
        assertEquals(239, breath.size)
        for (m in breath) {
            assertEquals("sleep", m.measurementMode)
            assertFalse(m.complete)
            assertNull(m.stage)
            assertEquals(60_000L, m.endMs - m.startMs)
            assertTrue(m.value!!.toInt() in 60..500)
        }
        val min = breath.minOfOrNull { it.value!!.toInt() } ?: 0
        val max = breath.maxOfOrNull { it.value!!.toInt() } ?: 0
        assertEquals(61, min)
        assertEquals(245, max)
    }

    @Test
    fun manualInvalidValuesAreSkippedWithoutLosingFollowingRecords() {
        val body = ByteArrayOutputStream()
        body.write(0)
        manual(body, START_SECONDS, 0x11, byteArrayOf(0))
        manual(body, START_SECONDS + 1, 0x11, byteArrayOf(251.toByte()))
        manual(body, START_SECONDS + 2, 0x12, byteArrayOf(0))
        manual(body, START_SECONDS + 3, 0x12, byteArrayOf(101))
        manual(body, START_SECONDS + 4, 0x13, byteArrayOf(101))
        manual(body, START_SECONDS + 5, 0x13, byteArrayOf(100))
        val result = parser().parseFile(file(2, 6 shl 2, body.toByteArray()))
        assertEquals(1, result.measurements.size)
        assertEquals("stress", result.measurements[0].kind)
        assertEquals(100, result.measurements[0].value!!.toInt())
        assertEquals((START_SECONDS + 5L) * 1000L, result.measurements[0].startMs)
    }

    @Test
    fun zeroSleepSampleCountDoesNotConsumeTheNextSeriesTimestamp() {
        val body = sleepBody(5, true).copyOf(43)
        body[2] = 0xc0.toByte() // HR and oxygen, no snore.
        put16(body, 30, 60)
        put16(body, 32, 0) // No firstTime when count is zero.
        put16(body, 34, 60)
        put16(body, 36, 1)
        put32(body, 38, START_SECONDS + 18 * 3600 + 30)
        body[42] = 97
        val records = parser().parseFile(file(5, (8 shl 2) or 1, body)).measurements
        assertEquals(2, records.size)
        assertEquals("sleep_interval", records[0].kind)
        assertEquals("spo2", records[1].kind)
        assertEquals(97, records[1].value!!.toInt())
        assertEquals((START_SECONDS + 18 * 3600L + 30L) * 1000L, records[1].startMs)
    }

    @Test
    fun replayMeasurementRetainsCompletenessAndRejectsLegacyPayloads() {
        val source = parser().parseFile(file(6, (8 shl 2) or 1, sleepBody(6, true))).measurements[0]
        val decoded = BandHistoryParser.Measurement.fromJson(source.toJson())
        assertTrue(decoded.toRecord(3).complete)
        assertEquals("sleep", decoded.toRecord(3).measurementMode)
        assertEquals(3, decoded.toRecord(3).revision)
        assertNull(decoded.value)
        val legacy = source.toJson()
        legacy.remove("measurementMode")
        assertThrows(JSONException::class.java) { BandHistoryParser.Measurement.fromJson(legacy) }
        val missing = source.toJson()
        missing.remove("complete")
        assertThrows(JSONException::class.java) { BandHistoryParser.Measurement.fromJson(missing) }
        val wrongType = source.toJson()
        wrongType.put("complete", "true")
        assertThrows(IllegalArgumentException::class.java) {
            BandHistoryParser.Measurement.fromJson(wrongType)
        }
    }

    @Test
    fun semanticFailureDoesNotHideLaterTruncation() {
        val complete = activity(intArrayOf(47), ByteArray(1441 * 2))
        assertEquals("INVALID_ACTIVITY_RECORD_COUNT", parser().parseFile(file(4, 0, complete)).parseStatus)
        val truncated = activity(intArrayOf(47), ByteArray(1441 * 2 + 1))
        assertThrows(IllegalArgumentException::class.java) { parser().parseFile(file(4, 0, truncated)) }
        val sleep = sleepBody(5, true)
        put16(sleep, 30, 0) // Two HR values with a semantically invalid zero interval.
        assertEquals(
            "INVALID_SLEEP_SAMPLE_INTERVAL",
            parser().parseFile(file(5, (8 shl 2) or 1, sleep)).parseStatus,
        )
        val missingOxygen = sleep.copyOf(42)
        assertThrows(IllegalArgumentException::class.java) {
            parser().parseFile(file(5, (8 shl 2) or 1, missingOxygen))
        }
    }

    @Test
    fun sportSummariesKeepTheWorkoutTheBandRecorded() {
        assertSport(22, 9, 1, 2, "户外跑步", 5_200, 310, 6_400, 148)
        assertSport(22, 9, 2, 1, "健走", 3_100, 180, 4_200, 102)
        assertSport(22, 9, 15, 506, "徒步", 8_400, 540, 11_000, 121)
        assertSport(23, 9, 6, 3, "户外骑行", 12_000, 260, null, 130)
        assertSport(3, 14, 3, 10, "室内跑步", 4_000, 280, 5_100, 150)
        assertSport(8, 14, null, 35, "自由活动", null, 90, null, 110)
        val rope = parseSport(14, 7, null, null, 70, 800)
        assertEquals(908, rope.value!!.toInt())
        assertEquals(800, rope.reps)
        assertEquals(70, rope.calories)
        val swim = parseSport(9, 8, null, 750, 210, null)
        assertEquals(7, swim.value!!.toInt())
        assertEquals(750, swim.distance)
        assertEquals(
            "UNSUPPORTED_SPORT_FILE",
            parser().parseFile(file(9, sportDescriptor(22, 0), byteArrayOf(0))).parseStatus,
        )
        assertEquals(
            "UNSUPPORTED_SPORT_TYPE",
            parser().parseFile(sportFile(23, 9, 100, 1, 1, 1, null)).parseStatus,
        )
    }

    @Test
    fun outdoorGpsBecomesTheTrackStringHealthDraws() {
        val parsed = parser().parseFile(gpsFile(22, 5, 31.25f, 121.5f))
        assertEquals("PARSED", parsed.parseStatus)
        assertEquals("sport_track", parsed.measurements[0].kind)
        assertEquals(2, parsed.measurements[0].value!!.toInt())
        val track = parsed.measurements[0].track!!
        assertTrue(track.startsWith("5,0,0,0,0,0,"))
        assertTrue(track.contains("31.250000"))
        assertTrue(track.contains("121.500000"))
        assertEquals(
            "UNSUPPORTED_SPORT_FILE",
            parser().parseFile(gpsFile(3, 5, 31f, 121f)).parseStatus,
        )
        val older = parser().parseFile(gpsV2(23, 28.6999f, 116.0169f))
        assertEquals("PARSED", older.parseStatus)
        assertEquals("sport_track", older.measurements[0].kind)
        assertTrue(older.measurements[0].track!!.contains("28.699900"))
        assertTrue(older.measurements[0].track!!.contains("116.016899"))
    }

    @Test
    fun band8ProExportedSummariesBecomeTheRideAndTheFreeSession() {
        var zip = File("analysis/history.zip")
        if (!zip.isFile) zip = File("../analysis/history.zip")
        Assume.assumeTrue(zip.isFile)
        ZipFile(zip).use { archive ->
            val ride = parser().parseFile(zipEntry(archive, "8ef97f7b7e03"))
            assertEquals("PARSED", ride.parseStatus)
            assertEquals(3, ride.measurements[0].value!!.toInt())
            assertEquals(56812, ride.measurements[0].distance)
            assertEquals(1435, ride.measurements[0].calories)
            assertEquals(143, ride.measurements[0].heart)
            assertEquals(14355, ride.measurements[0].duration)
            assertEquals(114, ride.measurements[0].bestPace)
            assertEquals("+08:00", ride.measurements[0].timezone)
            val free = parser().parseFile(zipEntry(archive, "f4a9967a492a"))
            assertEquals("PARSED", free.parseStatus)
            assertEquals(35, free.measurements[0].value!!.toInt())
            assertEquals(4, free.measurements[0].calories)
            assertEquals(90, free.measurements[0].heart)
            val track = parser().parseFile(zipEntry(archive, "a1e7d3860bb2"))
            assertEquals("PARSED", track.parseStatus)
            assertEquals(1500, track.measurements[0].value!!.toInt())
            assertTrue(track.measurements[0].track!!.contains("28.699883"))
            val pace = SportGpsParser.paceSeries(track.measurements[0].track)
            assertTrue(pace != null && pace.startsWith("2,0,0,"))
            val fastest = SportGpsParser.fastestPace(pace)!!
            assertTrue(fastest in 30..400)
            assertRideSpeedChart(ride.measurements[0], track.measurements[0])
        }
    }

    private fun stagesOf(body: ByteArray): List<BandHistoryParser.Measurement> =
        parser().parseFile(file(4, (8 shl 2) or 1, body)).measurements.filter { it.kind == "sleep_stage" }

    companion object {
        private const val START_SECONDS = 1_700_000_000
        private const val DID = "synthetic-confirmed-did"

        private fun step(kind: String, start: Long, end: Long, value: Int): BandHistoryParser.Measurement =
            BandHistoryParser.Measurement.fromJson(
                JSONObject()
                    .put("recordId", "$kind-$start")
                    .put("deviceId", "device")
                    .put("kind", kind)
                    .put("startMs", start)
                    .put("endMs", end)
                    .put("value", value)
                    .put("stage", JSONObject.NULL)
                    .put("timezone", "Asia/Shanghai")
                    .put("measurementMode", "continuous")
                    .put("complete", false)
                    .put("sourceFingerprint", "a".repeat(64)),
            )

        private fun stepRecord(kind: String, start: Long, end: Long, value: Int): HealthRecord =
            stepRecord(kind, start, end, value, "Asia/Shanghai")

        private fun stepRecord(
            kind: String,
            start: Long,
            end: Long,
            value: Int,
            zone: String,
        ): HealthRecord = HealthRecord(
            "rec-$kind-$start$zone",
            "device",
            kind,
            start,
            end,
            value,
            null,
            1,
            zone,
            "continuous",
            false,
        )

        private fun sleepBody(version: Int, complete: Boolean): ByteArray {
            val sleepStart = START_SECONDS + 18 * 3600
            val flags = if (version == 5) 2 else 3
            val fixed = if (version == 5) 27 else 51
            val body = ByteArray(1 + flags + fixed + 10 + 9 + (if (version == 6) 10 else 0) + 12 + 3)
            val valid = if (version == 5) 0xc0e0L else 0xc0001eL
            for (i in 0 until flags) {
                body[1 + i] = (valid ushr ((flags - i - 1) * 8)).toByte()
            }
            var p = 1 + flags
            body[p] = (if (complete) 1 else 0).toByte()
            put32(body, p + 1, sleepStart)
            put32(body, p + 5, sleepStart + 8 * 3600)
            put32(body, p + 11, 600) // Latency, not sleep duration.
            put32(body, p + 15, 32000) // Bed duration, not sleep duration.
            p += fixed
            put16(body, p, 60)
            put16(body, p + 2, 2)
            put32(body, p + 4, sleepStart)
            body[p + 8] = 70
            body[p + 9] = 71
            p += 10
            put16(body, p, 60)
            put16(body, p + 2, 1)
            put32(body, p + 4, sleepStart + 30)
            body[p + 8] = 98
            p += 9
            if (version == 6) {
                put16(body, p, 60)
                put16(body, p + 2, 1)
                put32(body, p + 4, sleepStart)
                put16(body, p + 8, 42)
                p += 10
            }
            put16(body, p, 60)
            put16(body, p + 2, 1)
            put32(body, p + 4, sleepStart)
            put32(body, p + 8, 30)
            p += 12
            body[p] = 2
            body[p + 1] = 3
            body[p + 2] = 4 // Opaque features never become stages.
            return body
        }

        private fun sleepV4(open: Int, quality: Boolean): ByteArray {
            val sleepStart = START_SECONDS + 18 * 3600
            val qualityBytes = if (quality) 1 else 0
            val body = ByteArray(1 + 1 + 1 + 8 + qualityBytes + 10 + 9 + 12 + 21)
            body[1] = ((if (quality) 1 shl 4 else 0) or (1 shl 3) or (1 shl 2) or (1 shl 1)).toByte()
            body[2] = open.toByte()
            put32(body, 3, sleepStart)
            put32(body, 7, sleepStart + 8 * 3600)
            var p = 11
            if (quality) body[p++] = 80
            put16(body, p, 60)
            put16(body, p + 2, 2)
            put32(body, p + 4, sleepStart)
            body[p + 8] = 70
            body[p + 9] = 71
            p += 10
            put16(body, p, 60)
            put16(body, p + 2, 1)
            put32(body, p + 4, sleepStart + 30)
            body[p + 8] = 98
            p += 9
            put16(body, p, 60)
            put16(body, p + 2, 1)
            put32(body, p + 4, sleepStart)
            p += 12
            body[p] = 0xfb.toByte()
            body[p + 1] = 0xfa.toByte()
            body[p + 2] = 0xfc.toByte()
            body[p + 3] = 0xff.toByte()
            body[p + 4] = 17
            put32(body, p + 5, sleepStart)
            body[p + 14] = 17
            body[p + 16] = 4
            val deep = (2 shl 12) or 120
            val light = (1 shl 12) or 360
            body[p + 17] = (deep ushr 8).toByte()
            body[p + 18] = deep.toByte()
            body[p + 19] = (light ushr 8).toByte()
            body[p + 20] = light.toByte()
            return body
        }

        private fun activity(flags: IntArray, payload: ByteArray): ByteArray {
            val body = ByteArray(7 + payload.size)
            for (bit in flags) {
                body[1 + (47 - bit) / 8] = (body[1 + (47 - bit) / 8].toInt() or (1 shl (bit % 8))).toByte()
            }
            System.arraycopy(payload, 0, body, 7, payload.size)
            return body
        }

        private fun manual(body: ByteArrayOutputStream, seconds: Int, descriptor: Int, payload: ByteArray) {
            val header = ByteArray(5)
            put32(header, 0, seconds)
            header[4] = descriptor.toByte()
            body.writeBytes(header)
            body.writeBytes(payload)
        }

        private fun summaryPacket(startSec: Int, seg0Mins: Int, gapMins: Int, seg1Mins: Int): ByteArray {
            val payloadLen = if (seg1Mins > 0 || gapMins > 0) 26 else 13
            val packet = ByteArray(17 + payloadLen)
            packet[0] = 0xfb.toByte()
            packet[1] = 0xfa.toByte()
            packet[2] = 0xfc.toByte()
            packet[3] = 0xff.toByte()
            packet[4] = 17
            put32(packet, 5, startSec)
            packet[14] = 16
            packet[16] = payloadLen.toByte()
            packet[17] = 0x10
            packet[19] = (seg0Mins and 0xff).toByte()
            packet[20] = ((seg0Mins ushr 8) and 0xff).toByte()
            if (payloadLen == 26) {
                packet[30] = 0x20
                packet[32] = (seg1Mins and 0xff).toByte()
                packet[33] = ((seg1Mins ushr 8) and 0xff).toByte()
                packet[42] = (gapMins and 0xff).toByte()
            }
            return packet
        }

        private fun stagePacket(sleepStart: Int, runs: IntArray): ByteArray {
            val packet = ByteArray(17 + runs.size * 2)
            packet[0] = 0xfb.toByte()
            packet[1] = 0xfa.toByte()
            packet[2] = 0xfc.toByte()
            packet[3] = 0xff.toByte()
            packet[4] = 17
            put32(packet, 5, sleepStart)
            packet[14] = 17
            packet[16] = (runs.size * 2).toByte()
            for (i in runs.indices) {
                packet[17 + i * 2] = (runs[i] ushr 8).toByte()
                packet[18 + i * 2] = runs[i].toByte()
            }
            return packet
        }

        private fun breathPacket(endTsSec: Long, entries: List<IntArray>): ByteArray {
            val payloadLen = entries.size * 4
            val packet = ByteArray(17 + payloadLen)
            packet[0] = 0xfb.toByte()
            packet[1] = 0xfa.toByte()
            packet[2] = 0xfc.toByte()
            packet[3] = 0xff.toByte()
            packet[4] = 17
            val tsNano = endTsSec * 1_000_000_000L
            put32(packet, 5, (tsNano and 0xffffffffL).toInt())
            put32(packet, 9, (tsNano ushr 32).toInt())
            packet[14] = 10
            packet[15] = (payloadLen ushr 8).toByte()
            packet[16] = payloadLen.toByte()
            for (i in entries.indices) {
                val entry = entries[i]
                val phase = entry[0]
                val bpmFp8 = entry[1]
                val w0 = bpmFp8 and 0xffff
                val w1 = (phase shl 12) or 0x40
                packet[17 + i * 4] = w0.toByte()
                packet[18 + i * 4] = (w0 ushr 8).toByte()
                packet[19 + i * 4] = w1.toByte()
                packet[20 + i * 4] = (w1 ushr 8).toByte()
            }
            return packet
        }

        private fun concat(a: ByteArray, b: ByteArray): ByteArray {
            val res = ByteArray(a.size + b.size)
            System.arraycopy(a, 0, res, 0, a.size)
            System.arraycopy(b, 0, res, a.size, b.size)
            return res
        }

        private fun parser(): BandHistoryParser = BandHistoryParser("4.100.139", stableId(), DID)

        private fun stableId(): String = "miband11_" + HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(DID.toByteArray(StandardCharsets.UTF_8)),
        )

        private fun recordBody(firstSteps: Int, secondSteps: Int, firstHr: Int, secondHr: Int): ByteArray {
            val data = ByteArray(7 + 2 * 16)
            // Present fields: v4 fixed 16-byte layout. Validity includes both steps and HR.
            for (bit in intArrayOf(47, 44, 43, 39, 35, 31, 30, 27, 23, 19, 15, 11, 7)) {
                data[1 + (47 - bit) / 8] = (data[1 + (47 - bit) / 8].toInt() or (1 shl (bit % 8))).toByte()
            }
            put16(data, 7, firstSteps)
            put16(data, 23, secondSteps)
            data[13] = firstHr.toByte()
            data[29] = secondHr.toByte()
            return data
        }

        private fun reportBody(steps: Int): ByteArray {
            val data = ByteArray(5 + 53)
            data[1] = 0x80.toByte() // totalStepsValid, not "all values valid".
            put32(data, 5, steps)
            return data
        }

        private fun file(version: Int, fileType: Int, data: ByteArray): ByteArray {
            val bytes = ByteArray(7 + data.size + 4)
            put32(bytes, 0, START_SECONDS)
            bytes[4] = 22 // +05:30, synthetic fixed offset
            bytes[5] = version.toByte()
            bytes[6] = fileType.toByte()
            System.arraycopy(data, 0, bytes, 7, data.size)
            val crc = CRC32()
            crc.update(bytes, 0, bytes.size - 4)
            put32(bytes, bytes.size - 4, crc.value.toInt())
            return bytes
        }

        /**
         * Health draws the ride speed chart as 3600/pace, in km/h, on an axis of seconds.
         * The GPS float is metres per second. Dividing it by 3.6 first pins the line to the axis floor.
         */
        private fun assertRideSpeedChart(
            session: BandHistoryParser.Measurement,
            track: BandHistoryParser.Measurement,
        ) {
            val pace = SportGpsParser.paceSeries(track.track)!!
            val part = pace.split(",")
            val stride = part[0].toInt()
            assertEquals(2, stride)
            val kmh = ArrayList<Double>()
            var onAxis = 0
            var minRelative = Long.MAX_VALUE
            var maxRelative = Long.MIN_VALUE
            val durationMs = session.duration!! * 1000L
            val axisSeconds = durationMs / 1000.0
            var i = stride + 1
            while (i <= part.size - stride) {
                val timestamp = part[i].toLong()
                val secondsPerKm = part[i + 1].toInt()
                val relative = timestamp - session.startMs
                minRelative = minOf(minRelative, relative)
                maxRelative = maxOf(maxRelative, relative)
                if (relative / 1000.0 >= -30 && relative / 1000.0 <= axisSeconds + 30) onAxis++
                kmh.add(3600.0 / secondsPerKm)
                i += stride
            }
            assertTrue(kmh.size >= 500)
            // GPS wall time runs past totalDuration because pauses stay in the timestamps.
            // The chart axis is the summary duration, so the line is the points inside that window.
            assertTrue(
                "on axis $onAxis/${kmh.size} relative ${minRelative / 1000}..${maxRelative / 1000} axis $axisSeconds",
                onAxis >= 500,
            )
            Collections.sort(kmh)
            val median = kmh[kmh.size / 2]
            val expected = session.distance!! * 3.6 / session.duration!!
            assertTrue(Math.abs(median - expected) / expected < 0.15)
            assertTrue(kmh[kmh.size - 1] <= median * 4.5)
        }

        private fun zipEntry(archive: ZipFile, prefix: String): ByteArray {
            var match: ZipEntry? = null
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name.contains(prefix)) {
                    match = entry
                    break
                }
            }
            if (match == null) throw IllegalStateException(prefix)
            return archive.getInputStream(match).use { it.readAllBytes() }
        }

        /** Band 8 Pro GPS v2: flag byte, then time, longitude, latitude, speed float, uint16. */
        private fun gpsV2(activity: Int, lat: Float, lon: Float): ByteArray {
            val body = ByteBuffer.allocate(18 * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until 2) {
                body.putInt(START_SECONDS + i)
                body.putFloat(lon)
                body.putFloat(lat)
                body.putFloat(14f)
                body.putShort(800)
            }
            val data = ByteArray(1 + 1 + body.capacity())
            data[1] = 0xFF.toByte()
            System.arraycopy(body.array(), 0, data, 2, body.capacity())
            return file(2, 0x80 or (activity shl 2) or 2, data)
        }

        private fun gpsFile(activity: Int, version: Int, lat: Float, lon: Float): ByteArray {
            val body = ByteBuffer.allocate(2 + 4 + 12 * 2).order(ByteOrder.LITTLE_ENDIAN)
            body.put(0xE0.toByte())
            body.put(0)
            body.putInt(2)
            for (i in 0 until 2) {
                body.putInt(START_SECONDS + i)
                body.putFloat(lon)
                body.putFloat(lat + i * 0.0001f)
            }
            val data = ByteArray(1 + body.capacity())
            System.arraycopy(body.array(), 0, data, 1, body.capacity())
            return file(version, 0x80 or (activity shl 2) or 2, data)
        }

        private fun assertSport(
            activity: Int,
            version: Int,
            subSport: Int?,
            mode: Int,
            name: String,
            distance: Int?,
            calories: Int,
            steps: Int?,
            heart: Int,
        ) {
            val session = parseSport(activity, version, subSport, distance, calories, steps, heart)
            assertEquals(mode, session.value!!.toInt())
            assertEquals(name, SportReportParser.name(mode))
            assertEquals(distance, session.distance)
            assertEquals(calories, session.calories)
            assertEquals(steps, session.reps)
            assertEquals(heart, session.heart)
            assertEquals(1_800_000L, session.endMs - session.startMs)
            assertEquals("+05:30", session.timezone)
        }

        private fun parseSport(
            activity: Int,
            version: Int,
            subSport: Int?,
            distance: Int?,
            calories: Int,
            steps: Int?,
        ): BandHistoryParser.Measurement = parseSport(activity, version, subSport, distance, calories, steps, null)

        private fun parseSport(
            activity: Int,
            version: Int,
            subSport: Int?,
            distance: Int?,
            calories: Int,
            steps: Int?,
            heart: Int?,
        ): BandHistoryParser.Measurement {
            val parsed = parser().parseFile(sportFile(activity, version, subSport, distance, calories, steps, heart))
            assertEquals("PARSED", parsed.parseStatus)
            assertEquals(1, parsed.measurements.size)
            return parsed.measurements[0]
        }

        private fun sportFile(
            activity: Int,
            version: Int,
            subSport: Int?,
            distance: Int?,
            calories: Int,
            steps: Int?,
            heart: Int?,
        ): ByteArray {
            val values = HashMap<String, Long>()
            if (subSport != null) values["subSportType"] = subSport.toLong()
            values["startTime"] = START_SECONDS.toLong()
            values["endTime"] = START_SECONDS.toLong() + 1_800L
            values["totalDuration"] = 1_800L
            values["totalCalories"] = calories.toLong()
            if (distance != null) values["totalDistance"] = distance.toLong()
            if (steps != null) values[if (activity == 14) "totalJumps" else "totalSteps"] = steps.toLong()
            if (heart != null) values["avgHeartRate"] = heart.toLong()
            val layout = SportReportLayouts.layout(if (activity == 10) 9 else activity, version)!!
            val gates = HashSet<String>()
            for (field in layout.fields) {
                if (values.containsKey(field.name) && field.gate != null) gates.add(field.gate)
            }
            val flags = ByteArray(layout.validityBytes)
            for (i in layout.flags.indices) {
                if (gates.contains(layout.flags[i])) {
                    flags[i / 8] = (flags[i / 8].toInt() or (1 shl (7 - (i % 8)))).toByte()
                }
            }
            val body = ByteArrayOutputStream()
            body.write(0)
            body.write(flags, 0, flags.size)
            for (field in layout.fields) {
                if (field.gate != null && !gates.contains(field.gate)) continue
                if (field.`when` != null) continue
                val fieldValue = values[field.name]
                if (fieldValue == null && field.gate == null) {
                    writeWidth(body, field.bytes, 0L)
                } else if (fieldValue != null) {
                    writeWidth(body, field.bytes, fieldValue)
                }
            }
            return file(version, sportDescriptor(activity, 1), body.toByteArray())
        }

        private fun sportDescriptor(activity: Int, detail: Int): Int = 0x80 or (activity shl 2) or detail

        private fun writeWidth(body: ByteArrayOutputStream, width: Int, value: Long) {
            for (i in 0 until width) body.write((value ushr (8 * i)).toInt())
        }

        private fun fragment(total: Int, sequence: Int, part: ByteArray): ByteArray {
            val packet = ByteArray(part.size + 4)
            put16(packet, 0, total)
            put16(packet, 2, sequence)
            System.arraycopy(part, 0, packet, 4, part.size)
            return packet
        }

        private fun put16(data: ByteArray, offset: Int, value: Int) {
            data[offset] = value.toByte()
            data[offset + 1] = (value ushr 8).toByte()
        }

        private fun put32(data: ByteArray, offset: Int, value: Int) {
            for (i in 0 until 4) data[offset + i] = (value ushr (i * 8)).toByte()
        }
    }
}
