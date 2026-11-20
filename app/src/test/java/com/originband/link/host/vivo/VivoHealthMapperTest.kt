// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.host.vivo

import com.originband.link.data.HealthRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class VivoHealthMapperTest {
    companion object {
        private const val DEV = "mi_aabbcc"
        private const val TZ = "Asia/Shanghai"
    }

    @Test
    fun mapsAllTwelveSportModesAndDefault() {
        assertEquals(2, VivoHealthMapper.mapSportType(2))
        assertEquals(12, VivoHealthMapper.mapSportType(1))
        assertEquals(16, VivoHealthMapper.mapSportType(506))
        assertEquals(4, VivoHealthMapper.mapSportType(3))
        assertEquals(1, VivoHealthMapper.mapSportType(10))
        assertEquals(10, VivoHealthMapper.mapSportType(35))
        assertEquals(17, VivoHealthMapper.mapSportType(908))
        assertEquals(5, VivoHealthMapper.mapSportType(7))
        assertEquals(15, VivoHealthMapper.mapSportType(809))
        assertEquals(6, VivoHealthMapper.mapSportType(36))
        assertEquals(7, VivoHealthMapper.mapSportType(37))
        assertEquals(112, VivoHealthMapper.mapSportType(19))
        assertEquals(10, VivoHealthMapper.mapSportType(9999))
    }

    @Test
    fun splitsVitalsIntoSupportedAndUnsupportedReceipts() {
        val t1 = 1_760_000_000_000L
        val records = listOf(
            HealthRecord("hr1", DEV, "heart_rate", t1, t1 + 60_000L, 75, null, 1, TZ, "continuous", false),
            HealthRecord("hr2", DEV, "heart_rate", t1 + 60_000L, t1 + 120_000L, 30, null, 1, TZ, "continuous", false),
            HealthRecord("hr_rest", DEV, "heart_rate", t1, t1 + 1L, 58, null, 1, TZ, "resting", false),
            HealthRecord("hr_walk", DEV, "heart_rate", t1, t1 + 1L, 96, null, 1, TZ, "walking", false),
            HealthRecord("sp1", DEV, "spo2", t1, t1 + 1L, 98, null, 1, TZ, "manual", false),
            HealthRecord("sp_bad", DEV, "spo2", t1 + 1000L, t1 + 1001L, 50, null, 1, TZ, "manual", false),
            HealthRecord("st1", DEV, "stress", t1, t1 + 60_000L, 42, null, 1, TZ, "continuous", false),
            HealthRecord("st_zero", DEV, "stress", t1 + 60_000L, t1 + 120_000L, 0, null, 1, TZ, "continuous", false),
        )

        val batch = VivoHealthMapper.mapBatch(records)
        assertEquals(1, batch.heartRates.size)
        assertEquals(t1 / 1000L, batch.heartRates[0].timestampS)
        assertEquals(75, batch.heartRates[0].value)
        assertEquals(1, batch.restingRates.size)
        assertEquals(58, batch.restingRates[0].bpm)
        assertEquals(1, batch.walkingRates.size)
        assertEquals(96, batch.walkingRates[0].bpm)
        assertEquals(1, batch.oxygens.size)
        assertEquals(98, batch.oxygens[0].value)
        assertEquals(1, batch.stresses.size)
        assertEquals(42, batch.stresses[0].value)

        assertEquals(3, batch.unsupported.size)
        assertEquals(
            listOf("hr2", "sp_bad", "st_zero"),
            batch.unsupported.map { it.recordId },
        )
        assertEquals(5, batch.handled.size)
    }

    @Test
    fun convertsStepCaloriesToSmallCaloriesAndGeneratesWholeHourStands() {
        val start = 1_760_025_600_000L // 2025-10-10 00:00:00 +08:00
        val end = start + 14 * 3_600_000L
        val records = listOf(
            HealthRecord(
                "si1", DEV, "steps_interval", start + 3_600_000L, start + 7_200_000L,
                1200, null, 1, TZ, "continuous", false, 45, 850, null,
            ),
            HealthRecord(
                "sd1", DEV, "steps_day", start, end,
                8500, null, 1, TZ, "continuous", false, 310, 6200, 6,
            ),
        )

        val batch = VivoHealthMapper.mapBatch(records)
        assertEquals(1, batch.stepSlices.size)
        assertEquals(1200, batch.stepSlices[0].steps)
        assertEquals(1, batch.calorieSlices.size)
        assertEquals(45_000, batch.calorieSlices[0].smallCalories)
        assertEquals(1, batch.distanceSlices.size)
        assertEquals(850, batch.distanceSlices[0].meters)

        assertEquals(1, batch.dailyTotals.size)
        assertEquals(8500, batch.dailyTotals[0].steps)
        assertEquals(310f, batch.dailyTotals[0].caloriesKcal!!, 0.01f)
        assertEquals(6200f, batch.dailyTotals[0].distanceMeters!!, 0.01f)

        assertEquals(6, batch.standSlices.size)
        for (stand in batch.standSlices) {
            assertEquals(0L, stand.hourStartMs % VivoHealthMapper.HOUR_MS)
            assertEquals(stand.hourStartMs + VivoHealthMapper.HOUR_MS, stand.hourEndMs)
            assertTrue(stand.hourStartMs >= start && stand.hourEndMs <= end)
            assertEquals(1, stand.value)
        }
    }

    @Test
    fun groupsSleepStagesIntoNightAndSkipsUnstaged() {
        val base = 1_760_025_600_000L
        val records = listOf(
            HealthRecord(
                "sleep_int", DEV, "sleep_interval", base, base + 210 * 60_000L,
                null, null, 1, TZ, "sleep", true,
            ),
            HealthRecord(
                "s1", DEV, "sleep_stage", base, base + 60 * 60_000L,
                null, 4, 1, TZ, "sleep", false,
            ),
            HealthRecord(
                "s2", DEV, "sleep_stage", base + 60 * 60_000L, base + 120 * 60_000L,
                null, 2, 1, TZ, "sleep", false,
            ),
            HealthRecord(
                "s3", DEV, "sleep_stage", base + 120 * 60_000L, base + 150 * 60_000L,
                null, 3, 1, TZ, "sleep", false,
            ),
            HealthRecord(
                "s4", DEV, "sleep_stage", base + 150 * 60_000L, base + 160 * 60_000L,
                null, 5, 1, TZ, "sleep", false,
            ),
            HealthRecord(
                "s5", DEV, "sleep_stage", base + 160 * 60_000L, base + 210 * 60_000L,
                null, 6, 1, TZ, "sleep", false,
            ),
        )

        val batch = VivoHealthMapper.mapBatch(records)
        assertEquals(1, batch.sleepNights.size)
        val night = batch.sleepNights[0]
        assertEquals(base, night.enterTimeMs)
        assertEquals(base + 210 * 60_000L, night.exitTimeMs)
        assertEquals(60, night.lightMin)
        assertEquals(60, night.deepMin)
        assertEquals(30, night.remMin)
        assertEquals(10, night.awakeMin)
        assertEquals(480, night.zoneOffsetMin)
        assertFalse(night.uuid.isEmpty())
    }

    @Test
    fun mapsSportSessionAndCorrelatesTrackPoints() {
        val start = 1_760_050_000_000L
        val end = start + 1_800_000L // 30 minutes
        val trackPayload = String.format(
            Locale.US,
            "5,0,0,0,0,0,%d,31.230400,121.473700,0,3.10,%d,0.000000,0.000000,0,0.00,%d,31.231000,121.474200,0,3.25",
            start,
            start + 5000L,
            start + 10_000L,
        )
        val records = listOf(
            HealthRecord(
                "sp_run", DEV, "sport_session", start, end, 2, null, 1, TZ, "continuous", false,
                320, 5000, null, 4500, 152, 1800, 320, null,
            ),
            HealthRecord(
                "sp_gps", DEV, "sport_track", start + 2000L, end, 2, null, 1, TZ, "continuous", false,
                null, null, null, null, null, null, null, trackPayload,
            ),
        )

        val batch = VivoHealthMapper.mapBatch(records)
        assertEquals(1, batch.sportSessions.size)
        val session = batch.sportSessions[0]
        assertEquals("sp_run", session.eid)
        assertEquals(2, session.vivoSportType)
        assertEquals(1_800_000L, session.durationMs)
        assertEquals(1800, session.activeDurationSec)
        assertEquals(360, session.averagePaceSecPerKm)
        assertEquals(5000f / 1800f, session.averageSpeedMps, 0.001f)

        assertEquals(2, batch.gpsPoints.size)
        assertEquals(session.tag, batch.gpsPoints[0].tag)
        assertEquals(start, batch.gpsPoints[0].rtcTimeMs)
        assertEquals(31.2304, batch.gpsPoints[0].latitude, 0.0001)
        assertEquals(121.4737, batch.gpsPoints[0].longitude, 0.0001)
    }
}
