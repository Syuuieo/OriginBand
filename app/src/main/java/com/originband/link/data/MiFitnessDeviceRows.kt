// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/** Picks wearable rows out of Mi Fitness device.detail. Values stay with the caller. */
object MiFitnessDeviceRows {
    private val HEX12_REGEX = Regex("[0-9A-Fa-f]{12}")

    class Detail(
        @JvmField val model: String?,
        @JvmField val name: String?,
        @JvmField val productId: String?,
        @JvmField val did: String?,
        @JvmField val detail: String?,
    )

    class Row(
        @JvmField val address: String,
        @JvmField val model: String,
        @JvmField val name: String,
        @JvmField val productId: String,
        @JvmField val did: String,
        @JvmField val token: String,
        @JvmField val phoneId: String,
    )

    @JvmStatic
    fun select(details: List<Detail>?, preferredAddress: String?): List<Row> {
        val wanted = mac(preferredAddress)
        val rows = ArrayList<Row>()
        if (details == null) return rows
        for (detail in details) {
            val row = one(detail) ?: continue
            if (wanted.isNotEmpty() && wanted != row.address) continue
            rows.add(row)
        }
        return rows
    }

    @JvmStatic
    fun one(detail: Detail?): Row? {
        if (detail == null || detail.detail.isNullOrBlank()) return null
        val json = try {
            JSONObject(detail.detail)
        } catch (_: JSONException) {
            return null
        }
        val token = AuthToken.firstKey(json.optString("encrypt_key"), json.optString("token"))
        if (!AuthToken.hex32(token)) return null
        val address = mac(json.optString("mac"))
        if (!BindingShape.mac(address)) return null
        val model = clip(detail.model, 128)
        if (model.isEmpty()) return null
        return Row(
            address = address,
            model = model,
            name = clip(detail.name, 64),
            productId = clip(detail.productId, 32),
            did = clip(detail.did, 64),
            token = token,
            phoneId = clip(json.optString("phone_id"), 128),
        )
    }

    @JvmStatic
    fun mac(value: String?): String {
        if (value == null) return ""
        val compact = value.trim().replace(":", "").replace("-", "")
        if (!HEX12_REGEX.matches(compact)) return ""
        val upper = compact.uppercase(Locale.ROOT)
        val out = StringBuilder(17)
        for (index in 0 until upper.length step 2) {
            if (index > 0) out.append(':')
            out.append(upper, index, index + 2)
        }
        return out.toString()
    }

    private fun clip(value: String?, limit: Int): String {
        if (value == null) return ""
        val trimmed = value.trim()
        return if (trimmed.length > limit) "" else trimmed
    }
}
