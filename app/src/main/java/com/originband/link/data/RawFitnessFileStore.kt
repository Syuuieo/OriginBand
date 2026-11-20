// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import com.originband.link.integration.HealthQueueProvider
import com.originband.link.protocol.BandHistoryParser
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Arrays
import java.util.HexFormat

/** Durable CE replay copies and their account-isolated database index, committed before band ACK. */
class RawFitnessFileStore(private val context: Context) {
    private val directory: File

    init {
        if (context.isDeviceProtectedStorage) throw IllegalArgumentException("CE_STORAGE_REQUIRED")
        directory = File(context.noBackupFilesDir, "band-history-files")
    }

    fun storedFiles(): Int {
        synchronized(FILE_LOCK) {
            val existing = directory.listFiles { _, name -> name.endsWith(".dat") }
            return existing?.size ?: 0
        }
    }

    @Throws(Exception::class)
    fun persist(
        result: BandHistoryParser.FileResult,
        deviceId: String,
        firmware: String?,
        capturedAtMs: Long,
    ): File {
        synchronized(FILE_LOCK) {
            requireMatchingIdentity(context, deviceId)
            val fileId = result.copyFileId()
            val bytes = result.copyRawFile()
            try {
                if (fileId.size != 7 || bytes.size < 12 || bytes.size > MAX_FILE_BYTES ||
                    !fileId.contentEquals(bytes.copyOf(7))
                ) {
                    throw IllegalArgumentException("INVALID_HISTORY_SOURCE_FILE")
                }
                val hash = fileHash(bytes)
                if (!directory.isDirectory && !directory.mkdirs()) {
                    throw IllegalStateException("HISTORY_DIRECTORY_UNAVAILABLE")
                }
                Os.chmod(directory.absolutePath, 0x1c0) // 0700
                val target = File(directory, "$hash.dat")
                val output = AtomicFile(target)
                if (target.isFile) {
                    val existing = readFile(hash)
                    try {
                        if (!existing.contentEquals(bytes)) throw IllegalStateException("HISTORY_REPLAY_CONFLICT")
                    } finally {
                        Arrays.fill(existing, 0.toByte())
                    }
                } else {
                    makeRoom(bytes.size.toLong())
                    val existing = directory.listFiles { _, name -> name.endsWith(".dat") }
                    if (existing == null || existing.size >= MAX_FILES) {
                        throw IllegalStateException("HISTORY_REPLAY_LIMIT_REACHED")
                    }
                    var occupied = 0L
                    for (file in existing) occupied += file.length()
                    if (occupied > MAX_TOTAL_BYTES - bytes.size) {
                        throw IllegalStateException("HISTORY_REPLAY_LIMIT_REACHED")
                    }
                    var stream: FileOutputStream? = null
                    try {
                        stream = output.startWrite()
                        stream.write(bytes)
                        // AtomicFile logs some sync failures instead of throwing: require a checked sync first.
                        stream.fd.sync()
                        output.finishWrite(stream)
                        stream = null
                        Os.chmod(target.absolutePath, 0x180) // 0600
                        val committed = readFile(hash)
                        Arrays.fill(committed, 0.toByte())
                    } catch (failure: Exception) {
                        if (stream != null) output.failWrite(stream)
                        throw failure
                    }
                }
                syncDirectory(directory)
                syncDirectory(directory.parentFile)
                HealthRecordStore(context).use { store ->
                    store.indexFile(hash, deviceId, firmware, capturedAtMs, result.parseStatus, result.measurements.size)
                }
                return target
            } finally {
                Arrays.fill(fileId, 0.toByte())
                Arrays.fill(bytes, 0.toByte())
            }
        }
    }

    /** Finished archives already live in the measurement tables. Drop the oldest until one slot fits. */
    private fun makeRoom(incomingBytes: Long) {
        val existing = directory.listFiles { _, name -> name.endsWith(".dat") }
        var count = existing?.size ?: 0
        var occupied = 0L
        if (existing != null) {
            for (file in existing) occupied += file.length()
        }
        if (count < MAX_FILES && occupied <= MAX_TOTAL_BYTES - incomingBytes) return
        HealthRecordStore(context).use { store ->
            for (hash in store.finishedFileHashes(maxOf(count, 1))) {
                val raw = File(directory, "$hash.dat")
                val present = raw.isFile
                val size = if (present) raw.length() else 0L
                if (!store.dropFinishedFile(hash)) continue
                if (present && !raw.delete() && raw.isFile) continue
                if (present) {
                    count--
                    occupied -= size
                }
                if (count < MAX_FILES && occupied <= MAX_TOTAL_BYTES - incomingBytes) return
            }
            val left = directory.listFiles { _, name -> name.endsWith(".dat") } ?: return
            count = left.size
            occupied = 0L
            for (file in left) occupied += file.length()
            for (file in left) {
                val name = file.name
                if (name.length != 68) continue
                val hash = name.substring(0, 64)
                if (store.isFileIndexed(hash)) continue
                val size = file.length()
                if (!file.delete() && file.isFile) continue
                count--
                occupied -= size
                if (count < MAX_FILES && occupied <= MAX_TOTAL_BYTES - incomingBytes) return
            }
        }
    }

    /** Verifies the legacy filename algorithm SHA256(fileId || raw), before parser CRC validation. */
    @Throws(Exception::class)
    fun readFile(hash: String?): ByteArray {
        HealthRecordStore.requireHash(hash)
        val nonNullHash = checkNotNull(hash)
        synchronized(FILE_LOCK) {
            val target = File(directory, "$nonNullHash.dat")
            if (target.length() < 12 || target.length() > MAX_FILE_BYTES) {
                throw IllegalArgumentException("INVALID_HISTORY_SOURCE_FILE")
            }
            val bytes = AtomicFile(target).readFully()
            if (bytes.size < 12 || bytes.size > MAX_FILE_BYTES || nonNullHash != fileHash(bytes)) {
                Arrays.fill(bytes, 0.toByte())
                throw IllegalArgumentException("HISTORY_REPLAY_HASH_MISMATCH")
            }
            return bytes
        }
    }

    /** No current binding is ever assigned to an old file unless the saved collection identity matches. */
    @Throws(Exception::class)
    fun migrateLegacyFiles(): Int {
        synchronized(FILE_LOCK) {
            val state = LocalPrefs.open(context, "band-state")
            val deviceId = state.getString("deviceId", "")
            val identity = try {
                requireMatchingIdentity(context, deviceId)
            } catch (_: SecurityException) {
                return 0
            }
            val existing = directory.listFiles { _, name -> HASHED_DAT_REGEX.matches(name) }
            if (existing == null || existing.isEmpty()) return 0
            val firmware = state.getString("verifiedFirmware", "")
            if (firmware.isBlank()) throw IllegalStateException("HISTORY_FIRMWARE_UNCONFIRMED")
            val parser = try {
                BandHistoryParser(firmware, deviceId, identity)
            } catch (_: IllegalArgumentException) {
                return 0
            }
            var migrated = 0
            HealthRecordStore(context).use { store ->
                for (file in existing) {
                    val hash = file.name.substring(0, 64)
                    if (store.isFileIndexed(hash)) continue
                    val bytes = try {
                        readFile(hash)
                    } catch (_: IllegalArgumentException) {
                        continue // Preserve damaged/unidentified originals, but never claim or ACK them.
                    }
                    try {
                        val result = try {
                            parser.parseFile(bytes)
                        } catch (_: IllegalArgumentException) {
                            continue
                        }
                        store.indexFile(hash, deviceId, firmware, file.lastModified(), result.parseStatus, result.measurements.size)
                        migrated++
                    } finally {
                        Arrays.fill(bytes, 0.toByte())
                    }
                }
            }
            return migrated
        }
    }

    /** One pass so step rows saved before calories and stand hours were parsed pick them up. */
    fun refreshStepMetrics() {
        if (stepMetricsCurrent) return
        synchronized(FILE_LOCK) {
            if (stepMetricsCurrent) return
            val prefs = context.getSharedPreferences("oplusband-history", Context.MODE_PRIVATE)
            if (prefs.getBoolean("step-metrics-v1", false)) {
                stepMetricsCurrent = true
                return
            }
            try {
                rewriteStepMetrics()
                if (!prefs.edit().putBoolean("step-metrics-v1", true).commit()) {
                    throw IllegalStateException("STEP_METRIC_MEMORY_FAILED")
                }
                stepMetricsCurrent = true
            } catch (_: Exception) {
                // Identity or a file can be unready. The next step read tries again.
            }
        }
    }

    /** One pass so sleep breath rows parsed from existing files are enqueued. */
    fun refreshSleepBreathMetrics(): Int {
        if (sleepBreathMetricsCurrent) return 0
        synchronized(FILE_LOCK) {
            if (sleepBreathMetricsCurrent) return 0
            val prefs = context.getSharedPreferences("oplusband-history", Context.MODE_PRIVATE)
            if (prefs.getBoolean("sleep-breath-v1", false)) {
                sleepBreathMetricsCurrent = true
                return 0
            }
            return try {
                val enqueued = rewriteSleepBreathMetrics()
                if (!prefs.edit().putBoolean("sleep-breath-v1", true).commit()) {
                    throw IllegalStateException("SLEEP_BREATH_MEMORY_FAILED")
                }
                sleepBreathMetricsCurrent = true
                if (enqueued > 0) {
                    context.contentResolver.notifyChange(HealthQueueProvider.URI, null)
                    context.contentResolver.notifyChange(HealthQueueProvider.RECORDS_URI, null)
                }
                enqueued
            } catch (_: Exception) {
                // Identity or a file can be unready. The next sleep read tries again.
                0
            }
        }
    }

    /** One pass so daily reports indexed before the resting rate was parsed reach the queue. */
    fun refreshRestingHeart(): Int {
        if (restingHeartCurrent) return 0
        synchronized(FILE_LOCK) {
            if (restingHeartCurrent) return 0
            val prefs = context.getSharedPreferences("oplusband-history", Context.MODE_PRIVATE)
            if (prefs.getBoolean("resting-heart-v1", false)) {
                restingHeartCurrent = true
                return 0
            }
            return try {
                val extended = rewriteRestingHeart()
                if (!prefs.edit().putBoolean("resting-heart-v1", true).commit()) {
                    throw IllegalStateException("RESTING_HEART_MEMORY_FAILED")
                }
                restingHeartCurrent = true
                extended
            } catch (_: Exception) {
                // Identity or a file can be unready. The next replay tries again.
                0
            }
        }
    }

    /** Only the record count grows, so the archive replay picks the new record up by index. */
    @Throws(Exception::class)
    private fun rewriteRestingHeart(): Int {
        val state = LocalPrefs.open(context, "band-state")
        val deviceId = state.getString("deviceId", "")
        val identity = requireMatchingIdentity(context, deviceId)
        val firmware = state.getString("verifiedFirmware", "")
        if (firmware.isBlank()) throw IllegalStateException("HISTORY_FIRMWARE_UNCONFIRMED")
        val parser = BandHistoryParser(firmware, deviceId, identity)
        val existing = directory.listFiles { _, name -> HASHED_DAT_REGEX.matches(name) } ?: return 0
        var extended = 0
        HealthRecordStore(context).use { store ->
            for (file in existing) {
                val hash = file.name.substring(0, 64)
                if (!store.isFileIndexed(hash)) continue
                val bytes = try {
                    readFile(hash)
                } catch (_: IllegalArgumentException) {
                    continue
                }
                try {
                    val descriptor = if (bytes.size > 6) bytes[6].toInt() and 0xff else 0
                    if (((descriptor and 0x7f) ushr 2) != 0 || (descriptor and 3) != 1) continue
                    val result = parser.parseFile(bytes)
                    if ("PARSED" != result.parseStatus) continue
                    if (store.extendParsedRecordCount(hash, result.measurements.size)) extended++
                } catch (_: IllegalArgumentException) {
                    continue
                } finally {
                    Arrays.fill(bytes, 0.toByte())
                }
            }
        }
        return extended
    }

    /** One pass over sport summaries rejected before this parser existed. */
    fun promoteRejectedSport(): Int {
        if (sportSummaryCurrent) return 0
        synchronized(FILE_LOCK) {
            if (sportSummaryCurrent) return 0
            val prefs = context.getSharedPreferences("oplusband-history", Context.MODE_PRIVATE)
            if (prefs.getBoolean("sport-gps-v4", false)) {
                sportSummaryCurrent = true
                return 0
            }
            return try {
                val promoted = rewriteRejectedSport()
                if (!prefs.edit().putBoolean("sport-gps-v4", true).commit()) {
                    throw IllegalStateException("SPORT_SUMMARY_MEMORY_FAILED")
                }
                sportSummaryCurrent = true
                promoted
            } catch (_: Exception) {
                0
            }
        }
    }

    @Throws(Exception::class)
    private fun rewriteRejectedSport(): Int {
        val state = LocalPrefs.open(context, "band-state")
        val deviceId = state.getString("deviceId", "")
        val identity = requireMatchingIdentity(context, deviceId)
        val firmware = state.getString("verifiedFirmware", "")
        if (firmware.isBlank()) throw IllegalStateException("HISTORY_FIRMWARE_UNCONFIRMED")
        val parser = BandHistoryParser(firmware, deviceId, identity)
        val existing = directory.listFiles { _, name -> HASHED_DAT_REGEX.matches(name) } ?: return 0
        var promoted = 0
        HealthRecordStore(context).use { store ->
            for (file in existing) {
                val hash = file.name.substring(0, 64)
                if (!store.isFileIndexed(hash)) continue
                val bytes = try {
                    readFile(hash)
                } catch (_: IllegalArgumentException) {
                    continue
                }
                try {
                    val descriptor = if (bytes.size > 6) bytes[6].toInt() and 0xff else 0
                    if ((descriptor and 0x80) == 0 || ((descriptor and 3) != 1 && (descriptor and 3) != 2)) continue
                    val result = parser.parseFile(bytes)
                    if ("PARSED" != result.parseStatus) continue
                    val sport = result.measurements.any {
                        "sport_session" == it.kind || "sport_track" == it.kind
                    }
                    if (!sport) continue
                    val rejected = store.promoteRejectedFile(hash, result.measurements.size)
                    var refreshed = 0
                    for (measurement in result.measurements) {
                        if ("sport_session" != measurement.kind && "sport_track" != measurement.kind) continue
                        if (store.enqueueMeasurement(measurement).added) refreshed++
                    }
                    if (rejected || refreshed > 0) promoted++
                } catch (_: IllegalArgumentException) {
                    continue
                } finally {
                    Arrays.fill(bytes, 0.toByte())
                }
            }
        }
        return promoted
    }

    /** One pass over files rejected before sleep summary version 4 could be parsed. */
    fun promoteRejectedSleep(): Int {
        if (sleepSummaryCurrent) return 0
        synchronized(FILE_LOCK) {
            if (sleepSummaryCurrent) return 0
            val prefs = context.getSharedPreferences("oplusband-history", Context.MODE_PRIVATE)
            if (prefs.getBoolean("sleep-summary-v4", false)) {
                sleepSummaryCurrent = true
                return 0
            }
            return try {
                val promoted = rewriteRejectedSleep()
                if (!prefs.edit().putBoolean("sleep-summary-v4", true).commit()) {
                    throw IllegalStateException("SLEEP_SUMMARY_MEMORY_FAILED")
                }
                sleepSummaryCurrent = true
                promoted
            } catch (_: Exception) {
                0
            }
        }
    }

    @Throws(Exception::class)
    private fun rewriteRejectedSleep(): Int {
        val state = LocalPrefs.open(context, "band-state")
        val deviceId = state.getString("deviceId", "")
        val identity = requireMatchingIdentity(context, deviceId)
        val firmware = state.getString("verifiedFirmware", "")
        if (firmware.isBlank()) throw IllegalStateException("HISTORY_FIRMWARE_UNCONFIRMED")
        val parser = BandHistoryParser(firmware, deviceId, identity)
        val existing = directory.listFiles { _, name -> HASHED_DAT_REGEX.matches(name) } ?: return 0
        var promoted = 0
        HealthRecordStore(context).use { store ->
            for (file in existing) {
                val hash = file.name.substring(0, 64)
                if (!store.isFileIndexed(hash)) continue
                val bytes = try {
                    readFile(hash)
                } catch (_: IllegalArgumentException) {
                    continue
                }
                try {
                    val descriptor = if (bytes.size > 6) bytes[6].toInt() and 0xff else 0
                    val dailyType = (descriptor and 0x7f) ushr 2
                    val fileType = descriptor and 3
                    val sleepFile = dailyType == 8 && fileType == 1
                    val pulseFile = dailyType == 9 && fileType == 0
                    if (!sleepFile && !pulseFile) continue
                    val result = parser.parseFile(bytes)
                    if ("PARSED" != result.parseStatus) continue
                    var wanted = false
                    for (measurement in result.measurements) {
                        if (sleepFile && ("sleep_interval" == measurement.kind || "sleep_stage" == measurement.kind)) {
                            wanted = true
                        }
                        if (pulseFile && "heart_rate" == measurement.kind) wanted = true
                        if (wanted) break
                    }
                    if (wanted && store.promoteRejectedFile(hash, result.measurements.size)) promoted++
                } catch (_: IllegalArgumentException) {
                    continue
                } finally {
                    Arrays.fill(bytes, 0.toByte())
                }
            }
        }
        return promoted
    }

    @Throws(Exception::class)
    private fun rewriteStepMetrics() {
        val state = LocalPrefs.open(context, "band-state")
        val deviceId = state.getString("deviceId", "")
        val identity = requireMatchingIdentity(context, deviceId)
        val firmware = state.getString("verifiedFirmware", "")
        if (firmware.isBlank()) throw IllegalStateException("HISTORY_FIRMWARE_UNCONFIRMED")
        val parser = BandHistoryParser(firmware, deviceId, identity)
        val existing = directory.listFiles { _, name -> HASHED_DAT_REGEX.matches(name) } ?: return
        HealthRecordStore(context).use { store ->
            for (file in existing) {
                val hash = file.name.substring(0, 64)
                if (!store.isFileIndexed(hash)) continue
                val bytes = try {
                    readFile(hash)
                } catch (_: IllegalArgumentException) {
                    continue
                }
                try {
                    val descriptor = if (bytes.size > 6) bytes[6].toInt() and 0xff else 0
                    if (((descriptor and 0x7f) ushr 2) != 0) continue
                    val result = parser.parseFile(bytes)
                    if ("PARSED" != result.parseStatus) continue
                    for (measurement in result.measurements) {
                        if ("steps_day" != measurement.kind && "steps_interval" != measurement.kind) continue
                        store.enqueueMeasurement(measurement)
                    }
                } catch (_: IllegalArgumentException) {
                    continue
                } finally {
                    Arrays.fill(bytes, 0.toByte())
                }
            }
        }
    }

    @Throws(Exception::class)
    private fun rewriteSleepBreathMetrics(): Int {
        val state = LocalPrefs.open(context, "band-state")
        val deviceId = state.getString("deviceId", "")
        val identity = requireMatchingIdentity(context, deviceId)
        val firmware = state.getString("verifiedFirmware", "")
        if (firmware.isBlank()) throw IllegalStateException("HISTORY_FIRMWARE_UNCONFIRMED")
        val parser = BandHistoryParser(firmware, deviceId, identity)
        val existing = directory.listFiles { _, name -> HASHED_DAT_REGEX.matches(name) } ?: return 0
        var enqueued = 0
        HealthRecordStore(context).use { store ->
            for (file in existing) {
                val hash = file.name.substring(0, 64)
                if (!store.isFileIndexed(hash)) continue
                val bytes = try {
                    readFile(hash)
                } catch (_: IllegalArgumentException) {
                    continue
                }
                try {
                    val descriptor = if (bytes.size > 6) bytes[6].toInt() and 0xff else 0
                    if (((descriptor and 0x7f) ushr 2) != 8 || (descriptor and 3) != 1) continue
                    val result = parser.parseFile(bytes)
                    if ("PARSED" != result.parseStatus) continue
                    for (measurement in result.measurements) {
                        if ("sleep_breath" != measurement.kind) continue
                        if (store.enqueueMeasurement(measurement).added) {
                            enqueued++
                        }
                    }
                } catch (_: IllegalArgumentException) {
                    continue
                } finally {
                    Arrays.fill(bytes, 0.toByte())
                }
            }
        }
        return enqueued
    }

    companion object {
        private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
        private const val MAX_FILES = 2048
        private const val MAX_FILE_BYTES = 1024 * 1024
        private val FILE_LOCK = Any()
        private val HASHED_DAT_REGEX = Regex("[0-9a-f]{64}\\.dat")
        private val MAC_REGEX = Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")

        @Volatile
        private var stepMetricsCurrent = false

        @Volatile
        private var sleepSummaryCurrent = false

        @Volatile
        private var sleepBreathMetricsCurrent = false

        @Volatile
        private var sportSummaryCurrent = false

        @Volatile
        private var restingHeartCurrent = false

        @Throws(Exception::class)
        private fun syncDirectory(directory: File?) {
            if (directory == null || !directory.isDirectory) throw IllegalStateException("HISTORY_DIRECTORY_UNAVAILABLE")
            val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(descriptor)
            } finally {
                Os.close(descriptor)
            }
        }

        @Throws(Exception::class)
        private fun fileHash(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(bytes, 0, 7)
            digest.update(bytes)
            return HexFormat.of().formatHex(digest.digest())
        }

        /** A persistent identity check, deliberately independent of registration, connection and ownership mode. */
        @JvmStatic
        fun requireMatchingIdentity(context: Context, deviceId: String?): String {
            try {
                val state = LocalPrefs.open(context, "band-state")
                val binding = BindingStore(context).readIdentity()
                if (deviceId.isNullOrBlank() ||
                    deviceId != state.getString("deviceId", "") ||
                    binding.address != state.getString("mac", "") ||
                    binding.model != state.getString("modelId", "")
                ) {
                    throw SecurityException("HEALTH_DEVICE_IDENTITY_UNCONFIRMED")
                }
                val did = binding.did
                val address = binding.address
                if (!MAC_REGEX.matches(address)) {
                    throw SecurityException("HEALTH_DEVICE_IDENTITY_UNCONFIRMED")
                }
                val source = if (did.isBlank()) "verifiedMac" else "did"
                if (source != state.getString("identitySource", "")) {
                    throw SecurityException("HEALTH_DEVICE_IDENTITY_UNCONFIRMED")
                }
                val identity = if (did.isBlank()) address.replace(":", "") else did
                val expected = "miband11_" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(identity.toByteArray(StandardCharsets.UTF_8)),
                )
                if (expected != deviceId) throw SecurityException("HEALTH_DEVICE_IDENTITY_UNCONFIRMED")
                return identity
            } catch (rejected: SecurityException) {
                throw rejected
            } catch (unavailable: Exception) {
                throw SecurityException("HEALTH_DEVICE_IDENTITY_UNCONFIRMED", unavailable)
            }
        }
    }
}
