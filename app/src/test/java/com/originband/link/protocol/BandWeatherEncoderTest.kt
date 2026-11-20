// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class BandWeatherEncoderTest {
    companion object {
        private val EPOCH = Instant.parse("2026-09-23T18:32:06Z").toEpochMilli()
        private const val BAND_CODE = "weathercn:000000001"

        private fun city(code: String, name: String): XiaomiProto.WeatherLocations =
            XiaomiProto.WeatherLocations.newBuilder().addLocation(
                XiaomiProto.WeatherLocation.newBuilder().setCode(code).setName(name),
            ).build()

        private fun frame(frames: List<XiaomiProto.Command>, subtype: Int): XiaomiProto.Command =
            frames.first { it.subtype == subtype }

        private fun entries(frames: List<XiaomiProto.Command>, subtype: Int): XiaomiProto.ForecastEntries =
            frame(frames, subtype).weather.forecast.entries

        private fun withForecasts(
            source: BandWeatherEncoder.Sample,
            days: List<BandWeatherEncoder.Day>,
            hours: List<BandWeatherEncoder.Hour>,
        ): BandWeatherEncoder.Sample = BandWeatherEncoder.Sample(
            source.locationKey(),
            source.cityName(),
            source.locationName(),
            source.timezone(),
            source.unit(),
            source.publishedAtMs(),
            source.conditionCode(),
            source.temperature(),
            source.humidityPercent(),
            source.aqi(),
            source.windPower(),
            source.windDegree(),
            source.uvIndex(),
            source.pressure(),
            days,
            hours,
        )

        private fun sample(
            unit: String,
            zone: String,
            condition: Int,
            temp: Double,
        ): BandWeatherEncoder.Sample {
            val days = ArrayList<BandWeatherEncoder.Day>()
            val hours = ArrayList<BandWeatherEncoder.Hour>()
            val published = Instant.ofEpochMilli(EPOCH).atZone(ZoneId.of(zone))
            for (i in 0 until 7) {
                val date = published.toLocalDate().plusDays(i.toLong())
                days.add(
                    BandWeatherEncoder.Day(
                        date.atStartOfDay(published.zone).toInstant().toEpochMilli(),
                        56,
                        2,
                        if (unit == "f") 69.8 else 21.0,
                        if (unit == "f") 78.8 else 26.0,
                        date.atTime(6, 0).atZone(published.zone).toInstant().toEpochMilli(),
                        date.atTime(18, 0).atZone(published.zone).toInstant().toEpochMilli(),
                    ),
                )
            }
            for (i in 0 until 23) {
                hours.add(
                    BandWeatherEncoder.Hour(
                        published.truncatedTo(ChronoUnit.HOURS).plusHours(i.toLong()).toInstant().toEpochMilli(),
                        56,
                        if (unit == "f") 69.8 else 21.0,
                    ),
                )
            }
            return BandWeatherEncoder.Sample(
                BAND_CODE,
                "Source city",
                "Source place",
                zone,
                unit,
                EPOCH,
                condition,
                temp,
                null,
                null,
                null,
                null,
                null,
                null,
                days,
                hours,
            )
        }
    }

    @Test
    fun sendingWeatherNeverReplacesConfiguredCitiesOrInventsMetrics() {
        val frames = BandWeatherEncoder.encode(sample("c", "Asia/Shanghai", 56, 21.0))
        assertEquals(listOf(0, 1, 2), frames.map { it.subtype })
        for (frame in frames) {
            assertEquals(10, frame.type)
            assertFalse(frame.weather.hasLocations())
        }
        val current = frame(frames, 0).weather.current
        assertEquals(1, current.weatherCondition)
        assertEquals(21, current.temperature.value)
        assertEquals("℃", current.temperature.unit)
        assertFalse(current.hasAqi())
        assertFalse(current.hasWind())
        assertFalse(current.hasHumidity())
        assertFalse(current.hasPressure())
        val day = entries(frames, 1).getEntry(0)
        assertEquals("2026-09-24T06:00:00+08:00", day.sunriseSunset.sunrise)
        assertEquals("2026-09-24T18:00:00+08:00", day.sunriseSunset.sunset)
        val hour = entries(frames, 2).getEntry(0)
        assertFalse(hour.hasWind())
        assertFalse(hour.hasAqi())
        assertFalse(hour.hasSunriseSunset())
    }

    @Test
    fun sppWholePacketLimitsIncludeTransportOverhead() {
        assertTrue(SppDiagnosticClient.commandFitsPayloadLimit(1, 2048, 2048))
        assertFalse(SppDiagnosticClient.commandFitsPayloadLimit(1, 2049, 2048))
        assertTrue(SppDiagnosticClient.commandFitsPayloadLimit(0, 510, 512))
        assertFalse(SppDiagnosticClient.commandFitsPayloadLimit(0, 511, 512))
        assertFalse(SppDiagnosticClient.commandFitsPayloadLimit(0, Int.MAX_VALUE, 512))
    }

    @Test
    @Throws(Exception::class)
    fun fullForecastsRoundTripThroughEncryptedBleChunks() {
        val commands = BandWeatherEncoder.encode(sample("c", "Asia/Shanghai", 56, 21.0))
        assertEquals(7, entries(commands, 1).entryCount)
        assertEquals(23, entries(commands, 2).entryCount)
        assertTrue(frame(commands, 1).serializedSize > 512)
        val key = ByteArray(16)
        val nonce = ByteArray(12)
        for (command in commands) {
            val cipher = XiaomiSessionCrypto.ccmEncrypt(key, nonce, command.toByteArray())
            assertTrue(SppDiagnosticClient.commandFitsPayloadLimit(2, cipher.size, 512))
            val frames = BleV1Codec.encodeOutgoing(cipher, 244, true, 1)
            val reassembler = BleV1Codec.Reassembler()
            var assembled: ByteArray? = null
            for (part in frames) {
                assertTrue(part.size <= 244)
                val result = reassembler.accept(part)
                if (result != null) assembled = result
            }
            val prefix = if (frames.size == 1) 3 else 2
            val assembledBytes = checkNotNull(assembled)
            val recovered = assembledBytes.copyOfRange(prefix, assembledBytes.size)
            assertEquals(
                command,
                XiaomiProto.Command.parseFrom(XiaomiSessionCrypto.ccmDecrypt(key, nonce, recovered)),
            )
        }
    }

    @Test
    fun fahrenheitConvertsExactlyOnceAcrossCurrentAndForecasts() {
        val frames = BandWeatherEncoder.encode(sample("f", "+05:30", 56, 69.8))
        assertEquals(21, frame(frames, 0).weather.current.temperature.value)
        assertEquals(26, entries(frames, 1).getEntry(0).temperatureRange.from)
        assertEquals(21, entries(frames, 1).getEntry(0).temperatureRange.to)
        assertEquals(21, entries(frames, 2).getEntry(0).temperatureRange.to)
        assertEquals(
            "2026-09-24T00:02:06+05:30",
            frame(frames, 0).weather.current.metadata.publicationTimestamp,
        )
    }

    @Test
    fun unsupportedCurrentConditionsRejectTheUpdate() {
        for (code in intArrayOf(0, 21, 70, 71, -1, 72)) {
            assertEquals(
                "UNKNOWN_WEATHER_CONDITION",
                assertThrows(IllegalArgumentException::class.java) {
                    BandWeatherEncoder.encode(sample("c", "Asia/Shanghai", code, 21.0))
                }.message,
            )
        }
    }

    @Test
    fun unknownForecastConditionsPreserveRowsAndTemperatures() {
        val source = sample("c", "Asia/Shanghai", 56, 21.0)
        val days = ArrayList(source.daily())
        val original = days[1]
        days[1] = BandWeatherEncoder.Day(original.timeMs(), 56, 21, 17.0, 24.0, null, null)
        val hours = ArrayList(source.hourly())
        hours[1] = BandWeatherEncoder.Hour(hours[1].timeMs(), 70, 18.0)
        val frames = BandWeatherEncoder.encode(withForecasts(source, days, hours))
        val daily = entries(frames, 1)
        assertEquals(7, daily.entryCount)
        assertFalse(daily.getEntry(1).hasConditionRange())
        assertEquals(17, daily.getEntry(1).temperatureRange.to)
        assertEquals(24, daily.getEntry(1).temperatureRange.from)
        assertFalse(daily.getEntry(1).hasSunriseSunset())
        assertTrue(daily.getEntry(2).hasConditionRange())
        val hourly = entries(frames, 2)
        assertEquals(23, hourly.entryCount)
        assertFalse(hourly.getEntry(1).hasConditionRange())
        assertEquals(18, hourly.getEntry(1).temperatureRange.to)
        assertTrue(hourly.getEntry(2).hasConditionRange())
    }

    @Test
    fun pastRowsAreTrimmedInCityTimezoneWithoutChangingPublication() {
        // Publication is already Sep 24 in this city, but remains Sep 23 in UTC.
        val source = sample("c", "Asia/Shanghai", 56, 21.0)
        val days = ArrayList(source.daily())
        val previousDay = days[0].timeMs() - 86_400_000L
        days.add(0, BandWeatherEncoder.Day(previousDay, 54, 54, -5.0, -1.0, null, null))
        val hours = ArrayList(source.hourly())
        hours.add(0, BandWeatherEncoder.Hour(hours[0].timeMs() - 3_600_000L, 54, -5.0))
        val frames = BandWeatherEncoder.encode(withForecasts(source, days, hours))
        assertEquals(7, entries(frames, 1).entryCount)
        assertEquals(21, entries(frames, 1).getEntry(0).temperatureRange.to)
        assertEquals(23, entries(frames, 2).entryCount)
        assertEquals(21, entries(frames, 2).getEntry(0).temperatureRange.to)
        assertEquals(
            "2026-09-24T02:32:06+08:00",
            frame(frames, 1).weather.forecast.metadata.publicationTimestamp,
        )
        assertEquals(
            "2026-09-24T02:32:06+08:00",
            frame(frames, 2).weather.forecast.metadata.publicationTimestamp,
        )
    }

    @Test
    fun missingFirstOrInternalDayDoesNotShiftRemainingDays() {
        val source = sample("c", "Asia/Shanghai", 56, 21.0)
        for (missing in intArrayOf(0, 2)) {
            val days = ArrayList(source.daily())
            days.removeAt(missing)
            val frames = BandWeatherEncoder.encode(withForecasts(source, days, source.hourly()))
            assertEquals(listOf(0, 2), frames.map { it.subtype })
            assertEquals(21, frame(frames, 0).weather.current.temperature.value)
        }
    }

    @Test
    fun missingFirstHourUsesTheLiveReadingAndAnInternalGapDropsTheFrame() {
        val source = sample("c", "Asia/Shanghai", 56, 21.0)
        val leading = ArrayList(source.hourly())
        leading.removeAt(0)
        val frames = BandWeatherEncoder.encode(withForecasts(source, source.daily(), leading))
        assertEquals(listOf(0, 1, 2), frames.map { it.subtype })
        val hourly = entries(frames, 2)
        assertEquals(23, hourly.entryCount)
        assertEquals(21, hourly.getEntry(0).temperatureRange.to)
        assertEquals(1, hourly.getEntry(0).conditionRange.to)
        val internal = ArrayList(source.hourly())
        internal.removeAt(2)
        assertEquals(
            listOf(0, 1),
            BandWeatherEncoder.encode(withForecasts(source, source.daily(), internal)).map { it.subtype },
        )
        assertEquals(
            listOf(0),
            BandWeatherEncoder.encode(withForecasts(source, emptyList(), emptyList())).map { it.subtype },
        )
    }

    @Test
    fun forecastsStopAtWireLimitsEvenWhenSourceHasMoreRows() {
        val source = sample("c", "Asia/Shanghai", 56, 21.0)
        val days = ArrayList(source.daily())
        days.add(BandWeatherEncoder.Day(days[6].timeMs() + 86_400_000L, 54, 54, 10.0, 15.0, null, null))
        val hours = ArrayList(source.hourly())
        hours.add(BandWeatherEncoder.Hour(hours[22].timeMs() + 3_600_000L, 54, 10.0))
        val frames = BandWeatherEncoder.encode(withForecasts(source, days, hours))
        assertEquals(7, entries(frames, 1).entryCount)
        assertEquals(23, entries(frames, 2).entryCount)
    }

    @Test
    fun explicitBindingRejectsChangesToEachOfFiveIdentityFields() {
        val source = sample("c", "Asia/Shanghai", 56, 21.0)
        val configured = city(BAND_CODE, "Band city")
        val confirmed = arrayOf(
            source.locationKey(),
            source.cityName(),
            source.locationName(),
            BAND_CODE,
            "Band city",
        )
        val bound = BandWeatherEncoder.bindExplicitCity(
            source,
            confirmed[0],
            confirmed[1],
            confirmed[2],
            confirmed[3],
            confirmed[4],
            configured,
        )
        assertEquals(BAND_CODE, bound.locationKey())
        for (changed in confirmed.indices) {
            val stale = confirmed.clone()
            stale[changed] = if (changed == 3) "weathercn:000000002" else "changed"
            assertThrows(IllegalArgumentException::class.java) {
                BandWeatherEncoder.bindExplicitCity(
                    source,
                    stale[0],
                    stale[1],
                    stale[2],
                    stale[3],
                    stale[4],
                    configured,
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            BandWeatherEncoder.bindExplicitCity(source, null, null, null, null, null, configured)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BandWeatherEncoder.bindExplicitCity(
                source,
                confirmed[0],
                confirmed[1],
                confirmed[2],
                confirmed[3],
                confirmed[4],
                null,
            )
        }
    }

    @Test
    fun oneConfiguredCitySyncsWithoutAManualConfirmation() {
        val source = sample("c", "Asia/Shanghai", 56, 21.0)
        val configured = city(BAND_CODE, "Band city")
        val bound = BandWeatherEncoder.bindObservedCity(source, configured, "", "")
        assertEquals(BAND_CODE, bound.locationKey())
        assertEquals("Band city", bound.locationName())
        assertEquals("Source city", bound.cityName())
        val echoed = BandWeatherEncoder.echoLocations(configured)
        assertEquals(10, echoed.type)
        assertEquals(6, echoed.subtype)
        assertEquals(1, echoed.weather.locations.locationCount)
        assertEquals(BAND_CODE, echoed.weather.locations.getLocation(0).code)
        assertEquals("Band city", echoed.weather.locations.getLocation(0).name)
        val several = configured.toBuilder().addLocation(
            XiaomiProto.WeatherLocation.newBuilder().setCode("weathercn:000000002").setName("Other"),
        ).build()
        assertThrows(IllegalArgumentException::class.java) {
            BandWeatherEncoder.bindObservedCity(source, several, "", "")
        }
        assertEquals(2, BandWeatherEncoder.echoLocations(several).weather.locations.locationCount)
    }

    @Test
    fun oneNonWeathercnCityIsKept() {
        val source = sample("c", "Asia/Shanghai", 56, 21.0)
        val configured = city("accu:246810", "Band city")
        val bound = BandWeatherEncoder.bindObservedCity(source, configured, "", "")
        assertEquals("accu:246810", bound.locationKey())
        assertEquals("Band city", bound.locationName())
        val echoed = BandWeatherEncoder.echoLocations(configured)
        assertEquals(6, echoed.subtype)
        assertEquals("accu:246810", echoed.weather.locations.getLocation(0).code)
        assertEquals(0, BandWeatherEncoder.acceptedCityCount(city("not a code", "Band city")))
        assertEquals(
            "WEATHER_CITY_SETUP_REQUIRED",
            assertThrows(IllegalArgumentException::class.java) {
                BandWeatherEncoder.echoLocations(city("not a code", "Band city"))
            }.message,
        )
    }

    @Test
    fun movingReplacesTheAutomaticCityAndKeepsManualOnes() {
        val source = sample("c", "Asia/Shanghai", 56, 21.0)
        val here = BandWeatherEncoder.Sample(
            "phone-shanghai",
            "上海",
            "徐汇",
            source.timezone(),
            source.unit(),
            source.publishedAtMs(),
            source.conditionCode(),
            source.temperature(),
            null,
            null,
            null,
            null,
            null,
            null,
            source.daily(),
            source.hourly(),
        )
        val stuck = city("phone-beijing", "北京")
        val replaced = BandWeatherEncoder.replaceCurrentLocation(here, stuck, "")
        assertEquals(6, replaced.subtype)
        assertEquals(1, replaced.weather.locations.locationCount)
        assertEquals("phone-shanghai", replaced.weather.locations.getLocation(0).code)
        assertEquals("徐汇", replaced.weather.locations.getLocation(0).name)
        val manual = stuck.toBuilder().addLocation(
            XiaomiProto.WeatherLocation.newBuilder().setCode("weathercn:000000002").setName("杭州"),
        ).build()
        val kept = BandWeatherEncoder.replaceCurrentLocation(here, manual, "phone-beijing")
        assertEquals(
            listOf("phone-shanghai", "weathercn:000000002"),
            kept.weather.locations.locationList.map { it.code },
        )
        val same = BandWeatherEncoder.replaceCurrentLocation(source, city(BAND_CODE, "Band city"), "")
        assertEquals(
            listOf(BAND_CODE),
            same.weather.locations.locationList.map { it.code },
        )
        val sole = BandWeatherEncoder.planTakeover(here, stuck, "")
        assertEquals(8, sole.remove()?.subtype)
        assertEquals("phone-beijing", sole.remove()?.weather?.locations?.getLocation(0)?.code)
        assertEquals(7, sole.write()?.subtype)
        assertEquals("phone-shanghai", sole.write()?.weather?.location?.code)
        assertNull(BandWeatherEncoder.planTakeover(here, city("phone-shanghai", "徐汇"), "phone-shanghai").write())
        val keptPlan = BandWeatherEncoder.planTakeover(here, manual, "phone-beijing")
        assertNull(keptPlan.remove())
        assertEquals(6, keptPlan.write()?.subtype)
        val current = frame(BandWeatherEncoder.encode(here), 0).weather.current.metadata
        assertEquals("phone-shanghai", current.locationKey)
        assertEquals("徐汇", current.locationName)
        assertTrue(current.isCurrentLocation)
    }

    @Test
    fun emptyCityListRegistersTheSourceLocationWithoutReplacingCities() {
        val source = sample("c", "Asia/Shanghai", 56, 21.0)
        val generic = BandWeatherEncoder.Sample(
            "phone-location",
            source.cityName(),
            source.locationName(),
            source.timezone(),
            source.unit(),
            source.publishedAtMs(),
            source.conditionCode(),
            source.temperature(),
            null,
            null,
            null,
            null,
            null,
            null,
            source.daily(),
            source.hourly(),
        )
        val add = BandWeatherEncoder.addCurrentLocation(generic)
        assertEquals(10, add.type)
        assertEquals(7, add.subtype)
        assertFalse(add.weather.hasLocations())
        assertEquals("phone-location", add.weather.location.code)
        assertEquals("Source place", add.weather.location.name)
        assertEquals(
            "WEATHER_CITY_SETUP_REQUIRED",
            assertThrows(IllegalArgumentException::class.java) {
                BandWeatherEncoder.echoLocations(XiaomiProto.WeatherLocations.getDefaultInstance())
            }.message,
        )
    }

    @Test
    fun observedHumidityAndAirQualityAreSentWithoutInventingEither() {
        val source = sample("c", "Asia/Shanghai", 56, 21.0)
        val measured = BandWeatherEncoder.Sample(
            source.locationKey(),
            source.cityName(),
            source.locationName(),
            source.timezone(),
            source.unit(),
            source.publishedAtMs(),
            source.conditionCode(),
            source.temperature(),
            94,
            36,
            3,
            180,
            2,
            1013.0,
            source.daily(),
            source.hourly(),
        )
        val current = frame(BandWeatherEncoder.encode(measured), 0).weather.current
        assertEquals(94, current.humidity.value)
        assertEquals("%", current.humidity.unit)
        assertEquals(36, current.aqi.value)
        assertEquals("", current.aqi.unit)
        assertEquals(3, current.wind.value)
        assertEquals("180.0", current.wind.unit)
        assertEquals(101300f, current.pressure, 0.1f)
        assertFalse(frame(BandWeatherEncoder.encode(source), 0).weather.current.hasHumidity())
        assertFalse(frame(BandWeatherEncoder.encode(source), 0).weather.current.hasWind())
    }

    @Test
    fun sourceLocationIsSentWhenTheBandHasNoConfiguredCity() {
        val source = sample("c", "Asia/Shanghai", 56, 21.0)
        val generic = BandWeatherEncoder.Sample(
            "phone-location",
            source.cityName(),
            source.locationName(),
            source.timezone(),
            source.unit(),
            source.publishedAtMs(),
            source.conditionCode(),
            source.temperature(),
            null,
            36,
            null,
            null,
            null,
            null,
            source.daily(),
            source.hourly(),
        )
        val current = frame(BandWeatherEncoder.encode(generic), 0).weather.current
        assertEquals("phone-location", current.metadata.locationKey)
        assertEquals("Source city", current.metadata.cityName)
        assertEquals(36, current.aqi.value)
        assertEquals("", current.aqi.unit)
        val unbound = BandWeatherEncoder.bindObservedCity(
            generic,
            XiaomiProto.WeatherLocations.getDefaultInstance(),
            "",
            "",
        )
        assertEquals("phone-location", unbound.locationKey())
        assertEquals("Source place", unbound.locationName())
    }
}
