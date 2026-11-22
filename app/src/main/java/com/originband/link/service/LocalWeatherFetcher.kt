// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log
import com.originband.link.data.SessionLog
import com.originband.link.integration.WeatherSnapshotProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Fetches live weather from Open-Meteo when no host weather publisher is active,
 * emitting snapshots in the exact schema validated by [WeatherSnapshotProvider].
 */
object LocalWeatherFetcher {
    private const val PREFS = "weather-local"
    private const val DEFAULT_LAT = 39.9042
    private const val DEFAULT_LON = 116.4074
    private const val DEFAULT_CITY = "北京"

    private val worker = Executors.newSingleThreadExecutor()
    private val inFlight = AtomicBoolean(false)

    data class Coords(val lat: Double, val lon: Double, val city: String)

    @JvmStatic
    fun fetchAsync(context: Context) {
        val app = context.applicationContext ?: context
        if (!inFlight.compareAndSet(false, true)) return
        worker.execute {
            try {
                val coords = resolveCoords(app)
                Log.i("OriginBand", "WEATHER_LOCAL_FETCH city=${coords.city} lat=${coords.lat} lon=${coords.lon}")
                val url = String.format(
                    Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                        "&current=temperature_2m,relative_humidity_2m,weather_code,surface_pressure,wind_speed_10m,wind_direction_10m" +
                        "&hourly=temperature_2m,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,uv_index_max" +
                        "&timezone=auto&timeformat=unixtime&forecast_days=7",
                    coords.lat,
                    coords.lon,
                )
                val body = httpGet(url)
                val snapshot = parseOpenMeteo(body, coords.city, coords.lat, coords.lon, System.currentTimeMillis())
                val stored = WeatherSnapshotProvider.storeLocalSnapshot(app, snapshot)
                Log.i("OriginBand", "WEATHER_LOCAL_OK city=${coords.city} stored=$stored")
                SessionLog.line(app, "WEATHER_LOCAL_OK city=${coords.city} stored=$stored")
            } catch (e: Exception) {
                Log.w("OriginBand", "WEATHER_LOCAL_ERR ${e.javaClass.simpleName}: ${e.message}")
                WeatherSnapshotProvider.reportLocalFailure(app, "OHEALTH_WEATHER_REQUEST_FAILED")
            } finally {
                inFlight.set(false)
            }
        }
    }

    @JvmStatic
    fun saveCityOverride(context: Context, lat: Double, lon: Double, city: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("city", city)
            .putString("lat", lat.toString())
            .putString("lon", lon.toString())
            .apply()
    }

    @JvmStatic
    fun searchCityAndFetchAsync(context: Context, query: String, onDone: (Boolean) -> Unit) {
        val app = context.applicationContext ?: context
        worker.execute {
            try {
                val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
                val geo = JSONObject(
                    httpGet("https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=zh"),
                )
                val results = geo.optJSONArray("results")
                if (results == null || results.length() == 0) {
                    onDone(false)
                    return@execute
                }
                val first = results.getJSONObject(0)
                val lat = first.getDouble("latitude")
                val lon = first.getDouble("longitude")
                val name = first.optString("name", query.trim()).ifBlank { query.trim() }
                saveCityOverride(app, lat, lon, name)
                fetchAsync(app)
                onDone(true)
            } catch (_: Exception) {
                onDone(false)
            }
        }
    }

    /** Maps WMO weather interpretation code (0..99) to OHealth WeatherCodeNewEnum (0..71). */
    @JvmStatic
    fun oHealthCodeFromWmo(wmo: Int): Int = when (wmo) {
        0 -> 54 // Clear
        1, 2 -> 56 // Partly cloudy
        3 -> 59 // Overcast
        45, 48 -> 38 // Fog
        51, 53, 55, 56, 57, 61, 80 -> 1 // Light rain / drizzle
        63, 81 -> 8 // Moderate rain
        65, 66, 67, 82 -> 9 // Heavy rain
        71, 77, 85 -> 27 // Light snow
        73 -> 29 // Moderate snow
        75, 86 -> 31 // Heavy snow
        95, 96, 99 -> 22 // Thunderstorm
        else -> 56
    }

    @JvmStatic
    fun parseOpenMeteo(
        json: String,
        cityName: String,
        lat: Double,
        lon: Double,
        nowMs: Long,
    ): JSONObject {
        val root = JSONObject(json)
        val current = root.getJSONObject("current")
        val temp = current.getDouble("temperature_2m")
        val humidity = current.optInt("relative_humidity_2m", -1)
        val wmo = current.optInt("weather_code", 0)
        val pressure = current.optDouble("surface_pressure", Double.NaN)
        val windSpeed = current.optDouble("wind_speed_10m", Double.NaN)
        val windDir = current.optInt("wind_direction_10m", -1)
        val tz = root.optString("timezone", TimeZone.getDefault().id).ifBlank { TimeZone.getDefault().id }

        val dailyObj = root.getJSONObject("daily")
        val dailyTimes = dailyObj.getJSONArray("time")
        val dailyCodes = dailyObj.getJSONArray("weather_code")
        val dailyMax = dailyObj.getJSONArray("temperature_2m_max")
        val dailyMin = dailyObj.getJSONArray("temperature_2m_min")
        val dailySunrise = dailyObj.optJSONArray("sunrise")
        val dailySunset = dailyObj.optJSONArray("sunset")
        val dailyUv = dailyObj.optJSONArray("uv_index_max")

        val dailyArray = JSONArray()
        val dayCount = minOf(7, dailyTimes.length())
        for (i in 0 until dayCount) {
            val code = oHealthCodeFromWmo(dailyCodes.optInt(i, wmo))
            val day = JSONObject()
                .put("timeMs", dailyTimes.getLong(i) * 1000L)
                .put("dayCode", code)
                .put("nightCode", code)
                .put("maxTemp", dailyMax.getDouble(i))
                .put("minTemp", dailyMin.getDouble(i))
            if (dailySunrise != null && i < dailySunrise.length()) {
                day.put("sunriseMs", dailySunrise.optLong(i, 0L) * 1000L)
            }
            if (dailySunset != null && i < dailySunset.length()) {
                day.put("sunsetMs", dailySunset.optLong(i, 0L) * 1000L)
            }
            dailyArray.put(day)
        }

        val hourlyObj = root.getJSONObject("hourly")
        val hourlyTimes = hourlyObj.getJSONArray("time")
        val hourlyTemps = hourlyObj.getJSONArray("temperature_2m")
        val hourlyCodes = hourlyObj.getJSONArray("weather_code")
        val hourlyArray = JSONArray()
        for (i in 0 until hourlyTimes.length()) {
            val tMs = hourlyTimes.getLong(i) * 1000L
            if (tMs + 3_600_000L < nowMs) continue
            if (hourlyArray.length() >= 23) break
            hourlyArray.put(
                JSONObject()
                    .put("timeMs", tMs)
                    .put("conditionCode", oHealthCodeFromWmo(hourlyCodes.optInt(i, wmo)))
                    .put("temperature", hourlyTemps.getDouble(i)),
            )
        }

        val key = String.format(Locale.US, "om_%.2f_%.2f", lat, lon).replace('.', '_').replace('-', 'm')
        val out = JSONObject()
            .put("locationKey", key)
            .put("cityName", cityName)
            .put("locationName", cityName)
            .put("timezone", tz)
            .put("unit", "c")
            .put("publishedAtMs", nowMs)
            .put("conditionCode", oHealthCodeFromWmo(wmo))
            .put("temperature", temp)
            .put("daily", dailyArray)
            .put("hourly", hourlyArray)
        if (humidity in 0..100) out.put("humidityPercent", humidity)
        if (!pressure.isNaN()) out.put("pressure", pressure)
        if (!windSpeed.isNaN()) out.put("windPower", windLevel(windSpeed))
        if (windDir in 0..360) out.put("windDegree", windDir)
        if (dailyUv != null && dailyUv.length() > 0 && !dailyUv.isNull(0)) {
            out.put("uvIndex", dailyUv.optDouble(0, 0.0).roundToInt().coerceAtLeast(0))
        }
        return out
    }

    private fun windLevel(kmh: Double): Int = when {
        kmh < 2 -> 0
        kmh < 6 -> 1
        kmh < 12 -> 2
        kmh < 20 -> 3
        kmh < 29 -> 4
        kmh < 39 -> 5
        kmh < 50 -> 6
        kmh < 62 -> 7
        else -> 8
    }

    @SuppressLint("MissingPermission")
    private fun resolveCoords(context: Context): Coords {
        val hasCoarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val hasFine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (hasCoarse || hasFine) {
            try {
                val lm = context.getSystemService(LocationManager::class.java)
                val best: Location? = lm?.getProviders(true)
                    ?.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                    ?.maxByOrNull { it.time }
                if (best != null) {
                    val city = reverseGeocode(context, best.latitude, best.longitude) ?: DEFAULT_CITY
                    return Coords(best.latitude, best.longitude, city)
                }
            } catch (_: Exception) {
            }
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedLat = prefs.getString("lat", null)?.toDoubleOrNull()
        val savedLon = prefs.getString("lon", null)?.toDoubleOrNull()
        val savedCity = prefs.getString("city", null)
        if (savedLat != null && savedLon != null && !savedCity.isNullOrBlank()) {
            return Coords(savedLat, savedLon, savedCity)
        }
        return Coords(DEFAULT_LAT, DEFAULT_LON, DEFAULT_CITY)
    }

    @Suppress("DEPRECATION")
    private fun reverseGeocode(context: Context, lat: Double, lon: Double): String? {
        return try {
            if (!Geocoder.isPresent()) return null
            val geocoder = Geocoder(context, Locale.CHINA)
            if (Build.VERSION.SDK_INT >= 33) {
                var resolved: String? = null
                val latch = java.util.concurrent.CountDownLatch(1)
                geocoder.getFromLocation(lat, lon, 1) { list ->
                    val a = list.firstOrNull()
                    resolved = a?.locality ?: a?.subAdminArea ?: a?.adminArea
                    latch.countDown()
                }
                latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
                resolved
            } else {
                val a = geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()
                a?.locality ?: a?.subAdminArea ?: a?.adminArea
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", "OriginBand")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP_$code")
            return BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use {
                it.readText()
            }
        } finally {
            conn.disconnect()
        }
    }
}
