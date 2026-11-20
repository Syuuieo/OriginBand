// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.service

import android.content.Context
import android.database.ContentObserver
import android.os.UserManager
import com.originband.link.data.BandStateRepository
import com.originband.link.data.BindingStore
import com.originband.link.data.HealthRecord
import com.originband.link.data.HealthRecordStore
import com.originband.link.data.RawFitnessFileStore
import com.originband.link.data.SessionLog
import com.originband.link.data.SleepStageAlign
import com.originband.link.data.WalkingHeartRate
import com.originband.link.integration.HealthQueueProvider
import com.originband.link.protocol.BandHistoryParser
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/** Replays durable device/account-owned files without occupying the socket or command coordinator. */
class HealthReplay(
    context: Context,
    private val status: Consumer<String>,
    private val capacityChanged: Runnable
) : AutoCloseable {
    private val context: Context = context.applicationContext
    private val records = HealthRecordStore(this.context)
    private val raw = RawFitnessFileStore(this.context)
    private val worker: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "OplusBandHealthReplay") }
    private val requested = AtomicBoolean()
    private val running = AtomicBoolean()

    @Volatile
    private var closed = false
    private var legacyIndexed = false
    private var sleepStagesAligned = false
    private val observer: ContentObserver = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) {
            request()
        }
    }

    init {
        this.context.contentResolver.registerContentObserver(HealthQueueProvider.URI, false, observer)
    }

    fun hasCapacity(): Boolean {
        if (closed) return false
        return try {
            records.hasCapacity()
        } catch (unavailable: RuntimeException) {
            status.accept("HEALTH_STORAGE_UNAVAILABLE")
            throw unavailable
        }
    }

    fun request() {
        if (closed) return
        requested.set(true)
        if (!running.compareAndSet(false, true)) return
        try {
            worker.execute { drain() }
        } catch (_: RejectedExecutionException) {
            running.set(false)
        }
    }

    private fun drain() {
        try {
            while (!closed && requested.getAndSet(false)) replay()
        } catch (failure: Exception) {
            val code = failure.message
            status.accept(
                when (code) {
                    "HEALTH_OUTBOX_FULL" -> "HEALTH_OUTBOX_FULL"
                    "ACCOUNT_CONFIRMATION_REQUIRED" -> "ACCOUNT_CONFIRMATION_REQUIRED"
                    else -> "HEALTH_REPLAY_PAUSED"
                }
            )
        } finally {
            running.set(false)
            if (!closed) {
                capacityChanged.run()
                if (requested.get()) request()
            }
        }
    }

    @Throws(Exception::class)
    private fun replay() {
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return
        if (!legacyIndexed) {
            raw.migrateLegacyFiles()
            legacyIndexed = true
        }
        if (records.confirmedAccountHash() == null) {
            status.accept("ACCOUNT_CONFIRMATION_REQUIRED")
            return
        }
        val reparsed = raw.promoteRejectedSleep()
        val sportReparsed = raw.promoteRejectedSport()
        if (sportReparsed > 0) {
            SessionLog.line(context, "history sport reparsed $sportReparsed")
        }
        if (reparsed > 0) {
            SessionLog.line(context, "history sleep reparsed $reparsed")
        }
        val breathReparsed = raw.refreshSleepBreathMetrics()
        if (breathReparsed > 0) {
            SessionLog.line(context, "history sleep breath enqueued $breathReparsed")
        }
        val restingExtended = raw.refreshRestingHeart()
        if (restingExtended > 0) {
            SessionLog.line(context, "history resting heart extended $restingExtended")
        }
        val binding = BindingStore(context).readIdentity()
        var identity = binding.did()
        if (identity.isBlank()) identity = binding.address().replace(":", "")
        val deviceId = binding.deviceId()
        val measuredDays = HashSet<LocalDate>()
        while (!closed) {
            val files = records.replayFiles(4)
            if (files.isEmpty()) break
            var added = false
            var sawSleep = false
            try {
                for (file in files) {
                    if (closed) return
                    if (deviceId != file.deviceId()) {
                        throw IllegalStateException("HEALTH_COLLECTION_IDENTITY_CHANGED")
                    }
                    val parsed = BandHistoryParser(file.firmware(), file.deviceId(), identity)
                        .parseFile(raw.readFile(file.fileHash()))
                    var indexed = file.recordCount()
                    // A daily report gained its resting heart-rate row. Only a trailing daily
                    // heart value may grow an archived file; anything else is a parser change.
                    if (parsed.measurements.size > indexed
                        && BandHistoryParser.onlyDailyHeartFrom(parsed.measurements, indexed)
                        && records.extendParsedRecordCount(file.fileHash(), parsed.measurements.size)
                    ) {
                        indexed = parsed.measurements.size
                    }
                    if ("PARSED" != parsed.parseStatus || parsed.measurements.size != indexed) {
                        throw IllegalStateException("HEALTH_ARCHIVE_PARSE_CHANGED")
                    }
                    for (index in file.nextRecordIndex() until parsed.measurements.size) {
                        if (closed) return
                        val measurement = parsed.measurements[index]
                        val result = records.enqueueArchivedMeasurement(file.fileHash(), index, measurement)
                        added = added or result.added()
                        if (result.added()) measuredDays.add(localDay(measurement))
                        if ("sleep_interval" == measurement.kind || "sleep_stage" == measurement.kind) {
                            sawSleep = true
                        }
                    }
                }
            } finally {
                if (added) {
                    context.contentResolver.notifyChange(HealthQueueProvider.URI, null)
                    context.contentResolver.notifyChange(HealthQueueProvider.RECORDS_URI, null)
                }
            }
            if (sawSleep) sleepStagesAligned = false
        }
        // Before the sleep pass: a night that cannot be aligned must not hold the heart values back.
        try {
            deriveWalkingHeart(deviceId, identity, measuredDays)
        } catch (failed: Exception) {
            SessionLog.line(context, "history walking deferred " + failed.javaClass.simpleName)
        }
        alignStoredSleep(identity)
        if (!closed) BandStateRepository.refreshStoredSteps(context)
        status.accept("HEALTH_LOCAL_RECORDS_READY")
    }

    @Throws(Exception::class)
    private fun stored(deviceId: String, kind: String, start: Long, end: Long): List<HealthRecord> {
        return records.storedRecords(deviceId, kind, start, end)
    }

    /**
     * Health only shows a walking average when it holds a heart-rate point typed for walking, so
     * each day that gained rows is averaged from its own stored step and heart-rate minutes. The
     * first pass also covers the last month of archived days, which are already fully replayed.
     */
    private fun deriveWalkingHeart(deviceId: String, identity: String, measuredDays: Set<LocalDate>) {
        if (closed) return
        val zone = ZoneId.systemDefault()
        val days = HashSet(measuredDays)
        val prefs = context.getSharedPreferences("oplusband-history", Context.MODE_PRIVATE)
        val backfill = !walkingBackfilled && !prefs.getBoolean("walking-heart-v2", false)
        if (backfill) {
            val today = LocalDate.now(zone)
            for (back in 0 until WALKING_BACKFILL_DAYS) days.add(today.minusDays(back.toLong()))
        }
        if (days.isEmpty()) return
        val parser: BandHistoryParser = try {
            BandHistoryParser("derived", deviceId, identity)
        } catch (_: IllegalArgumentException) {
            SessionLog.line(context, "history walking skipped identity")
            return
        }
        var added = false
        var retry = false
        for (day in days) {
            if (closed) return
            val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            try {
                val average = WalkingHeartRate.average(
                    stored(deviceId, "steps_interval", start, end),
                    stored(deviceId, "heart_rate", start, end),
                    stored(deviceId, "sport_session", start, end)
                ) ?: continue
                // Noon keeps the value inside its own day and out of the night heart-rate window.
                if (records.enqueueMeasurement(
                        parser.dailyHeart("walking", start + DAY_HALF_MS, average, zone.id)
                    ).added()
                ) {
                    added = true
                }
            } catch (skipped: Exception) {
                // An unreadable day is retried by the next pass; the rest of the month still runs.
                if (!retry) {
                    SessionLog.line(
                        context,
                        "history walking failed " + skipped.javaClass.simpleName + " " + skipped.message
                    )
                }
                retry = true
            }
        }
        SessionLog.line(
            context,
            "history walking days=" + days.size + " derived=" + (if (added) 1 else 0) +
                    " retry=" + (if (retry) 1 else 0) + " backfill=" + (if (backfill) 1 else 0)
        )
        if (backfill && !retry && prefs.edit().putBoolean("walking-heart-v2", true).commit()) {
            walkingBackfilled = true
        }
        if (added) {
            context.contentResolver.notifyChange(HealthQueueProvider.URI, null)
            context.contentResolver.notifyChange(HealthQueueProvider.RECORDS_URI, null)
        }
    }

    /** The newest file that still has stages owns the interval. Older files leave that interval in place. */
    @Throws(Exception::class)
    private fun alignStoredSleep(identity: String) {
        if (sleepStagesAligned || closed) return
        var added = false
        val files = records.completedFiles()
        val blocked = HashSet<String>()
        val claimed = HashMap<String, MutableList<SleepStageAlign.Interval>>()
        for (i in files.indices.reversed()) {
            if (closed) return
            val file = files[i]
            if (blocked.contains(file.deviceId())) continue
            val parsed: BandHistoryParser.FileResult = try {
                BandHistoryParser(file.firmware(), file.deviceId(), identity)
                    .parseFile(raw.readFile(file.fileHash()))
            } catch (_: RuntimeException) {
                blocked.add(file.deviceId())
                continue
            }
            if ("PARSED" != parsed.parseStatus) {
                blocked.add(file.deviceId())
                continue
            }
            val intervals = spans(parsed.measurements, file.deviceId(), "sleep_interval")
            val stages = spans(parsed.measurements, file.deviceId(), "sleep_stage")
            val owned = claimed.getOrPut(file.deviceId()) { ArrayList() }
            val covered = SleepStageAlign.withStages(SleepStageAlign.claim(intervals, owned), stages)
            if (covered.isEmpty()) continue
            if (records.retainSleepStages(file.deviceId(), parsed.measurements, covered) > 0) added = true
            owned.addAll(covered)
        }
        sleepStagesAligned = true
        if (added) {
            context.contentResolver.notifyChange(HealthQueueProvider.URI, null)
            context.contentResolver.notifyChange(HealthQueueProvider.RECORDS_URI, null)
        }
    }

    override fun close() {
        closed = true
        context.contentResolver.unregisterContentObserver(observer)
        worker.execute { records.close() }
        worker.shutdown()
    }

    companion object {
        private const val WALKING_BACKFILL_DAYS = 30
        private const val DAY_HALF_MS = 43_200_000L

        @Volatile
        private var walkingBackfilled = false

        private fun localDay(measurement: BandHistoryParser.Measurement): LocalDate {
            val zone = if (measurement.timezone == null) {
                ZoneId.systemDefault()
            } else {
                ZoneId.of(measurement.timezone)
            }
            return Instant.ofEpochMilli(measurement.startMs).atZone(zone).toLocalDate()
        }

        private fun spans(
            parsed: List<BandHistoryParser.Measurement?>?,
            deviceId: String,
            kind: String
        ): List<SleepStageAlign.Interval> {
            val found = ArrayList<SleepStageAlign.Interval>()
            if (parsed == null) return found
            for (measurement in parsed) {
                if (measurement != null && deviceId == measurement.deviceId && kind == measurement.kind) {
                    found.add(SleepStageAlign.Interval(measurement.startMs, measurement.endMs))
                }
            }
            return found
        }
    }
}
