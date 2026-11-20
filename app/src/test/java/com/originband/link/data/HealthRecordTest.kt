// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthRecordTest {
    @Test
    fun metricBoundsNeverTurnMissingValuesIntoZeroHeartOrOxygen() {
        for (kind in arrayOf("heart_rate", "spo2", "stress")) {
            val min = if (kind == "stress") 0 else 1
            val max = if (kind == "heart_rate") 250 else 100
            for (invalid in arrayOf<Number?>(null, min - 1, max + 1, 1.5, Double.NaN)) {
                assertThrows(IllegalArgumentException::class.java) {
                    metric(kind, invalid, "continuous", 60_000)
                }
            }
            assertEquals(min, metric(kind, min, "continuous", 60_000).value!!.toInt())
            assertEquals(max, metric(kind, max, "manual", 1).value!!.toInt())
            assertThrows(IllegalArgumentException::class.java) {
                metric(kind, min, "manual", 60_000)
            }
        }
        val quietMinute = HealthRecord(
            "steps", "stable", "steps_interval",
            60_000, 120_000, 0, null, 1, "+05:30", "continuous", false,
        )
        assertEquals(0, quietMinute.value!!.toInt())
    }

    @Test
    fun hostStoreRejectsParserValuesItWillNotKeep() {
        assertFalse(metric("stress", 0, "continuous", 60_000).hostAccepts())
        assertTrue(metric("stress", 1, "continuous", 60_000).hostAccepts())
        assertFalse(metric("heart_rate", 39, "continuous", 60_000).hostAccepts())
        assertTrue(metric("heart_rate", 40, "continuous", 60_000).hostAccepts())
        assertFalse(metric("spo2", 59, "manual", 1).hostAccepts())
        assertTrue(metric("spo2", 60, "manual", 1).hostAccepts())
    }

    @Test
    fun sleepIntervalIsNotATotalAndOnlyKnownStagesAreAccepted() {
        val sleep = HealthRecord(
            "sleep", "stable", "sleep_interval", 0, 60_000,
            null, null, 1, "+05:30", "sleep", true,
        )
        assertNull(sleep.value)
        assertTrue(sleep.complete)
        assertThrows(IllegalArgumentException::class.java) {
            HealthRecord(
                "sleep", "stable", "sleep_interval", 0, 60_000,
                60_001, null, 1, null, "sleep", true,
            )
        }
        for (stage in arrayOf<Int?>(null, -1, 0, 1, 7)) {
            assertThrows(IllegalArgumentException::class.java) {
                HealthRecord(
                    "sleep", "stable", "sleep_stage", 0, 60_000,
                    null, stage, 1, null, "sleep", false,
                )
            }
        }
        for (stage in 2..6) {
            assertEquals(
                stage,
                HealthRecord(
                    "sleep", "stable", "sleep_stage", 0, 60_000,
                    null, stage, 1, null, "continuous", false,
                ).stage!!.toInt(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            HealthRecord(
                "steps", "stable", "steps_day", 0, 86_400_000,
                10, null, 0, null, "continuous", false,
            )
        }
    }

    @Test
    @Throws(Exception::class)
    fun serializationRequiresNewModeAndBooleanCompleteness() {
        val original = metric("spo2", 98, "manual", 1)
        val decoded = HealthRecord.fromJson(original.toJson())
        assertEquals("manual", decoded.measurementMode)
        assertEquals(98, decoded.value!!.toInt())
        assertFalse(decoded.complete)
        val legacy = original.toJson()
        legacy.remove("measurementMode")
        assertThrows(JSONException::class.java) { HealthRecord.fromJson(legacy) }
        val missingComplete = original.toJson()
        missingComplete.remove("complete")
        assertThrows(JSONException::class.java) { HealthRecord.fromJson(missingComplete) }
        val textBoolean = original.toJson()
        textBoolean.put("complete", "false")
        assertThrows(IllegalArgumentException::class.java) { HealthRecord.fromJson(textBoolean) }
        val unknown = original.toJson()
        unknown.put("derivedSleepScore", 99)
        assertThrows(IllegalArgumentException::class.java) { HealthRecord.fromJson(unknown) }
        val falseComplete = original.toJson()
        falseComplete.put("complete", true)
        assertThrows(IllegalArgumentException::class.java) { HealthRecord.fromJson(falseComplete) }
    }

    @Test
    @Throws(Exception::class)
    fun sleepBreathValidatesRangeAndIntervalAndBypassesHostOutbox() {
        val record = HealthRecord(
            "rec-breath", "stable", "sleep_breath", 0, 60_000,
            142, null, 1, "+08:00", "sleep", false,
        )
        assertEquals(142, record.value!!.toInt())
        assertEquals("sleep_breath", record.kind)
        assertEquals("sleep", record.measurementMode)
        assertFalse(record.complete)
        assertNull(record.stage)
        assertFalse(record.hostAccepts())

        // Range 60..500
        assertEquals(
            60,
            HealthRecord(
                "r1", "stable", "sleep_breath", 0, 60_000,
                60, null, 1, null, "sleep", false,
            ).value!!.toInt(),
        )
        assertEquals(
            500,
            HealthRecord(
                "r2", "stable", "sleep_breath", 0, 60_000,
                500, null, 1, null, "sleep", false,
            ).value!!.toInt(),
        )

        for (invalid in arrayOf<Number?>(null, 59, 501, -1, 14.5, Double.NaN)) {
            assertThrows(IllegalArgumentException::class.java) {
                HealthRecord(
                    "rx", "stable", "sleep_breath", 0, 60_000,
                    invalid, null, 1, null, "sleep", false,
                )
            }
        }

        // Must be exactly 60_000 interval
        assertThrows(IllegalArgumentException::class.java) {
            HealthRecord(
                "rx", "stable", "sleep_breath", 0, 59_999,
                150, null, 1, null, "sleep", false,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            HealthRecord(
                "rx", "stable", "sleep_breath", 0, 60_001,
                150, null, 1, null, "sleep", false,
            )
        }

        // Must be mode "sleep"
        assertThrows(IllegalArgumentException::class.java) {
            HealthRecord(
                "rx", "stable", "sleep_breath", 0, 60_000,
                150, null, 1, null, "continuous", false,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            HealthRecord(
                "rx", "stable", "sleep_breath", 0, 60_000,
                150, null, 1, null, "manual", false,
            )
        }

        // Serialization round trip
        val decoded = HealthRecord.fromJson(record.toJson())
        assertEquals("sleep_breath", decoded.kind)
        assertEquals(142, decoded.value!!.toInt())
        assertEquals(60_000L, decoded.endMs - decoded.startMs)
        assertFalse(decoded.hostAccepts())
    }

    @Test
    fun dailyHeartValuesAreOnePointAndOnlyExistForHeartRate() {
        for (mode in arrayOf("resting", "walking")) {
            val daily = metric("heart_rate", 58, mode, 1)
            assertEquals(mode, daily.measurementMode)
            assertEquals(58, daily.value!!.toInt())
            assertThrows(IllegalArgumentException::class.java) { metric("heart_rate", 58, mode, 60_000) }
            assertThrows(IllegalArgumentException::class.java) { metric("spo2", 98, mode, 1) }
            assertThrows(IllegalArgumentException::class.java) { metric("stress", 40, mode, 1) }
        }
        assertThrows(IllegalArgumentException::class.java) { metric("heart_rate", 58, "resting ", 1) }
    }

    companion object {
        private fun metric(kind: String, value: Number?, mode: String, duration: Long): HealthRecord =
            HealthRecord(
                "metric", "stable", kind, 60_000, 60_000 + duration,
                value, null, 1, "+05:30", mode, false,
            )
    }
}
