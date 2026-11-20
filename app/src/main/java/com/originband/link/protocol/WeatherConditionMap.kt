/* Copyright (C) 2017-2024 Andreas Shimokawa, Yoran Vulker.
 * Adapted Xiaomi condition codes from Gadgetbridge commit
 * 75f923904f8504b03fabdee0987fd1c269a92278; see compat/upstream.json.
 */
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

/** Current OHealth WeatherCodeNewEnum.weatherCode -> Xiaomi condition; -1 means unsupported. */
object WeatherConditionMap {
    @JvmStatic
    fun fromOHealth(code: Int): Int =
        // OHealth 6.9.37 WeatherCodeNewEnum; Xiaomi values from the pinned
        // Gadgetbridge XiaomiWeatherConditions table and observed Band 11 frames.
        // An unsupported category stays unmapped. Clear skies is only the clear category.
        when (code) {
            1, 6, 7 -> 7
            2, 3, 4, 5 -> 3
            8 -> 8
            9, 10, 11 -> 9
            12, 13 -> 10
            14, 15, 16 -> 12
            17 -> 19
            18, 19, 20 -> 5
            22, 23, 24, 25 -> 4
            26 -> 33
            27, 28 -> 13
            29 -> 14
            30 -> 26
            31 -> 15
            32 -> 27
            33 -> 16
            34 -> 28
            35, 36 -> 17
            37 -> 6
            38, 39, 40, 50, 51, 52, 53 -> 18
            41, 42, 43, 44 -> 32
            45, 46, 47 -> 29
            48 -> 20
            49 -> 31
            54, 55, 68 -> 0
            56, 57, 58, 69 -> 1
            59 -> 2
            60, 61, 62, 63, 64, 65, 66, 67 -> 30
            else -> -1
        }
}
