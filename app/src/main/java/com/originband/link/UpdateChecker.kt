// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** Checks Syuuieo/OriginBand GitHub Releases for newer builds. */
object UpdateChecker {
    private const val LATEST_URL = "https://api.github.com/repos/Syuuieo/OriginBand/releases/latest"
    const val RELEASES_URL = "https://github.com/Syuuieo/OriginBand/releases"

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
        return try {
            val conn = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6_000
                readTimeout = 8_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "OriginBand")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    return Result(
                        false,
                        "",
                        installedName ?: "",
                        installedCode,
                        RELEASES_URL,
                        "检查更新受限（HTTP $code），稍后再试。",
                    )
                }
                val body = BufferedReader(
                    InputStreamReader(conn.inputStream, StandardCharsets.UTF_8),
                ).use { it.readText() }
                parseReleaseJson(body, installedCode, installedName)
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            Result(
                false,
                "",
                installedName ?: "",
                installedCode,
                RELEASES_URL,
                "检查更新中断，稍后再试或前往发布页查看。",
            )
        }
    }

    @JvmStatic
    fun parseReleaseJson(json: String, installedCode: Int, installedName: String?): Result {
        val obj = JSONObject(json)
        val tag = obj.optString("tag_name", "").trim()
        val releaseName = obj.optString("name", "").trim()
        val htmlUrl = obj.optString("html_url", RELEASES_URL).ifBlank { RELEASES_URL }
        val body = obj.optString("body", "").trim()
        val parsed = parseTag(tag, releaseName.ifBlank { installedName ?: "" })
        val newer = isNewerVersion(parsed.name, parsed.code, installedName, installedCode)
        val summary = if (newer) {
            val header = "发现新版本：${tag.ifBlank { parsed.name }}"
            if (body.isBlank()) header else "$header\n$body"
        } else {
            val current = installedName?.ifBlank { tag } ?: tag
            "已是最新版本：$current"
        }
        return Result(newer, tag, parsed.name, parsed.code, htmlUrl, summary)
    }

    @JvmStatic
    fun isNewerVersion(
        remoteName: String?,
        remoteCode: Int,
        installedName: String?,
        installedCode: Int,
    ): Boolean {
        if (remoteCode > 0 && installedCode > 0) {
            return remoteCode > installedCode
        }
        val remoteParts = versionParts(remoteName)
        val localParts = versionParts(installedName)
        if (remoteParts.isEmpty() || localParts.isEmpty()) {
            return !remoteName.isNullOrBlank() && remoteName != installedName
        }
        val len = maxOf(remoteParts.size, localParts.size)
        for (i in 0 until len) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r != l) return r > l
        }
        return false
    }

    private fun versionParts(raw: String?): List<Int> {
        val clean = (raw ?: "").trim().removePrefix("v").removePrefix("V")
            .substringBefore('-').substringBefore('+')
        if (clean.isBlank()) return emptyList()
        return clean.split('.').mapNotNull { it.trim().toIntOrNull() }
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
