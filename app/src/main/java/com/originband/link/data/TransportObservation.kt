// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import android.content.Context
import org.json.JSONException
import org.json.JSONObject

/** Last checked transport profile, SPP or GATT. Stored under no_backup so a preference redirect cannot hide it, and a new diagnostic capture replaces it. */
object TransportObservation {
    const val APP_CAPABILITY = 25_171_686

    /** Mi Fitness CAPABILITY_SUPPORT_ZEN_RULE_SYNC. The band hides 「同步手机勿扰」(sync phone DND) without this bit. */
    const val ZEN_RULE_SYNC = 0x100000

    private const val NAME = "transport-observation"
    private const val KEY = "snapshot"
    private const val SERVICE = "00001101-0000-1000-8000-00805f9b34fb"

    @JvmStatic
    fun withZenRuleSync(captured: Int): Int = captured or ZEN_RULE_SYNC

    /**
     * A saved capture wins. Otherwise a known Xiaomi band gets BLE or SPP defaults,
     * so setup does not wait for another official-app reconnect.
     */
    @JvmStatic
    fun liveProfile(context: Context, model: String?): JSONObject {
        val saved = read(context)
        if (supportsLive(saved, model)) return saved
        val known = knownProfile(model) ?: return saved
        write(context, known)
        return known
    }

    @JvmStatic
    fun knownProfile(model: String?): JSONObject? {
        if (model == null || !(model.startsWith("miwear.watch.") || model.startsWith("lchz.watch."))) {
            return null
        }
        return try {
            val profile = JSONObject()
                .put("model", model)
                .put("officialAuthConnected", true)
                .put("appCapability", APP_CAPABILITY)
                .put("authOobPresent", false)
                .put("authAppDeviceIdPresent", false)
                .put("authImplementation", "WearAuthV2")
            if (model.contains(".m66")) {
                profile.put("transport", "GATT")
                return profile
            }
            val legacy = model.startsWith("lchz.") || model.contains(".m67")
            profile.put("transport", "SPP")
                .put("versionName", if (legacy) "1.0.50" else "3.0.0")
                .put(
                    "queueClass",
                    if (legacy) {
                        "com.xiaomi.wearable.transport.queue.SppTaskQueueV1"
                    } else {
                        "com.xiaomi.wearable.transport.queue.SppTaskQueueV2"
                    },
                )
                .put("rfcommUuid", SERVICE)
                .put("rfcommSecure", true)
            profile
        } catch (_: JSONException) {
            null
        }
    }

    @JvmStatic
    fun read(context: Context): JSONObject {
        val snapshot = LocalPrefs.open(context, NAME).getString(KEY, "")
        if (snapshot.isBlank()) return JSONObject()
        return try {
            JSONObject(snapshot)
        } catch (_: JSONException) {
            JSONObject()
        }
    }

    @JvmStatic
    fun write(context: Context, observation: JSONObject?): Boolean {
        if (observation == null) return false
        return LocalPrefs.open(context, NAME).edit().putString(KEY, observation.toString()).commit()
    }

    /**
     * Official reconnect: WearAuthV2, no OOB. SPP uses secure RFCOMM; GATT uses the captured BLE path.
     */
    @JvmStatic
    fun supportsLive(observation: JSONObject?, bindingModel: String?): Boolean {
        if (observation == null || bindingModel.isNullOrBlank()) return false
        val model = observation.optString("model", "")
        if (!observation.has("model") || !observation.has("transport") ||
            !observation.has("officialAuthConnected") || !observation.has("appCapability") ||
            !observation.has("authOobPresent") || !observation.has("authAppDeviceIdPresent") ||
            bindingModel != model ||
            !observation.optBoolean("officialAuthConnected") ||
            observation.optInt("appCapability") <= 0 ||
            observation.optBoolean("authOobPresent") ||
            observation.optBoolean("authAppDeviceIdPresent")
        ) {
            return false
        }
        val transport = observation.optString("transport")
        if (transport == "GATT") return true
        val versionName = observation.optString("versionName", "")
        return transport == "SPP" &&
            observation.has("versionName") && observation.has("rfcommUuid") && observation.has("rfcommSecure") &&
            versionName.isNotBlank() &&
            SERVICE == observation.optString("rfcommUuid") &&
            observation.optBoolean("rfcommSecure")
    }

    /** Copy live-profile evidence onto the encrypted binding so import can complete without firmware. */
    @JvmStatic
    @Throws(JSONException::class)
    fun applyToBinding(binding: JSONObject?, observation: JSONObject?) {
        if (binding == null || observation == null) return
        if (binding.optString("firmware", "").isBlank()) {
            val firmware = observation.optString("firmware", "")
            if (firmware.isNotBlank()) binding.put("firmware", firmware)
        }
        val transport = observation.optString("transport", "")
        if (transport.isNotBlank()) binding.put("observedTransport", transport)
        val framing = framingVersion(observation)
        if (framing.isNotBlank()) binding.put("framingVersion", framing)
        if (observation.optString("authImplementation") == "WearAuthV2" &&
            !observation.optBoolean("authOobPresent") &&
            !observation.optBoolean("authAppDeviceIdPresent")
        ) {
            binding.put("authenticationBranch", "WearAuthV2")
        }
    }

    @JvmStatic
    fun framingVersion(observation: JSONObject?): String {
        if (observation == null) return ""
        val queue = observation.optString("queueClass", "")
        if (queue.contains("SppTaskQueueV1")) return "1"
        if (queue.contains("SppTaskQueueV2")) return "2"
        if (observation.optString("transport") == "GATT") return "1"
        val version = observation.optString("versionName", "")
        if (version.startsWith("1.")) return "1"
        if (version.startsWith("2.") || version.startsWith("3.")) return "2"
        return ""
    }

    @JvmStatic
    fun useV1Framing(observation: JSONObject?, versionMajor: Int): Boolean {
        val framed = framingVersion(observation)
        if (framed == "1") return true
        if (framed == "2") return false
        return versionMajor < 2
    }

    @JvmStatic
    fun missingForLive(binding: JSONObject?, observation: JSONObject?): String {
        val missing = StringBuilder()
        if (binding == null) return "address,model,userId,region,token"
        for (key in arrayOf("address", "model", "userId", "region", "token")) {
            if (binding.optString(key, "").isBlank()) {
                append(missing, key)
            }
        }
        val model = binding.optString("model", "")
        if (supportsLive(observation, model)) return missing.toString()
        if (observation == null || observation.optString("transport", "").isBlank()) append(missing, "observedTransport")
        if (framingVersion(observation).isBlank()) append(missing, "framingVersion")
        if ("WearAuthV2" != (observation?.optString("authImplementation") ?: "") ||
            (observation != null && observation.optBoolean("authOobPresent"))
        ) {
            append(missing, "authenticationBranch")
        }
        return missing.toString()
    }

    private fun append(missing: StringBuilder, field: String) {
        if (missing.isNotEmpty()) missing.append(',')
        missing.append(field)
    }
}
