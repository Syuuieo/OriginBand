// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWeatherFetcherTest {
    @Test
    fun mapsWmoCodesAndParsesOpenMeteoPayload() {
        assertEquals(54, LocalWeatherFetcher.oHealthCodeFromWmo(0))
        assertEquals(1, LocalWeatherFetcher.oHealthCodeFromWmo(61))
        assertEquals(22, LocalWeatherFetcher.oHealthCodeFromWmo(95))

        val now = 1_760_000_000_000L
        val sec = now / 1000L
        val sampleJson = """
            {
              "timezone": "Asia/Shanghai",
              "current": {
                "temperature_2m": 22.5,
                "relative_humidity_2m": 55,
                "weather_code": 1,
                "surface_pressure": 1014.2,
                "wind_speed_10m": 10.5,
                "wind_direction_10m": 180
              },
              "hourly": {
                "time": [$sec, ${sec + 3600}],
                "temperature_2m": [22.5, 23.0],
                "weather_code": [1, 0]
              },
              "daily": {
                "time": [$sec],
                "weather_code": [1],
                "temperature_2m_max": [25.0],
                "temperature_2m_min": [16.0],
                "sunrise": [${sec - 10000}],
                "sunset": [${sec + 20000}],
                "uv_index_max": [5.4]
              }
            }
        """.trimIndent()

        val parsed = LocalWeatherFetcher.parseOpenMeteo(sampleJson, "北京", 39.9, 116.4, now)
        assertEquals("北京", parsed.getString("cityName"))
        assertEquals("c", parsed.getString("unit"))
        assertEquals(56, parsed.getInt("conditionCode"))
        assertEquals(22.5, parsed.getDouble("temperature"), 0.01)
        assertEquals(1, parsed.getJSONArray("daily").length())
        assertTrue(parsed.getJSONArray("hourly").length() >= 1)
    }
}
