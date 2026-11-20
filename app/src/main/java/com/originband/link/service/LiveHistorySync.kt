// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.service

import com.google.protobuf.ByteString
import com.originband.link.protocol.LiveCommandQueue
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import java.util.ArrayDeque
import java.util.HexFormat
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier
import java.util.function.Consumer

/** One list/download round at a time; only durably saved and acknowledged files advance a batch. */
class LiveHistorySync(
    private val commands: LiveCommandQueue,
    private val worker: ScheduledExecutorService,
    private val started: Runnable,
    private val completed: Runnable,
    private val failed: Consumer<String>,
    private val capacity: BooleanSupplier,
    private val storageRetryDelayMs: Long
) {
    private val remaining = ArrayDeque<ByteArray>()
    private val pending = HashSet<String>()

    /** File ids already stored this session. The band keeps listing them; the next pass skips the download. */
    private val stored = HashSet<String>()
    private var busy = false
    private var includeHistory = false
    private var historical = false
    private var listing = false
    private var generation: Long = 0
    private var batchDeadline: ScheduledFuture<*>? = null
    private var storageRetry: ScheduledFuture<*>? = null
    private var storageRetries = 0

    constructor(
        commands: LiveCommandQueue,
        worker: ScheduledExecutorService,
        completed: Runnable,
        failed: Consumer<String>,
        capacity: BooleanSupplier
    ) : this(commands, worker, Runnable {}, completed, failed, capacity, 5_000L)

    constructor(
        commands: LiveCommandQueue,
        worker: ScheduledExecutorService,
        started: Runnable,
        completed: Runnable,
        failed: Consumer<String>,
        capacity: BooleanSupplier
    ) : this(commands, worker, started, completed, failed, capacity, 5_000L)

    constructor(
        commands: LiveCommandQueue,
        worker: ScheduledExecutorService,
        completed: Runnable,
        failed: Consumer<String>,
        capacity: BooleanSupplier,
        storageRetryDelayMs: Long
    ) : this(commands, worker, Runnable {}, completed, failed, capacity, storageRetryDelayMs)

    // Phone-initiated, matching the official app: 8/1 lists today, then 8/2 lists older days.
    // The band's own step total arrives separately as an unsolicited 8/47 after 8/45.
    fun request(history: Boolean) {
        val retryHistory = storageRetry != null && includeHistory
        cancelStorageRetry()
        storageRetries = 0
        startRound(history || retryHistory)
    }

    private fun startRound(history: Boolean) {
        includeHistory = includeHistory or history
        if (busy) return
        busy = true
        started.run()
        historical = false
        generation++
        list(1, generation)
    }

    private fun list(subtype: Int, round: Long) {
        listing = true
        val command = XiaomiProto.Command.newBuilder().setType(8).setSubtype(subtype)
        if (subtype == 1) {
            command.setHealth(
                XiaomiProto.Health.newBuilder().setActivitySyncRequestToday(
                    XiaomiProto.ActivitySyncRequestToday.newBuilder().setUnknown1(0)
                )
            )
        }
        commands.request(command.build(), 8, subtype).whenCompleteAsync({ response, error ->
            if (!busy || round != generation) return@whenCompleteAsync
            listing = false
            if (error != null || response == null) {
                fail("HEALTH_LIST_FAILED")
                return@whenCompleteAsync
            }
            val ids = if (response.hasHealth() && response.health.hasActivityRequestFileIds()) {
                response.health.activityRequestFileIds.toByteArray()
            } else {
                ByteArray(0)
            }
            if (ids.size % 7 != 0) {
                fail("HEALTH_FILE_LIST_INVALID")
                return@whenCompleteAsync
            }
            val unique = HashSet<String>()
            var offset = 0
            while (offset < ids.size) {
                val id = ids.copyOfRange(offset, offset + 7)
                val hex = HexFormat.of().formatHex(id)
                if (!stored.contains(hex) && unique.add(hex)) {
                    remaining.addLast(id)
                }
                offset += 7
            }
            nextBatch(round)
        }, worker)
    }

    private fun nextBatch(round: Long) {
        if (!busy || listing || pending.isNotEmpty()) return
        if (remaining.isEmpty()) {
            if (!historical && includeHistory) {
                historical = true
                includeHistory = false
                list(2, round)
            } else {
                busy = false
                includeHistory = false
                completed.run()
            }
            return
        }
        try {
            if (!capacity.asBoolean) return
        } catch (_: RuntimeException) {
            fail("HEALTH_STORAGE_UNAVAILABLE")
            return
        }
        val count = minOf(4, remaining.size)
        val ids = ByteArray(count * 7)
        for (i in 0 until count) {
            val id = remaining.removeFirst()
            System.arraycopy(id, 0, ids, i * 7, 7)
            pending.add(HexFormat.of().formatHex(id))
        }
        progress()
        commands.send(
            XiaomiProto.Command.newBuilder().setType(8).setSubtype(3)
                .setHealth(XiaomiProto.Health.newBuilder().setActivityRequestFileIds(ByteString.copyFrom(ids)))
                .build()
        ).whenCompleteAsync({ _, error ->
            if (busy && round == generation && error != null) fail("HEALTH_DOWNLOAD_REQUEST_FAILED")
        }, worker)
    }

    fun saved(fileId: ByteArray) {
        val hex = HexFormat.of().formatHex(fileId)
        if (busy && pending.remove(hex)) {
            stored.add(hex)
            if (pending.isEmpty()) {
                cancelDeadline()
                nextBatch(generation)
            } else {
                progress()
            }
        }
    }

    fun resume() {
        nextBatch(generation)
    }

    fun rejected(reason: String) {
        if (busy) fail(reason)
    }

    fun onCommand(command: XiaomiProto.Command) {
        if (busy && command.type == 8 && command.hasStatus() && command.status != 0
            && (command.subtype == 3 || command.subtype == 4)
        ) {
            fail("HEALTH_DOWNLOAD_REJECTED")
        }
    }

    fun progress() {
        if (!busy || pending.isEmpty()) return
        cancelDeadline()
        val round = generation
        batchDeadline = worker.schedule({
            if (busy && round == generation && pending.isNotEmpty()) fail("HEALTH_DOWNLOAD_TIMEOUT")
        }, commands.timeoutMillis(), TimeUnit.MILLISECONDS)
    }

    private fun cancelDeadline() {
        batchDeadline?.let {
            it.cancel(false)
            batchDeadline = null
        }
    }

    private fun cancelStorageRetry() {
        storageRetry?.let {
            it.cancel(false)
            storageRetry = null
        }
    }

    fun close() {
        cancelStorageRetry()
        cancelDeadline()
        generation++
        busy = false
        listing = false
        includeHistory = false
        remaining.clear()
        pending.clear()
    }

    private fun fail(reason: String) {
        val retryHistory = historical || includeHistory
        cancelDeadline()
        cancelStorageRetry()
        val round = ++generation
        busy = false
        listing = false
        includeHistory = false
        remaining.clear()
        pending.clear()
        // A durable archive failure leaves its file unconfirmed on the band. Re-listing retries
        // that file and preserves older dates as well as today's data after a transient DB lock.
        if (("HISTORY_STORAGE_FAILED" == reason || "HEALTH_STORAGE_UNAVAILABLE" == reason)
            && storageRetries < MAX_STORAGE_RETRIES
        ) {
            includeHistory = retryHistory
            val delay = storageRetryDelayMs shl storageRetries++
            storageRetry = worker.schedule({
                storageRetry = null
                if (round == generation) startRound(retryHistory)
            }, delay, TimeUnit.MILLISECONDS)
        }
        failed.accept(reason)
    }

    companion object {
        private const val MAX_STORAGE_RETRIES = 3
    }
}
