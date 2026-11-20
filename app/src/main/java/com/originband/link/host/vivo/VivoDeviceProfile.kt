// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.host.vivo

import android.os.Bundle
import com.originband.link.data.BandCatalog
import java.util.Locale

/**
 * Pure translation from OriginBand's device snapshot to com.vivo.health 7.1.3.72
 * virtual watch metadata, battery encoding, product form factor, and BID capability bits.
 */
object VivoDeviceProfile {
    const val DEVICE_TYPE_WATCH = 0

    /** Rectangular Watch GT2 profile (432x514, WatchProduct7CommonBusiness g()==1). */
    const val PRODUCT_ID_RECT = 7

    /** Round Watch 6 profile (WatchProduct8CommonBusiness g()==0). */
    const val PRODUCT_ID_ROUND = 8

    const val CONN_STATE_CONNECTED = 0
    const val CONN_STATE_DISCONNECTED = 3

    const val BIND_STATE_BOUND = 1
    const val WEAR_STATE_WEARING = 1

    /** WatchBatteryState codes passed into OnlineDeviceManager.updateStorageAndBattry(). */
    const val RAW_BATTERY_CHARGING = 0
    const val RAW_BATTERY_NOT_CHARGING = 1
    const val RAW_BATTERY_FULL = 15

    const val DEFAULT_TOTAL_STORAGE = 4L * 1024 * 1024 * 1024
    const val DEFAULT_FREE_STORAGE = 2L * 1024 * 1024 * 1024

    /** Synthetic (bid, version) pairs unlocked across OnlineDeviceManager.getBidSupportVersion(). */
    @JvmField
    val SYNTHETIC_BIDS: Array<IntArray> = arrayOf(
        intArrayOf(1, 1),
        intArrayOf(3, 4),
        intArrayOf(20, 80),
        intArrayOf(48, 4),
        intArrayOf(102, 2),
    )

    private val MAC_COLON_REGEX = Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")
    private val NON_HEX_REGEX = Regex("[^0-9A-F]")

    class Snapshot private constructor(
        @JvmField val registered: Boolean,
        @JvmField val name: String,
        @JvmField val deviceId: String,
        @JvmField val mac: String,
        @JvmField val modelId: String,
        @JvmField val firmware: String,
        @JvmField val hardware: String,
        @JvmField val connected: Boolean,
        @JvmField val battery: Int,
        @JvmField val charging: Boolean,
        @JvmField val stepsToday: Long,
        @JvmField val heartRate: Int,
        @JvmField val lastUpdateMs: Long,
    ) {
        fun isUsable(): Boolean = registered && normalizedMac().isNotEmpty()

        fun normalizedMac(): String = VivoDeviceProfile.normalizeMac(mac)

        fun stableDeviceId(): String = VivoDeviceProfile.stableDeviceId(deviceId, mac)

        fun displayName(): String = BandCatalog.displayName(modelId, name)

        fun productId(): Int = VivoDeviceProfile.productId(modelId)

        fun isRoundWatch(): Boolean = productId() == PRODUCT_ID_ROUND

        fun deviceType(): Int = DEVICE_TYPE_WATCH

        fun connState(): Int = if (connected) CONN_STATE_CONNECTED else CONN_STATE_DISCONNECTED

        fun clampedBattery(): Int = if (battery < 0) 0 else minOf(100, battery)

        fun hasKnownBattery(): Boolean = battery in 0..100

        fun rawWatchBatteryState(): Int = VivoDeviceProfile.rawWatchBatteryState(charging, battery)

        fun firmwareVersion(): String = firmware.ifBlank { "1.0.0" }

        fun hardwareVersion(): String = hardware.ifBlank { modelId }

        companion object {
            @JvmStatic
            fun fromValues(
                registered: Boolean,
                name: String?,
                deviceId: String?,
                mac: String?,
                modelId: String?,
                firmware: String?,
                hardware: String?,
                connected: Boolean,
                battery: Int,
                charging: Boolean,
                stepsToday: Long,
                heartRate: Int,
                lastUpdateMs: Long,
            ): Snapshot = Snapshot(
                registered,
                nonNull(name),
                nonNull(deviceId),
                nonNull(mac),
                nonNull(modelId),
                nonNull(firmware),
                nonNull(hardware),
                connected,
                battery,
                charging,
                stepsToday,
                heartRate,
                lastUpdateMs,
            )

            @JvmStatic
            fun fromBundle(display: Bundle?): Snapshot {
                if (display == null) {
                    return fromValues(false, "", "", "", "", "", "", false, -1, false, -1L, -1, 0L)
                }
                return fromValues(
                    display.getBoolean("registered", false),
                    display.getString("name", ""),
                    display.getString("deviceId", ""),
                    display.getString("mac", ""),
                    display.getString("modelId", ""),
                    display.getString("firmware", ""),
                    display.getString("hardware", ""),
                    display.getBoolean("connected", false),
                    display.getInt("battery", -1),
                    display.getBoolean("charging", false),
                    display.getLong("stepsToday", -1L),
                    display.getInt("heartRate", -1),
                    display.getLong("lastUpdateMs", 0L),
                )
            }
        }
    }

    @JvmStatic
    fun productId(modelId: String?): Int {
        if (modelId == null || modelId.isBlank()) return PRODUCT_ID_RECT
        if (!BandCatalog.watchForm(modelId)) return PRODUCT_ID_RECT
        val normalized = modelId.trim().lowercase(Locale.ROOT)
        // Band 7 Pro (l67) and REDMI Watch 4/5/6 (n65/o65/p65/q65) are rectangular displays.
        if (normalized.contains(".l67")
            || normalized.contains(".n65")
            || normalized.contains(".o65")
            || normalized.contains(".p65")
            || normalized.contains(".q65")
        ) {
            return PRODUCT_ID_RECT
        }
        return PRODUCT_ID_ROUND
    }

    @JvmStatic
    fun rawWatchBatteryState(charging: Boolean, battery: Int): Int {
        if (!charging) return RAW_BATTERY_NOT_CHARGING
        return if (battery >= 100) RAW_BATTERY_FULL else RAW_BATTERY_CHARGING
    }

    @JvmStatic
    fun normalizeMac(mac: String?): String {
        if (mac == null) return ""
        val cleaned = mac.trim().uppercase(Locale.ROOT)
        if (cleaned.isEmpty()) return ""
        if (MAC_COLON_REGEX.matches(cleaned)) return cleaned
        val hex = cleaned.replace(NON_HEX_REGEX, "")
        if (hex.length != 12) return cleaned
        val out = StringBuilder(17)
        for (i in 0 until 12 step 2) {
            if (i > 0) out.append(':')
            out.append(hex, i, i + 2)
        }
        return out.toString()
    }

    @JvmStatic
    fun stableDeviceId(rawDeviceId: String?, mac: String?): String {
        if (rawDeviceId != null && rawDeviceId.isNotBlank()) return rawDeviceId.trim()
        val hex = normalizeMac(mac).replace(":", "")
        return if (hex.isEmpty()) "" else "mi_" + hex.lowercase(Locale.ROOT)
    }

    private fun nonNull(value: String?): String = value?.trim() ?: ""
}
