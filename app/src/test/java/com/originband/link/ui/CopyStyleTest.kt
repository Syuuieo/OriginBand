// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.Pattern

/** User-facing Chinese copy states the situation and the next step in positive words. */
class CopyStyleTest {
    companion object {
        /** Words that turn a hint into a prohibition or a dead end. */
        @JvmField
        val AVOID: List<String> = listOf(
            "不", "无法", "请勿", "禁止", "没有", "未", "暂不", "失败", "拒绝", "错误", "丢失",
        )

        /** Files whose Chinese strings match host text or name products, not bridge copy. */
        @JvmField
        val EXEMPT: Set<String> = setOf(
            "notify/CallPresentation.kt",
            "data/BandCatalog.kt",
            "protocol/SportReportParser.kt",
        )

        private val LITERAL = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"")
        private val CJK = Pattern.compile("[\\u4e00-\\u9fff]")
        private val PROTOCOL_TOKEN = Pattern.compile("\\b\\d+/\\d+\\b")

        @JvmStatic
        fun firstAvoided(value: String): String? = AVOID.firstOrNull { value.contains(it) }

        private fun sourceRoot(): Path {
            for (candidate in listOf("src/main/java", "app/src/main/java")) {
                val path = Paths.get(candidate)
                if (Files.isDirectory(path)) return path
            }
            throw IllegalStateException("main source root")
        }
    }

    @Test
    fun sourceLiteralsUsePositiveWording() {
        val root = sourceRoot()
        val offenders = ArrayList<String>()
        var scanned = 0
        Files.walk(root).use { files ->
            for (file in files.filter { p ->
                val name = p.toString()
                name.endsWith(".java") || name.endsWith(".kt")
            }.iterator()) {
                var relative = root.relativize(file).toString().replace('\\', '/')
                relative = relative.substring(relative.indexOf("originband/link/") + "originband/link/".length)
                if (EXEMPT.contains(relative)) continue
                val lines = Files.readAllLines(file, StandardCharsets.UTF_8)
                for (index in lines.indices) {
                    val line = lines[index].trim()
                    if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) continue
                    val literal = LITERAL.matcher(line)
                    while (literal.find()) {
                        val value = literal.group(1) ?: continue
                        if (!CJK.matcher(value).find()) continue
                        scanned++
                        var hit = firstAvoided(value)
                        if (hit == null && PROTOCOL_TOKEN.matcher(value).find()) hit = "protocol token"
                        if (hit != null) offenders.add("$relative:${index + 1} [$hit] $value")
                    }
                }
            }
        }
        assertTrue("scanner found $scanned Chinese literals", scanned > 300)
        assertEquals("firstAvoided self-check", "未", firstAvoided("未连接"))
        assertTrue("protocol token self-check", PROTOCOL_TOKEN.matcher("2/78").find())
        assertEquals("Rephrase these strings positively:\n${offenders.joinToString("\n")}", 0, offenders.size)
    }

    @Test
    fun statusCopyEndsWithStateOrAction() {
        val samples = ArrayList<String>()
        for (step in SetupProgress.Step.values()) {
            samples.add(Copy.setupLabel(step))
            samples.add(Copy.setupHint(step))
        }
        for (code in listOf(
            "", "OBSERVED_PROFILE_REQUIRED", "NATIVE_OWNERSHIP_REQUIRED", "BINDING_REGION",
            "BLUETOOTH_DISABLED", "SOMETHING_ELSE",
        )) {
            samples.add(Copy.offlineReason(code))
        }
        for (code in listOf(
            "USER_LOCKED", "UNPROVISIONED", "BINDING_ADDRESS", "BINDING_REGION",
            "TOKEN_ENCODING_UNSUPPORTED", "OBSERVED_PROFILE_REQUIRED", "FIRMWARE_OR_MODEL_UNSUPPORTED",
            "OOB_BRANCH_UNSUPPORTED", "PROTOCOL_VERSION_UNSUPPORTED", "DEVICE_IDENTITY_CHANGED",
            "BAND_STATE_STORAGE_FAILED", "ROOT_REQUIRED", "HOST_VERSION_UNSUPPORTED",
            "NEARBY_PERMISSION_REQUIRED", "DEVICE_NOT_REGISTERED", "DIAGNOSTIC_SOCKET_STILL_ACTIVE",
            "OWNERSHIP_RECOVERY_REQUIRED", "OWNERSHIP_ROLLBACK_FAILED", "X", "",
        )) {
            samples.add(Copy.ownershipFailure(code))
        }
        for (code in listOf(
            "IMPORT_WINDOW_OPEN", "IMPORT_WINDOW_CLOSED", "DIAGNOSTIC_WINDOW_OPEN",
            "PROTOCOL_CAPTURE_OPEN", "UNPROVISIONED", "BINDING_SAVED", "BINDING_INCOMPLETE", "NO_BAND_KEY",
            "MI_DATABASE_MISSING", "MI_DATABASE_UNREADABLE", "ROOT_ACTION_FAILED", "MULTIPLE_BANDS",
            "USER_LOCKED", "CREDENTIAL_STORAGE_FAILED", "X",
        )) {
            samples.add(Copy.importResult(code, "", "", ""))
        }
        for (code in listOf(
            "WEATHER_CITY_CONFIRMATION_REQUIRED", "WEATHER_CITY_SETUP_REQUIRED",
            "WEATHER_CITY_LIST_INVALID", "WEATHER_SOURCE_STALE", "WEATHER_BAND_REJECTED", "WEATHER_BUSY",
            "WEATHER_DISCONNECTED", "WEATHER_DATA_INVALID", "X",
        )) {
            samples.add(Copy.weatherError(code))
        }
        for (code in listOf(
            "OHEALTH_LOCATION_PERMISSION_REQUIRED", "OHEALTH_LOCATION_DISABLED",
            "OHEALTH_LOCATION_UNAVAILABLE", "OHEALTH_WEATHER_CLOUD_UNAVAILABLE", "OHEALTH_WEATHER_TIMEOUT", "X",
        )) {
            samples.add(Copy.weatherSource(code, false))
        }
        for (code in listOf(
            "ACCOUNT_CONFIRMATION_REQUIRED", "HEALTH_OUTBOX_FULL", "HISTORY_FORMAT_UNSUPPORTED",
            "HISTORY_FILE_REJECTED", "HISTORY_STORAGE_FAILED", "HEALTH_REPLAY_PAUSED",
        )) {
            samples.add(Copy.healthCollection(code))
        }
        samples.add(Copy.healthHost("UNAVAILABLE"))
        for (stage in 0 until 8) samples.add(Copy.stageName(stage))
        samples.add(Copy.stageName(null))
        samples.add(Copy.tileSubtitle(false, false, false, -1))
        samples.add(Copy.tileSubtitle(true, false, false, -1))
        samples.add(Copy.tileSubtitle(true, true, false, -1))
        samples.add(Copy.tileSubtitle(true, true, true, 50))
        samples.add(Copy.GUIDE)
        for (sample in samples) {
            if (sample.isEmpty()) continue
            val hit = firstAvoided(sample)
            assertTrue("[$hit] $sample", hit == null)
        }
        assertFalse(Copy.ownershipFailure("X").isBlank())
        assertFalse(Copy.importResult("X", "", "", "").isBlank())
        assertFalse(Copy.weatherError("X").isBlank())
    }
}
