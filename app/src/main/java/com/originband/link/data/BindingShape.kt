// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import org.json.JSONObject
import java.util.Locale

/** Registration gate and a secret-free description of a saved binding. */
object BindingShape {
    private val MAC_REGEX = Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")
    private val MODEL_REGEX = Regex("[A-Za-z0-9._-]{1,64}")

    /**
     * Null when the binding can be registered.
     * V2 auth sends region and the 16-byte key. userId stays off that packet.
     */
    @JvmStatic
    fun reject(binding: JSONObject?): String? {
        if (binding == null) return "UNPROVISIONED"
        if (!mac(binding.optString("address", ""))) return "BINDING_ADDRESS"
        if (binding.optString("region", "").isBlank()) return "BINDING_REGION"
        if (!AuthToken.hex32(binding.optString("token", ""))) return "TOKEN_ENCODING_UNSUPPORTED"
        return null
    }

    /** Field classes only. The address, user id, region text, and token stay out of this description. */
    @JvmStatic
    fun describe(binding: JSONObject?): String {
        if (binding == null) return "binding=absent"
        return "address=${if (mac(binding.optString("address", ""))) "ok" else "bad"}" +
            " userId=${if (binding.optString("userId", "").isBlank()) "blank" else "set"}" +
            " region=${if (binding.optString("region", "").isBlank()) "blank" else "set"}" +
            " token=${token(binding.optString("token", ""))}" +
            " model=${model(binding.optString("model", ""))}"
    }

    @JvmStatic
    fun mac(address: String?): Boolean = address != null && MAC_REGEX.matches(address)

    private fun token(value: String?): String {
        if (value.isNullOrBlank()) return "blank"
        if (AuthToken.hex32(value)) return "hex32"
        val kind = if (AuthToken.hex32(AuthToken.normalize(value))) "normalizable" else "other"
        val length = value.length
        return "$kind len=${if (length > 128) "long" else length}"
    }

    private fun model(value: String?): String {
        if (value == null || !MODEL_REGEX.matches(value)) return "other"
        return value.lowercase(Locale.ROOT)
    }
}
