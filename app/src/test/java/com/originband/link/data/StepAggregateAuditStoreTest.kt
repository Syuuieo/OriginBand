// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StepAggregateAuditStoreTest {
    @Rule
    @JvmField
    val folder = TemporaryFolder()

    private fun request(): StepAggregateAuditStore.Request =
        request(ACCOUNT, QUEUED, LocalDate.of(2026, 11, 1), 221)

    private fun review(request: StepAggregateAuditStore.Request, fingerprint: String): StepAggregateReview =
        StepAggregateReview(
            fingerprint, request.accountHash(), request.queuedDevice(),
            request.nativeDevice(), request.date(), request.daySteps().toInt(), 11292, 11292,
        )

    private fun file(): File = File(folder.root, "step-aggregate-audits.json")

    private fun store(): StepAggregateAuditStore = StepAggregateAuditStore(file())

    private fun entry(store: StepAggregateAuditStore, request: StepAggregateAuditStore.Request): StepAggregateAuditStore.Entry =
        store.entries(request.accountHash(), request.queuedDevice())[0]

    @Test
    fun requestRoundTripsAllEightFieldsAndKeepsAccountOutOfDiagnostics() {
        val request = request()
        assertEquals(8, request.toJson().length())
        assertEquals(request, StepAggregateAuditStore.Request.fromJson(request.toJson()))
        assertEquals(64, request.accountHash().length)
        assertTrue(request.key().startsWith(request.accountHash() + ":" + QUEUED + ":"))
        assertFalse(request.toString().contains(ACCOUNT))
    }

    @Test
    fun requestAcceptsBothSupportedMinuteModes() {
        val r = request()
        val negative = StepAggregateAuditStore.Request(
            r.account(), r.queuedDevice(), r.nativeDevice(),
            r.date(), r.startMs(), r.timezone(), r.daySteps(), -1,
        )
        assertEquals(-1, StepAggregateAuditStore.Request.fromJson(negative.toJson()).minuteMode())
    }

    @Test
    @Throws(Exception::class)
    fun requestRejectsCoercionExtraFieldsMissingFieldsAndIntegerOverflow() {
        for (field in listOf("date", "startMs", "daySteps", "minuteMode")) {
            val json = request().toJson().put(field, "1")
            failure("STEP_AUDIT_REQUEST_INVALID") { StepAggregateAuditStore.Request.fromJson(json) }
        }
        val extra = request().toJson().put("approved", true)
        failure("STEP_AUDIT_REQUEST_INVALID") { StepAggregateAuditStore.Request.fromJson(extra) }
        val missing = request().toJson()
        missing.remove("account")
        failure("STEP_AUDIT_REQUEST_INVALID") { StepAggregateAuditStore.Request.fromJson(missing) }
        val decimal = request().toJson().put("daySteps", 221.0)
        failure("STEP_AUDIT_REQUEST_INVALID") { StepAggregateAuditStore.Request.fromJson(decimal) }
        val overflow = request().toJson().put("date", 0x1_0000_0000L + 20261101)
        failure("STEP_AUDIT_REQUEST_INVALID") { StepAggregateAuditStore.Request.fromJson(overflow) }
    }

    @Test
    fun requestRejectsInvalidIdentitiesDatesTimezonesAndDayBoundaries() {
        val r = request()
        for (account in listOf("", " ", "account\n", " leading", "x".repeat(513))) {
            failure("STEP_AUDIT_REQUEST_INVALID") {
                StepAggregateAuditStore.Request(
                    account,
                    r.queuedDevice(), r.nativeDevice(), r.date(), r.startMs(), r.timezone(), 221, 1,
                )
            }
        }
        failure("STEP_AUDIT_REQUEST_INVALID") {
            StepAggregateAuditStore.Request(
                r.account(),
                r.queuedDevice(), r.nativeDevice(), 20260230, r.startMs(), r.timezone(), 221, 1,
            )
        }
        failure("STEP_AUDIT_REQUEST_INVALID") {
            StepAggregateAuditStore.Request(
                r.account(),
                r.queuedDevice(), r.nativeDevice(), r.date(), r.startMs() + 1, r.timezone(), 221, 1,
            )
        }
        failure("STEP_AUDIT_REQUEST_INVALID") {
            StepAggregateAuditStore.Request(
                r.account(),
                r.queuedDevice(), r.nativeDevice(), r.date(), r.startMs(), "Invalid/Zone", 221, 1,
            )
        }
        for (steps in longArrayOf(0, -1, Int.MAX_VALUE.toLong() + 1)) {
            failure("STEP_AUDIT_REQUEST_INVALID") {
                StepAggregateAuditStore.Request(
                    r.account(),
                    r.queuedDevice(), r.nativeDevice(), r.date(), r.startMs(), r.timezone(), steps, 1,
                )
            }
        }
        failure("STEP_AUDIT_REQUEST_INVALID") {
            StepAggregateAuditStore.Request(
                r.account(),
                r.queuedDevice(), r.nativeDevice(), r.date(), r.startMs(), r.timezone(), 221, 2,
            )
        }
    }

    @Test
    fun requestRejectsShortAndLongDstDays() {
        for (day in listOf(LocalDate.of(2026, 3, 8), LocalDate.of(2026, 11, 1))) {
            val date = day.year * 10_000 + day.monthValue * 100 + day.dayOfMonth
            val start = day.atStartOfDay(ZoneId.of("America/New_York")).toInstant().toEpochMilli()
            failure("STEP_AUDIT_REQUEST_INVALID") {
                StepAggregateAuditStore.Request(
                    ACCOUNT,
                    QUEUED, NATIVE, date, start, "America/New_York", 221, 1,
                )
            }
        }
    }

    @Test
    fun scheduledCandidatesAreVisibleBeforeReviewAndScopedToAccountAndDevice() {
        val store = store()
        val request = request()
        assertTrue(
            store.schedule(
                listOf(
                    request,
                    request("other-account", QUEUED, LocalDate.of(2026, 11, 1), 300),
                    request(ACCOUNT, "other-device", LocalDate.of(2026, 11, 1), 400),
                ),
            ),
        )
        assertEquals(1, store.entries(request.accountHash(), QUEUED).size)
        assertNull(entry(store, request).review())
        assertFalse(entry(store, request).approved())
        assertTrue(store.pending(request.accountHash(), QUEUED).isEmpty())
        assertFalse(store.schedule(listOf(request)))
    }

    @Test
    fun identicalScheduleAndRepeatedReportsPreserveApprovalAcrossInstances() {
        val first = store()
        val request = request()
        val review = review(request, FP)
        first.schedule(listOf(request))
        assertEquals(StepAggregateAuditStore.ReportResult(false, true), first.report(request, review))
        first.approve(request.accountHash(), QUEUED, FP)
        val second = store()
        assertFalse(second.schedule(listOf(request)))
        assertTrue(entry(second, request).approved())
        assertEquals(listOf(review), second.pending(request.accountHash(), QUEUED))
        assertEquals(StepAggregateAuditStore.ReportResult(true, false), second.report(request, review))
    }

    @Test
    fun changedRequestClearsReviewAndApprovalAndRejectsStaleReportAndCompletion() {
        val store = store()
        val old = request()
        val oldReview = review(old, FP)
        store.schedule(listOf(old))
        store.report(old, oldReview)
        store.approve(old.accountHash(), QUEUED, FP)
        val changed = request(ACCOUNT, QUEUED, LocalDate.of(2026, 11, 1), 222)
        assertTrue(store.schedule(listOf(changed)))
        assertEquals(StepAggregateAuditStore.ReportResult(false, false), store.report(old, oldReview))
        assertFalse(store.complete(old, oldReview))
        assertEquals(changed, entry(store, changed).request())
        assertNull(entry(store, changed).review())
        assertFalse(entry(store, changed).approved())
    }

    @Test
    fun changedFingerprintAndNullReportClearApproval() {
        val store = store()
        val request = request()
        store.schedule(listOf(request))
        store.report(request, review(request, FP))
        store.approve(request.accountHash(), QUEUED, FP)
        assertEquals(StepAggregateAuditStore.ReportResult(false, true), store.report(request, review(request, OTHER_FP)))
        assertFalse(entry(store, request).approved())
        failure("STEP_AUDIT_CHANGED") { store.approve(request.accountHash(), QUEUED, FP) }
        store.approve(request.accountHash(), QUEUED, OTHER_FP)
        assertEquals(StepAggregateAuditStore.ReportResult(false, true), store.report(request, null))
        assertNull(entry(store, request).review())
        assertFalse(entry(store, request).approved())
        assertEquals(StepAggregateAuditStore.ReportResult(false, false), store.report(request, null))
    }

    @Test
    fun reviewIdentityFieldsMustMatchTheScheduledRequest() {
        val store = store()
        val request = request()
        store.schedule(listOf(request))
        val mismatches = listOf(
            StepAggregateReview(FP, "b".repeat(64), QUEUED, NATIVE, request.date(), 221, 1000, 1000),
            StepAggregateReview(FP, request.accountHash(), "other-device", NATIVE, request.date(), 221, 1000, 1000),
            StepAggregateReview(FP, request.accountHash(), QUEUED, "other-native", request.date(), 221, 1000, 1000),
            StepAggregateReview(FP, request.accountHash(), QUEUED, NATIVE, 20261102, 221, 1000, 1000),
            StepAggregateReview(FP, request.accountHash(), QUEUED, NATIVE, request.date(), 222, 1000, 1000),
        )
        for (review in mismatches) {
            failure("STEP_AUDIT_REVIEW_MISMATCH") { store.report(request, review) }
            assertNull(entry(store, request).review())
        }
    }

    @Test
    fun approvalIsScopedAndCompletionRequiresCurrentApprovedEvidence() {
        val store = store()
        val request = request()
        val review = review(request, FP)
        store.schedule(listOf(request))
        store.report(request, review)
        assertFalse(store.complete(request, review))
        failure("STEP_AUDIT_CHANGED") { store.approve("b".repeat(64), QUEUED, FP) }
        failure("STEP_AUDIT_CHANGED") { store.approve(request.accountHash(), "other-device", FP) }
        store.approve(request.accountHash(), QUEUED, FP)
        assertFalse(store.complete(request, review(request, OTHER_FP)))
        assertTrue(store.complete(request, review))
        assertEquals(request, entry(store, request).request())
        assertNull(entry(store, request).review())
        assertFalse(entry(store, request).approved())
        assertFalse(store.complete(request, review))
        assertEquals(StepAggregateAuditStore.ReportResult(false, true), store.report(request, review))
    }

    @Test
    @Throws(Exception::class)
    fun keepsTheNewestNinetyThreeCandidatesInDescendingDateOrder() {
        val store = store()
        val requests = ArrayList<StepAggregateAuditStore.Request>()
        val newest = LocalDate.of(2026, 11, 1)
        for (i in 104 downTo 0) requests.add(request(ACCOUNT, QUEUED, newest.minusDays(i.toLong()), 221))
        assertTrue(store.schedule(requests))
        val entries = store.entries(request().accountHash(), QUEUED)
        assertEquals(93, entries.size)
        assertEquals(20261101, entries[0].request().date())
        assertEquals(request(ACCOUNT, QUEUED, newest.minusDays(92), 221), entries[92].request())
        assertEquals(93, JSONObject(text(file())).length())
        assertFalse(store.schedule(listOf(request(ACCOUNT, QUEUED, newest.minusDays(200), 221))))
    }

    @Test
    @Throws(Exception::class)
    fun allStoreOperationsReportCorruptionAndPreserveTheOriginalFile() {
        val store = store()
        val request = request()
        val review = review(request, FP)
        for (broken in listOf("", "{", "{} trailing", "[]", "{\"foreign\":true}")) {
            save(file(), broken)
            failure("STEP_AUDIT_CORRUPT") { store.schedule(listOf(request)) }
            failure("STEP_AUDIT_CORRUPT") { store.entries(request.accountHash(), QUEUED) }
            failure("STEP_AUDIT_CORRUPT") { store.pending(request.accountHash(), QUEUED) }
            failure("STEP_AUDIT_CORRUPT") { store.report(request, review) }
            failure("STEP_AUDIT_CORRUPT") { store.approve(request.accountHash(), QUEUED, FP) }
            failure("STEP_AUDIT_CORRUPT") { store.complete(request, review) }
            assertEquals(broken, text(file()))
        }
    }

    @Test
    @Throws(Exception::class)
    fun persistedApprovalFingerprintMustMatchPersistedReview() {
        val store = store()
        val request = request()
        store.schedule(listOf(request))
        store.report(request, review(request, FP))
        store.approve(request.accountHash(), QUEUED, FP)
        val data = JSONObject(text(file()))
        data.getJSONObject(request.key()).put("approvedFingerprint", OTHER_FP)
        save(file(), data.toString())
        failure("STEP_AUDIT_CORRUPT") { store.entries(request.accountHash(), QUEUED) }
    }

    @Test
    @Throws(Exception::class)
    fun failedAtomicWritePreservesPriorApproval() {
        val store = store()
        val request = request()
        store.schedule(listOf(request))
        store.report(request, review(request, FP))
        store.approve(request.accountHash(), QUEUED, FP)
        val before = text(file())
        val temporary = File(file().path + ".tmp")
        assertTrue(temporary.mkdir())
        save(File(temporary, "occupied"), "occupied")
        failure("STEP_AUDIT_STORAGE_FAILED") { store.report(request, review(request, OTHER_FP)) }
        assertEquals(before, text(file()))
        assertTrue(entry(store, request).approved())
    }

    @Test
    @Throws(Exception::class)
    fun concurrentInstancesSerializeReadModifyCommit() {
        val first = store()
        val second = store()
        val pool = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        try {
            val a = pool.submit(Callable<Unit> {
                ready.countDown()
                start.await()
                for (i in 0 until 20) {
                    first.schedule(
                        listOf(
                            request(ACCOUNT, QUEUED, LocalDate.of(2026, 11, 1).minusDays(i.toLong()), 221),
                        ),
                    )
                }
            })
            val b = pool.submit(Callable<Unit> {
                ready.countDown()
                start.await()
                for (i in 0 until 20) {
                    second.schedule(
                        listOf(
                            request("other-account", QUEUED, LocalDate.of(2026, 11, 1).minusDays(i.toLong()), 222),
                        ),
                    )
                }
            })
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            a.get(10, TimeUnit.SECONDS)
            b.get(10, TimeUnit.SECONDS)
            assertEquals(20, first.entries(request().accountHash(), QUEUED).size)
            assertEquals(
                20,
                first.entries(
                    request("other-account", QUEUED, LocalDate.of(2026, 11, 1), 222).accountHash(),
                    QUEUED,
                ).size,
            )
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
    }

    companion object {
        private const val ACCOUNT = "account-a"
        private const val QUEUED = "miband11_device-a"
        private const val NATIVE = "02:00:00:00:00:11"
        private val FP = "a".repeat(64)
        private val OTHER_FP = "b".repeat(64)

        private fun request(account: String, queued: String, day: LocalDate, steps: Long): StepAggregateAuditStore.Request {
            val date = day.year * 10_000 + day.monthValue * 100 + day.dayOfMonth
            return StepAggregateAuditStore.Request(
                account, queued, NATIVE, date,
                day.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(),
                "Asia/Shanghai", steps, 1,
            )
        }

        @Throws(Exception::class)
        private fun text(file: File): String =
            String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)

        @Throws(Exception::class)
        private fun save(file: File, text: String) {
            Files.write(file.toPath(), text.toByteArray(StandardCharsets.UTF_8))
        }

        private fun failure(code: String, action: () -> Unit) {
            try {
                action()
                fail("Expected $code")
            } catch (failure: IllegalArgumentException) {
                assertEquals(code, failure.message)
            } catch (failure: IllegalStateException) {
                assertEquals(code, failure.message)
            }
        }
    }
}
