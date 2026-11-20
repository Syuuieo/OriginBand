// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import java.util.Base64
import java.util.Locale

object AuthToken {
    private val HEX32_REGEX = Regex("[0-9A-Fa-f]{32}")

    @JvmStatic
    fun hex32(value: String?): Boolean = value != null && HEX32_REGEX.matches(value)

    @JvmStatic
    fun firstHex32(vararg values: String?): String {
        for (value in values) {
            if (hex32(value)) return value!!
        }
        return ""
    }

    /** Hex, spaced hex, or a 16-byte Base64 key. Empty when none of those match. */
    @JvmStatic
    fun normalize(value: String?): String {
        if (value == null) return ""
        if (hex32(value)) return value
        var compact = value.trim().replace(" ", "").replace(":", "")
        if (compact.regionMatches(0, "0x", 0, 2, ignoreCase = true)) {
            compact = compact.substring(2)
        }
        if (hex32(compact)) return compact.lowercase(Locale.ROOT)
        val decoded = fromKeyBytes(decodeBase64(value.trim()))
        return if (hex32(decoded)) decoded else ""
    }

    @JvmStatic
    fun firstKey(vararg values: String?): String {
        for (value in values) {
            val key = normalize(value)
            if (hex32(key)) return key
        }
        return ""
    }

    private fun decodeBase64(value: String): ByteArray? {
        for (decoder in arrayOf(Base64.getDecoder(), Base64.getUrlDecoder())) {
            try {
                return decoder.decode(value)
            } catch (_: IllegalArgumentException) {
            }
        }
        return null
    }

    @JvmStatic
    fun fromKeyBytes(key: ByteArray?): String {
        if (key == null || key.size != 16) return ""
        val hex = StringBuilder(32)
        for (b in key) {
            hex.append(String.format(Locale.US, "%02x", b.toInt() and 0xff))
        }
        return hex.toString()
    }
}
