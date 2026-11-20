// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import android.content.Context
import android.os.UserManager
import com.originband.link.integration.DeviceCardProvider
import com.originband.link.protocol.BandBatteryState
import com.originband.link.protocol.BandHistoryParser
import com.originband.link.service.OwnershipController
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.HexFormat
import java.util.Locale

/** Single writer for public, non-secret device state projected into MyDevices. */
class BandStateRepository(context: Context) {
    private val context: Context = context.applicationContext ?: context

    /** True when `address` is the stored band. Throws when the user is still locked. */
    fun matchesAddress(address: String?): Boolean {
        synchronized(STATE_LOCK) {
            if (!isUnlocked()) throw IllegalStateException("USER_LOCKED")
            return MacIds.same(state().getString("mac", ""), address)
        }
    }

    /** Stored band MAC, or empty while the snapshot cannot be read. Used to ask for the system bond again. */
    fun address(): String {
        synchronized(STATE_LOCK) {
            if (!isUnlocked()) return ""
            return state().getString("mac", "")
        }
    }

    /** Stable per-device key used for capability caches. Empty while locked or unregistered. */
    fun deviceId(): String {
        synchronized(STATE_LOCK) {
            if (!isUnlocked()) return ""
            return state().getString("deviceId", "")
        }
    }

    /** Latest total from 8/47 or a stored daily report, or -1 when no step value has arrived. */
    fun stepsToday(): Long {
        synchronized(STATE_LOCK) {
            if (!isUnlocked()) return -1
            return state().getLong("stepsToday", -1)
        }
    }

    /** Time of the latest step sample, or 0 when none has arrived. */
    fun stepsAtMs(): Long {
        synchronized(STATE_LOCK) {
            if (!isUnlocked()) return 0
            return state().getLong("stepsAtMs", 0)
        }
    }

    fun isRegistered(): Boolean {
        synchronized(STATE_LOCK) {
            // Credential-encrypted preferences and binding decryption wait until the user is unlocked.
            return isUnlocked() && state().getBoolean("registered", false)
        }
    }

    @Throws(Exception::class)
    fun registerDevice() {
        synchronized(STATE_LOCK) {
            val state = state()
            val binding = BindingStore(context).read() ?: throw IllegalStateException("UNPROVISIONED")
            val model = binding.optString("model", "")
            if (model.isBlank() ||
                !TransportObservation.supportsLive(TransportObservation.liveProfile(context, model), model)
            ) {
                throw IllegalStateException("OBSERVED_PROFILE_REQUIRED")
            }
            normalizeStoredSecrets(binding)
            val problem = BindingShape.reject(binding)
            SessionLog.line(
                context,
                "bridge BINDING_CHECK " + BindingShape.describe(binding) +
                    " reject=" + (problem ?: "none"),
            )
            if (problem != null) throw IllegalStateException(problem)
            val mac = binding.optString("address", "")
            val deviceId = deviceId(binding)
            val existing = state.getString("deviceId", "")
            // A retained snapshot owns its existing history, even after removal.
            if (existing.isNotEmpty() && existing != deviceId) {
                throw IllegalStateException("DEVICE_IDENTITY_CHANGED")
            }
            if (state.getBoolean("registered", false)) {
                requireMatchingBinding(state, binding)
                return
            }
            val name = BandCatalog.displayName(model, binding.optString("deviceName", ""))
            val now = System.currentTimeMillis()
            val registeredAt = state.getLong("registeredAtMs", 0)
            if (!state.edit().putString("deviceId", deviceId)
                    .putString("identitySource", if (binding.optString("did", "").isBlank()) "verifiedMac" else "did")
                    .putString("name", name).putString("mac", mac).putString("modelId", model)
                    .remove("firmware")
                    .putBoolean("registered", true).putBoolean("connected", false)
                    .putLong("registeredAtMs", if (registeredAt > 0) registeredAt else now)
                    .putLong("lastUpdateMs", now)
                    .putLong("revision", state.getLong("revision", 0) + 1).commit()
            ) {
                throw IllegalStateException("BAND_STATE_STORAGE_FAILED")
            }
            context.contentResolver.notifyChange(DeviceCardProvider.URI, null)
        }
    }

    fun unregisterDevice() {
        synchronized(STATE_LOCK) {
            val state = state()
            if (!state.edit().putBoolean("registered", false).putBoolean("connected", false)
                    .putLong("lastUpdateMs", System.currentTimeMillis())
                    .putLong("revision", state.getLong("revision", 0) + 1).commit()
            ) {
                throw IllegalStateException("BAND_STATE_STORAGE_FAILED")
            }
            context.contentResolver.notifyChange(DeviceCardProvider.URI, null)
        }
    }

    @Throws(Exception::class)
    fun recordVerifiedDevice(
        battery: Int,
        chargerState: Int?,
        connected: Boolean,
        firmware: String?,
        hardware: String?,
        sku: String?,
    ) {
        if (!isRegistered()) return
        val nativeReady = OwnershipController(context).nativeReady()
        synchronized(STATE_LOCK) {
            if (!isRegistered()) return
            if (!nativeReady) {
                throw IllegalStateException("OFFICIAL_MODE")
            }
            val batteryState = BandBatteryState(battery, chargerState)
            val state = state()
            val binding = BindingStore(context).readIdentity()
            if (state.getString("deviceId", "") != binding.deviceId ||
                state.getString("mac", "") != binding.address ||
                state.getString("modelId", "") != binding.model
            ) {
                throw IllegalStateException("DEVICE_IDENTITY_CHANGED")
            }
            if (firmware.isNullOrBlank() || hardware.isNullOrBlank()) {
                throw IllegalStateException("FIRMWARE_OR_MODEL_UNSUPPORTED")
            }
            val previousBattery = state.getInt("battery", -1)
            val previousChargerState = state.getInt("chargerState", 0)
            val editor = state.edit().putBoolean("connected", connected)
                .putString("verifiedFirmware", firmware).putString("verifiedHardware", hardware)
                .putLong("lastUpdateMs", System.currentTimeMillis())
                .putLong("revision", state.getLong("revision", 0) + 1)
            writeBattery(editor, batteryState)
            if (!sku.isNullOrBlank()) editor.putString("sku", sku)
            if (!editor.commit()) {
                throw IllegalStateException("BAND_STATE_STORAGE_FAILED")
            }
            if (previousBattery != battery ||
                previousChargerState != (batteryState.chargerState ?: 0)
            ) {
                // One line per change: the wrist value and the phone value can be compared later.
                SessionLog.line(context, "BATTERY level=$battery chargerState=$chargerState")
            }
            context.contentResolver.notifyChange(DeviceCardProvider.URI, null)
        }
    }

    /** The body picture changed. Bump the card revision so device space reads the new URI. */
    fun noteDeviceImage() {
        synchronized(STATE_LOCK) {
            if (!isUnlocked() || !state().getBoolean("registered", false)) return
            val state = state()
            if (!state.edit().putLong("revision", state.getLong("revision", 0) + 1)
                    .putLong("lastUpdateMs", System.currentTimeMillis()).commit()
            ) {
                return
            }
            context.contentResolver.notifyChange(DeviceCardProvider.URI, null)
        }
    }

    /** Rewrite a spaced or Base64 key, and an uppercased MAC, before the gate runs. */
    @Throws(Exception::class)
    private fun normalizeStoredSecrets(binding: JSONObject) {
        var changed = false
        val token = AuthToken.normalize(binding.optString("token", ""))
        if (AuthToken.hex32(token) && token != binding.optString("token", "")) {
            binding.put("token", token)
            changed = true
        }
        val address = binding.optString("address", "").trim().uppercase(Locale.ROOT)
        if (BindingShape.mac(address) && address != binding.optString("address", "")) {
            binding.put("address", address)
            changed = true
        }
        if (changed) BindingStore(context).save(binding)
    }

    private fun isUnlocked(): Boolean {
        val users = context.getSystemService(UserManager::class.java)
        return users != null && users.isUserUnlocked
    }

    private fun state(): LocalPrefs {
        if (!isUnlocked()) throw IllegalStateException("USER_LOCKED")
        return LocalPrefs.open(context, "band-state")
    }

    fun markSessionClosed() {
        synchronized(STATE_LOCK) {
            if (!isUnlocked()) return
            val state = state()
            if (state.getString("deviceId", "").isBlank() || !state.getBoolean("connected", false)) return
            if (!state.edit().putBoolean("connected", false)
                    .putLong("lastUpdateMs", System.currentTimeMillis())
                    .putLong("revision", state.getLong("revision", 0) + 1).commit()
            ) {
                throw IllegalStateException("BAND_STATE_STORAGE_FAILED")
            }
            context.contentResolver.notifyChange(DeviceCardProvider.URI, null)
        }
    }

    fun disconnected() {
        synchronized(STATE_LOCK) {
            if (!isUnlocked()) return
            val state = state()
            if (!state.getBoolean("connected", false)) return
            if (!state.edit().putBoolean("connected", false).putInt("battery", -1)
                    .putLong("lastUpdateMs", System.currentTimeMillis())
                    .putLong("revision", state.getLong("revision", 0) + 1).commit()
            ) {
                throw IllegalStateException("BAND_STATE_STORAGE_FAILED")
            }
            context.contentResolver.notifyChange(DeviceCardProvider.URI, null)
        }
    }

    fun recordSyncCompleted() {
        synchronized(STATE_LOCK) {
            if (!isRegistered()) return
            val state = state()
            if (!state.edit().putLong("lastSyncAtMs", System.currentTimeMillis())
                    .putLong("revision", state.getLong("revision", 0) + 1).commit()
            ) {
                throw IllegalStateException("BAND_STATE_STORAGE_FAILED")
            }
            context.contentResolver.notifyChange(DeviceCardProvider.URI, null)
        }
    }

    /** Activity rows stay on this band. OHealth's shared daily statistics are written by the health hooks. */
    fun recordSport(stepsToday: Long, heartRate: Int, measuredAtMs: Long) {
        synchronized(STATE_LOCK) {
            if (!isUnlocked()) return
            val state = state()
            if (state.getString("deviceId", "").isBlank()) return
            if (measuredAtMs <= 0) return
            val hasSteps = stepsToday in 0..200_000L &&
                measuredAtMs >= state.getLong("stepsAtMs", 0)
            val hasHeartRate = heartRate in 1..250 &&
                measuredAtMs >= state.getLong("heartRateAtMs", 0)
            if (!hasSteps && !hasHeartRate) return
            val edit = state.edit()
            if (hasSteps) edit.putLong("stepsToday", stepsToday).putLong("stepsAtMs", measuredAtMs)
            if (hasHeartRate) edit.putInt("heartRate", heartRate).putLong("heartRateAtMs", measuredAtMs)
            if (!edit.remove("sportAtMs")
                    .putLong("revision", state.getLong("revision", 0) + 1).commit()
            ) {
                throw IllegalStateException("BAND_STATE_STORAGE_FAILED")
            }
            context.contentResolver.notifyChange(DeviceCardProvider.URI, null)
        }
    }

    /** A daily report replaces the total. Minute sums on the same local day only move the total up. */
    fun recordDailySteps(stepsToday: Long, measuredAtMs: Long, authoritative: Boolean) {
        synchronized(STATE_LOCK) {
            if (!isUnlocked()) return
            val state = state()
            if (state.getString("deviceId", "").isBlank()) return
            if (measuredAtMs <= 0 || stepsToday < 0 || stepsToday > 200_000L) return
            val previousAt = state.getLong("stepsAtMs", 0)
            val previous = state.getLong("stepsToday", -1)
            val sameDay = previousAt > 0 &&
                LocalDate.ofInstant(Instant.ofEpochMilli(previousAt), ZoneId.systemDefault()) ==
                LocalDate.ofInstant(Instant.ofEpochMilli(measuredAtMs), ZoneId.systemDefault())
            if (measuredAtMs < previousAt && !(authoritative && sameDay)) return
            if (!authoritative && sameDay && previous >= stepsToday) return
            val storedAt = if (authoritative && previousAt > measuredAtMs) previousAt else measuredAtMs
            if (previous == stepsToday && storedAt == previousAt) return
            if (!state.edit().putLong("stepsToday", stepsToday).putLong("stepsAtMs", storedAt)
                    .remove("sportAtMs").putLong("revision", state.getLong("revision", 0) + 1).commit()
            ) {
                throw IllegalStateException("BAND_STATE_STORAGE_FAILED")
            }
            context.contentResolver.notifyChange(DeviceCardProvider.URI, null)
        }
    }

    companion object {
        private val STATE_LOCK = Any()
        private val MAC_REGEX = Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")

        /** Clear stale charging telemetry when the current response supplies only a percentage. */
        @JvmStatic
        fun writeBattery(editor: LocalPrefs.Editor, battery: BandBatteryState) {
            editor.putInt("battery", battery.level)
            val charging = battery.charging()
            val chargerState = battery.chargerState
            if (charging == null || chargerState == null) {
                editor.remove("charging").remove("chargerState")
            } else {
                editor.putBoolean("charging", charging).putInt("chargerState", chargerState)
            }
        }

        @JvmStatic
        @Throws(Exception::class)
        fun deviceId(binding: JSONObject): String {
            val did = binding.optString("did", "")
            val mac = binding.optString("address", "")
            if (did.isBlank() && !MAC_REGEX.matches(mac)) {
                throw IllegalStateException("DEVICE_IDENTITY_UNCONFIRMED")
            }
            val identity = if (did.isBlank()) mac.replace(":", "") else did
            return "miband11_" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(identity.toByteArray(StandardCharsets.UTF_8)),
            )
        }

        @Throws(Exception::class)
        private fun requireMatchingBinding(state: LocalPrefs, binding: JSONObject) {
            if (state.getString("deviceId", "") != deviceId(binding) ||
                state.getString("mac", "") != binding.optString("address", "") ||
                state.getString("modelId", "") != binding.optString("model", "")
            ) {
                throw IllegalStateException("DEVICE_IDENTITY_CHANGED")
            }
        }

        /** Already stored files update the card without waiting for another band download. */
        @JvmStatic
        fun refreshStoredSteps(context: Context) {
            val zone = ZoneId.systemDefault()
            val dayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEnd = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            try {
                val binding = BindingStore(context).readIdentity()
                if (binding.deviceId.isBlank()) return
                val rows = HealthRecordStore(context).use { store ->
                    store.storedSteps(binding.deviceId, dayStart, dayEnd)
                }
                val steps = BandHistoryParser.todaySteps(rows, dayStart, dayEnd) ?: return
                BandStateRepository(context).recordDailySteps(steps.steps, steps.measuredAtMs, steps.authoritative)
            } catch (_: Exception) {
                // A locked or unconfirmed store leaves the last displayed total unchanged.
            }
        }
    }
}
