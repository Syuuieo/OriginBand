// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.integration

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import com.originband.link.data.BandStateRepository
import com.originband.link.data.HealthRecordStore
import com.originband.link.data.LocalPrefs
import com.originband.link.data.RawFitnessFileStore
import com.originband.link.data.StepAggregateAuditStore
import com.originband.link.data.StepAggregateAuditStore.Request
import com.originband.link.data.StepAggregateReview
import com.originband.link.host.HostIdentity
import com.originband.link.protocol.BandHistoryParser.Measurement
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Pending writes in credential-encrypted storage. Callers are checked by package name and read only the confirmed account. */
class HealthQueueProvider : ContentProvider() {
    private lateinit var store: HealthRecordStore
    private var proposedFingerprint: String? = null
    private var proposedAtMs = 0L

    override fun onCreate(): Boolean {
        store = HealthRecordStore(requireContext())
        return true
    }

    private fun requireUnlocked() {
        val users = requireContext().getSystemService(UserManager::class.java)
        if (users == null || !users.isUserUnlocked) {
            throw SecurityException("HEALTH_USER_LOCKED")
        }
    }

    private fun requireCaller(): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return true
        if (!HostIdentity.uidHas(context, uid, HOST)) {
            throw SecurityException("HEALTH_CALLER_NOT_AUTHORIZED")
        }
        return false
    }

    private fun requireCollectionReady(deviceId: String) {
        if (!store.ownsDeviceArchive(deviceId)) {
            throw SecurityException("HEALTH_ARCHIVE_OWNERSHIP_REQUIRED")
        }
    }

    @Synchronized
    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        requireCaller()
        requireUnlocked()
        val history = recordsUri(uri)
        if (sortOrder != null || (projection != null && !projection.contentEquals(COLUMNS))) {
            throw IllegalArgumentException("UNSUPPORTED_HEALTH_PROJECTION")
        }
        val ctx = requireContext()
        val cursor: Cursor
        if (history) {
            if ("account=? AND deviceId=? AND kind=? AND startMs<? AND endMs>?" != selection ||
                selectionArgs == null || selectionArgs.size != 5
            ) {
                throw IllegalArgumentException("HEALTH_RECORD_SELECTION_REQUIRED")
            }
            if ("steps_day" == selectionArgs[2] || "steps_interval" == selectionArgs[2]) {
                RawFitnessFileStore(ctx).refreshStepMetrics()
            }
            if ("sleep_interval" == selectionArgs[2] || "sleep_stage" == selectionArgs[2] ||
                "sleep_breath" == selectionArgs[2]
            ) {
                RawFitnessFileStore(ctx).refreshSleepBreathMetrics()
            }
            cursor = store.records(
                selectionArgs[0],
                selectionArgs[1],
                selectionArgs[2],
                selectionArgs[4].toLong(),
                selectionArgs[3].toLong(),
                uri.getQueryParameter("after"),
            )
        } else {
            if ("account=?" != selection || selectionArgs == null || selectionArgs.size != 1) {
                throw IllegalArgumentException("HEALTH_ACCOUNT_SELECTION_REQUIRED")
            }
            cursor = store.pending(selectionArgs[0])
        }
        cursor.setNotificationUri(ctx.contentResolver, if (history) RECORDS_URI else URI)
        return cursor
    }

    @Synchronized
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val self = requireCaller()
        requireUnlocked()
        when (method) {
            "scheduleStepAudits",
            "stepAudits",
            "reportStepAudit",
            "completeStepAudit",
            "approveStepAudit",
            -> return stepAudit(method, self, extras)

            "adoptAccount" -> {
                if (self || extras == null) throw SecurityException("OHEALTH_CALLER_REQUIRED")
                val account = extras.getString("account")
                if (account.isNullOrBlank() || account.length > 512 ||
                    HostIdentity.HEALTH_PACKAGE == account
                ) {
                    return status(store.accountState())
                }
                store.confirmAccountHash(HealthRecordStore.hashAccount(account))
                notifyRecordsChanged()
                return status("HEALTH_ACCOUNT_CONFIRMED")
            }

            "proposeAccount" -> {
                if (self || extras == null) throw SecurityException("OHEALTH_CALLER_REQUIRED")
                val account = extras.getString("account")
                proposedFingerprint = HealthRecordStore.hashAccount(account)
                proposedAtMs = SystemClock.elapsedRealtime()
                store.authorizedAccount(account) // The account Health presented becomes the write target.
                notifyRecordsChanged()
                return status("ACCOUNT_PROPOSED")
            }

            "confirmProposedAccount" -> {
                requireSelf(self)
                if (extras == null || !extras.getBoolean("userConfirmed", false)) {
                    throw SecurityException("HEALTH_ACCOUNT_USER_CONFIRMATION_REQUIRED")
                }
                val fingerprint = proposedFingerprint
                if (fingerprint == null || SystemClock.elapsedRealtime() - proposedAtMs > 300_000) {
                    throw IllegalStateException("HEALTH_ACCOUNT_PROPOSAL_EXPIRED")
                }
                if (fingerprint != extras.getString("expectedFingerprint")) {
                    throw SecurityException("HEALTH_ACCOUNT_PROPOSAL_CHANGED")
                }
                store.confirmAccountHash(fingerprint)
                proposedFingerprint = null
                notifyRecordsChanged()
                return status("HEALTH_ACCOUNT_CONFIRMED")
            }

            "enqueueMeasurement" -> {
                requireSelf(self)
                val raw = extras?.getString("measurement")
                    ?: throw IllegalArgumentException("HEALTH_MEASUREMENT_REQUIRED")
                try {
                    val measurement = Measurement.fromJson(JSONObject(raw))
                    requireCollectionReady(measurement.deviceId)
                    val queued = store.enqueueMeasurement(measurement)
                    if (queued.added) notifyRecordsChanged()
                    val result = status(if (queued.added) "HEALTH_QUEUED" else "HEALTH_ALREADY_QUEUED")
                    result.putInt("revision", queued.revision)
                    return result
                } catch (malformed: JSONException) {
                    throw IllegalArgumentException("INVALID_HEALTH_MEASUREMENT", malformed)
                }
            }

            "status" -> {
                requireSelf(self)
                val result = status(store.accountState())
                val fingerprint = proposedFingerprint
                val elapsed = SystemClock.elapsedRealtime() - proposedAtMs
                if (fingerprint != null &&
                    fingerprint != store.confirmedAccountHash() &&
                    elapsed <= 300_000
                ) {
                    result.putString("proposedFingerprintFull", fingerprint)
                    result.putString("proposedFingerprint", fingerprint.substring(0, 12))
                    result.putLong("proposedRemainingMs", 300_000 - elapsed)
                }
                return result
            }

            "ack" -> {
                if (self) throw SecurityException("OHEALTH_CALLER_REQUIRED")
                if (extras == null || extras.keySet() != setOf("account", "recordId", "revision")) {
                    throw IllegalArgumentException("HEALTH_RECEIPT_REQUIRED")
                }
                val removed = store.acknowledge(
                    extras.getString("account"),
                    extras.getString("recordId"),
                    extras.getInt("revision", 0),
                )
                if (removed != 0) notifyRecordsChanged()
                val result = status("HEALTH_ACKNOWLEDGED")
                result.putInt("acknowledged", removed)
                return result
            }

            "releaseUnsupported" -> {
                if (self) throw SecurityException("OHEALTH_CALLER_REQUIRED")
                if (extras == null || extras.keySet() != setOf("account", "recordId", "revision")) {
                    throw IllegalArgumentException("HEALTH_RECEIPT_REQUIRED")
                }
                val removed = try {
                    store.releaseUnsupported(
                        extras.getString("account"),
                        extras.getString("recordId"),
                        extras.getInt("revision", 0),
                    )
                } catch (malformed: JSONException) {
                    throw IllegalArgumentException("INVALID_HEALTH_RECORD", malformed)
                }
                if (removed != 0) notifyRecordsChanged()
                val result = status("HEALTH_HOST_UNSUPPORTED")
                result.putInt("released", removed)
                return result
            }

            "unmeasuredStress" -> {
                if (self) throw SecurityException("OHEALTH_CALLER_REQUIRED")
                val account = account(extras)
                val gaps = store.unmeasuredStress(account)
                val starts = LongArray(gaps.size)
                val ends = LongArray(gaps.size)
                val devices = Array(gaps.size) { i ->
                    starts[i] = gaps[i].startMs
                    ends[i] = gaps[i].endMs
                    gaps[i].deviceId
                }
                val result = status("STRESS_GAPS")
                result.putLongArray("starts", starts)
                result.putLongArray("ends", ends)
                result.putStringArray("devices", devices)
                return result
            }

            "forgetUnmeasuredStress" -> {
                if (self) throw SecurityException("OHEALTH_CALLER_REQUIRED")
                val removed = store.forgetUnmeasuredStress(account(extras))
                if (removed != 0) notifyRecordsChanged()
                val result = status("STRESS_ZEROS_FORGOTTEN")
                result.putInt("removed", removed)
                return result
            }

            else -> throw IllegalArgumentException("UNSUPPORTED_HEALTH_OPERATION")
        }
    }

    private fun stepAudit(method: String, self: Boolean, extras: Bundle?): Bundle {
        if (self != ("approveStepAudit" == method)) throw SecurityException("STEP_AUDIT_CALLER_REQUIRED")
        val ctx = requireContext()
        val band = BandStateRepository(ctx)
        val device = LocalPrefs.open(ctx, "band-state").getString("deviceId", "")
        val account = store.confirmedAccountHash()
        if (!band.isRegistered() || device.isBlank() || account == null) {
            throw SecurityException("STEP_AUDIT_COLLECTION_REQUIRED")
        }
        val audits = StepAggregateAuditStore(ctx)
        try {
            if ("stepAudits" == method) {
                val entries = JSONArray()
                for (entry in audits.entries(account, device)) {
                    requireStepRequest(entry.request)
                    val item = JSONObject().put("request", entry.request.toJson())
                        .put("approved", entry.approved)
                    if (entry.review != null) item.put("review", entry.review.toJson())
                    entries.put(item)
                }
                val result = Bundle()
                result.putString("entries", entries.toString())
                return result
            }
            if (extras == null) throw IllegalArgumentException("STEP_AUDIT_PAYLOAD_REQUIRED")
            if ("approveStepAudit" == method) {
                if (extras.keySet() != setOf("fingerprint")) {
                    throw IllegalArgumentException("STEP_AUDIT_PAYLOAD_REQUIRED")
                }
                audits.approve(account, device, extras.getString("fingerprint", ""))
                ctx.contentResolver.notifyChange(STEP_REVIEWS_URI, null)
                ctx.contentResolver.notifyChange(STEP_AUDITS_URI, null)
                return status("STEP_AUDIT_APPROVED")
            }
            if ("scheduleStepAudits" == method) {
                if (extras.keySet() != setOf("requests")) {
                    throw IllegalArgumentException("STEP_AUDIT_PAYLOAD_REQUIRED")
                }
                val encoded = JSONArray(extras.getString("requests", "[]"))
                if (encoded.length() > 31) throw IllegalArgumentException("STEP_AUDIT_LIMIT")
                val requests = ArrayList<Request>()
                for (i in 0 until encoded.length()) {
                    val request = Request.fromJson(encoded.getJSONObject(i))
                    requireStepRequest(request)
                    requests.add(request)
                }
                if (audits.schedule(requests)) {
                    ctx.contentResolver.notifyChange(STEP_AUDITS_URI, null)
                    ctx.contentResolver.notifyChange(STEP_REVIEWS_URI, null)
                }
                return status("STEP_AUDIT_SCHEDULED")
            }
            val allowed = setOf("request", "review")
            if (!allowed.containsAll(extras.keySet()) || !extras.containsKey("request")) {
                throw IllegalArgumentException("STEP_AUDIT_PAYLOAD_REQUIRED")
            }
            val request = Request.fromJson(JSONObject(extras.getString("request", "")))
            requireStepRequest(request)
            val review = if (extras.containsKey("review")) {
                StepAggregateReview.fromJson(JSONObject(extras.getString("review", "")))
            } else {
                null
            }
            if ("completeStepAudit" == method) {
                if (review == null || !audits.complete(request, review)) {
                    throw IllegalStateException("STEP_AUDIT_CHANGED")
                }
                ctx.contentResolver.notifyChange(STEP_REVIEWS_URI, null)
                ctx.contentResolver.notifyChange(STEP_REPAIRED_URI, null)
                return status("STEP_AUDIT_COMPLETED")
            }
            val outcome = audits.report(request, review)
            if (outcome.changed) ctx.contentResolver.notifyChange(STEP_REVIEWS_URI, null)
            val result = status("STEP_AUDIT_REPORTED")
            result.putBoolean("approved", outcome.approved)
            return result
        } catch (invalid: JSONException) {
            throw IllegalArgumentException("STEP_AUDIT_PAYLOAD_INVALID", invalid)
        }
    }

    private fun requireStepRequest(request: Request) {
        val account = store.confirmedAccountHash()
        val ctx = requireContext()
        val band = BandStateRepository(ctx)
        val device = LocalPrefs.open(ctx, "band-state").getString("deviceId", "")
        if (account == null || account != request.accountHash() || !band.isRegistered() ||
            device != request.queuedDevice || band.address() != request.nativeDevice
        ) {
            throw SecurityException("STEP_AUDIT_IDENTITY_CHANGED")
        }
        requireCollectionReady(device)
    }

    private fun notifyRecordsChanged() {
        val resolver = requireContext().contentResolver
        resolver.notifyChange(URI, null)
        resolver.notifyChange(RECORDS_URI, null)
    }

    private fun status(code: String): Bundle {
        val result = Bundle()
        result.putString("status", code)
        result.putInt("pendingCount", store.count())
        return result
    }

    override fun getType(uri: Uri): String {
        requireCaller()
        return "vnd.android.cursor.dir/vnd.$AUTHORITY${if (recordsUri(uri)) ".records" else ".pending"}"
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        requireCaller()
        throw SecurityException("HEALTH_CALL_ONLY")
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int {
        requireCaller()
        throw SecurityException("HEALTH_CALL_ONLY")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        requireCaller()
        throw SecurityException("HEALTH_READBACK_REQUIRED")
    }

    companion object {
        const val AUTHORITY = "com.originband.link.health"

        @JvmField
        val URI: Uri = Uri.parse("content://$AUTHORITY/pending")

        @JvmField
        val RECORDS_URI: Uri = Uri.parse("content://$AUTHORITY/records")

        @JvmField
        val STEP_AUDITS_URI: Uri = Uri.parse("content://$AUTHORITY/step-audits")

        @JvmField
        val STEP_REVIEWS_URI: Uri = Uri.parse("content://$AUTHORITY/step-reviews")

        @JvmField
        val STEP_REPAIRED_URI: Uri = Uri.parse("content://$AUTHORITY/step-repaired")

        private const val HOST = HostIdentity.HEALTH_PACKAGE
        private val COLUMNS = arrayOf("recordId", "revision", "record")

        private fun requireSelf(self: Boolean) {
            if (!self) throw SecurityException("HEALTH_SELF_REQUIRED")
        }

        private fun recordsUri(uri: Uri?): Boolean {
            if (URI == uri) return false
            if (uri == null ||
                RECORDS_URI != uri.buildUpon().clearQuery().build() ||
                !setOf("after").containsAll(uri.queryParameterNames) ||
                uri.getQueryParameters("after").size > 1
            ) {
                throw IllegalArgumentException("UNSUPPORTED_HEALTH_URI")
            }
            return true
        }

        private fun account(extras: Bundle?): String? {
            if (extras == null || extras.keySet() != setOf("account")) {
                throw IllegalArgumentException("HEALTH_ACCOUNT_REQUIRED")
            }
            return extras.getString("account")
        }
    }
}
