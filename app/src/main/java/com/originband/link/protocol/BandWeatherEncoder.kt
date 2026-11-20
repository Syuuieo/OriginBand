/* Copyright (C) 2023-2024 Andreas Shimokawa, José Rebelo, opcode.
 * Adapted weather wire semantics from Gadgetbridge commit
 * 75f923904f8504b03fabdee0987fd1c269a92278; see compat/upstream.json.
 */
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Converts complete OHealth weather samples into only fields observed on Band 11. */
object BandWeatherEncoder {
    data class Day(
        @JvmField val timeMs: Long,
        @JvmField val dayCode: Int,
        @JvmField val nightCode: Int,
        @JvmField val minTemp: Double,
        @JvmField val maxTemp: Double,
        @JvmField val sunriseMs: Long?,
        @JvmField val sunsetMs: Long?,
    ) {
        fun timeMs(): Long = timeMs
        fun dayCode(): Int = dayCode
        fun nightCode(): Int = nightCode
        fun minTemp(): Double = minTemp
        fun maxTemp(): Double = maxTemp
        fun sunriseMs(): Long? = sunriseMs
        fun sunsetMs(): Long? = sunsetMs
    }

    data class Hour(
        @JvmField val timeMs: Long,
        @JvmField val conditionCode: Int,
        @JvmField val temperature: Double,
    ) {
        fun timeMs(): Long = timeMs
        fun conditionCode(): Int = conditionCode
        fun temperature(): Double = temperature
    }

    data class Sample(
        @JvmField val locationKey: String,
        @JvmField val cityName: String,
        @JvmField val locationName: String,
        @JvmField val timezone: String,
        @JvmField val unit: String,
        @JvmField val publishedAtMs: Long,
        @JvmField val conditionCode: Int,
        @JvmField val temperature: Double,
        @JvmField val humidityPercent: Int?,
        @JvmField val aqi: Int?,
        @JvmField val windPower: Int?,
        @JvmField val windDegree: Int?,
        @JvmField val uvIndex: Int?,
        @JvmField val pressure: Double?,
        @JvmField val daily: List<Day>,
        @JvmField val hourly: List<Hour>,
    ) {
        fun locationKey(): String = locationKey
        fun cityName(): String = cityName
        fun locationName(): String = locationName
        fun timezone(): String = timezone
        fun unit(): String = unit
        fun publishedAtMs(): Long = publishedAtMs
        fun conditionCode(): Int = conditionCode
        fun temperature(): Double = temperature
        fun humidityPercent(): Int? = humidityPercent
        fun aqi(): Int? = aqi
        fun windPower(): Int? = windPower
        fun windDegree(): Int? = windDegree
        fun uvIndex(): Int? = uvIndex
        fun pressure(): Double? = pressure
        fun daily(): List<Day> = daily
        fun hourly(): List<Hour> = hourly
    }

    /**
     * One city slot cannot be swapped in place. Band 8 keeps the old weather row until
     * 10/8 frees it; a forecast for the new key then finds no free row and the link drops.
     * Several slots still use one 10/6 list, with the phone city first.
     */
    data class CityUpdate(
        @JvmField val remove: XiaomiProto.Command?,
        @JvmField val write: XiaomiProto.Command?,
    ) {
        fun remove(): XiaomiProto.Command? = remove
        fun write(): XiaomiProto.Command? = write

        companion object {
            @JvmStatic
            fun none(): CityUpdate = CityUpdate(null, null)
        }
    }

    private val BAND_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)
    private val CITY_CODE_REGEX = Regex("[A-Za-z0-9:_-]{1,64}")

    @JvmStatic
    @Throws(Exception::class)
    fun parse(snapshot: JSONObject): Sample {
        val days = snapshot.getJSONArray("daily")
        val hours = snapshot.getJSONArray("hourly")
        val daily = ArrayList<Day>(days.length())
        val hourly = ArrayList<Hour>(hours.length())
        for (i in 0 until days.length()) {
            val day = days.getJSONObject(i)
            daily.add(
                Day(
                    timeMs = day.getLong("timeMs"),
                    dayCode = day.getInt("dayCode"),
                    nightCode = day.getInt("nightCode"),
                    minTemp = day.getDouble("minTemp"),
                    maxTemp = day.getDouble("maxTemp"),
                    sunriseMs = if (day.has("sunriseMs")) day.getLong("sunriseMs") else null,
                    sunsetMs = if (day.has("sunsetMs")) day.getLong("sunsetMs") else null,
                ),
            )
        }
        for (i in 0 until hours.length()) {
            val hour = hours.getJSONObject(i)
            hourly.add(
                Hour(
                    timeMs = hour.getLong("timeMs"),
                    conditionCode = hour.getInt("conditionCode"),
                    temperature = hour.getDouble("temperature"),
                ),
            )
        }
        return Sample(
            locationKey = snapshot.getString("locationKey"),
            cityName = snapshot.getString("cityName"),
            locationName = snapshot.getString("locationName"),
            timezone = snapshot.getString("timezone"),
            unit = snapshot.getString("unit"),
            publishedAtMs = snapshot.getLong("publishedAtMs"),
            conditionCode = snapshot.getInt("conditionCode"),
            temperature = snapshot.getDouble("temperature"),
            humidityPercent = if (snapshot.has("humidity")) snapshot.getInt("humidity") else null,
            aqi = if (snapshot.has("aqi")) snapshot.getInt("aqi") else null,
            windPower = if (snapshot.has("windPower")) snapshot.getInt("windPower") else null,
            windDegree = if (snapshot.has("windDegree")) snapshot.getInt("windDegree") else null,
            uvIndex = if (snapshot.has("uvIndex")) snapshot.getInt("uvIndex") else null,
            pressure = if (snapshot.has("pressure")) snapshot.getDouble("pressure") else null,
            daily = java.util.List.copyOf(daily),
            hourly = java.util.List.copyOf(hourly),
        )
    }

    /** Check the forecast without assuming OHealth's city key is a Band weather code. */
    @JvmStatic
    fun validateForecast(sample: Sample?) {
        requireValid(sample)
    }

    private fun requireValid(sample: Sample?): Sample {
        if (sample == null ||
            sample.locationKey.isBlank() ||
            sample.locationKey.length > 64 ||
            sample.cityName.isBlank() ||
            sample.cityName.length > 80 ||
            sample.locationName.isBlank() ||
            sample.locationName.length > 80 ||
            (sample.unit != "c" && sample.unit != "f")
        ) {
            throw IllegalArgumentException("WEATHER_SAMPLE_INCOMPLETE")
        }
        zone(sample.timezone)
        checkTime(sample.publishedAtMs)
        mapCondition(sample.conditionCode)
        celsius(sample.temperature, sample.unit)
        val humidity = sample.humidityPercent
        if (humidity != null && (humidity < 0 || humidity > 100)) {
            throw IllegalArgumentException("WEATHER_HUMIDITY_INVALID")
        }
        val aqi = sample.aqi
        if (aqi != null && (aqi < 0 || aqi > 500)) {
            throw IllegalArgumentException("WEATHER_AQI_INVALID")
        }
        val windPower = sample.windPower
        if (windPower != null && (windPower < 0 || windPower > 17)) {
            throw IllegalArgumentException("WEATHER_WIND_INVALID")
        }
        val windDegree = sample.windDegree
        if (windDegree != null && (windDegree < 0 || windDegree > 360)) {
            throw IllegalArgumentException("WEATHER_WIND_INVALID")
        }
        val uvIndex = sample.uvIndex
        if (uvIndex != null && (uvIndex < 0 || uvIndex > 15)) {
            throw IllegalArgumentException("WEATHER_UV_INVALID")
        }
        val pressure = sample.pressure
        if (pressure != null && !(pressure in 800.0..1100.0 || pressure in 80_000.0..110_000.0)) {
            throw IllegalArgumentException("WEATHER_PRESSURE_INVALID")
        }
        var previousDay = -1L
        for (day in sample.daily) {
            checkTime(day.timeMs)
            if (day.timeMs <= previousDay ||
                !day.minTemp.isFinite() ||
                !day.maxTemp.isFinite() ||
                day.maxTemp < day.minTemp
            ) {
                throw IllegalArgumentException("WEATHER_DAY_INVALID")
            }
            previousDay = day.timeMs
            celsius(day.minTemp, sample.unit)
            celsius(day.maxTemp, sample.unit)
            val sunrise = day.sunriseMs
            val sunset = day.sunsetMs
            if (sunrise != null && sunset != null) {
                checkTime(sunrise)
                checkTime(sunset)
                if (sunrise >= sunset) throw IllegalArgumentException("WEATHER_SUN_TIMES_INVALID")
            }
        }
        var previousHour = -1L
        for (hour in sample.hourly) {
            checkTime(hour.timeMs)
            if (hour.timeMs <= previousHour || !hour.temperature.isFinite()) {
                throw IllegalArgumentException("WEATHER_HOUR_INVALID")
            }
            previousHour = hour.timeMs
            celsius(hour.temperature, sample.unit)
        }
        return sample
    }

    @JvmStatic
    fun bindExplicitCity(
        sample: Sample?,
        sourceKey: String?,
        sourceCity: String?,
        sourcePlace: String?,
        bandCode: String?,
        bandName: String?,
        configured: XiaomiProto.WeatherLocations?,
    ): Sample {
        val validSample = requireValid(sample)
        if (validSample.locationKey != sourceKey ||
            validSample.cityName != sourceCity ||
            validSample.locationName != sourcePlace ||
            bandCode == null ||
            !acceptableCityCode(bandCode) ||
            bandName == null ||
            bandName.isBlank() ||
            bandName.length > 80
        ) {
            throw IllegalArgumentException("WEATHER_CITY_CONFIRMATION_INVALID")
        }
        var matched = false
        if (configured != null) {
            for (city in configured.locationList) {
                if (bandCode == city.code && bandName == city.name) {
                    matched = true
                    break
                }
            }
        }
        if (!matched) throw IllegalArgumentException("WEATHER_CITY_CONFIRMATION_INVALID")
        return copy(validSample, bandCode, validSample.cityName, validSample.locationName)
    }

    /** One configured city is enough. Several cities still need the previously confirmed pair. */
    @JvmStatic
    fun bindObservedCity(
        sample: Sample?,
        configured: XiaomiProto.WeatherLocations?,
        confirmedCode: String?,
        confirmedName: String?,
    ): Sample {
        val validSample = requireValid(sample)
        val valid = validCities(configured)
        if (valid.isEmpty()) return validSample
        var chosen: XiaomiProto.WeatherLocation? = valid[0]
        if (valid.size > 1) {
            chosen = null
            for (city in valid) {
                if (city.code == confirmedCode && city.name == confirmedName) chosen = city
            }
            if (chosen == null) throw IllegalArgumentException("WEATHER_CITY_CONFIRMATION_REQUIRED")
        }
        val locationName = chosen!!.name
        val cityName = if (locationName == validSample.cityName) validSample.locationName else validSample.cityName
        return copy(validSample, chosen.code, cityName, locationName)
    }

    @JvmStatic
    fun planTakeover(
        sample: Sample?,
        configured: XiaomiProto.WeatherLocations?,
        previousKey: String?,
    ): CityUpdate {
        val validSample = requireValid(sample)
        val existing = validCities(configured)
        if (existing.isNotEmpty() && existing[0].code == validSample.locationKey) {
            return CityUpdate.none()
        }
        if (existing.isEmpty()) return CityUpdate(null, addCurrentLocation(validSample))
        if (existing.size == 1) {
            return CityUpdate(removeLocation(existing[0]), addCurrentLocation(validSample))
        }
        return CityUpdate(null, replaceCurrentLocation(validSample, configured, previousKey))
    }

    @JvmStatic
    fun removeLocation(city: XiaomiProto.WeatherLocation?): XiaomiProto.Command {
        if (city == null || !acceptableCityCode(city.code)) {
            throw IllegalArgumentException("WEATHER_CITY_SETUP_REQUIRED")
        }
        return XiaomiProto.Command.newBuilder().setType(10).setSubtype(8)
            .setWeather(
                XiaomiProto.Weather.newBuilder().setLocations(
                    XiaomiProto.WeatherLocations.newBuilder().addLocation(city),
                ),
            ).build()
    }

    /**
     * Xiaomi Sports writes the current city first, drops the previous automatic city,
     * and keeps any other cities. A single city with no saved key is that previous city.
     */
    @JvmStatic
    fun replaceCurrentLocation(
        sample: Sample?,
        configured: XiaomiProto.WeatherLocations?,
        previousKey: String?,
    ): XiaomiProto.Command {
        val validSample = requireValid(sample)
        val ordered = ArrayList<XiaomiProto.WeatherLocation>()
        ordered.add(
            XiaomiProto.WeatherLocation.newBuilder()
                .setCode(validSample.locationKey)
                .setName(validSample.locationName)
                .build(),
        )
        val previous = previousKey ?: ""
        val existing = validCities(configured)
        val dropSole = previous.isEmpty() &&
            existing.size == 1 &&
            existing[0].code != validSample.locationKey
        for (city in existing) {
            if (city.code == validSample.locationKey) continue
            if (previous.isNotEmpty() && city.code == previous) continue
            if (dropSole) continue
            ordered.add(city)
        }
        while (ordered.size > 5) ordered.removeAt(ordered.size - 1)
        val copy = XiaomiProto.WeatherLocations.newBuilder()
        for (city in ordered) copy.addLocation(city)
        return XiaomiProto.Command.newBuilder().setType(10).setSubtype(6)
            .setWeather(XiaomiProto.Weather.newBuilder().setLocations(copy)).build()
    }

    /** Official sync writes the band's current list back unchanged before the forecast frames. */
    @JvmStatic
    fun echoLocations(configured: XiaomiProto.WeatherLocations?): XiaomiProto.Command {
        val copy = XiaomiProto.WeatherLocations.newBuilder()
        for (city in validCities(configured)) copy.addLocation(city)
        if (copy.locationCount == 0) throw IllegalArgumentException("WEATHER_CITY_SETUP_REQUIRED")
        return XiaomiProto.Command.newBuilder().setType(10).setSubtype(6)
            .setWeather(XiaomiProto.Weather.newBuilder().setLocations(copy)).build()
    }

    /** Band 10 drops a forecast whose key is absent. 10/7 adds one city and leaves the rest of the list. */
    @JvmStatic
    fun addCurrentLocation(sample: Sample?): XiaomiProto.Command {
        val validSample = requireValid(sample)
        return XiaomiProto.Command.newBuilder().setType(10).setSubtype(7)
            .setWeather(
                XiaomiProto.Weather.newBuilder().setLocation(
                    XiaomiProto.WeatherLocation.newBuilder()
                        .setCode(validSample.locationKey)
                        .setName(validSample.locationName),
                ),
            ).build()
    }

    @JvmStatic
    fun acceptedCityCount(configured: XiaomiProto.WeatherLocations?): Int = validCities(configured).size

    @JvmStatic
    fun acceptableCityCode(code: String?): Boolean = code != null && CITY_CODE_REGEX.matches(code)

    private fun validCities(configured: XiaomiProto.WeatherLocations?): ArrayList<XiaomiProto.WeatherLocation> {
        val valid = ArrayList<XiaomiProto.WeatherLocation>()
        if (configured == null) return valid
        for (city in configured.locationList) {
            if (acceptableCityCode(city.code) && city.hasName() &&
                !city.name.isBlank() && city.name.length <= 80
            ) {
                valid.add(city)
            }
        }
        return valid
    }

    private fun copy(sample: Sample, code: String, cityName: String, locationName: String): Sample =
        Sample(
            locationKey = code,
            cityName = cityName,
            locationName = locationName,
            timezone = sample.timezone,
            unit = sample.unit,
            publishedAtMs = sample.publishedAtMs,
            conditionCode = sample.conditionCode,
            temperature = sample.temperature,
            humidityPercent = sample.humidityPercent,
            aqi = sample.aqi,
            windPower = sample.windPower,
            windDegree = sample.windDegree,
            uvIndex = sample.uvIndex,
            pressure = sample.pressure,
            daily = sample.daily,
            hourly = sample.hourly,
        )

    @JvmStatic
    fun encode(sample: Sample?): List<XiaomiProto.Command> {
        val validSample = requireValid(sample)
        val zone = zone(validSample.timezone)
        val condition = mapCondition(validSample.conditionCode)
        val metadata = XiaomiProto.WeatherMetadata.newBuilder()
            .setPublicationTimestamp(format(validSample.publishedAtMs, zone))
            .setCityName(validSample.cityName)
            .setLocationName(validSample.locationName)
            .setLocationKey(validSample.locationKey)
            .setIsCurrentLocation(true)
            .build()
        val symbol = "\u2103"
        val current = XiaomiProto.WeatherCurrent.newBuilder()
            .setMetadata(metadata)
            .setWeatherCondition(condition)
            .setTemperature(
                XiaomiProto.WeatherUnitValue.newBuilder()
                    .setUnit(symbol)
                    .setValue(celsius(validSample.temperature, validSample.unit)),
            )
        val humidity = validSample.humidityPercent
        if (humidity != null) current.setHumidity(measurement(humidity, "%"))
        val aqi = validSample.aqi
        if (aqi != null) current.setAqi(measurement(aqi, ""))
        val windPower = validSample.windPower
        val windDegree = validSample.windDegree
        if (windPower != null && windDegree != null) {
            current.setWind(measurement(windPower, String.format(Locale.ROOT, "%.1f", windDegree.toDouble())))
        }
        val uvIndex = validSample.uvIndex
        if (uvIndex != null) current.setUv(measurement(uvIndex, ""))
        val pressure = validSample.pressure
        if (pressure != null) {
            current.setPressure((if (pressure >= 80_000) pressure else pressure * 100).toFloat())
        }
        current.setWarning(XiaomiProto.WeatherWarnings.newBuilder())
        val commands = ArrayList<XiaomiProto.Command>(3)
        commands.add(
            XiaomiProto.Command.newBuilder().setType(10).setSubtype(0)
                .setWeather(XiaomiProto.Weather.newBuilder().setCurrent(current)).build(),
        )
        val daily = dailyEntries(validSample, zone, symbol)
        if (daily != null) commands.add(forecastCommand(1, metadata, daily))
        val hourly = hourlyEntries(validSample, zone)
        if (hourly != null) commands.add(forecastCommand(2, metadata, hourly))
        return java.util.List.copyOf(commands)
    }

    private fun forecastCommand(
        subtype: Int,
        metadata: XiaomiProto.WeatherMetadata,
        entries: XiaomiProto.ForecastEntries,
    ): XiaomiProto.Command =
        XiaomiProto.Command.newBuilder().setType(10).setSubtype(subtype)
            .setWeather(
                XiaomiProto.Weather.newBuilder().setForecast(
                    XiaomiProto.WeatherForecast.newBuilder().setMetadata(metadata).setEntries(entries),
                ),
            ).build()

    private fun dailyEntries(
        sample: Sample,
        zone: ZoneId,
        symbol: String,
    ): XiaomiProto.ForecastEntries? {
        var expected = Instant.ofEpochMilli(sample.publishedAtMs).atZone(zone).toLocalDate()
        val entries = XiaomiProto.ForecastEntries.newBuilder()
        for (day in sample.daily) {
            val date = Instant.ofEpochMilli(day.timeMs).atZone(zone).toLocalDate()
            if (entries.entryCount == 0 && date.isBefore(expected)) continue
            // Entries carry no timestamp: a gap would shift every later row on the band.
            if (date != expected) return null
            val entry = XiaomiProto.ForecastEntry.newBuilder()
                .setTemperatureRange(
                    XiaomiProto.WeatherRange.newBuilder()
                        .setFrom(celsius(day.maxTemp, sample.unit))
                        .setTo(celsius(day.minTemp, sample.unit)),
                )
                .setTemperatureSymbol(symbol)
            val aqi = sample.aqi
            if (aqi != null) entry.setAqi(measurement(aqi, ""))
            val dayCondition = WeatherConditionMap.fromOHealth(day.dayCode)
            val nightCondition = WeatherConditionMap.fromOHealth(day.nightCode)
            if (dayCondition >= 0 && nightCondition >= 0) {
                entry.setConditionRange(
                    XiaomiProto.WeatherRange.newBuilder().setFrom(dayCondition).setTo(nightCondition),
                )
            }
            val sunrise = day.sunriseMs
            val sunset = day.sunsetMs
            if (sunrise != null && sunset != null) {
                entry.setSunriseSunset(
                    XiaomiProto.WeatherSunriseSunset.newBuilder()
                        .setSunrise(format(sunrise, zone))
                        .setSunset(format(sunset, zone)),
                )
            }
            entries.addEntry(entry)
            if (entries.entryCount == 7) break
            expected = expected.plusDays(1)
        }
        return if (entries.entryCount == 0) null else entries.build()
    }

    private fun hourlyEntries(sample: Sample, zone: ZoneId): XiaomiProto.ForecastEntries? {
        var expected = Instant.ofEpochMilli(sample.publishedAtMs).atZone(zone).truncatedTo(ChronoUnit.HOURS)
        val entries = XiaomiProto.ForecastEntries.newBuilder()
        for (hour in sample.hourly) {
            val time = Instant.ofEpochMilli(hour.timeMs).atZone(zone).truncatedTo(ChronoUnit.HOURS)
            if (entries.entryCount == 0 && time.isBefore(expected)) continue
            if (entries.entryCount == 0 && time == expected.plusHours(1)) {
                entries.addEntry(observedHour(sample, sample.conditionCode, sample.temperature))
                expected = expected.plusHours(1)
            }
            // Entries carry no timestamp: an internal gap would shift every later row.
            if (time != expected) return null
            entries.addEntry(observedHour(sample, hour.conditionCode, hour.temperature))
            if (entries.entryCount == 23) break
            expected = expected.plusHours(1)
        }
        return if (entries.entryCount == 0) null else entries.build()
    }

    private fun observedHour(
        sample: Sample,
        conditionCode: Int,
        temperature: Double,
    ): XiaomiProto.ForecastEntry {
        val entry = XiaomiProto.ForecastEntry.newBuilder()
            .setTemperatureRange(
                XiaomiProto.WeatherRange.newBuilder()
                    .setFrom(0)
                    .setTo(celsius(temperature, sample.unit)),
            )
        val condition = WeatherConditionMap.fromOHealth(conditionCode)
        if (condition >= 0) {
            entry.setConditionRange(XiaomiProto.WeatherRange.newBuilder().setFrom(0).setTo(condition))
        }
        val aqi = sample.aqi
        if (aqi != null) entry.setAqi(measurement(aqi, ""))
        val windPower = sample.windPower
        val windDegree = sample.windDegree
        if (windPower != null && windDegree != null) {
            entry.setWind(measurement(windPower, String.format(Locale.ROOT, "%.1f", windDegree.toDouble())))
        }
        return entry.build()
    }

    private fun measurement(value: Int, unit: String): XiaomiProto.WeatherUnitValue =
        XiaomiProto.WeatherUnitValue.newBuilder().setUnit(unit).setValue(value).build()

    private fun mapCondition(oHealthCode: Int): Int {
        val mapped = WeatherConditionMap.fromOHealth(oHealthCode)
        if (mapped < 0) throw IllegalArgumentException("UNKNOWN_WEATHER_CONDITION")
        return mapped
    }

    private fun celsius(value: Double, unit: String): Int {
        val celsius = if (unit == "f") (value - 32) * 5 / 9 else value
        if (!celsius.isFinite() || celsius < -90 || celsius > 60) {
            throw IllegalArgumentException("WEATHER_TEMPERATURE_INVALID")
        }
        return Math.round(celsius).toInt()
    }

    private fun zone(raw: String?): ZoneId {
        if (raw == null || raw.isBlank() || raw.length > 64) {
            throw IllegalArgumentException("WEATHER_ZONE_MISSING")
        }
        return try {
            val hours = raw.toDouble()
            if (!hours.isFinite() || Math.abs(hours) > 18) {
                throw IllegalArgumentException("WEATHER_ZONE_INVALID")
            }
            ZoneOffset.ofTotalSeconds(Math.round(hours * 3600).toInt())
        } catch (_: NumberFormatException) {
            try {
                ZoneId.of(raw)
            } catch (_: Exception) {
                throw IllegalArgumentException("WEATHER_ZONE_INVALID")
            }
        }
    }

    private fun checkTime(epochMs: Long) {
        if (epochMs < 1_000_000_000_000L || epochMs > 4_102_444_800_000L) {
            throw IllegalArgumentException("WEATHER_TIME_INVALID")
        }
    }

    private fun format(epochMs: Long, zone: ZoneId): String {
        checkTime(epochMs)
        return BAND_TIME.format(Instant.ofEpochMilli(epochMs).atZone(zone))
    }
}
