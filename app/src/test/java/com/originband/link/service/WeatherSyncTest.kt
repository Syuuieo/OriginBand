// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.service

import com.originband.link.protocol.BandWeatherEncoder
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherSyncTest {
    @Test
    fun idlePollSkipsTheForecastTheBandAlreadyHas() {
        val forecast = sample(1_000L)
        assertFalse(WeatherSync.transmitForecast(false, forecast, forecast))
        assertTrue(WeatherSync.transmitForecast(true, forecast, forecast))
        assertTrue(WeatherSync.transmitForecast(false, null, forecast))
        assertTrue(WeatherSync.transmitForecast(false, forecast, sample(2_000L)))
    }

    @Test
    fun band8CityListStatusStillAllowsTheForecast() {
        val unsupported = XiaomiProto.Command.newBuilder().setType(10).setSubtype(5).setStatus(1).build()
        assertTrue(WeatherSync.unsupportedCityList(unsupported))
        assertFalse(WeatherSync.unsupportedCityList(unsupported.toBuilder().setStatus(0).build()))
        assertFalse(WeatherSync.unsupportedCityList(unsupported.toBuilder().setSubtype(6).build()))
        assertTrue(WeatherSync.unsupportedCityList(unsupported.toBuilder().setStatus(2).build()))
        assertFalse(WeatherSync.unsupportedCityList(unsupported.toBuilder().setStatus(3).build()))
    }

    companion object {
        private fun sample(publishedAtMs: Long): BandWeatherEncoder.Sample {
            return BandWeatherEncoder.Sample(
                "weathercn:101110102",
                "城市",
                "地点",
                "Asia/Shanghai",
                "C",
                publishedAtMs,
                1,
                21.0,
                null,
                null,
                null,
                null,
                null,
                null,
                emptyList(),
                emptyList()
            )
        }
    }
}
