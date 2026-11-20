// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import com.originband.link.data.StepAggregateReview.Daily
import com.originband.link.data.StepAggregateReview.SavedMinute
import com.originband.link.data.StepAggregateReview.SourceMinute
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.util.HexFormat

class StepAggregateReviewTest {
    private class Fixture {
        var account: String? = ACCOUNT
        var queued: String? = QUEUED
        var nativeDevice: String? = NATIVE
        var zone: String? = "Asia/Shanghai"
        var date = DATE
        var minuteMode = 1
        var start = START
        var steps = 221L
        var now = END
        val sources = ArrayList<SourceMinute?>()
        val saved = ArrayList<SavedMinute?>()
        val days = ArrayList<Daily?>()

        init {
            for (i in 0 until 1440) {
                val at = START + i * 60_000L
                sources.add(
                    SourceMinute(
                        QUEUED, at, at + 60_000L,
                        if (i == 20) 221 else 0,
                        if (i == 20) 60 else 0,
                        if (i == 20) 117 else 0,
                        "+08:00",
                    ),
                )
            }
            saved.add(SavedMinute(ACCOUNT, NATIVE, AT, AT + 60_000L, 221, 60000, 117, 1, 1, "+0800"))
            days.add(Daily(ACCOUNT, NATIVE, DATE, -3, 221, 60000, 117))
            days.add(Daily(ACCOUNT, NATIVE, DATE, 1, 221, 60000, 117))
            days.add(Daily(ACCOUNT, null, DATE, -2, 11292, 60000, 117))
            days.add(Daily(ACCOUNT, null, DATE, -4, 11292, 60000, 61))
        }

        fun assess(): StepAggregateReview? = StepAggregateReview.assess(
            account, queued, nativeDevice, date, start, zone,
            steps, minuteMode, now, sources, saved, days,
        )
    }

    @Test
    @Throws(Exception::class)
    fun novemberFirstEvidenceProducesManualReviewOfBothOldTotals() {
        val f = Fixture()
        val result = f.assess()!!
        assertNotNull(result)
        assertEquals(221, result.expectedSteps())
        assertEquals(11292, result.combinedSteps())
        assertEquals(11292, result.phoneSteps())
        assertEquals(DATE, result.date())
        assertEquals(QUEUED, result.queuedDevice())
        assertEquals(NATIVE, result.nativeDevice())
        assertEquals(
            HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(ACCOUNT.toByteArray(StandardCharsets.UTF_8)),
            ),
            result.accountHash(),
        )
        assertTrue(result.fingerprint().matches(Regex("[0-9a-f]{64}")))
        assertEquals(1440, f.sources.size)
        assertEquals(11292L, f.days[2]!!.steps())
    }

    @Test
    fun watchMinutesUseTheNativeNegativeOneMode() {
        val f = Fixture()
        f.minuteMode = -1
        val minute = f.saved[0]!!
        f.saved[0] = SavedMinute(
            minute.account(), minute.device(), minute.startMs(), minute.endMs(),
            minute.steps(), minute.calories(), minute.distance(), -1, minute.display(), minute.timezone(),
        )
        val day = f.days[1]!!
        f.days[1] = Daily(day.account(), day.device(), day.date(), -1, day.steps(), day.calories(), day.distance())
        assertNotNull(f.assess())
        f.minuteMode = 2
        assertNull(f.assess())
    }

    @Test
    fun zeroSourceMinutesMayHaveExactZeroSavedRows() {
        val f = Fixture()
        f.saved.add(SavedMinute(ACCOUNT, NATIVE, START, START + 60000, 0, 0, 0, 1, 1, "Asia/Shanghai"))
        assertNotNull(f.assess())
        f.saved[1] = SavedMinute(ACCOUNT, NATIVE, START, START + 60000, 1, 0, 0, 1, 1, "+08:00")
        assertNull(f.assess())
    }

    @Test
    fun namedMinuteDailyIsOptionalAndEmptyAggregateDeviceIsAccepted() {
        val f = Fixture()
        f.days.removeAt(1)
        f.days[1] = Daily(ACCOUNT, "", DATE, -2, 11292, 60000, 117)
        assertNotNull(f.assess())
    }

    @Test
    fun eitherHighAggregateProducesReviewIncludingHighPhoneSource() {
        val f = Fixture()
        f.days[2] = Daily(ACCOUNT, null, DATE, -2, 221, 60000, 117)
        assertNotNull(f.assess())
        assertEquals(11292, f.assess()!!.phoneSteps())
        f.days[3] = Daily(ACCOUNT, null, DATE, -4, 221, 60000, 61)
        assertNull(f.assess())
        f.days[2] = Daily(ACCOUNT, null, DATE, -2, 222, 60000, 117)
        assertNotNull(f.assess())
    }

    @Test
    fun todayAndFutureDatesWaitForWholeDayToEnd() {
        val f = Fixture()
        f.now = END - 1
        assertNull(f.assess())
        f.now = START
        assertNull(f.assess())
        f.now = START - 1
        assertNull(f.assess())
        f.now = END
        assertNotNull(f.assess())
    }

    @Test
    fun daylightSavingDaysUseConservativeTwentyFourHourBoundary() {
        for (date in intArrayOf(20260308, 20261101)) {
            val f = Fixture()
            f.date = date
            f.zone = "America/Los_Angeles"
            val day = LocalDate.of(date / 10000, date / 100 % 100, date % 100)
            f.start = day.atStartOfDay(ZoneId.of(f.zone)).toInstant().toEpochMilli()
            f.now = f.start + 2 * 86_400_000L
            assertNull(f.assess())
        }
    }

    @Test
    fun dateZoneAndMidnightMustMatchExactly() {
        var f = Fixture(); f.date = 20260230; assertNull(f.assess())
        f = Fixture(); f.zone = "not-a-zone"; assertNull(f.assess())
        f = Fixture(); f.zone = null; assertNull(f.assess())
        f = Fixture(); f.start++; assertNull(f.assess())
        f = Fixture(); f.start += 3600000; assertNull(f.assess())
        f = Fixture(); f.start = -1; assertNull(f.assess())
        f = Fixture(); f.date = 20261031; assertNull(f.assess())
    }

    @Test
    fun crossMidnightSavedSourcesKeepTheDayPendingReview() {
        for (device in listOf(NATIVE, "phone")) {
            var f = Fixture()
            f.saved.add(
                SavedMinute(
                    ACCOUNT, device, START - 30_000, START + 30_000,
                    20, 1000, 10, 1, 1, "+08:00",
                ),
            )
            assertNull(f.assess())
            f = Fixture()
            f.saved.add(
                SavedMinute(
                    ACCOUNT, device, END - 30_000, END + 30_000,
                    20, 1000, 10, 1, 1, "+08:00",
                ),
            )
            assertNull(f.assess())
        }
    }

    @Test
    fun sourceIncludesEveryMinuteExactlyOnce() {
        var f = Fixture(); f.sources.removeAt(100); assertNull(f.assess())
        f = Fixture(); f.sources[100] = f.sources[101]; assertNull(f.assess())
        f = Fixture(); f.sources.add(f.sources[100]); assertNull(f.assess())
        f = Fixture(); f.sources[100] = null; assertNull(f.assess())
    }

    @Test
    fun sourceIdentityTimezoneAndMinuteShapeAreRequired() {
        val invalid = listOf(
            SourceMinute("other", AT, AT + 60000, 221, 60, 117, "+08:00"),
            SourceMinute(QUEUED, AT, AT + 60000, 221, 60, 117, "+09:00"),
            SourceMinute(QUEUED, AT, AT + 60000, 221, 60, 117, null),
            SourceMinute(QUEUED, AT + 1, AT + 60001, 221, 60, 117, "+08:00"),
            SourceMinute(QUEUED, AT, AT + 59999, 221, 60, 117, "+08:00"),
            SourceMinute(QUEUED, END, END + 60000, 221, 60, 117, "+08:00"),
            SourceMinute(QUEUED, START - 60000, START, 221, 60, 117, "+08:00"),
            SourceMinute(QUEUED, Long.MAX_VALUE, Long.MIN_VALUE, 221, 60, 117, "+08:00"),
        )
        for (source in invalid) {
            val f = Fixture(); f.sources[20] = source; assertNull(f.assess())
        }
    }

    @Test
    fun sourceValuesFitHostMinuteLimitsAndUseCheckedTotals() {
        for (values in arrayOf(
            longArrayOf(-1, 60, 117), longArrayOf(351, 60, 117), longArrayOf(221, -1, 117),
            longArrayOf(221, 66, 117), longArrayOf(221, 60, -1), longArrayOf(221, 60, 1051),
            longArrayOf(Long.MAX_VALUE, 60, 117), longArrayOf(221, Long.MAX_VALUE, 117),
            longArrayOf(221, 60, Long.MAX_VALUE),
        )) {
            val f = Fixture()
            f.sources[20] = SourceMinute(QUEUED, AT, AT + 60000, values[0], values[1], values[2], "+08:00")
            assertNull(f.assess())
        }
        var f = Fixture(); f.steps = -1; assertNull(f.assess())
        f = Fixture(); f.steps = Long.MAX_VALUE; assertNull(f.assess())
        f = Fixture(); f.steps = Int.MAX_VALUE.toLong() + 1; assertNull(f.assess())
    }

    @Test
    fun exactHostMinuteUpperBoundsAreAccepted() {
        val f = Fixture()
        f.steps = 350
        f.sources[20] = SourceMinute(QUEUED, AT, AT + 60000, 350, 65, 1050, "+08:00")
        f.saved[0] = SavedMinute(ACCOUNT, NATIVE, AT, AT + 60000, 350, 65000, 1050, 1, 1, "+08:00")
        f.days[0] = Daily(ACCOUNT, NATIVE, DATE, -3, 350, 65000, 1050)
        f.days[1] = Daily(ACCOUNT, NATIVE, DATE, 1, 350, 65000, 1050)
        assertNotNull(f.assess())
    }

    @Test
    fun caloriesOnlyAndDistanceOnlyMinutesRequireSavedEvidence() {
        for (extra in arrayOf(longArrayOf(1, 0), longArrayOf(0, 1))) {
            val f = Fixture()
            f.sources[21] = SourceMinute(QUEUED, AT + 60000, AT + 120000, 0, extra[0], extra[1], "+08:00")
            for (i in 0 until 2) {
                val day = f.days[i]!!
                f.days[i] = Daily(ACCOUNT, NATIVE, DATE, day.mode(), 221, 60000 + extra[0] * 1000, 117 + extra[1])
            }
            assertNull(f.assess())
            f.saved.add(SavedMinute(ACCOUNT, NATIVE, AT + 60000, AT + 120000, 0, extra[0] * 1000, extra[1], 1, 1, "+08:00"))
            assertNotNull(f.assess())
        }
    }

    @Test
    fun sourceDailyTotalAgreesWithMinutesAndHasPositiveSteps() {
        var f = Fixture(); f.steps = 11292; assertNull(f.assess())
        f = Fixture(); f.steps = 220; assertNull(f.assess())
        f = Fixture()
        f.steps = 0
        f.sources[20] = SourceMinute(QUEUED, AT, AT + 60000, 0, 0, 0, "+08:00")
        assertNull(f.assess())
    }

    @Test
    fun savedNonzeroMinutesMatchSourceExactly() {
        var f = Fixture(); f.saved.clear(); assertNull(f.assess())
        for (values in arrayOf(
            longArrayOf(220, 60000, 117), longArrayOf(221, 60, 117), longArrayOf(221, 60001, 117),
            longArrayOf(221, 60000, 116), longArrayOf(-1, 60000, 117), longArrayOf(221, -1, 117),
            longArrayOf(221, 60000, -1), longArrayOf(Long.MAX_VALUE, 60000, 117),
            longArrayOf(221, Long.MAX_VALUE, 117), longArrayOf(221, 60000, Long.MAX_VALUE),
        )) {
            f = Fixture()
            f.saved[0] = SavedMinute(ACCOUNT, NATIVE, AT, AT + 60000, values[0], values[1], values[2], 1, 1, "+08:00")
            assertNull(f.assess())
        }
    }

    @Test
    fun savedAccountDeviceModeDisplayTimezoneAndMinuteShapeAreRequired() {
        val invalid = listOf(
            SavedMinute("other", NATIVE, AT, AT + 60000, 221, 60000, 117, 1, 1, "+08:00"),
            SavedMinute(ACCOUNT, "other", AT, AT + 60000, 221, 60000, 117, 1, 1, "+08:00"),
            SavedMinute(ACCOUNT, NATIVE, AT, AT + 60000, 221, 60000, 117, 2, 1, "+08:00"),
            SavedMinute(ACCOUNT, NATIVE, AT, AT + 60000, 221, 60000, 117, 1, 0, "+08:00"),
            SavedMinute(ACCOUNT, NATIVE, AT, AT + 60000, 221, 60000, 117, 1, 1, "+09:00"),
            SavedMinute(ACCOUNT, NATIVE, AT + 1, AT + 60001, 221, 60000, 117, 1, 1, "+08:00"),
            SavedMinute(ACCOUNT, NATIVE, AT, AT + 59999, 221, 60000, 117, 1, 1, "+08:00"),
            SavedMinute(ACCOUNT, NATIVE, END, END + 60000, 221, 60000, 117, 1, 1, "+08:00"),
        )
        for (row in invalid) {
            val f = Fixture(); f.saved[0] = row; assertNull(f.assess())
        }
        var f = Fixture(); f.saved.add(f.saved[0]); assertNull(f.assess())
        f = Fixture(); f.saved.add(null); assertNull(f.assess())
    }

    @Test
    fun extraPhoneOrWearableSavedRowsExcludeMixedSources() {
        for (other in listOf("phone", "another-band")) {
            val f = Fixture()
            f.saved.add(SavedMinute(ACCOUNT, other, START, START + 60000, 0, 0, 0, 1, 1, "+08:00"))
            assertNull(f.assess())
        }
    }

    @Test
    fun dailySetContainsUniqueDeviceCombinedAndPhoneRows() {
        for (missing in intArrayOf(0, 2, 3)) {
            val f = Fixture(); f.days.removeAt(missing); assertNull(f.assess())
        }
        var f = Fixture(); f.days[1] = f.days[0]; assertNull(f.assess())
        f = Fixture(); f.days[3] = f.days[2]; assertNull(f.assess())
        f = Fixture(); f.days.add(f.days[0]); assertNull(f.assess())
        f = Fixture(); f.days[1] = null; assertNull(f.assess())
    }

    @Test
    fun dailyAccountDateAndAllNamedSourcesAreExact() {
        val invalid = listOf(
            Daily("other", NATIVE, DATE, -3, 221, 60000, 117),
            Daily(ACCOUNT, NATIVE, 20261031, -3, 221, 60000, 117),
            Daily(ACCOUNT, "phone", DATE, -3, 221, 60000, 117),
            Daily(ACCOUNT, "another-band", DATE, -3, 221, 60000, 117),
            Daily(ACCOUNT, NATIVE, DATE, 2, 221, 60000, 117),
            Daily(ACCOUNT, null, DATE, -3, 221, 60000, 117),
            Daily(ACCOUNT, NATIVE, DATE, -3, 222, 60000, 117),
            Daily(ACCOUNT, NATIVE, DATE, -3, 221, 60001, 117),
            Daily(ACCOUNT, NATIVE, DATE, -3, 221, 60000, 118),
        )
        for (row in invalid) {
            val f = Fixture(); f.days[0] = row; assertNull(f.assess())
        }
        var f = Fixture()
        f.days[1] = Daily(ACCOUNT, NATIVE, DATE, 1, 11292, 60000, 117)
        assertNull(f.assess())
        f = Fixture(); f.days[2] = Daily(ACCOUNT, " ", DATE, -2, 11292, 60000, 117)
        assertNull(f.assess())
    }

    @Test
    fun dailyMetricRangesIncludeAggregateFields() {
        for (values in arrayOf(
            longArrayOf(-1, 60000, 117), longArrayOf(11292, -1, 117), longArrayOf(11292, 60000, -1),
            longArrayOf(Int.MAX_VALUE.toLong() + 1, 60000, 117),
            longArrayOf(11292, Long.MAX_VALUE, 117),
            longArrayOf(11292, 60000, Long.MAX_VALUE),
        )) {
            val f = Fixture()
            f.days[2] = Daily(ACCOUNT, null, DATE, -2, values[0], values[1], values[2])
            assertNull(f.assess())
        }
        val f = Fixture()
        f.days[2] = Daily(ACCOUNT, null, DATE, -2, Int.MAX_VALUE.toLong(), Int.MAX_VALUE.toLong(), Int.MAX_VALUE.toLong())
        assertNotNull(f.assess())
    }

    @Test
    fun emptyIdentityAndUnsupportedMinuteModeExcludeReports() {
        var f = Fixture(); f.account = " "; assertNull(f.assess())
        f = Fixture(); f.queued = ""; assertNull(f.assess())
        f = Fixture(); f.nativeDevice = null; assertNull(f.assess())
        f = Fixture(); f.minuteMode = -3; assertNull(f.assess())
        f = Fixture(); f.minuteMode = 0; assertNull(f.assess())
    }

    @Test
    fun fingerprintIsStableAcrossInputOrderAndReviewTime() {
        val f = splitFixture()
        val before = f.assess()!!.fingerprint()
        f.sources.reverse()
        f.saved.reverse()
        f.days.reverse()
        f.now += 999999
        assertEquals(before, f.assess()!!.fingerprint())
    }

    @Test
    fun fingerprintBindsAccountsAndBothDeviceIdentities() {
        val f = Fixture()
        val original = f.assess()!!
        f.account = "account-2"
        f.saved.replaceAll { r ->
            val row = r!!
            SavedMinute(f.account, row.device(), row.startMs(), row.endMs(), row.steps(), row.calories(), row.distance(), row.mode(), row.display(), row.timezone())
        }
        f.days.replaceAll { r ->
            val row = r!!
            Daily(f.account, row.device(), row.date(), row.mode(), row.steps(), row.calories(), row.distance())
        }
        var changed = f.assess()!!
        assertNotEquals(original.accountHash(), changed.accountHash())
        assertNotEquals(original.fingerprint(), changed.fingerprint())
        f.queued = "different-queued"
        f.sources.replaceAll { r ->
            val row = r!!
            SourceMinute(f.queued, row.startMs(), row.endMs(), row.steps(), row.calories(), row.distance(), row.timezone())
        }
        assertNotEquals(changed.fingerprint(), f.assess()!!.fingerprint())
        changed = f.assess()!!
        f.nativeDevice = "different-native"
        f.saved.replaceAll { r ->
            val row = r!!
            SavedMinute(f.account, f.nativeDevice, row.startMs(), row.endMs(), row.steps(), row.calories(), row.distance(), row.mode(), row.display(), row.timezone())
        }
        f.days.replaceAll { r ->
            val row = r!!
            Daily(f.account, if (row.device() == null) null else f.nativeDevice, row.date(), row.mode(), row.steps(), row.calories(), row.distance())
        }
        assertNotEquals(changed.fingerprint(), f.assess()!!.fingerprint())
    }

    @Test
    fun fingerprintBindsMinuteVectorSavedEvidenceAndAggregateValues() {
        var f = Fixture()
        val original = f.assess()!!.fingerprint()
        f.days[2] = Daily(ACCOUNT, null, DATE, -2, 11293, 60000, 117)
        assertNotEquals(original, f.assess()!!.fingerprint())
        f = Fixture()
        f.saved.add(SavedMinute(ACCOUNT, NATIVE, START, START + 60000, 0, 0, 0, 1, 1, "+08:00"))
        assertNotEquals(original, f.assess()!!.fingerprint())
        assertNotEquals(original, splitFixture().assess()!!.fingerprint())
        f = Fixture(); f.zone = "+08:00"
        assertNotEquals(original, f.assess()!!.fingerprint())
    }

    @Test
    fun fingerprintBindsLocalDateAndDayStart() {
        val f = Fixture()
        val original = f.assess()!!.fingerprint()
        f.date = 20261102; f.start += 86400000; f.now += 86400000
        f.sources.replaceAll { r ->
            val row = r!!
            SourceMinute(row.device(), row.startMs() + 86400000, row.endMs() + 86400000, row.steps(), row.calories(), row.distance(), row.timezone())
        }
        f.saved.replaceAll { r ->
            val row = r!!
            SavedMinute(row.account(), row.device(), row.startMs() + 86400000, row.endMs() + 86400000, row.steps(), row.calories(), row.distance(), row.mode(), row.display(), row.timezone())
        }
        f.days.replaceAll { r ->
            val row = r!!
            Daily(row.account(), row.device(), f.date, row.mode(), row.steps(), row.calories(), row.distance())
        }
        assertNotNull(f.assess())
        assertNotEquals(original, f.assess()!!.fingerprint())
    }

    @Test
    @Throws(Exception::class)
    fun jsonRoundTripPreservesExactReview() {
        val review = Fixture().assess()!!
        assertEquals(review, StepAggregateReview.fromJson(JSONObject(review.toJson().toString())))
        val asLongs = review.toJson().put("date", DATE.toLong()).put("combinedSteps", 11292L)
        assertEquals(review, StepAggregateReview.fromJson(asLongs))
    }

    @Test
    @Throws(Exception::class)
    fun jsonRequiresExactFieldsAndIntegralValues() {
        val json = Fixture().assess()!!.toJson().put("extra", true)
        assertThrows(IllegalArgumentException::class.java) { StepAggregateReview.fromJson(json) }
        val missing = Fixture().assess()!!.toJson()
        missing.remove("phoneSteps")
        assertThrows(IllegalArgumentException::class.java) { StepAggregateReview.fromJson(missing) }
        for (key in listOf("date", "expectedSteps", "combinedSteps", "phoneSteps")) {
            for (invalid in listOf(-1, Long.MAX_VALUE, 1.5, "221", true, JSONObject.NULL)) {
                val bad = Fixture().assess()!!.toJson().put(key, invalid)
                assertThrows(IllegalArgumentException::class.java) { StepAggregateReview.fromJson(bad) }
            }
        }
        assertThrows(IllegalArgumentException::class.java) { StepAggregateReview.fromJson(null) }
    }

    @Test
    @Throws(Exception::class)
    fun jsonValidatesHashesIdentitiesDateAndReviewRange() {
        for (key in listOf("fingerprint", "accountHash", "queuedDevice", "nativeDevice")) {
            for (invalid in listOf("", " ", 1, JSONObject.NULL)) {
                val bad = Fixture().assess()!!.toJson().put(key, invalid)
                assertThrows(IllegalArgumentException::class.java) { StepAggregateReview.fromJson(bad) }
            }
        }
        for (key in listOf("fingerprint", "accountHash")) {
            val bad = Fixture().assess()!!.toJson().put(key, "F".repeat(64))
            assertThrows(IllegalArgumentException::class.java) { StepAggregateReview.fromJson(bad) }
        }
        val badDate = Fixture().assess()!!.toJson().put("date", 20260230)
        assertThrows(RuntimeException::class.java) { StepAggregateReview.fromJson(badDate) }
        val zero = Fixture().assess()!!.toJson().put("expectedSteps", 0)
        assertThrows(IllegalArgumentException::class.java) { StepAggregateReview.fromJson(zero) }
        val equal = Fixture().assess()!!.toJson().put("combinedSteps", 221).put("phoneSteps", 221)
        assertThrows(IllegalArgumentException::class.java) { StepAggregateReview.fromJson(equal) }
    }

    companion object {
        private const val ACCOUNT = "account-1"
        private const val QUEUED = "queued-band"
        private const val NATIVE = "02:00:00:00:00:11"
        private const val DATE = 20261101
        private const val START = 1793462400000L
        private const val END = START + 86_400_000L
        private const val AT = START + 20 * 60_000L

        private fun splitFixture(): Fixture {
            val f = Fixture()
            f.sources[20] = SourceMinute(QUEUED, AT, AT + 60000, 200, 50, 100, "+08:00")
            f.sources[21] = SourceMinute(QUEUED, AT + 60000, AT + 120000, 21, 10, 17, "Asia/Shanghai")
            f.saved[0] = SavedMinute(ACCOUNT, NATIVE, AT, AT + 60000, 200, 50000, 100, 1, 1, "+0800")
            f.saved.add(SavedMinute(ACCOUNT, NATIVE, AT + 60000, AT + 120000, 21, 10000, 17, 1, 1, "+08:00"))
            return f
        }
    }
}
