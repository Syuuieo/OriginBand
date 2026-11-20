// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import java.util.Locale
import java.util.regex.Pattern

/**
 * Display names for Xiaomi Wear band models the official app speaks.
 * Not an allow-list: live sessions still depend on the captured SPP profile.
 */
object BandCatalog {
    private val CHINESE = Pattern.compile(
        "小米手环\\s*(8|9|10|11)\\s*(Pro|NFC|活力版)?",
        Pattern.CASE_INSENSITIVE,
    )
    private val ADVERTISED = Pattern.compile(
        "(?i)(?:xiaomi\\s+)?(?:smart\\s+)?band\\s+(8|9|10|11)(?:\\s+(pro|active|nfc))?",
    )
    private val REDMI = Pattern.compile(
        "(?i)redmi\\s+(?:smart\\s+)?band\\s*(pro|2|3)?",
    )
    private val CONTROL_CHARS = Regex("[\\p{Cntrl}\\p{Cf}]")

    private data class ModelEntry(val prefix: String, val displayName: String, val form: String)

    /** Longest prefix wins. The last column is the device-space card: band or watch. */
    private val MODELS = listOf(
        ModelEntry("lchz.watch.m67ys", "小米手环8 Pro 原神定制版", "band"),
        ModelEntry("lchz.watch.m67", "小米手环8 Pro", "band"),
        ModelEntry("miwear.watch.m66nfc", "小米手环8 NFC", "band"),
        ModelEntry("lchz.watch.m66", "小米手环8", "band"),
        ModelEntry("miwear.watch.m66", "小米手环8", "band"),
        ModelEntry("mijia.watch.m69b", "小米手环8 活力版", "band"),
        ModelEntry("miwear.watch.n66tc", "小米手环9 陶瓷特别版", "band"),
        ModelEntry("miwear.watch.n66nfc", "小米手环9 NFC", "band"),
        ModelEntry("miwear.watch.n66", "小米手环9", "band"),
        ModelEntry("lchz.watch.n67", "小米手环9 Pro", "band"),
        ModelEntry("miwear.watch.n67", "小米手环9 Pro", "band"),
        ModelEntry("mijia.watch.n69", "Redmi手环3", "band"),
        ModelEntry("miwear.watch.n69", "Redmi手环3", "band"),
        ModelEntry("miwear.watch.o66nfc", "小米手环10 NFC", "band"),
        ModelEntry("miwear.watch.o66gln", "小米手环10 NFC", "band"),
        ModelEntry("miwear.watch.o66tc", "小米手环10 陶瓷版", "band"),
        ModelEntry("miwear.watch.o66lj", "小米手环10 耀影金特别版", "band"),
        ModelEntry("miwear.watch.o66", "小米手环10", "band"),
        ModelEntry("miwear.watch.p67tc", "小米手环10 Pro 陶瓷版", "band"),
        ModelEntry("miwear.watch.p67gln", "小米手环10 Pro NFC", "band"),
        ModelEntry("miwear.watch.p67", "小米手环10 Pro", "band"),
        ModelEntry("miwear.watch.q66nfc", "小米手环11 NFC", "band"),
        ModelEntry("miwear.watch.q66tc", "小米手环11 陶瓷版", "band"),
        ModelEntry("miwear.watch.q66", "小米手环11", "band"),
        ModelEntry("mijia.watch.band01", "Redmi手环", "band"),
        ModelEntry("mijia.watch.m69", "Redmi手环2", "band"),
        ModelEntry("hqbd3.watch.l67", "小米手环7 Pro", "watch"),
        ModelEntry("mijia.watch.n62lte", "小米手表 S3 eSIM", "watch"),
        ModelEntry("mijia.watch.n62car", "小米手表 S3 SU7 限定版", "watch"),
        ModelEntry("mijia.watch.n62cg", "小米手表 S3 SU7 限定版", "watch"),
        ModelEntry("mijia.watch.n62s", "小米手表 S4 Sport", "watch"),
        ModelEntry("mijia.watch.n62", "小米手表 S3", "watch"),
        ModelEntry("mijia.watch.o62lte", "小米手表 S4 eSIM", "watch"),
        ModelEntry("mijia.watch.o62m", "小米手表 S4 15周年版", "watch"),
        ModelEntry("mijia.watch.o62", "小米手表 S4", "watch"),
        ModelEntry("miwear.watch.o63", "小米手表 S4", "watch"),
        ModelEntry("miwear.watch.p62lte", "小米手表 S5 eSIM", "watch"),
        ModelEntry("miwear.watch.p62", "小米手表 S5", "watch"),
        ModelEntry("mijia.watch.l61", "小米手表 S1 Pro", "watch"),
        ModelEntry("lchz.watch.n65", "REDMI Watch 4", "watch"),
        ModelEntry("miwear.watch.o65m", "REDMI Watch 5 eSIM", "watch"),
        ModelEntry("miwear.watch.o65", "REDMI Watch 5", "watch"),
        ModelEntry("miwear.watch.p65", "REDMI Watch 6", "watch"),
        ModelEntry("miwear.watch.q65", "REDMI Watch 6 活力版", "watch"),
    )

    @JvmStatic
    fun looksLikeBand(advertisedName: String?): Boolean = fromAdvertisedName(advertisedName) != null

    @JvmStatic
    fun displayName(model: String?, rawName: String?): String {
        fromModelId(model)?.let { return it }
        fromAdvertisedName(rawName)?.let { return it }
        sanitize(rawName)?.let { return it }
        return sanitize(model) ?: "小米手环"
    }

    /**
     * OPPO health row category (`DeviceCategory.BAND` or `WATCH`).
     * An unknown model stays `Band`, the same fallback as the wristband card.
     */
    @JvmStatic
    fun healthCategory(model: String?): String = if (watchForm(model)) "Watch" else "Band"

    /** Device-space card type. Bands use the system wristband glyph, watches use the watch glyph. */
    @JvmStatic
    fun spaceType(model: String?): String = if (watchForm(model)) "WATCH" else "WRISTBAND"

    /** Legacy device-space drawable name. */
    @JvmStatic
    fun spaceIcon(model: String?): String = if (watchForm(model)) "default_watch" else "default_wristband"

    @JvmStatic
    fun watchForm(model: String?): Boolean = match(model)?.form == "watch"

    @JvmStatic
    fun fromModelId(model: String?): String? = match(model)?.displayName

    private fun match(model: String?): ModelEntry? {
        if (model.isNullOrBlank()) return null
        val id = model.trim().lowercase(Locale.ROOT)
        var best: ModelEntry? = null
        for (row in MODELS) {
            if (!id.startsWith(row.prefix)) continue
            if (best == null || row.prefix.length > best.prefix.length) best = row
        }
        return best
    }

    @JvmStatic
    fun fromAdvertisedName(raw: String?): String? {
        val clean = sanitize(raw) ?: return null
        val chinese = CHINESE.matcher(clean)
        if (chinese.find()) return labeled(chinese.group(1), chinese.group(2))
        val advertised = ADVERTISED.matcher(clean)
        if (advertised.find()) return labeled(advertised.group(1), advertised.group(2))
        val redmi = REDMI.matcher(clean)
        if (redmi.find()) {
            val variant = redmi.group(1)
            if (variant.isNullOrBlank()) return "红米手环"
            if (variant.equals("pro", ignoreCase = true)) return "红米手环 Pro"
            return "红米手环$variant"
        }
        return null
    }

    private fun labeled(generation: String?, variant: String?): String {
        val base = "小米手环$generation"
        if (variant.isNullOrBlank()) return base
        val kind = variant.trim().lowercase(Locale.ROOT)
        return when {
            kind == "pro" -> "$base Pro"
            kind == "nfc" -> "$base NFC"
            kind == "active" || kind.contains("活力") -> "$base 活力版"
            else -> base
        }
    }

    private fun sanitize(value: String?): String? {
        if (value == null) return null
        val clean = value.replace(CONTROL_CHARS, "").trim()
        return if (clean.isEmpty() || clean.length > 80) null else clean
    }
}
