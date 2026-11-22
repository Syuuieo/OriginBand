// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.service

import android.content.Context
import android.database.ContentObserver
import android.os.Bundle
import com.originband.link.data.SessionLog
import com.originband.link.integration.WeatherSnapshotProvider
import com.originband.link.protocol.BandWeatherEncoder
import com.originband.link.protocol.LiveCommandQueue
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/** Weather transactions run on the service's single-thread coordinator. The live session owns the socket. */
class WeatherSync(
    context: Context,
    private val queue: Supplier<LiveCommandQueue?>,
    private val coordinator: ScheduledExecutorService
) : AutoCloseable {
    class Failure(@JvmField val code: String) : IllegalStateException(code)

    private enum class Operation { REFRESH, IF_CHANGED, INSPECT, SEND }

    private val context: Context = context.applicationContext
    private val observer: ContentObserver = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) {
            dispatch { if (waitingSnapshot) readRefreshResult() }
        }
    }

    @Volatile
    private var closed = false

    @Volatile
    private var awaitingCities = false

    /** History sync owns the radio until [releaseForecast] for this epoch. */
    @Volatile
    private var deferForecast = false
    private var forecastEpoch = 0
    private var observing = false
    private var waitingSnapshot = false
    private var active: CompletableFuture<Void?>? = null
    private var operation: Operation? = null
    private var expiry: ScheduledFuture<*>? = null
    private var transactionQueue: LiveCommandQueue? = null
    private var lastSent: BandWeatherEncoder.Sample? = null

    init {
        dispatch {
            if (!closed) {
                try {
                    this.context.contentResolver.registerContentObserver(
                        WeatherSnapshotProvider.UPDATED,
                        false,
                        observer
                    )
                    observing = true
                } catch (_: RuntimeException) {
                    record("WEATHER_SOURCE_UNAVAILABLE")
                }
            }
        }
    }

    fun refreshAndSend(): CompletionStage<Void?> {
        if (deferForecast) return CompletableFuture.completedFuture(null)
        return submit(Operation.REFRESH, null)
    }

    /**
     * The connect burst also lists and pulls activity files. This firmware drops the GATT
     * link when a weather forecast (10/0) is written while that transfer is still open,
     * and the service then reconnects three seconds later. Hold forecasts off the radio
     * until the history round finishes. Returns the epoch [releaseForecast] must pass.
     */
    @Synchronized
    fun deferForecast(): Int {
        deferForecast = true
        return ++forecastEpoch
    }

    /** One forecast after the matching history round. A stale epoch is a session that already ended. */
    @Synchronized
    fun releaseForecast(epoch: Int) {
        if (!deferForecast || epoch != forecastEpoch) return
        deferForecast = false
        refreshAndSend()
    }

    /** Idle poll. The watch's own weather request still uses [refreshAndSend]. */
    fun sendIfChanged(): CompletionStage<Void?> = submit(Operation.IF_CHANGED, null)

    fun inspectCities(sample: BandWeatherEncoder.Sample?): CompletionStage<Void?> =
        submit(Operation.INSPECT, sample)

    fun send(sample: BandWeatherEncoder.Sample?): CompletionStage<Void?> =
        submit(Operation.SEND, sample)

    private fun submit(requested: Operation, sample: BandWeatherEncoder.Sample?): CompletionStage<Void?> {
        val result = CompletableFuture<Void?>()
        try {
            coordinator.execute {
                if (closed) {
                    result.completeExceptionally(Failure("WEATHER_CLOSED"))
                    return@execute
                }
                if (deferForecast && requested != Operation.SEND && requested != Operation.INSPECT) {
                    result.complete(null)
                    return@execute
                }
                val currentActive = active
                if (currentActive != null) {
                    if (operation == requested) relay(currentActive, result)
                    else result.completeExceptionally(Failure("WEATHER_BUSY"))
                    return@execute
                }
                active = result
                operation = requested
                transactionQueue = queue.get()
                if (transactionQueue == null) {
                    finish("WEATHER_DISCONNECTED")
                    return@execute
                }
                try {
                    if (requested == Operation.REFRESH || requested == Operation.IF_CHANGED) {
                        val response: Bundle? = try {
                            context.contentResolver.call(
                                WeatherSnapshotProvider.URI,
                                "requestRefresh",
                                null,
                                null
                            )
                        } catch (_: RuntimeException) {
                            null
                        }
                        if (response == null || "WEATHER_REQUESTED" != response.getString("status")) {
                            // A source failure leaves a previously fresh snapshot in place.
                            deliver(freshSnapshot(null))
                            return@execute
                        }
                        waitingSnapshot = true
                        readRefreshResult()
                    } else {
                        queryCities(freshSnapshot(sample))
                    }
                } catch (failure: Exception) {
                    finish(code(failure))
                }
            }
        } catch (_: RejectedExecutionException) {
            result.completeExceptionally(Failure("WEATHER_CLOSED"))
        }
        return result
    }

    private fun snapshot(): Bundle {
        return context.contentResolver.call(WeatherSnapshotProvider.URI, "getSnapshot", null, null)
            ?: throw Failure("WEATHER_SOURCE_UNAVAILABLE")
    }

    @Throws(Exception::class)
    private fun freshSnapshot(displayed: BandWeatherEncoder.Sample?): BandWeatherEncoder.Sample {
        val response = snapshot()
        val sample = freshSample(response)
        if (displayed != null && (sample.locationKey() != displayed.locationKey()
                    || sample.cityName() != displayed.cityName()
                    || sample.locationName() != displayed.locationName())
        ) {
            throw Failure("WEATHER_PREVIEW_CHANGED")
        }
        return sample
    }

    private fun readRefreshResult() {
        if (!waitingSnapshot || active == null) return
        try {
            val response = snapshot()
            if (response.getBoolean("pending", false)) {
                if (expiry == null) {
                    expiry = coordinator.schedule(
                        {
                            expiry = null
                            readRefreshResult()
                        },
                        maxOf(1L, response.getLong("remainingMs", 90_000L)),
                        TimeUnit.MILLISECONDS
                    )
                }
                return
            }
            waitingSnapshot = false
            cancelExpiry()
            deliver(freshSample(response))
        } catch (failure: Exception) {
            finish(code(failure))
        }
    }

    private fun deliver(sample: BandWeatherEncoder.Sample) {
        if (operation == Operation.IF_CHANGED && !transmitForecast(false, lastSent, sample)) {
            skipUnchanged()
            return
        }
        queryCities(sample)
    }

    private fun queryCities(sample: BandWeatherEncoder.Sample) {
        val transaction = active
        val currentQueue = transactionQueue ?: run {
            finish("WEATHER_DISCONNECTED")
            return
        }
        awaitingCities = true
        record("WEATHER_READING_CITIES")
        currentQueue.request(XiaomiProto.Command.newBuilder().setType(10).setSubtype(5).build(), 10, 5)
            .whenComplete { response, failure ->
                dispatch {
                    if (active !== transaction) return@dispatch
                    awaitingCities = false
                    if (failure != null || response == null) {
                        finish("WEATHER_TRANSPORT_FAILED")
                        return@dispatch
                    }
                    if (response.hasStatus() && response.status != 0 && !unsupportedCityList(response)) {
                        finish("WEATHER_BAND_REJECTED")
                        return@dispatch
                    }
                    if (!unsupportedCityList(response)
                        && (!response.hasWeather() || !response.weather.hasLocations())
                    ) {
                        finish("WEATHER_CITY_LIST_INVALID")
                        return@dispatch
                    }
                    try {
                        val fresh = freshSnapshot(sample)
                        if (unsupportedCityList(response)) {
                            android.util.Log.i("OriginBand", "WEATHER_CITIES unsupported status=${response.status}")
                            SessionLog.line(context, "WEATHER_CITIES unsupported")
                            if (operation == Operation.INSPECT) {
                                finish(null)
                                return@dispatch
                            }
                            sendCurrentCity(transaction, fresh)
                            return@dispatch
                        }
                        val cities = response.weather.locations
                        android.util.Log.i(
                            "OriginBand",
                            "WEATHER_CITIES raw=${cities.locationCount} accepted=${BandWeatherEncoder.acceptedCityCount(cities)} list=${cities.locationList.joinToString { "${it.code}:${it.name}" }}",
                        )
                        SessionLog.line(
                            context,
                            "WEATHER_CITIES raw=" + cities.locationCount
                                    + " accepted=" + BandWeatherEncoder.acceptedCityCount(cities)
                        )
                        saveReview(fresh, cities)
                        if (operation == Operation.INSPECT) {
                            finish(null)
                            return@dispatch
                        }
                        publish(transaction, fresh, cities)
                    } catch (invalid: Exception) {
                        finish(code(invalid))
                    }
                }
            }
    }

    private fun saveReview(sample: BandWeatherEncoder.Sample, cities: XiaomiProto.WeatherLocations) {
        val prefs = context.getSharedPreferences("weather-city-review", 0)
        val edit = prefs.edit()
            .putString("sourceKey", sample.locationKey())
            .putString("sourceCity", sample.cityName())
            .putString("sourcePlace", sample.locationName())
        var count = 0
        for (city in cities.locationList) {
            if (!BandWeatherEncoder.acceptableCityCode(city.code) || !city.hasName()
                || city.name.isBlank() || city.name.length > 80
            ) continue
            edit.putString("bandCode$count", city.code).putString("bandName$count", city.name)
            count++
        }
        for (i in count until prefs.getInt("bandCount", 0)) {
            edit.remove("bandCode$i").remove("bandName$i")
        }
        if (!edit.putInt("bandCount", count).commit()) throw Failure("WEATHER_CITY_STORAGE_FAILED")
    }

    /** 10/5 status 1 or 2 has no list to edit. 10/7 writes this one city; 1 and 3 still allow the forecast. */
    private fun sendCurrentCity(transaction: CompletableFuture<Void?>?, fresh: BandWeatherEncoder.Sample) {
        writeCity(transaction, fresh, BandWeatherEncoder.addCurrentLocation(fresh))
    }

    private fun publish(
        transaction: CompletableFuture<Void?>?,
        fresh: BandWeatherEncoder.Sample,
        cities: XiaomiProto.WeatherLocations
    ) {
        val review = context.getSharedPreferences("weather-city-review", 0)
        val plan: BandWeatherEncoder.CityUpdate = try {
            BandWeatherEncoder.planTakeover(fresh, cities, review.getString("currentLocationKey", ""))
        } catch (invalid: IllegalArgumentException) {
            finish(code(invalid))
            return
        }
        val removeCmd = plan.remove()
        if (removeCmd == null) {
            writeCity(transaction, fresh, plan.write())
            return
        }
        val currentQueue = transactionQueue ?: run {
            finish("WEATHER_DISCONNECTED")
            return
        }
        SessionLog.line(context, "WEATHER_REMOVE_CITY")
        currentQueue.request(removeCmd, 10, 8).whenComplete { removed, failure ->
            dispatch {
                if (active !== transaction) return@dispatch
                if (failure != null) {
                    finish("WEATHER_TRANSPORT_FAILED")
                    return@dispatch
                }
                val status = if (removed != null && removed.hasStatus()) removed.status else 0
                // 1: this band has no remove command. The add below still installs the phone city.
                if (status != 0 && status != 1) {
                    finish("WEATHER_BAND_REJECTED")
                    return@dispatch
                }
                if (status != 0) SessionLog.line(context, "WEATHER_REMOVE_CITY status=$status")
                writeCity(transaction, fresh, plan.write())
            }
        }
    }

    private fun writeCity(
        transaction: CompletableFuture<Void?>?,
        fresh: BandWeatherEncoder.Sample,
        write: XiaomiProto.Command?
    ) {
        if (write == null) {
            sendFrame(transaction, fresh, BandWeatherEncoder.encode(fresh), 0)
            return
        }
        val currentQueue = transactionQueue ?: run {
            finish("WEATHER_DISCONNECTED")
            return
        }
        SessionLog.line(context, "WEATHER_SET_CURRENT_CITY")
        val subtype = write.subtype
        currentQueue.request(write, 10, subtype).whenComplete { set, setFailure ->
            dispatch {
                if (active !== transaction) return@dispatch
                if (setFailure != null) {
                    finish("WEATHER_TRANSPORT_FAILED")
                    return@dispatch
                }
                val status = if (set != null && set.hasStatus()) set.status else 0
                val added = subtype == 7 && (status == 1 || status == 3)
                android.util.Log.i("OriginBand", "WEATHER_WRITE_CITY subtype=$subtype status=$status added=$added")
                if (status != 0 && !added) {
                    finish("WEATHER_BAND_REJECTED")
                    return@dispatch
                }
                if (status != 0) SessionLog.line(context, "WEATHER_CURRENT_CITY status=$status")
                sendFrame(transaction, fresh, BandWeatherEncoder.encode(fresh), 0)
            }
        }
    }

    private fun sendFrame(
        transaction: CompletableFuture<Void?>?,
        sample: BandWeatherEncoder.Sample,
        frames: List<XiaomiProto.Command>,
        index: Int
    ) {
        if (active !== transaction) return
        if (index == frames.size) {
            lastSent = sample
            rememberCurrent(sample)
            finish(null)
            return
        }
        try {
            freshSnapshot(sample)
            val currentQueue = transactionQueue
            if (currentQueue == null || currentQueue !== queue.get()) throw Failure("WEATHER_DISCONNECTED")
            val frame = frames[index]
            android.util.Log.i(
                "OriginBand",
                "WEATHER_TX_FRAME ${index + 1}/${frames.size} subtype=${frame.subtype}",
            )
            currentQueue.send(frame).whenComplete { _, failure ->
                dispatch {
                    if (active !== transaction) return@dispatch
                    if (failure != null) finish("WEATHER_TRANSPORT_FAILED")
                    else sendFrame(transaction, sample, frames, index + 1)
                }
            }
        } catch (failure: Exception) {
            finish(code(failure))
        }
    }

    /** Called before queue.onCommand by the sole socket reader; only schedules work. */
    fun onCommand(command: XiaomiProto.Command) {
        if (closed || command.type != 10) return
        android.util.Log.i(
            "OriginBand",
            "WEATHER_RX subtype=${command.subtype} status=${if (command.hasStatus()) command.status else -1}",
        )
        val ownCityResponse = command.subtype == 5 && awaitingCities
        dispatch {
            if (closed) return@dispatch
            if (command.hasStatus() && command.status != 0) {
                // 10/5 status 1 or 2: no city list. The request handler adds the phone city.
                // 10/7: 1 means this device has no single-city add; 3 means the city is already there.
                // 10/8 status 1: remove is unsupported. The following add still runs.
                if (unsupportedCityList(command) && awaitingCities) return@dispatch
                if (command.subtype == 7 && (command.status == 1 || command.status == 3)) return@dispatch
                if (command.subtype == 8 && command.status == 1) return@dispatch
                if (active != null) finish("WEATHER_BAND_REJECTED")
                else record("WEATHER_BAND_REJECTED")
                return@dispatch
            }
            if (command.subtype == 5 && !ownCityResponse) {
                refreshAndSend()
            } else if (command.subtype == 3 && command.hasWeather() && command.weather.hasLocation()) {
                val review = context.getSharedPreferences("weather-city-review", 0)
                val current = review.getString("currentLocationKey", "") ?: ""
                val confirmed = review.getString("confirmedBandCode", "") ?: ""
                val code = command.weather.location.code
                if (code.isEmpty() || current.isEmpty() || code == current
                    || (confirmed.isNotEmpty() && code == confirmed)
                ) {
                    refreshAndSend()
                }
            }
        }
    }

    fun onDisconnected() {
        synchronized(this) {
            forecastEpoch++
            deferForecast = false
        }
        dispatch { finish("WEATHER_DISCONNECTED") }
    }

    override fun close() {
        closed = true
        dispatch {
            finish("WEATHER_CLOSED")
            if (observing) {
                context.contentResolver.unregisterContentObserver(observer)
                observing = false
            }
        }
    }

    private fun rememberCurrent(sample: BandWeatherEncoder.Sample) {
        context.getSharedPreferences("weather-city-review", 0).edit()
            .putString("currentLocationKey", sample.locationKey())
            .putString("currentLocationName", sample.locationName())
            .apply()
    }

    private fun cancelExpiry() {
        expiry?.let {
            it.cancel(false)
            expiry = null
        }
    }

    private fun skipUnchanged() {
        val result = active ?: return
        active = null
        awaitingCities = false
        waitingSnapshot = false
        transactionQueue = null
        cancelExpiry()
        record("WEATHER_UNCHANGED")
        result.complete(null)
    }

    private fun finish(error: String?) {
        val result = active ?: return
        val completed = operation
        active = null
        awaitingCities = false
        waitingSnapshot = false
        transactionQueue = null
        cancelExpiry()
        record(
            error ?: if (completed == Operation.INSPECT) "WEATHER_CITIES_READY"
            else "WEATHER_TRANSPORT_CONFIRMED"
        )
        if (error == null) result.complete(null) else result.completeExceptionally(Failure(error))
    }

    private fun record(status: String) {
        try {
            val edit = context.getSharedPreferences("weather-sync", 0).edit().putString("status", status)
            if ("WEATHER_TRANSPORT_CONFIRMED" == status) {
                edit.putLong("confirmedAtMs", System.currentTimeMillis())
            }
            edit.apply()
            context.contentResolver.notifyChange(WeatherSnapshotProvider.UPDATED, null)
        } catch (_: RuntimeException) {
            // Status persistence cannot strand a completed transport future.
        }
    }

    private fun dispatch(work: Runnable) {
        try {
            coordinator.execute(work)
        } catch (_: RejectedExecutionException) {
            /* Service has stopped. */
        }
    }

    companion object {
        private fun relay(source: CompletionStage<Void?>, target: CompletableFuture<Void?>) {
            source.whenComplete { _, error ->
                if (error == null) target.complete(null) else target.completeExceptionally(error)
            }
        }

        /** Pending or failed refreshes preserve usable older data and its original timestamps. */
        @JvmStatic
        @Throws(Exception::class)
        fun freshSample(response: Bundle?): BandWeatherEncoder.Sample {
            if (response == null) throw Failure("WEATHER_SOURCE_UNAVAILABLE")
            val now = System.currentTimeMillis()
            val updated = response.getLong("updatedAtMs", 0)
            if (updated <= 0 || updated > now || now - updated > 3_600_000L) {
                throw Failure("WEATHER_SOURCE_STALE")
            }
            val sample = BandWeatherEncoder.parse(JSONObject(response.getString("snapshot", "")))
            if (sample.publishedAtMs() > now + 3_600_000L
                || now - sample.publishedAtMs() > 6 * 3_600_000L
            ) {
                throw Failure("WEATHER_FORECAST_STALE")
            }
            BandWeatherEncoder.validateForecast(sample)
            return sample
        }

        /**
         * Status 1: this band has no city list. Status 2: weather was never primed, so the list
         * is empty. Either way the phone city is added and the forecast still goes out.
         */
        @JvmStatic
        fun unsupportedCityList(command: XiaomiProto.Command?): Boolean {
            return command != null && command.type == 10 && command.subtype == 5
                    && command.hasStatus() && (command.status == 1 || command.status == 2)
        }

        /** A watch request always transmits. An idle poll keeps the same forecast off the radio. */
        @JvmStatic
        fun transmitForecast(
            forced: Boolean,
            lastSent: BandWeatherEncoder.Sample?,
            current: BandWeatherEncoder.Sample?
        ): Boolean {
            return forced || lastSent == null || current == null || lastSent != current
        }

        private fun code(error: Exception): String {
            if (error is Failure) return error.code
            val message = error.message
            return if (message != null && message.startsWith("WEATHER_")) message else "WEATHER_DATA_INVALID"
        }
    }
}
