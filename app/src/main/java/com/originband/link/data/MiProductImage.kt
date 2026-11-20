// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Picks the server picture Mi Fitness uses for the device page.
 * `big_preview` already contains the default watch face. The icon fields are the empty body.
 */
object MiProductImage {
    const val PICK = "preview"
    private const val MAX_DEPTH = 12
    private const val MAX_URL = 1024
    private val SKU_REGEX = Regex("[A-Za-z0-9._-]{1,32}")
    private val SOURCES = arrayOf(
        "sku_big_preview", "big_preview", "sku_big_icon", "big_icon_3", "big_icon",
    )

    data class Choice(
        @JvmField val url: String,
        @JvmField val source: String,
        @JvmField val productType: Int,
    ) {
        fun url(): String = url
        fun source(): String = source
        fun productType(): Int = productType
    }

    /** Segment before `/` in the band serial. Anything else stays empty. */
    @JvmStatic
    fun sku(serialNumber: String?): String {
        if (serialNumber == null) return ""
        val slash = serialNumber.indexOf('/')
        val head = (if (slash >= 0) serialNumber.substring(0, slash) else serialNumber).trim()
        return if (SKU_REGEX.matches(head)) head else ""
    }

    /** First product whose `model` matches. Preview pictures come before the empty body. */
    @JvmStatic
    fun choose(productJson: String?, model: String?, sku: String?): Choice? {
        if (productJson.isNullOrBlank() || model.isNullOrBlank()) return null
        val wanted = model.trim()
        val color = sku?.trim() ?: ""
        val root = try {
            JSONObject(productJson)
        } catch (_: JSONException) {
            return null
        }
        val matches = ArrayList<JSONObject>()
        walk(root, wanted, 0, matches)
        for (product in matches) {
            val choice = fromProduct(product, color)
            if (choice != null) return choice
        }
        return null
    }

    private fun fromProduct(product: JSONObject, sku: String): Choice? {
        for (source in SOURCES) {
            val url = picture(product, source, sku)
            if (https(url)) return Choice(url, source, product.optInt("pd_type", -1))
        }
        return null
    }

    private fun picture(product: JSONObject, source: String, sku: String): String {
        if (source.startsWith("sku_")) return mapped(product.opt(source), sku)
        return product.optString(source, "").trim()
    }

    private fun mapped(raw: Any?, sku: String): String {
        if (sku.isEmpty() || raw == null) return ""
        val map: JSONObject = when {
            raw is JSONObject -> raw
            raw is String && raw.trim().startsWith("{") -> try {
                JSONObject(raw)
            } catch (_: JSONException) {
                return ""
            }
            else -> return ""
        }
        if (!map.has(sku)) return ""
        return map.optString(sku, "").trim()
    }

    private fun https(url: String?): Boolean =
        url != null && url.startsWith("https://") && url.length <= MAX_URL && !url.contains(" ")

    private fun walk(node: Any?, model: String, depth: Int, matches: MutableList<JSONObject>) {
        if (node == null || depth > MAX_DEPTH) return
        when (node) {
            is JSONObject -> {
                if (node.has("model") && model == node.optString("model", "")) matches.add(node)
                val keys = node.keys()
                while (keys.hasNext()) walk(node.opt(keys.next()), model, depth + 1, matches)
            }
            is JSONArray -> {
                for (index in 0 until node.length()) {
                    walk(node.opt(index), model, depth + 1, matches)
                }
            }
        }
    }
}
