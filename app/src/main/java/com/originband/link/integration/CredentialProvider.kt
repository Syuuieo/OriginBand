// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.integration

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.os.UserManager
import com.originband.link.BuildConfig
import com.originband.link.data.AuthToken
import com.originband.link.data.BindingShape
import com.originband.link.data.BindingStore
import com.originband.link.data.DeviceImageStore
import com.originband.link.data.ImportWindow
import com.originband.link.data.MiFitnessDatabase
import com.originband.link.data.MiFitnessDeviceRows
import com.originband.link.data.ProtocolCaptureStore
import com.originband.link.data.SessionLog
import com.originband.link.data.TransportObservation
import com.originband.link.host.HostIdentity
import org.json.JSONObject

/** A selected-device, process-lifetime import window. No credential read IPC. */
class CredentialProvider : ContentProvider() {
    private lateinit var store: BindingStore
    private val window = ImportWindow()
    private val diagnosticWindow = ImportWindow()
    private var diagnosticWrites = 0
    private val captureWindow = ImportWindow()
    private lateinit var captureStore: ProtocolCaptureStore
    private var captureError = ""
    private var pendingToken = ""

    override fun onCreate(): Boolean {
        val ctx = requireContext()
        store = BindingStore(ctx)
        captureStore = ProtocolCaptureStore(ctx)
        return true
    }

    @Synchronized
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = requireContext()
        val self = HostIdentity.isSelf()
        if (!self) {
            HostIdentity.requireMiCaller(ctx)
        }
        val users = ctx.getSystemService(UserManager::class.java)
        if (users == null || !users.isUserUnlocked) {
            window.close()
            diagnosticWindow.close()
            closeCapture()
            pendingToken = ""
            return result("USER_LOCKED")
        }
        val open = window.isOpen(SystemClock.elapsedRealtime())
        val diagnosticOpen = diagnosticWindow.isOpen(SystemClock.elapsedRealtime())
        val captureOpen = captureWindow.isOpen(SystemClock.elapsedRealtime())
        if (!captureOpen) closeCapture()
        try {
            return when (method) {
                "openWindow" -> {
                    requireSelf(self)
                    if (extras == null) throw IllegalArgumentException("DEVICE_SELECTION_REQUIRED")
                    val address = extras.getString("address", "")
                    window.open(address, SystemClock.elapsedRealtime())
                    diagnosticWindow.open(address, SystemClock.elapsedRealtime())
                    diagnosticWrites = 0
                    closeCapture()
                    result("IMPORT_WINDOW_OPEN")
                }

                "closeWindow" -> {
                    requireSelf(self)
                    window.close()
                    diagnosticWindow.close()
                    closeCapture()
                    pendingToken = ""
                    result("IMPORT_WINDOW_CLOSED")
                }

                "getImportRequest" -> {
                    val request = result(if (open) "IMPORT_WINDOW_OPEN" else "IMPORT_WINDOW_CLOSED")
                    if (open) {
                        request.putString("address", window.address())
                        request.putString("nonce", window.nonce())
                    }
                    request
                }

                "importBinding" -> {
                    if (self) throw SecurityException("MI_CALLER_REQUIRED")
                    importBinding(extras)
                }

                "importFromDatabase" -> {
                    requireSelf(self)
                    importFromDatabase(extras)
                }

                "openDiagnostics" -> {
                    requireSelf(self)
                    val binding = store.read()
                    if (binding == null) {
                        result("UNPROVISIONED")
                    } else {
                        window.close()
                        closeCapture()
                        diagnosticWindow.open(binding.getString("address"), SystemClock.elapsedRealtime())
                        diagnosticWrites = 0
                        result("DIAGNOSTIC_WINDOW_OPEN")
                    }
                }

                "getDiagnosticRequest" -> {
                    val active = when {
                        diagnosticWindow.isOpen(SystemClock.elapsedRealtime()) -> diagnosticWindow
                        open -> window
                        else -> null
                    }
                    val request = result(if (active != null) "DIAGNOSTIC_WINDOW_OPEN" else "DIAGNOSTIC_WINDOW_CLOSED")
                    if (active != null) {
                        request.putString("address", active.address())
                        request.putString("nonce", active.nonce())
                    }
                    request
                }

                "observeTransport" -> {
                    if (self) throw SecurityException("MI_CALLER_REQUIRED")
                    observeTransport(extras)
                }

                "supplementToken" -> {
                    if (self) throw SecurityException("MI_CALLER_REQUIRED")
                    supplementToken(extras)
                }

                "openCapture" -> {
                    requireSelf(self)
                    if (!BuildConfig.DEBUG) throw SecurityException("ANALYSIS_BUILD_REQUIRED")
                    val binding = store.read()
                    if (binding == null) {
                        result("UNPROVISIONED")
                    } else {
                        window.close()
                        closeCapture()
                        captureError = ""
                        captureStore.start()
                        val address = binding.getString("address")
                        captureWindow.open(address, SystemClock.elapsedRealtime())
                        diagnosticWindow.open(address, SystemClock.elapsedRealtime())
                        diagnosticWrites = 0
                        val status = result("PROTOCOL_CAPTURE_OPEN")
                        captureStore.addStatus(status)
                        status
                    }
                }

                "getCaptureRequest" -> {
                    val active = BuildConfig.DEBUG && captureOpen
                    val request = result(if (active) "PROTOCOL_CAPTURE_OPEN" else "PROTOCOL_CAPTURE_CLOSED")
                    if (active) {
                        request.putString("address", captureWindow.address())
                        request.putString("nonce", captureWindow.nonce())
                    }
                    request
                }

                "recordPacket" -> {
                    if (self || !BuildConfig.DEBUG) throw SecurityException("MI_ANALYSIS_CALLER_REQUIRED")
                    if (extras == null) throw SecurityException("PROTOCOL_CAPTURE_CLOSED")
                    captureWindow.authorize(extras.getString("nonce"), extras.getString("address"), SystemClock.elapsedRealtime())
                    captureStore.append(extras)
                    result("PACKET_CAPTURED")
                }

                "captureFault" -> {
                    if (self || !BuildConfig.DEBUG || extras == null) {
                        throw SecurityException("MI_ANALYSIS_CALLER_REQUIRED")
                    }
                    captureWindow.authorize(extras.getString("nonce"), extras.getString("address"), SystemClock.elapsedRealtime())
                    captureError = "CAPTURE_INCOMPLETE"
                    closeCapture()
                    ctx.contentResolver.notifyChange(URI, null)
                    result(captureError)
                }

                "status" -> {
                    requireSelf(self)
                    val saved = store.read()
                    val observation = TransportObservation.liveProfile(ctx, saved?.optString("model", "") ?: "")
                    val ready = saved != null && TransportObservation.supportsLive(
                        observation,
                        saved.optString("model", ""),
                    )
                    val status = result(
                        when {
                            open -> "IMPORT_WINDOW_OPEN"
                            saved == null -> "UNPROVISIONED"
                            ready -> "BINDING_SAVED"
                            else -> "BINDING_INCOMPLETE"
                        },
                    )
                    if (saved != null) {
                        TransportObservation.applyToBinding(saved, observation)
                        for (field in STATUS_FIELDS) {
                            status.putString(field, saved.optString(field, ""))
                        }
                        status.putString("missing", TransportObservation.missingForLive(saved, observation))
                        status.putString("transportObservation", observation.toString())
                        status.putBoolean("diagnosticOpen", diagnosticOpen)
                        status.putBoolean("captureOpen", captureOpen)
                        status.putString("captureError", captureError)
                        captureStore.addStatus(status)
                    }
                    status
                }

                else -> throw IllegalArgumentException("UNSUPPORTED_OPERATION")
            }
        } catch (rejected: SecurityException) {
            throw rejected
        } catch (rejected: IllegalArgumentException) {
            throw rejected
        } catch (unavailable: Exception) {
            if (unavailable is IllegalStateException && "USER_LOCKED" == unavailable.message) {
                window.close()
                diagnosticWindow.close()
                pendingToken = ""
                if (captureOpen) captureError = "CAPTURE_STORAGE_FAILED"
                closeCapture()
                return result("USER_LOCKED")
            }
            var kind = unavailable.javaClass.simpleName
            val detail = unavailable.message
            if (!detail.isNullOrBlank()) kind = "$kind $detail"
            SessionLog.line(ctx, "bridge STORAGE_FAIL $kind")
            window.close()
            diagnosticWindow.close()
            pendingToken = ""
            if (captureOpen) captureError = "CAPTURE_STORAGE_FAILED"
            closeCapture()
            val failed = result("CREDENTIAL_STORAGE_FAILED")
            failed.putString("detail", kind)
            return failed
        }
    }

    private fun closeCapture() {
        captureWindow.close()
        try {
            captureStore.close()
        } catch (_: Exception) {
            captureError = "CAPTURE_CLOSE_FAILED"
        }
    }

    @Throws(Exception::class)
    private fun observeTransport(input: Bundle?): Bundle {
        if (input == null) throw SecurityException("DIAGNOSTIC_WINDOW_CLOSED")
        val nonce = input.getString("nonce")
        val address = input.getString("address")
        val now = SystemClock.elapsedRealtime()
        try {
            diagnosticWindow.authorize(nonce, address, now)
        } catch (_: SecurityException) {
            window.authorize(nonce, address, now)
        }
        if (diagnosticWrites >= 32) {
            diagnosticWindow.close()
            throw SecurityException("DIAGNOSTIC_LIMIT_REACHED")
        }
        val ctx = requireContext()
        val observation = TransportObservation.read(ctx)
        for (name in OBSERVE_STRINGS) {
            val value = input.getString(name)
            if (!value.isNullOrBlank()) {
                if (value.length > 256) throw IllegalArgumentException("OBSERVATION_TOO_LARGE")
                observation.put(name, value)
            }
        }
        for (name in OBSERVE_INTS) {
            if (input.containsKey(name)) observation.put(name, input.getInt(name))
        }
        for (name in OBSERVE_BOOLEANS) {
            if (input.containsKey(name)) observation.put(name, input.getBoolean(name))
        }
        observation.put("recordedAtMs", System.currentTimeMillis())
        if (!TransportObservation.write(ctx, observation)) {
            throw IllegalStateException("OBSERVATION_STORAGE_FAILED")
        }
        val saved = store.read()
        if (saved != null) {
            TransportObservation.applyToBinding(saved, observation)
            saved.put("missing", TransportObservation.missingForLive(saved, observation))
            store.save(saved)
        }
        diagnosticWrites++
        ctx.contentResolver.notifyChange(URI, null)
        return result("TRANSPORT_OBSERVED")
    }

    @Throws(Exception::class)
    private fun supplementToken(input: Bundle?): Bundle {
        if (input == null) throw SecurityException("DIAGNOSTIC_WINDOW_CLOSED")
        val nonce = input.getString("nonce")
        val address = input.getString("address")
        val now = SystemClock.elapsedRealtime()
        try {
            diagnosticWindow.authorize(nonce, address, now)
        } catch (_: SecurityException) {
            window.authorize(nonce, address, now)
        }
        val token = input.getString("token")
        if (token == null || !AuthToken.hex32(token)) {
            throw IllegalArgumentException("TOKEN_ENCODING_UNSUPPORTED")
        }
        val saved = store.read()
        if (saved == null) {
            pendingToken = token
            return result("TOKEN_PENDING")
        }
        if (AuthToken.hex32(saved.optString("token"))) {
            return result("TOKEN_ALREADY_PRESENT")
        }
        saved.put("token", token)
        val ctx = requireContext()
        val observation = TransportObservation.read(ctx)
        TransportObservation.applyToBinding(saved, observation)
        saved.put("missing", TransportObservation.missingForLive(saved, observation))
        store.save(saved)
        pendingToken = ""
        ctx.contentResolver.notifyChange(URI, null)
        val ready = saved.optString("missing").isBlank() &&
            TransportObservation.supportsLive(observation, saved.optString("model", ""))
        return result(if (ready) "BINDING_SAVED" else "BINDING_INCOMPLETE")
    }

    @Throws(Exception::class)
    private fun importBinding(input: Bundle?): Bundle {
        if (input == null) throw SecurityException("IMPORT_WINDOW_CLOSED")
        window.authorize(input.getString("nonce"), input.getString("address"), SystemClock.elapsedRealtime())
        val record = JSONObject()
        for (key in STRINGS) {
            val value = input.getString(key)
            if (value != null) {
                if (value.length > 4096) throw IllegalArgumentException("FIELD_TOO_LARGE")
                record.put(key, value)
            }
        }
        if (!AuthToken.hex32(record.optString("token")) && AuthToken.hex32(pendingToken)) {
            record.put("token", pendingToken)
            pendingToken = ""
        }
        val token = AuthToken.normalize(record.optString("token", ""))
        if (AuthToken.hex32(token)) record.put("token", token)
        for (key in IMPORT_INTS) {
            if (input.containsKey(key)) record.put(key, input.getInt(key))
        }
        val uuids = input.getBundle("privateUUID")
        if (uuids != null) {
            val privateUUID = JSONObject()
            for (key in UUID_FIELDS) {
                val value = uuids.getString(key)
                if (value != null) {
                    if (value.length > 128) throw IllegalArgumentException("UUID_TOO_LARGE")
                    privateUUID.put(key, value)
                }
            }
            record.put("privateUUID", privateUUID)
        }
        val ctx = requireContext()
        val observation = TransportObservation.read(ctx)
        TransportObservation.applyToBinding(record, observation)
        record.put("missing", TransportObservation.missingForLive(record, observation))
        SessionLog.line(ctx, "bridge IMPORT_FIELDS ${BindingShape.describe(record)}")
        store.save(record)
        closeCapture()
        window.close()
        ctx.contentResolver.notifyChange(URI, null)
        val ready = record.optString("missing").isBlank() &&
            TransportObservation.supportsLive(observation, record.optString("model", ""))
        return result(if (ready) "BINDING_SAVED" else "BINDING_INCOMPLETE")
    }

    @Throws(Exception::class)
    private fun importFromDatabase(extras: Bundle?): Bundle {
        val ctx = requireContext()
        val address = extras?.getString("address", "") ?: ""
        val snapshot = try {
            MiFitnessDatabase.read(ctx)
        } catch (failure: MiFitnessDatabase.Failure) {
            SessionLog.line(ctx, "bridge IMPORT_DB_FAIL ${failure.code}")
            return result(failure.code)
        }
        val rows = MiFitnessDeviceRows.select(snapshot.details, address)
        if (rows.isEmpty()) {
            SessionLog.line(ctx, "bridge IMPORT_DB_FAIL NO_BAND_KEY")
            return result("NO_BAND_KEY")
        }
        if (rows.size > 1) {
            val many = result("MULTIPLE_BANDS")
            val choices = Array(rows.size) { index ->
                val row = rows[index]
                "${row.address}\t${row.model}\t${row.name}"
            }
            many.putStringArray("choices", choices)
            return many
        }
        val row = rows[0]
        val record = JSONObject()
        record.put("address", row.address)
        record.put("model", row.model)
        record.put("token", row.token)
        if (row.did.isNotEmpty()) record.put("did", row.did)
        if (row.productId.isNotEmpty()) record.put("productId", row.productId)
        if (row.name.isNotEmpty()) record.put("deviceName", row.name)
        if (row.phoneId.isNotEmpty()) record.put("appDeviceId", row.phoneId)
        if (snapshot.userId.isNotEmpty()) record.put("userId", snapshot.userId)
        if (snapshot.region.isNotEmpty()) record.put("region", snapshot.region)
        val observation = TransportObservation.liveProfile(ctx, row.model)
        TransportObservation.applyToBinding(record, observation)
        record.put("missing", TransportObservation.missingForLive(record, observation))
        SessionLog.line(ctx, "bridge IMPORT_DB ${BindingShape.describe(record)}")
        store.save(record)
        closeCapture()
        window.close()
        ctx.contentResolver.notifyChange(URI, null)
        DeviceImageStore.requestRefresh(ctx)
        val ready = record.optString("missing").isBlank() &&
            TransportObservation.supportsLive(observation, record.optString("model", ""))
        val saved = result(if (ready) "BINDING_SAVED" else "BINDING_INCOMPLETE")
        saved.putString("model", record.optString("model", ""))
        saved.putString("productId", record.optString("productId", ""))
        saved.putString("firmware", record.optString("firmware", ""))
        saved.putString("missing", record.optString("missing", ""))
        return saved
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? {
        throw SecurityException("CREDENTIAL_QUERY_FORBIDDEN")
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw SecurityException("USE_IMPORT_WINDOW")
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int {
        throw SecurityException("USE_IMPORT_WINDOW")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        throw SecurityException("CREDENTIAL_DELETE_FORBIDDEN")
    }

    companion object {
        @JvmField
        val URI: Uri = Uri.parse("content://com.originband.link.credentials")

        private val STRINGS = arrayOf(
            "address", "did", "model", "productId", "userId",
            "region", "appDeviceId", "token", "firmware", "oob", "deviceName",
        )
        private val UUID_FIELDS = arrayOf(
            "fitness", "mass", "otaRX", "otaTX", "protoRX", "protoTX", "service", "voice",
        )
        private val STATUS_FIELDS = arrayOf("address", "model", "productId", "firmware")
        private val OBSERVE_STRINGS = arrayOf(
            "transport", "connectionClass", "queueClass", "versionName",
            "authImplementation", "rfcommUuid", "model", "firmware", "productId",
        )
        private val OBSERVE_INTS = arrayOf("apiVersion", "authCtorVersion", "appCapability", "deviceType", "accessType")
        private val OBSERVE_BOOLEANS = arrayOf(
            "officialAuthConnected", "authFlagObserved", "rfcommSecure",
            "rfcommSocketConnected", "authAppDeviceIdPresent", "authOobPresent",
        )
        private val IMPORT_INTS = arrayOf("type", "accessType")

        private fun requireSelf(self: Boolean) {
            if (!self) throw SecurityException("OWNER_ONLY")
        }

        private fun result(status: String): Bundle {
            val out = Bundle()
            out.putString("status", status)
            return out
        }
    }
}
