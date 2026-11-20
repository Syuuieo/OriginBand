// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

/** Identity comparison for the same 48-bit address in colon, dash, or plain form. */
object MacIds {
    @JvmStatic
    fun normalize(value: String?): String {
        if (value == null) return ""
        val out = StringBuilder(12)
        for (i in 0 until value.length) {
            val c = value[i].lowercaseChar()
            if (c in '0'..'9' || c in 'a'..'f') out.append(c)
        }
        return if (out.length == 12) out.toString() else ""
    }

    @JvmStatic
    fun same(left: String?, right: String?): Boolean {
        val normalized = normalize(left)
        return normalized.isNotEmpty() && normalized == normalize(right)
    }
}
