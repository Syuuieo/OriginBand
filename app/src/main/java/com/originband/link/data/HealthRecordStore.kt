// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.originband.link.host.HostIdentity
import com.originband.link.protocol.BandHistoryParser.Measurement
import org.json.JSONException
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/** CE measurement history, revision ledger, bounded outbox and durable archive ownership. */
class HealthRecordStore(context: Context) :
    SQLiteOpenHelper(requireCredentialProtected(context), "health-outbox.db", null, 2) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        if (!db.isReadOnly) {
            try {
                db.execSQL("PRAGMA synchronous=NORMAL")
            } catch (_: Exception) {
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE binding (id INTEGER PRIMARY KEY CHECK(id=1), account_hash TEXT NOT NULL, paused INTEGER NOT NULL)",
        )
        createOutbox(db)
        db.execSQL(
            "CREATE TABLE revisions (account_hash TEXT NOT NULL, record_id TEXT NOT NULL, " +
                "device_id TEXT NOT NULL, last_fingerprint TEXT NOT NULL, last_revision INTEGER NOT NULL, " +
                "PRIMARY KEY(account_hash, record_id))",
        )
        createHistory(db)
    }

    /** SQLiteOpenHelper runs this entire migration and version change in its upgrade transaction. */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion != 1 || newVersion != 2) throw IllegalStateException("HEALTH_OUTBOX_MIGRATION_REQUIRED")
        db.execSQL("ALTER TABLE records RENAME TO records_v1")
        createOutbox(db)
        createHistory(db)
        try {
            db.rawQuery(
                "SELECT r.account_hash, r.record_id, r.revision, r.device_id, r.payload " +
                    "FROM records_v1 r WHERE r.revision=(SELECT MAX(v.revision) FROM records_v1 v " +
                    "WHERE v.account_hash=r.account_hash AND v.record_id=r.record_id)",
                null,
            ).use { rows ->
                while (rows.moveToNext()) {
                    val json = JSONObject(rows.getString(4))
                    val id = rows.getString(1) + ":continuous"
                    json.put("recordId", id)
                    json.put("measurementMode", "continuous")
                    json.put("complete", false)
                    val payload = json.toString()
                    val kind = json.getString("kind")
                    val measurement = measurementRow(
                        rows.getString(0), id, rows.getInt(2),
                        rows.getString(3), kind, json.getLong("startMs"), json.getLong("endMs"), payload,
                    )
                    db.insertOrThrow("measurements", null, measurement)
                    if (isHostKind(kind)) {
                        db.insertOrThrow(
                            "records",
                            null,
                            outboxRow(
                                rows.getString(0), id, rows.getInt(2),
                                rows.getString(3), payload, payload.toByteArray(StandardCharsets.UTF_8).size,
                            ),
                        )
                    }
                }
            }
        } catch (invalid: JSONException) {
            throw IllegalStateException("HEALTH_OUTBOX_MIGRATION_INVALID_PAYLOAD", invalid)
        }
        db.execSQL("UPDATE revisions SET record_id=record_id || ':continuous'")
        db.execSQL("DROP TABLE records_v1")
    }

    /** Unsent archives and the outbox follow the current account. Stored measurements stay put. */
    @Synchronized
    fun confirmAccountHash(hash: String?) {
        if (hash == null || !HEX64_REGEX.matches(hash)) throw IllegalArgumentException("ACCOUNT_HASH_INVALID")
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues(3).apply {
                put("id", 1)
                put("account_hash", hash)
                put("paused", 0)
            }
            if (db.insertWithOnConflict("binding", null, values, SQLiteDatabase.CONFLICT_REPLACE) < 0) {
                throw IllegalStateException("HEALTH_ACCOUNT_WRITE_FAILED")
            }
            val owner = ContentValues(1).apply {
                put("account_hash", hash)
            }
            db.update(
                "files",
                owner,
                "account_hash IS NULL OR (account_hash!=? AND next_record_index<record_count)",
                arrayOf(hash),
            )
            db.execSQL(
                "DELETE FROM records WHERE account_hash!=? AND EXISTS (" +
                    "SELECT 1 FROM records existing WHERE existing.account_hash=? " +
                    "AND existing.record_id=records.record_id)",
                arrayOf<Any>(hash, hash),
            )
            db.update("records", owner, "account_hash IS NULL OR account_hash!=?", arrayOf(hash))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun accountState(): String {
        readableDatabase.rawQuery("SELECT paused FROM binding WHERE id=1", null).use { binding ->
            if (!binding.moveToFirst()) return "ACCOUNT_UNCONFIRMED"
            return if (binding.getInt(0) == 0) "ACCOUNT_CONFIRMED" else "OHEALTH_ACCOUNT_CHANGED"
        }
    }

    @Synchronized
    fun confirmedAccountHash(): String? = confirmedAccountHash(readableDatabase)

    data class EnqueueResult(
        @JvmField val revision: Int,
        @JvmField val added: Boolean,
    ) {
        fun revision(): Int = revision

        fun added(): Boolean = added
    }

    data class ArchivedFile(
        @JvmField val fileHash: String,
        @JvmField val deviceId: String,
        @JvmField val firmware: String?,
        @JvmField val capturedAtMs: Long,
        @JvmField val accountHash: String?,
        @JvmField val nextRecordIndex: Int,
        @JvmField val parseStatus: String,
        @JvmField val recordCount: Int,
    ) {
        fun fileHash(): String = fileHash

        fun deviceId(): String = deviceId

        fun firmware(): String? = firmware

        fun capturedAtMs(): Long = capturedAtMs

        fun accountHash(): String? = accountHash

        fun nextRecordIndex(): Int = nextRecordIndex

        fun parseStatus(): String = parseStatus

        fun recordCount(): Int = recordCount
    }

    data class StressGap(
        @JvmField val deviceId: String,
        @JvmField val startMs: Long,
        @JvmField val endMs: Long,
    ) {
        fun deviceId(): String = deviceId

        fun startMs(): Long = startMs

        fun endMs(): Long = endMs
    }

    @Synchronized
    fun enqueueMeasurement(measurement: Measurement?): EnqueueResult {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = enqueue(db, requireConfirmedAccount(db), measurement)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    /** One transaction covers deduplication, history, ledger, replacement outbox and replay cursor. */
    @Synchronized
    fun enqueueArchivedMeasurement(fileHash: String?, expectedIndex: Int, measurement: Measurement?): EnqueueResult {
        requireHash(fileHash)
        val nonNullHash = checkNotNull(fileHash)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val account = requireConfirmedAccount(db)
            db.rawQuery(
                "SELECT device_id, account_hash, next_record_index, record_count, parse_status " +
                    "FROM files WHERE file_hash=?",
                arrayOf(nonNullHash),
            ).use { file ->
                if (!file.moveToFirst() || account != file.getString(1) ||
                    measurement == null || measurement.deviceId != file.getString(0)
                ) {
                    throw SecurityException("HEALTH_ARCHIVE_OWNERSHIP_REQUIRED")
                }
                if (expectedIndex < 0 || expectedIndex != file.getInt(2) || expectedIndex >= file.getInt(3) ||
                    "PARSED" != file.getString(4)
                ) {
                    throw IllegalStateException("HEALTH_ARCHIVE_CURSOR_CHANGED")
                }
            }
            val result = enqueue(db, account, measurement)
            val cursor = ContentValues(1).apply {
                put("next_record_index", expectedIndex + 1)
            }
            if (db.update(
                    "files",
                    cursor,
                    "file_hash=? AND account_hash=? AND next_record_index=?",
                    arrayOf(nonNullHash, account, expectedIndex.toString()),
                ) != 1
            ) {
                throw IllegalStateException("HEALTH_ARCHIVE_CURSOR_CHANGED")
            }
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    /** Any real Health account becomes the write target. A blank or package id leaves collection running. */
    @Synchronized
    fun authorizedAccount(account: String?): Boolean {
        if (account.isNullOrBlank() || account.length > 512 ||
            HostIdentity.HEALTH_PACKAGE == account
        ) {
            return false
        }
        val hash = hashAccount(account)
        readableDatabase.rawQuery("SELECT account_hash, paused FROM binding WHERE id=1", null).use { binding ->
            if (binding.moveToFirst() && hash == binding.getString(0) && binding.getInt(1) == 0) {
                return true
            }
        }
        confirmAccountHash(hash)
        return true
    }

    @Synchronized
    fun pending(account: String?): Cursor {
        if (!authorizedAccount(account)) throw SecurityException("HEALTH_ACCOUNT_NOT_CONFIRMED")
        return readableDatabase.rawQuery(
            "SELECT record_id AS recordId, revision, payload AS record " +
                "FROM records WHERE account_hash=? AND account_hash=(SELECT account_hash FROM binding WHERE id=1 AND paused=0) " +
                "ORDER BY rowid LIMIT " + PAGE_SIZE,
            arrayOf(hashAccount(account)),
        )
    }

    @Synchronized
    fun acknowledge(account: String?, recordId: String?, revision: Int): Int {
        if (!authorizedAccount(account)) throw SecurityException("HEALTH_ACCOUNT_NOT_CONFIRMED")
        if (recordId.isNullOrBlank() || recordId.length > 256 || revision <= 0) {
            throw IllegalArgumentException("INVALID_HEALTH_RECEIPT")
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val hash = hashAccount(account)
            if (hash != requireConfirmedAccount(db)) throw SecurityException("HEALTH_ACCOUNT_NOT_CONFIRMED")
            val removed = db.delete(
                "records",
                "account_hash=? AND record_id=? AND revision=?",
                arrayOf(hash, recordId, revision.toString()),
            )
            db.setTransactionSuccessful()
            return removed
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    @Throws(JSONException::class)
    fun releaseUnsupported(account: String?, recordId: String?, revision: Int): Int {
        if (!authorizedAccount(account)) throw SecurityException("HEALTH_ACCOUNT_NOT_CONFIRMED")
        if (recordId.isNullOrBlank() || recordId.length > 256 || revision <= 0) {
            throw IllegalArgumentException("INVALID_HEALTH_RECEIPT")
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val hash = hashAccount(account)
            if (hash != requireConfirmedAccount(db)) throw SecurityException("HEALTH_ACCOUNT_NOT_CONFIRMED")
            val payload: String
            db.rawQuery(
                "SELECT payload FROM records WHERE account_hash=? AND record_id=? AND revision=?",
                arrayOf(hash, recordId, revision.toString()),
            ).use { cursor ->
                if (!cursor.moveToFirst()) return 0
                payload = cursor.getString(0)
            }
            if (HealthRecord.fromJson(JSONObject(payload)).hostAccepts()) {
                throw IllegalArgumentException("HOST_VALUE_IS_STORABLE")
            }
            val removed = db.delete(
                "records",
                "account_hash=? AND record_id=? AND revision=?",
                arrayOf(hash, recordId, revision.toString()),
            )
            db.setTransactionSuccessful()
            return removed
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun records(
        account: String?,
        deviceId: String?,
        kind: String?,
        startMs: Long,
        endMs: Long,
        after: String?,
    ): Cursor {
        if (!authorizedAccount(account)) throw SecurityException("HEALTH_ACCOUNT_NOT_CONFIRMED")
        if (isDeviceSleep(kind)) return deviceSleep(deviceId, kind, startMs, endMs, after)
        return measurements(hashAccount(account), deviceId, kind, startMs, endMs, after)
    }

    @Synchronized
    fun localMeasurements(
        deviceId: String?,
        kind: String?,
        startMs: Long,
        endMs: Long,
        after: String?,
    ): Cursor {
        requireConfirmedAccount(readableDatabase)
        if (isDeviceSleep(kind)) return deviceSleep(deviceId, kind, startMs, endMs, after)
        return measurements(requireConfirmedAccount(readableDatabase), deviceId, kind, startMs, endMs, after)
    }

    @Synchronized
    @Throws(JSONException::class)
    fun storedSteps(deviceId: String?, dayStart: Long, dayEnd: Long): List<Measurement> {
        val found = ArrayList<Measurement>()
        for (kind in arrayOf("steps_day", "steps_interval")) {
            storedMeasurements(deviceId, kind, dayStart, dayEnd, found)
        }
        return found
    }

    /**
     * Every stored measurement of one kind in a window, as the normalized record it was written
     * as. The `measurements` payload is a [HealthRecord], not a parser measurement.
     */
    @Synchronized
    @Throws(JSONException::class)
    fun storedRecords(deviceId: String?, kind: String?, startMs: Long, endMs: Long): List<HealthRecord> {
        val found = ArrayList<HealthRecord>()
        var after = ""
        while (found.size <= 4_000) {
            var count = 0
            val rows = if (isDeviceSleep(kind)) {
                deviceSleep(deviceId, kind, startMs, endMs, after)
            } else {
                measurements(requireConfirmedAccount(readableDatabase), deviceId, kind, startMs, endMs, after)
            }
            rows.use {
                while (it.moveToNext()) {
                    count++
                    after = it.getString(0)
                    found.add(HealthRecord.fromJson(JSONObject(it.getString(2))))
                }
            }
            if (count < PAGE_SIZE) break
        }
        return found
    }

    @Throws(JSONException::class)
    private fun storedMeasurements(
        deviceId: String?,
        kind: String,
        startMs: Long,
        endMs: Long,
        found: MutableList<Measurement>,
    ) {
        var after = ""
        while (found.size <= 4_000) {
            var count = 0
            measurements(requireConfirmedAccount(readableDatabase), deviceId, kind, startMs, endMs, after).use { rows ->
                while (rows.moveToNext()) {
                    count++
                    after = rows.getString(0)
                    found.add(Measurement.fromJson(JSONObject(rows.getString(2))))
                }
            }
            if (count < PAGE_SIZE) break
        }
    }

    /**
     * Local-only sleep read. It intentionally skips the host account confirmation because these
     * rows are device data rather than OPPO Health account data, but it still requires an explicit,
     * non-empty device id and filters on it in SQL.
     */
    private fun deviceSleep(
        deviceId: String?,
        kind: String?,
        startMs: Long,
        endMs: Long,
        after: String?,
    ): Cursor {
        if (deviceId.isNullOrBlank() || deviceId.length > 128 || !isDeviceSleep(kind) ||
            startMs < 0 || endMs <= startMs || (after != null && after.length > 256)
        ) {
            throw IllegalArgumentException("INVALID_HEALTH_WINDOW")
        }
        return readableDatabase.rawQuery(
            "SELECT record_id AS recordId, revision, payload AS record " +
                "FROM measurements WHERE device_id=? AND kind=? AND start_ms<? AND end_ms>? AND record_id>? " +
                "ORDER BY record_id LIMIT " + PAGE_SIZE,
            arrayOf(deviceId, checkNotNull(kind), endMs.toString(), startMs.toString(), after ?: ""),
        )
    }

    private fun measurements(
        account: String,
        deviceId: String?,
        kind: String?,
        startMs: Long,
        endMs: Long,
        after: String?,
    ): Cursor {
        if (deviceId.isNullOrBlank() || deviceId.length > 128 || kind.isNullOrBlank() ||
            startMs < 0 || endMs <= startMs || (after != null && after.length > 256)
        ) {
            throw IllegalArgumentException("INVALID_HEALTH_WINDOW")
        }
        return readableDatabase.rawQuery(
            "SELECT record_id AS recordId, revision, payload AS record " +
                "FROM measurements WHERE account_hash=? AND device_id=? AND kind=? AND start_ms<? AND end_ms>? " +
                "AND record_id>? AND account_hash=(SELECT account_hash FROM binding WHERE id=1 AND paused=0) " +
                "ORDER BY record_id LIMIT " + PAGE_SIZE,
            arrayOf(account, deviceId, kind, endMs.toString(), startMs.toString(), after ?: ""),
        )
    }

    @Synchronized
    fun hasCapacity(): Boolean {
        readableDatabase.rawQuery("SELECT COUNT(*), COALESCE(SUM(byte_size), 0) FROM records", null).use { totals ->
            totals.moveToFirst()
            return totals.getInt(0) < MAX_RECORDS && totals.getLong(1) < MAX_BYTES
        }
    }

    @Synchronized
    fun count(): Int {
        val account = confirmedAccountHash() ?: return 0
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM records WHERE account_hash=?",
            arrayOf(account),
        ).use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(0)
        }
    }

    /** RawFitnessFileStore calls this only after the raw bytes have been durably committed. */
    @Synchronized
    fun indexFile(
        hash: String?,
        deviceId: String?,
        firmware: String?,
        capturedAtMs: Long,
        parseStatus: String?,
        recordCount: Int,
    ) {
        requireHash(hash)
        val nonNullHash = checkNotNull(hash)
        if (deviceId.isNullOrBlank() || deviceId.length > 128 || capturedAtMs < 0 ||
            parseStatus == null || !PARSE_STATUS_REGEX.matches(parseStatus) || recordCount < 0 ||
            ("PARSED" != parseStatus && recordCount != 0)
        ) {
            throw IllegalArgumentException("INVALID_HISTORY_INDEX")
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.rawQuery("SELECT device_id FROM files WHERE file_hash=?", arrayOf(nonNullHash)).use { old ->
                if (old.moveToFirst()) {
                    if (deviceId != old.getString(0)) throw SecurityException("HEALTH_ARCHIVE_IDENTITY_CONFLICT")
                    db.setTransactionSuccessful()
                    return // Never transfer existing files to a new account or reset a replay cursor.
                }
            }
            val row = ContentValues(8).apply {
                put("file_hash", nonNullHash)
                put("device_id", deviceId)
                put("firmware", firmware)
                put("captured_at_ms", capturedAtMs)
                // Unsent files are claimed by the next real account. A paused binding keeps its queue.
                db.rawQuery("SELECT account_hash FROM binding WHERE id=1", null).use { binding ->
                    put("account_hash", if (binding.moveToFirst()) binding.getString(0) else null)
                }
                put("next_record_index", 0)
                put("parse_status", parseStatus)
                put("record_count", recordCount)
            }
            db.insertOrThrow("files", null, row)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * A parsed file gained a record an older parser did not produce. Only the count grows, so a
     * replay resumes at the new index instead of restarting the whole file.
     */
    @Synchronized
    fun extendParsedRecordCount(hash: String?, recordCount: Int): Boolean {
        requireHash(hash)
        if (recordCount < 1) return false
        val values = ContentValues(1).apply {
            put("record_count", recordCount)
        }
        return writableDatabase.update(
            "files",
            values,
            "file_hash=? AND parse_status='PARSED' AND record_count<?",
            arrayOf(checkNotNull(hash), recordCount.toString()),
        ) == 1
    }

    /** A rejected file gained a parser. The cursor stays where it already moved. */
    @Synchronized
    fun promoteRejectedFile(hash: String?, recordCount: Int): Boolean {
        requireHash(hash)
        val nonNullHash = checkNotNull(hash)
        if (recordCount < 1) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.rawQuery(
                "SELECT parse_status, record_count, next_record_index FROM files WHERE file_hash=?",
                arrayOf(nonNullHash),
            ).use { row ->
                if (!row.moveToFirst() || "PARSED" == row.getString(0) ||
                    row.getInt(1) != 0 || row.getInt(2) != 0
                ) {
                    db.setTransactionSuccessful()
                    return false
                }
            }
            val values = ContentValues(2).apply {
                put("parse_status", "PARSED")
                put("record_count", recordCount)
            }
            val updated = db.update(
                "files",
                values,
                "file_hash=? AND parse_status!='PARSED' AND record_count=0 AND next_record_index=0",
                arrayOf(nonNullHash),
            )
            db.setTransactionSuccessful()
            return updated == 1
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Write this file's stages for `owned` intervals, then drop any other stages there.
     * Caller passes only intervals the newest analysis still owns.
     */
    @Synchronized
    fun retainSleepStages(
        deviceId: String?,
        parsed: List<Measurement?>?,
        owned: List<SleepStageAlign.Interval?>?,
    ): Int {
        if (deviceId.isNullOrBlank() || parsed == null || owned.isNullOrEmpty()) return 0
        val stages = ArrayList<Measurement>()
        for (measurement in parsed) {
            if (measurement != null && deviceId == measurement.deviceId && "sleep_stage" == measurement.kind) {
                stages.add(measurement)
            }
        }
        if (stages.isEmpty()) return 0
        val db = writableDatabase
        val account = requireConfirmedAccount(db)
        var changed = 0
        db.beginTransaction()
        try {
            for (stage in stages) {
                if (!SleepStageAlign.overlapsAny(stage.startMs, stage.endMs, owned)) continue
                if (enqueue(db, account, stage).added) changed++
            }
            for (interval in owned) {
                if (interval == null) continue
                val keep = ArrayList<String>()
                for (stage in stages) {
                    if (stage.startMs < interval.endMs && stage.endMs > interval.startMs) {
                        keep.add(stage.recordId)
                    }
                }
                if (keep.isEmpty()) continue
                val drop = ArrayList<String>()
                db.rawQuery(
                    "SELECT record_id FROM measurements WHERE account_hash=? " +
                        "AND device_id=? AND kind='sleep_stage' AND start_ms<? AND end_ms>?",
                    arrayOf(account, deviceId, interval.endMs.toString(), interval.startMs.toString()),
                ).use { rows ->
                    while (rows.moveToNext()) {
                        val id = rows.getString(0)
                        if (!keep.contains(id)) drop.add(id)
                    }
                }
                for (id in drop) {
                    val key = arrayOf(account, id)
                    db.delete("measurements", "account_hash=? AND record_id=?", key)
                    db.delete("revisions", "account_hash=? AND record_id=?", key)
                    changed++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return changed
    }

    /** Continuous stress 0 is an empty minute, not a reading. Ranges are [start, end). */
    @Synchronized
    fun unmeasuredStress(account: String?): List<StressGap> {
        if (!authorizedAccount(account)) throw SecurityException("HEALTH_ACCOUNT_NOT_CONFIRMED")
        val hash = hashAccount(account)
        if (hash != requireConfirmedAccount(readableDatabase)) {
            throw SecurityException("HEALTH_ACCOUNT_NOT_CONFIRMED")
        }
        val gaps = ArrayList<StressGap>()
        readableDatabase.rawQuery(
            "SELECT device_id, start_ms, end_ms FROM measurements " +
                "WHERE account_hash=? AND kind='stress' AND json_extract(payload,'$.measurementMode')='continuous' " +
                "AND json_extract(payload,'$.value')=0 ORDER BY device_id, start_ms",
            arrayOf(hash),
        ).use { rows ->
            var device: String? = null
            var runStart = -1L
            var runEnd = -1L
            while (rows.moveToNext()) {
                val nextDevice = rows.getString(0)
                val start = rows.getLong(1)
                val end = rows.getLong(2)
                if (device != null && device == nextDevice && runEnd == start) {
                    runEnd = end
                    continue
                }
                if (device != null) gaps.add(StressGap(device, runStart, runEnd))
                device = nextDevice
                runStart = start
                runEnd = end
            }
            if (device != null) gaps.add(StressGap(device, runStart, runEnd))
        }
        return gaps
    }

    @Synchronized
    fun forgetUnmeasuredStress(account: String?): Int {
        if (!authorizedAccount(account)) throw SecurityException("HEALTH_ACCOUNT_NOT_CONFIRMED")
        val db = writableDatabase
        val hash = hashAccount(account)
        if (hash != requireConfirmedAccount(db)) throw SecurityException("HEALTH_ACCOUNT_NOT_CONFIRMED")
        db.beginTransaction()
        try {
            val ids = ArrayList<String>()
            db.rawQuery(
                "SELECT record_id FROM measurements WHERE account_hash=? AND kind='stress' " +
                    "AND json_extract(payload,'$.measurementMode')='continuous' AND json_extract(payload,'$.value')=0",
                arrayOf(hash),
            ).use { rows ->
                while (rows.moveToNext()) ids.add(rows.getString(0))
            }
            for (id in ids) {
                val key = arrayOf(hash, id)
                db.delete("records", "account_hash=? AND record_id=?", key)
                db.delete("revisions", "account_hash=? AND record_id=?", key)
                db.delete("measurements", "account_hash=? AND record_id=?", key)
            }
            db.setTransactionSuccessful()
            return ids.size
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun completedFiles(): List<ArchivedFile> {
        val files = ArrayList<ArchivedFile>()
        val account = confirmedAccountHash() ?: return files
        readableDatabase.rawQuery(
            "SELECT file_hash, device_id, firmware, captured_at_ms, " +
                "account_hash, next_record_index, parse_status, record_count FROM files " +
                "WHERE account_hash=? AND parse_status='PARSED' AND next_record_index>=record_count " +
                "ORDER BY captured_at_ms, file_hash",
            arrayOf(account),
        ).use { rows ->
            while (rows.moveToNext()) {
                files.add(
                    ArchivedFile(
                        rows.getString(0), rows.getString(1), rows.getString(2),
                        rows.getLong(3), rows.getString(4), rows.getInt(5), rows.getString(6), rows.getInt(7),
                    ),
                )
            }
        }
        return files
    }

    @Synchronized
    fun replayFiles(limit: Int): List<ArchivedFile> {
        if (limit < 1 || limit > PAGE_SIZE) throw IllegalArgumentException("INVALID_HISTORY_PAGE_SIZE")
        val files = ArrayList<ArchivedFile>(limit)
        val account = confirmedAccountHash() ?: return files
        readableDatabase.rawQuery(
            "SELECT file_hash, device_id, firmware, captured_at_ms, " +
                "account_hash, next_record_index, parse_status, record_count FROM files " +
                "WHERE account_hash=? AND parse_status='PARSED' AND next_record_index<record_count " +
                "ORDER BY captured_at_ms, file_hash LIMIT ?",
            arrayOf(account, limit.toString()),
        ).use { rows ->
            while (rows.moveToNext()) {
                files.add(
                    ArchivedFile(
                        rows.getString(0), rows.getString(1), rows.getString(2),
                        rows.getLong(3), rows.getString(4), rows.getInt(5), rows.getString(6), rows.getInt(7),
                    ),
                )
            }
        }
        return files
    }

    /** Oldest finished archives first. Pending and unparsed files stay, so replay can still finish. */
    @Synchronized
    fun finishedFileHashes(limit: Int): List<String> {
        if (limit < 1) throw IllegalArgumentException("INVALID_HISTORY_PAGE_SIZE")
        val hashes = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT file_hash FROM files " +
                "WHERE parse_status='PARSED' AND next_record_index>=record_count " +
                "ORDER BY captured_at_ms, file_hash LIMIT ?",
            arrayOf(limit.toString()),
        ).use { rows ->
            while (rows.moveToNext()) hashes.add(rows.getString(0))
        }
        return hashes
    }

    /** Removes the index row only. The caller deletes the raw bytes after this commits. */
    @Synchronized
    fun dropFinishedFile(hash: String?): Boolean {
        requireHash(hash)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val removed = db.delete(
                "files",
                "file_hash=? AND parse_status='PARSED' AND next_record_index>=record_count",
                arrayOf(checkNotNull(hash)),
            )
            db.setTransactionSuccessful()
            return removed == 1
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun isFileIndexed(hash: String?): Boolean {
        requireHash(hash)
        readableDatabase.rawQuery(
            "SELECT 1 FROM files WHERE file_hash=?",
            arrayOf(checkNotNull(hash)),
        ).use { row ->
            return row.moveToFirst()
        }
    }

    @Synchronized
    fun ownsDeviceArchive(deviceId: String?): Boolean {
        val account = confirmedAccountHash() ?: return false
        if (deviceId == null) return false
        readableDatabase.rawQuery(
            "SELECT 1 FROM files WHERE device_id=? AND account_hash=? LIMIT 1",
            arrayOf(deviceId, account),
        ).use { row ->
            return row.moveToFirst()
        }
    }

    companion object {
        const val PAGE_SIZE = 200
        private const val MAX_RECORDS = 32768
        private const val MAX_BYTES = 64L * 1024 * 1024
        private const val MAX_RECORD_BYTES = 8192
        private val HEX64_REGEX = Regex("[0-9a-f]{64}")
        private val PARSE_STATUS_REGEX = Regex("[A-Z0-9_]{1,128}")

        private fun requireCredentialProtected(context: Context): Context {
            if (context.isDeviceProtectedStorage) throw IllegalArgumentException("CE_STORAGE_REQUIRED")
            return context
        }

        private fun createOutbox(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE records (record_id TEXT NOT NULL, revision INTEGER NOT NULL, " +
                    "device_id TEXT NOT NULL, account_hash TEXT NOT NULL, payload TEXT NOT NULL, " +
                    "byte_size INTEGER NOT NULL, PRIMARY KEY(account_hash, record_id))",
            )
        }

        private fun createHistory(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE measurements (account_hash TEXT NOT NULL, record_id TEXT NOT NULL, " +
                    "revision INTEGER NOT NULL, device_id TEXT NOT NULL, kind TEXT NOT NULL, " +
                    "start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, payload TEXT NOT NULL, " +
                    "PRIMARY KEY(account_hash, record_id))",
            )
            db.execSQL("CREATE INDEX measurements_window ON measurements(account_hash, device_id, kind, start_ms)")
            db.execSQL(
                "CREATE TABLE files (file_hash TEXT PRIMARY KEY NOT NULL, device_id TEXT NOT NULL, " +
                    "firmware TEXT, captured_at_ms INTEGER NOT NULL, account_hash TEXT, " +
                    "next_record_index INTEGER NOT NULL DEFAULT 0, parse_status TEXT NOT NULL, " +
                    "record_count INTEGER NOT NULL)",
            )
            db.execSQL("CREATE INDEX files_replay ON files(account_hash, parse_status, captured_at_ms)")
        }

        @JvmStatic
        fun hashAccount(account: String?): String {
            if (account.isNullOrBlank() || account.length > 512) {
                throw IllegalArgumentException("ACCOUNT_REQUIRED")
            }
            try {
                return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(account.toByteArray(StandardCharsets.UTF_8)),
                )
            } catch (impossible: Exception) {
                throw IllegalStateException("ACCOUNT_HASH_UNAVAILABLE", impossible)
            }
        }

        private fun confirmedAccountHash(db: SQLiteDatabase): String? {
            db.rawQuery("SELECT account_hash FROM binding WHERE id=1 AND paused=0", null).use { binding ->
                return if (binding.moveToFirst()) binding.getString(0) else null
            }
        }

        private fun requireConfirmedAccount(db: SQLiteDatabase): String {
            return confirmedAccountHash(db) ?: throw IllegalStateException("ACCOUNT_CONFIRMATION_REQUIRED")
        }

        private fun enqueue(db: SQLiteDatabase, account: String, measurement: Measurement?): EnqueueResult {
            if (measurement == null || measurement.recordId.length > 256 || measurement.deviceId.length > 128 ||
                !HEX64_REGEX.matches(measurement.sourceFingerprint)
            ) {
                throw IllegalArgumentException("INVALID_HEALTH_MEASUREMENT_KEY")
            }
            var revision = 1
            db.rawQuery(
                "SELECT device_id, last_fingerprint, last_revision FROM revisions " +
                    "WHERE account_hash=? AND record_id=?",
                arrayOf(account, measurement.recordId),
            ).use { current ->
                if (current.moveToFirst()) {
                    if (measurement.deviceId != current.getString(0)) {
                        throw IllegalStateException("HEALTH_RECORD_IDENTITY_CONFLICT")
                    }
                    if (measurement.sourceFingerprint == current.getString(1)) {
                        return EnqueueResult(current.getInt(2), false)
                    }
                    if (current.getInt(2) == Int.MAX_VALUE) {
                        throw IllegalStateException("HEALTH_REVISION_EXHAUSTED")
                    }
                    revision = current.getInt(2) + 1
                }
            }
            val record = measurement.toRecord(revision)
            val payload = record.toJson().toString()
            val size = payload.toByteArray(StandardCharsets.UTF_8).size
            val limit = if ("sport_track" == record.kind) 256 * 1024 else MAX_RECORD_BYTES
            if (size > limit) throw IllegalArgumentException("HEALTH_RECORD_TOO_LARGE")
            val key = arrayOf(account, record.recordId)
            // Delete inside the transaction: a failed capacity check restores the previous revision.
            db.delete("records", "account_hash=? AND record_id=?", key)
            if (isHostKind(record.kind)) {
                db.rawQuery("SELECT COUNT(*), COALESCE(SUM(byte_size), 0) FROM records", null).use { totals ->
                    totals.moveToFirst()
                    if (totals.getInt(0) >= MAX_RECORDS || totals.getLong(1) + size > MAX_BYTES) {
                        throw IllegalStateException("HEALTH_OUTBOX_FULL")
                    }
                }
                db.insertOrThrow("records", null, outboxRow(account, record.recordId, revision, record.deviceId, payload, size))
            }
            if (db.insertWithOnConflict(
                    "measurements",
                    null,
                    measurementRow(account, record.recordId, revision, record.deviceId, record.kind, record.startMs, record.endMs, payload),
                    SQLiteDatabase.CONFLICT_REPLACE,
                ) < 0
            ) {
                throw IllegalStateException("HEALTH_MEASUREMENT_WRITE_FAILED")
            }
            val version = ContentValues(5).apply {
                put("account_hash", account)
                put("record_id", record.recordId)
                put("device_id", record.deviceId)
                put("last_fingerprint", measurement.sourceFingerprint)
                put("last_revision", revision)
            }
            if (db.insertWithOnConflict("revisions", null, version, SQLiteDatabase.CONFLICT_REPLACE) < 0) {
                throw IllegalStateException("HEALTH_REVISION_WRITE_FAILED")
            }
            return EnqueueResult(revision, true)
        }

        private fun isHostKind(kind: String): Boolean =
            "heart_rate" == kind || "spo2" == kind || "stress" == kind

        private fun outboxRow(
            account: String,
            id: String,
            revision: Int,
            device: String,
            payload: String,
            size: Int,
        ): ContentValues = ContentValues(6).apply {
            put("account_hash", account)
            put("record_id", id)
            put("revision", revision)
            put("device_id", device)
            put("payload", payload)
            put("byte_size", size)
        }

        private fun measurementRow(
            account: String,
            id: String,
            revision: Int,
            device: String,
            kind: String,
            start: Long,
            end: Long,
            payload: String,
        ): ContentValues = ContentValues(8).apply {
            put("account_hash", account)
            put("record_id", id)
            put("revision", revision)
            put("device_id", device)
            put("kind", kind)
            put("start_ms", start)
            put("end_ms", end)
            put("payload", payload)
        }

        /**
         * Sleep stays on the device and is not imported, so a later account confirmation can still
         * read it. The explicit device id is part of every local query and prevents a missing account
         * hash from widening the result.
         */
        private fun isDeviceSleep(kind: String?): Boolean =
            "sleep_interval" == kind || "sleep_stage" == kind || "sleep_breath" == kind

        @JvmStatic
        fun requireHash(hash: String?) {
            if (hash == null || !HEX64_REGEX.matches(hash)) throw IllegalArgumentException("INVALID_HISTORY_FILE_HASH")
        }
    }
}
