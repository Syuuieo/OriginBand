// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Comparator
import java.util.HexFormat

/** Private CE review state. Callers publish state changes after a confirmed write. */
class StepAggregateAuditStore(private val file: File) {
    constructor(context: Context?) : this(resolveFile(context))

    data class Request(
        @JvmField val account: String,
        @JvmField val queuedDevice: String,
        @JvmField val nativeDevice: String,
        @JvmField val date: Int,
        @JvmField val startMs: Long,
        @JvmField val timezone: String,
        @JvmField val daySteps: Long,
        @JvmField val minuteMode: Int,
    ) {
        init {
            if (!identity(account) || !identity(queuedDevice) || !identity(nativeDevice) ||
                timezone.isBlank() || timezone.length > 128 ||
                date < 19700101 || date > 99991231 || daySteps < 1 || daySteps > Int.MAX_VALUE ||
                (minuteMode != 1 && minuteMode != -1)
            ) {
                throw invalidRequest()
            }
            try {
                val day = LocalDate.of(date / 10_000, date / 100 % 100, date % 100)
                val zone = ZoneId.of(timezone)
                val start = day.atStartOfDay(zone)
                val end = day.plusDays(1).atStartOfDay(zone)
                if (start.toLocalDate() != day || start.toLocalTime() != LocalTime.MIDNIGHT ||
                    end.toLocalTime() != LocalTime.MIDNIGHT ||
                    start.toInstant().toEpochMilli() != startMs ||
                    Math.subtractExact(end.toInstant().toEpochMilli(), startMs) != 86_400_000L
                ) {
                    throw invalidRequest()
                }
            } catch (_: DateTimeException) {
                throw invalidRequest()
            } catch (_: ArithmeticException) {
                throw invalidRequest()
            }
        }

        fun account(): String = account

        fun queuedDevice(): String = queuedDevice

        fun nativeDevice(): String = nativeDevice

        fun date(): Int = date

        fun startMs(): Long = startMs

        fun timezone(): String = timezone

        fun daySteps(): Long = daySteps

        fun minuteMode(): Int = minuteMode

        fun accountHash(): String {
            return try {
                HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(account.toByteArray(StandardCharsets.UTF_8)),
                )
            } catch (_: NoSuchAlgorithmException) {
                throw IllegalStateException("STEP_AUDIT_HASH_UNAVAILABLE")
            }
        }

        fun key(): String = "${accountHash()}:$queuedDevice:$date"

        fun toJson(): JSONObject {
            return try {
                JSONObject().put("account", account).put("queuedDevice", queuedDevice)
                    .put("nativeDevice", nativeDevice).put("date", date).put("startMs", startMs)
                    .put("timezone", timezone).put("daySteps", daySteps).put("minuteMode", minuteMode)
            } catch (_: JSONException) {
                throw IllegalStateException("STEP_AUDIT_SERIALIZATION_FAILED")
            }
        }

        override fun toString(): String =
            "StepAggregateRequest[accountHash=${accountHash()}, date=$date]"

        companion object {
            private val FIELDS = setOf(
                "account", "queuedDevice", "nativeDevice", "date",
                "startMs", "timezone", "daySteps", "minuteMode",
            )

            @JvmStatic
            fun fromJson(json: JSONObject?): Request {
                return try {
                    requireFields(json, FIELDS)
                    val nonNullJson = checkNotNull(json)
                    val date = integer(nonNullJson, "date")
                    val mode = integer(nonNullJson, "minuteMode")
                    if (date < Int.MIN_VALUE || date > Int.MAX_VALUE ||
                        mode < Int.MIN_VALUE || mode > Int.MAX_VALUE
                    ) {
                        throw invalidRequest()
                    }
                    Request(
                        string(nonNullJson, "account"),
                        string(nonNullJson, "queuedDevice"),
                        string(nonNullJson, "nativeDevice"),
                        date.toInt(),
                        integer(nonNullJson, "startMs"),
                        string(nonNullJson, "timezone"),
                        integer(nonNullJson, "daySteps"),
                        mode.toInt(),
                    )
                } catch (_: JSONException) {
                    throw invalidRequest()
                }
            }
        }
    }

    data class Entry(
        @JvmField val request: Request,
        @JvmField val review: StepAggregateReview?,
        @JvmField val approved: Boolean,
    ) {
        fun request(): Request = request

        fun review(): StepAggregateReview? = review

        fun approved(): Boolean = approved
    }

    data class ReportResult(
        @JvmField val approved: Boolean,
        @JvmField val changed: Boolean,
    ) {
        fun approved(): Boolean = approved

        fun changed(): Boolean = changed
    }

    private data class Stored(
        val request: Request,
        val review: StepAggregateReview?,
        val approvedFingerprint: String?,
    ) {
        fun entry(): Entry = Entry(request, review, approvedFingerprint != null)
    }

    @Synchronized
    fun schedule(requests: List<Request?>?): Boolean {
        if (requests == null || requests.size > 4_096) throw invalidRequest()
        val incoming = ArrayList<Request>(requests.size)
        for (item in requests) {
            incoming.add(item ?: throw invalidRequest())
        }
        synchronized(LOCK) {
            val current = read()
            val next = HashMap(current)
            val seen = HashMap<String, Request>()
            for (request in incoming) {
                val duplicate = seen.put(request.key(), request)
                if (duplicate != null && duplicate != request) {
                    throw IllegalArgumentException("STEP_AUDIT_REQUEST_CONFLICT")
                }
                val old = next[request.key()]
                if (old == null || old.request != request) {
                    next[request.key()] = Stored(request, null, null)
                }
            }
            val newest = ArrayList(next.values)
            newest.sortWith(order())
            if (newest.size > MAX_ENTRIES) {
                for (i in MAX_ENTRIES until newest.size) next.remove(newest[i].request.key())
            }
            if (next == current) return false
            commit(next)
            return true
        }
    }

    fun entries(accountHash: String?, queuedDevice: String?): List<Entry> {
        requireScope(accountHash, queuedDevice)
        synchronized(LOCK) {
            val selected = ArrayList<Stored>()
            for (item in read().values) {
                if (item.request.accountHash() == accountHash && item.request.queuedDevice == queuedDevice) {
                    selected.add(item)
                }
            }
            selected.sortWith(order())
            val result = ArrayList<Entry>(selected.size)
            for (item in selected) result.add(item.entry())
            return java.util.List.copyOf(result)
        }
    }

    fun pending(accountHash: String?, queuedDevice: String?): List<StepAggregateReview> {
        val result = ArrayList<StepAggregateReview>()
        for (item in entries(accountHash, queuedDevice)) {
            if (item.review != null) result.add(item.review)
        }
        return java.util.List.copyOf(result)
    }

    fun report(request: Request?, review: StepAggregateReview?): ReportResult {
        if (request == null) throw invalidRequest()
        synchronized(LOCK) {
            val state = read()
            val old = state[request.key()]
            if (old == null || old.request != request) return ReportResult(false, false)
            if (review != null && !matches(request, review)) {
                throw IllegalArgumentException("STEP_AUDIT_REVIEW_MISMATCH")
            }
            val approval = if (review != null && review.fingerprint == old.approvedFingerprint) {
                old.approvedFingerprint
            } else {
                null
            }
            val next = Stored(request, review, approval)
            if (next == old) return ReportResult(approval != null, false)
            state[request.key()] = next
            commit(state)
            return ReportResult(approval != null, true)
        }
    }

    fun approve(accountHash: String?, queuedDevice: String?, fingerprint: String?) {
        requireScope(accountHash, queuedDevice)
        if (!hash(fingerprint)) throw IllegalArgumentException("STEP_AUDIT_CHANGED")
        val nonNullFingerprint = checkNotNull(fingerprint)
        synchronized(LOCK) {
            val state = read()
            var selected: Stored? = null
            for (item in state.values) {
                if (item.review != null && item.request.accountHash() == accountHash &&
                    item.request.queuedDevice == queuedDevice &&
                    item.review.fingerprint == nonNullFingerprint
                ) {
                    if (selected != null) throw IllegalStateException("STEP_AUDIT_CHANGED")
                    selected = item
                }
            }
            if (selected == null) throw IllegalStateException("STEP_AUDIT_CHANGED")
            state[selected.request.key()] = Stored(selected.request, selected.review, nonNullFingerprint)
            commit(state)
        }
    }

    fun complete(request: Request?, review: StepAggregateReview?): Boolean {
        synchronized(LOCK) {
            val state = read()
            if (request == null || review == null) return false
            val old = state[request.key()]
            if (old == null || old.request != request || old.review == null ||
                !matches(request, review) || old.review.fingerprint != review.fingerprint ||
                review.fingerprint != old.approvedFingerprint
            ) {
                return false
            }
            state[request.key()] = Stored(request, null, null)
            commit(state)
            return true
        }
    }

    private fun read(): MutableMap<String, Stored> {
        if (!file.exists()) return HashMap()
        try {
            if (!file.isFile || file.length() < 2 || file.length() > MAX_BYTES) throw corrupt()
            val bytes = Files.readAllBytes(file.toPath())
            if (bytes.size < 2 || bytes.size > MAX_BYTES) throw corrupt()
            val encoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
            val tokens = JSONTokener(encoded)
            val decoded = tokens.nextValue()
            if (decoded !is JSONObject || tokens.nextClean() != '\u0000') throw corrupt()
            if (decoded.length() > MAX_ENTRIES) throw corrupt()
            val result = HashMap<String, Stored>()
            val keys = decoded.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = decoded.get(key)
                if (value !is JSONObject) throw corrupt()
                requireFields(value, ENTRY_FIELDS)
                val rawRequest = value.get("request")
                if (rawRequest !is JSONObject) throw corrupt()
                val request = Request.fromJson(rawRequest)
                if (request.key() != key) throw corrupt()
                val rawReview = value.get("review")
                val review = when (rawReview) {
                    JSONObject.NULL -> null
                    is JSONObject -> StepAggregateReview.fromJson(rawReview)
                    else -> throw corrupt()
                }
                if (review != null && !matches(request, review)) throw corrupt()
                val rawApproval = value.get("approvedFingerprint")
                val approval = when (rawApproval) {
                    JSONObject.NULL -> null
                    is String -> rawApproval
                    else -> throw corrupt()
                }
                if (approval != null && (review == null || !hash(approval) || approval != review.fingerprint)) {
                    throw corrupt()
                }
                result[key] = Stored(request, review, approval)
            }
            return result
        } catch (_: Exception) {
            throw corrupt()
        }
    }

    private fun commit(entries: Map<String, Stored>) {
        val json = JSONObject()
        try {
            for (item in entries.values) {
                json.put(
                    item.request.key(),
                    JSONObject().put("request", item.request.toJson())
                        .put("review", item.review?.toJson() ?: JSONObject.NULL)
                        .put("approvedFingerprint", item.approvedFingerprint ?: JSONObject.NULL),
                )
            }
        } catch (_: JSONException) {
            throw IllegalStateException("STEP_AUDIT_SERIALIZATION_FAILED")
        }
        if (entries.size > MAX_ENTRIES || json.toString().toByteArray(StandardCharsets.UTF_8).size > MAX_BYTES) {
            throw IllegalStateException("STEP_AUDIT_LIMIT_REACHED")
        }
        if (!LocalPrefs.writeJson(file, json)) throw IllegalStateException("STEP_AUDIT_STORAGE_FAILED")
    }

    companion object {
        private val LOCK = Any()
        private const val NAME = "step-aggregate-audits.json"
        private const val MAX_ENTRIES = 93
        private const val MAX_BYTES = 512 * 1024
        private val HEX64_REGEX = Regex("[0-9a-f]{64}")
        private val ENTRY_FIELDS = setOf("request", "review", "approvedFingerprint")

        private fun resolveFile(context: Context?): File {
            if (context == null || context.isDeviceProtectedStorage) {
                throw IllegalArgumentException("CE_STORAGE_REQUIRED")
            }
            val app = context.applicationContext
            val target = app ?: context
            if (target.isDeviceProtectedStorage) throw IllegalArgumentException("CE_STORAGE_REQUIRED")
            return File(target.noBackupFilesDir, NAME)
        }

        private fun order(): Comparator<Stored> =
            compareByDescending<Stored> { it.request.date }
                .thenBy { it.request.key() }

        private fun matches(request: Request, review: StepAggregateReview): Boolean =
            request.accountHash() == review.accountHash &&
                request.queuedDevice == review.queuedDevice &&
                request.nativeDevice == review.nativeDevice &&
                request.date == review.date &&
                request.daySteps == review.expectedSteps.toLong()

        private fun requireScope(accountHash: String?, queuedDevice: String?) {
            if (!hash(accountHash) || !identity(queuedDevice)) {
                throw IllegalArgumentException("STEP_AUDIT_SCOPE_INVALID")
            }
        }

        private fun hash(value: String?): Boolean = value != null && HEX64_REGEX.matches(value)

        private fun identity(value: String?): Boolean {
            if (value.isNullOrBlank() || value.length > 512 || value != value.trim { Character.isWhitespace(it) }) {
                return false
            }
            var i = 0
            while (i < value.length) {
                val character = value[i]
                if (Character.isISOControl(character)) return false
                if (character.isHighSurrogate()) {
                    i++
                    if (i >= value.length || !value[i].isLowSurrogate()) return false
                } else if (character.isLowSurrogate()) {
                    return false
                }
                i++
            }
            return true
        }

        private fun requireFields(json: JSONObject?, fields: Set<String>) {
            if (json == null || json.length() != fields.size) throw invalidRequest()
            val keys = json.keys()
            while (keys.hasNext()) {
                if (!fields.contains(keys.next())) throw invalidRequest()
            }
        }

        @Throws(JSONException::class)
        private fun string(json: JSONObject, name: String): String {
            val value = json.get(name)
            if (value !is String) throw invalidRequest()
            return value
        }

        @Throws(JSONException::class)
        private fun integer(json: JSONObject, name: String): Long {
            val value = json.get(name)
            if (value !is Int && value !is Long) throw invalidRequest()
            return (value as Number).toLong()
        }

        private fun invalidRequest(): IllegalArgumentException =
            IllegalArgumentException("STEP_AUDIT_REQUEST_INVALID")

        private fun corrupt(): IllegalStateException = IllegalStateException("STEP_AUDIT_CORRUPT")
    }
}
