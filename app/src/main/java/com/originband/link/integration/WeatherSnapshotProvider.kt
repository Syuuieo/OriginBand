// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.integration

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.originband.link.data.LocalPrefs
import com.originband.link.host.HostIdentity
import com.originband.link.service.LocalWeatherFetcher
import org.json.JSONObject
import java.util.UUID

/** Weather handoff for Health. Callers are checked by package name. The location stays in this provider, so a device-space card cannot read it. */
class WeatherSnapshotProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    @Synchronized
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = requireContext()
        val self = Binder.getCallingUid() == Process.myUid()
        if (!self) {
            HostIdentity.requireCaller(ctx, HEALTH)
        }
        try {
            val prefs = ctx.getSharedPreferences("weather-bridge", 0)
            return when (method) {
                "requestRefresh" -> {
                    requireSelf(self)
                    // No weather polling or sending while the official app owns the band.
                    if ("NATIVE" != LocalPrefs.open(ctx, "ownership").getString("mode", "OFFICIAL")) {
                        status("OFFICIAL_MODE")
                    } else {
                        ctx.grantUriPermission(HEALTH, URI, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        if (isPending(prefs)) {
                            LocalWeatherFetcher.fetchAsync(ctx)
                            val existing = status("WEATHER_REQUESTED")
                            existing.putString("requestId", prefs.getString("request", ""))
                            existing
                        } else {
                            val request = UUID.randomUUID().toString()
                            val expiry = System.currentTimeMillis() + WINDOW_MS
                            if (!prefs.edit().putString("request", request).putString("error", "")
                                    .putLong("expiryWallMs", expiry)
                                    .putLong("expiryElapsedMs", SystemClock.elapsedRealtime() + WINDOW_MS)
                                    .commit()
                            ) {
                                status("WEATHER_REQUEST_STORAGE_FAILED")
                            } else {
                                ctx.contentResolver.notifyChange(URI, null)
                                LocalWeatherFetcher.fetchAsync(ctx)
                                val response = status("WEATHER_REQUESTED")
                                response.putString("requestId", request)
                                response
                            }
                        }
                    }
                }

                "getPendingRequest" -> {
                    if (self) throw SecurityException("OHEALTH_CALLER_REQUIRED")
                    val pending = isPending(prefs)
                    if (!pending) ctx.revokeUriPermission(HEALTH, URI, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val response = status(if (pending) "WEATHER_REQUESTED" else "NO_PENDING_REQUEST")
                    if (pending) response.putString("requestId", prefs.getString("request", ""))
                    response
                }

                "publishWeather" -> {
                    if (self || extras == null) throw SecurityException("OHEALTH_CALLER_REQUIRED")
                    val request = extras.getString("requestId")
                    if (!isPending(prefs) || (prefs.getString("request", "") ?: "") != request) {
                        throw SecurityException("WEATHER_REQUEST_EXPIRED")
                    }
                    val json = extras.getString("snapshot")
                    if (json == null || json.length > 131_072) {
                        throw IllegalArgumentException("WEATHER_SNAPSHOT_SIZE_INVALID")
                    }
                    val snapshot = JSONObject(json)
                    validate(snapshot)
                    if (!prefs.edit().putString("snapshot", snapshot.toString()).putString("request", "")
                            .putString("error", "").putLong("updatedAtMs", System.currentTimeMillis())
                            .commit()
                    ) {
                        status("WEATHER_STORAGE_FAILED")
                    } else {
                        ctx.revokeUriPermission(HEALTH, URI, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        ctx.contentResolver.notifyChange(UPDATED, null)
                        status("WEATHER_STORED")
                    }
                }

                "reportFailure" -> {
                    if (self || extras == null || !isPending(prefs) ||
                        (prefs.getString("request", "") ?: "") != extras.getString("requestId")
                    ) {
                        throw SecurityException("WEATHER_REQUEST_EXPIRED")
                    }
                    val error = sourceFailure(extras.getString("status", ""))
                    if (!prefs.edit().putString("request", "").putString("error", error).commit()) {
                        status("WEATHER_STORAGE_FAILED")
                    } else {
                        ctx.revokeUriPermission(HEALTH, URI, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        ctx.contentResolver.notifyChange(UPDATED, null)
                        status(error)
                    }
                }

                "getSnapshot" -> {
                    requireSelf(self)
                    val pending = isPending(prefs)
                    var error = prefs.getString("error", "") ?: ""
                    if (!pending && !prefs.getString("request", "").isNullOrEmpty()) {
                        error = "OHEALTH_WEATHER_TIMEOUT"
                    }
                    val response = status(error)
                    response.putString("snapshot", prefs.getString("snapshot", ""))
                    response.putLong("updatedAtMs", prefs.getLong("updatedAtMs", 0))
                    response.putBoolean("pending", pending)
                    response.putLong(
                        "remainingMs",
                        if (pending) {
                            maxOf(
                                0L,
                                minOf(
                                    prefs.getLong("expiryWallMs", 0) - System.currentTimeMillis(),
                                    prefs.getLong("expiryElapsedMs", 0) - SystemClock.elapsedRealtime(),
                                ),
                            )
                        } else {
                            0L
                        },
                    )
                    response
                }

                else -> throw IllegalArgumentException("UNSUPPORTED_WEATHER_OPERATION")
            }
        } catch (rejected: SecurityException) {
            throw rejected
        } catch (rejected: IllegalArgumentException) {
            if ("publishWeather" == method) {
                Log.i("OplusBandBridge", "OHEALTH_WEATHER_REJECTED ${rejected.message}")
            }
            throw rejected
        } catch (_: Exception) {
            return status("WEATHER_PROVIDER_UNAVAILABLE")
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? {
        throw SecurityException("WEATHER_QUERY_FORBIDDEN")
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw SecurityException("WEATHER_WRITE_FORBIDDEN")
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int {
        throw SecurityException("WEATHER_WRITE_FORBIDDEN")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        throw SecurityException("WEATHER_DELETE_FORBIDDEN")
    }

    companion object {
        @JvmField
        val URI: Uri = Uri.parse("content://com.originband.link.weather/request")

        @JvmField
        val UPDATED: Uri = Uri.parse("content://com.originband.link.weather/snapshot")

        private const val HEALTH = HostIdentity.HEALTH_PACKAGE
        private const val WINDOW_MS = 90_000L
        private val LOCATION_KEY_REGEX = Regex("[A-Za-z0-9:_-]{1,64}")

        @Throws(Exception::class)
        private fun validate(value: JSONObject) {
            val key = value.getString("locationKey")
            val city = value.getString("cityName")
            val place = value.getString("locationName")
            val zone = value.getString("timezone")
            val unit = value.getString("unit")
            val published = value.getLong("publishedAtMs")
            val code = value.getInt("conditionCode")
            val temp = value.getDouble("temperature")
            if (!LOCATION_KEY_REGEX.matches(key)) {
                throw IllegalArgumentException("WEATHER_LOCATION_KEY_INVALID")
            }
            if (city.isBlank() || city.length > 80 || place.isBlank() || place.length > 80) {
                throw IllegalArgumentException("WEATHER_CITY_INVALID")
            }
            if (zone.isBlank() || zone.length > 64) {
                throw IllegalArgumentException("WEATHER_TIMEZONE_INVALID")
            }
            if ("c" != unit && "f" != unit) {
                throw IllegalArgumentException("WEATHER_UNIT_INVALID")
            }
            if (published < 1_000_000_000_000L || published > System.currentTimeMillis() + 3_600_000L) {
                Log.i(
                    "OplusBandBridge",
                    "OHEALTH_PUBLICATION_SHAPE epochSeconds=" +
                        (published in 1_000_000_000L until 10_000_000_000L) +
                        " future=" + (published > System.currentTimeMillis() + 3_600_000L),
                )
                throw IllegalArgumentException("WEATHER_PUBLICATION_TIME_INVALID")
            }
            if (code < 0 || code > 71) {
                throw IllegalArgumentException("WEATHER_CONDITION_INVALID")
            }
            if (!temp.isFinite() || temp < -130 || temp > 140) {
                throw IllegalArgumentException("WEATHER_TEMPERATURE_INVALID")
            }
            val daily = value.getJSONArray("daily")
            val hourly = value.getJSONArray("hourly")
            if (daily.length() > 7 || hourly.length() > 23) {
                throw IllegalArgumentException("WEATHER_FORECAST_INCOMPLETE")
            }
            for (i in 0 until daily.length()) {
                val day = daily.getJSONObject(i)
                if (!day.has("timeMs") || !day.has("dayCode") || !day.has("nightCode") ||
                    !day.has("maxTemp") || !day.has("minTemp")
                ) {
                    throw IllegalArgumentException("WEATHER_DAY_INCOMPLETE")
                }
            }
            for (i in 0 until hourly.length()) {
                val hour = hourly.getJSONObject(i)
                if (!hour.has("timeMs") || !hour.has("conditionCode") || !hour.has("temperature")) {
                    throw IllegalArgumentException("WEATHER_HOUR_INCOMPLETE")
                }
            }
        }

        private fun sourceFailure(code: String): String = when (code) {
            "OHEALTH_LOCATION_PERMISSION_REQUIRED",
            "OHEALTH_LOCATION_DISABLED",
            "OHEALTH_LOCATION_UNAVAILABLE",
            "OHEALTH_WEATHER_TIMEOUT",
            "OHEALTH_WEATHER_CLOUD_UNAVAILABLE",
            "OHEALTH_WEATHER_STORAGE_FAILED",
            "OHEALTH_WEATHER_EXTRACT_FAILED",
            "OHEALTH_WEATHER_REQUEST_FAILED",
            -> code

            else -> "OHEALTH_WEATHER_UNAVAILABLE"
        }

        private fun isPending(prefs: SharedPreferences): Boolean =
            !prefs.getString("request", "").isNullOrEmpty() &&
                System.currentTimeMillis() < prefs.getLong("expiryWallMs", 0) &&
                SystemClock.elapsedRealtime() < prefs.getLong("expiryElapsedMs", 0)

        private fun requireSelf(self: Boolean) {
            if (!self) throw SecurityException("OWNER_ONLY")
        }

        private fun status(code: String): Bundle {
            val result = Bundle()
            result.putString("status", code)
            return result
        }

        @JvmStatic
        fun storeLocalSnapshot(context: Context, snapshot: JSONObject): Boolean {
            return try {
                validate(snapshot)
                val app = context.applicationContext ?: context
                val prefs = app.getSharedPreferences("weather-bridge", 0)
                val ok = prefs.edit()
                    .putString("snapshot", snapshot.toString())
                    .putString("request", "")
                    .putString("error", "")
                    .putLong("updatedAtMs", System.currentTimeMillis())
                    .commit()
                if (ok) app.contentResolver.notifyChange(UPDATED, null)
                ok
            } catch (_: Exception) {
                false
            }
        }

        @JvmStatic
        fun reportLocalFailure(context: Context, code: String) {
            val app = context.applicationContext ?: context
            val prefs = app.getSharedPreferences("weather-bridge", 0)
            // Only overwrite error if no valid snapshot is stored yet and a request is still pending.
            if (!isPending(prefs)) return
            prefs.edit()
                .putString("request", "")
                .putString("error", sourceFailure(code))
                .commit()
            app.contentResolver.notifyChange(UPDATED, null)
        }
    }
}
