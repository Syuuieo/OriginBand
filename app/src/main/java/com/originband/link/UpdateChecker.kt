// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link

/** Update check stub for OriginBand until a dedicated release repository is configured. */
object UpdateChecker {
    data class Result(
        val newer: Boolean,
        val tag: String,
        val versionName: String,
        val versionCode: Int,
        val htmlUrl: String,
        val notes: String,
    ) {
        fun newer(): Boolean = newer
        fun tag(): String = tag
        fun versionName(): String = versionName
        fun versionCode(): Int = versionCode
        fun htmlUrl(): String = htmlUrl
        fun notes(): String = notes
    }

    @JvmStatic
    fun check(installedCode: Int, installedName: String?): Result {
        return Result(
            false,
            "",
            installedName ?: "",
            installedCode,
            "",
            "OriginBand 更新检查等待配置远程仓库。",
        )
    }

    @JvmStatic
    fun parseTag(tag: String?, fallbackName: String): Parsed {
        var value = tag?.trim() ?: ""
        if (value.startsWith("v") || value.startsWith("V")) value = value.substring(1)
        val dash = value.indexOf('-')
        if (dash > 0) {
            try {
                val code = value.substring(0, dash).toInt()
                var name = value.substring(dash + 1).trim()
                if (name.isBlank()) name = fallbackName
                return Parsed(code, name)
            } catch (_: NumberFormatException) {
            }
        }
        val name = value.ifBlank { fallbackName }
        return Parsed(0, name)
    }

    @JvmRecord
    data class Parsed(val code: Int, val name: String)
}
