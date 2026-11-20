// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WalkingHeartRateTest {
    companion object {
        private const val MINUTE = 60_000L
        private const val DAY = 86_400_000L
        private const val NOON = 1_700_000_000_000L
    }

    @Test
    fun walkingMinutesAreAveraged() {
        val steps = ArrayList<HealthRecord>()
        val heart = ArrayList<HealthRecord>()
        for (i in 0 until 10) {
            steps.add(step(i, 90))
            heart.add(beat(i, 90 + i))
        }
        assertEquals(95, WalkingHeartRate.average(steps, heart, emptyList()))
    }

    @Test
    fun tooFewWalkingMinutesHaveNoAverage() {
        val steps = ArrayList<HealthRecord>()
        val heart = ArrayList<HealthRecord>()
        for (i in 0 until WalkingHeartRate.MIN_SAMPLES - 1) {
            steps.add(step(i, 90))
            heart.add(beat(i, 90))
        }
        assertNull(WalkingHeartRate.average(steps, heart, emptyList()))
        assertNull(WalkingHeartRate.average(emptyList(), emptyList(), emptyList()))
    }

    @Test
    fun idleAndRunningMinutesAreNotWalking() {
        val steps = ArrayList<HealthRecord>()
        val heart = ArrayList<HealthRecord>()
        var index = 0
        for (count in intArrayOf(
            WalkingHeartRate.MIN_STEPS_PER_MINUTE - 1,
            WalkingHeartRate.MAX_STEPS_PER_MINUTE + 1,
            0,
        )) {
            steps.add(step(index, count))
            heart.add(beat(index, 120))
            index++
        }
        for (i in 0 until WalkingHeartRate.MIN_SAMPLES) {
            steps.add(step(index, WalkingHeartRate.MIN_STEPS_PER_MINUTE))
            heart.add(beat(index, 100))
            index++
        }
        assertEquals(100, WalkingHeartRate.average(steps, heart, emptyList()))
    }

    @Test
    fun onlyAContinuousHeartRateCounts() {
        val steps = ArrayList<HealthRecord>()
        val heart = ArrayList<HealthRecord>()
        for (i in 0 until WalkingHeartRate.MIN_SAMPLES) {
            steps.add(step(i, 90))
        }
        for (i in 0 until WalkingHeartRate.MIN_SAMPLES) {
            heart.add(
                HealthRecord(
                    "manual$i", "device", "heart_rate", NOON + i * MINUTE,
                    NOON + i * MINUTE + 1, 120, null, 1, "+05:30", "manual", false,
                ),
            )
            heart.add(
                HealthRecord(
                    "resting$i", "device", "heart_rate", NOON + i * MINUTE,
                    NOON + i * MINUTE + 1, 120, null, 1, "+05:30", "resting", false,
                ),
            )
        }
        assertNull(WalkingHeartRate.average(steps, heart, emptyList()))

        for (i in 0 until WalkingHeartRate.MIN_SAMPLES) {
            heart.add(beat(i, 88))
        }
        assertEquals(88, WalkingHeartRate.average(steps, heart, emptyList()))
    }

    @Test
    fun minutesInsideAWorkoutAreLeftOut() {
        val steps = ArrayList<HealthRecord>()
        val heart = ArrayList<HealthRecord>()
        for (i in 0 until WalkingHeartRate.MIN_SAMPLES + 2) {
            steps.add(step(i, 90))
            heart.add(beat(i, 130))
        }
        // The last two minutes are part of a recorded workout, not a walk to work.
        val from = NOON + WalkingHeartRate.MIN_SAMPLES * MINUTE
        val workout = HealthRecord(
            "sport", "device", "sport_session", from,
            from + 2 * MINUTE, 2, null, 1, "+05:30", "continuous", false,
        )
        assertEquals(130, WalkingHeartRate.average(steps, heart, listOf(workout)))
        val shortWalk = ArrayList<HealthRecord>()
        val shortHeart = ArrayList<HealthRecord>()
        for (i in 0 until WalkingHeartRate.MIN_SAMPLES) {
            shortWalk.add(step(i, 90))
            shortHeart.add(beat(i, 130))
        }
        val all = NOON
        assertNull(
            WalkingHeartRate.average(
                shortWalk,
                shortHeart,
                listOf(
                    HealthRecord(
                        "sport", "device", "sport_session", all, all + DAY / 2, 2,
                        null, 1, "+05:30", "continuous", false,
                    ),
                ),
            ),
        )
    }

    @Test
    fun aSparseHeartRateIsBorrowedByTheNearbyWalkingMinutes() {
        // The band reports a heart rate about every ten minutes, not every minute.
        val steps = ArrayList<HealthRecord>()
        val heart = ArrayList<HealthRecord>()
        for (i in 0 until 60) {
            steps.add(step(i, 90))
        }
        for (i in 0 until 10) {
            heart.add(beat(i * 6, 95 + i))
        }
        assertEquals(100, WalkingHeartRate.average(steps, heart, emptyList()))
    }

    @Test
    fun oneReadingIsCountedOnce() {
        // Twenty walking minutes covered by only five readings are five measurements, not twenty.
        val steps = ArrayList<HealthRecord>()
        val heart = ArrayList<HealthRecord>()
        for (i in 0 until 20) {
            steps.add(step(i, 90))
        }
        for (i in 0 until 5) {
            heart.add(beat(i * 4, 110))
        }
        assertNull(WalkingHeartRate.average(steps, heart, emptyList()))
    }

    @Test
    fun averageRoundsToTheNearestBeat() {
        val steps = ArrayList<HealthRecord>()
        val heart = ArrayList<HealthRecord>()
        for (i in 0 until 10) {
            steps.add(step(i, 90))
            heart.add(beat(i, if (i < 5) 100 else 101))
        }
        assertEquals(101, WalkingHeartRate.average(steps, heart, emptyList()))
    }

    private fun step(minute: Int, count: Int): HealthRecord {
        val start = NOON + minute * MINUTE
        return HealthRecord(
            "step$minute", "device", "steps_interval", start, start + MINUTE,
            count, null, 1, "+05:30", "continuous", false,
        )
    }

    private fun beat(minute: Int, value: Int): HealthRecord {
        val start = NOON + minute * MINUTE
        return HealthRecord(
            "beat$minute", "device", "heart_rate", start, start + MINUTE,
            value, null, 1, "+05:30", "continuous", false,
        )
    }
}
