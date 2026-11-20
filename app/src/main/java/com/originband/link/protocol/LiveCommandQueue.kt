// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The live session's bounded command owner. No socket reads belong here.
 *
 * <p>Capacity includes queued and in-flight work: 64 general operations, eight independent
 * file confirmations, and two in-flight calls plus one replaceable latest call state. Call
 * state has priority over file confirmations, then general work. Only unsent ordinary posts
 * may be evicted; full non-disposable queues fail admission. Deadlines start at admission.
 * Exact duplicate health/weather/battery operations share their original deadline/result;
 * payload equality keeps different file IDs and weather snapshots separate. Nothing is replayed.
 *
 * <p>The initial sequence is the NEXT sequence to send after the handshake. A sequence stays
 * reserved until the entire operation finishes, then is quarantined for at least ten seconds
 * (or the configured timeout, if longer). The eight-bit wire ACK has no generation: an ACK
 * delayed beyond that quarantine cannot be distinguished after reuse. Likewise, semantic
 * responses have no transaction ID; requests for the same response type/subtype are serialized.
 * Unsolicited or arbitrarily late responses with that same pair are not distinguishable.
 *
 * <p>Only the owned worker calls Sender. ACK and command ingress hand consumer callbacks to the common pool:
 * completions use the common pool, so a callback may submit and await a file confirmation
 * without blocking the socket reader or writer. A separate deadline worker keeps expiration
 * observable even during a blocked write. Closing the transport itself remains the caller's job;
 * interrupting the writer cannot guarantee that an underlying socket write returns.
 */
class LiveCommandQueue(
    initialSequence: Int,
    timeoutMillis: Long,
    private val sender: Sender,
) : AutoCloseable {
    companion object {
        private const val CAPACITY = 64
        private const val FILE_CAPACITY = 8
        private const val CALL_IN_FLIGHT_CAPACITY = 2
        private const val DEFAULT_TIMEOUT_MILLIS = 10_000L

        private fun scheduler(name: String): ScheduledThreadPoolExecutor {
            val executor = ScheduledThreadPoolExecutor(1) { task ->
                Thread(task, name).apply { isDaemon = true }
            }
            executor.removeOnCancelPolicy = true
            executor.executeExistingDelayedTasksAfterShutdownPolicy = false
            return executor
        }

        private fun priority(kind: Kind): Int = when (kind) {
            Kind.CALL -> 0
            Kind.FILE_CONFIRM -> 1
            else -> 2
        }

        private fun coalescible(command: XiaomiProto.Command): Boolean =
            (command.type == 8 && command.subtype != 5) ||
                command.type == 10 ||
                (command.type == 2 && command.subtype == 1)

        private fun classify(command: XiaomiProto.Command): Kind {
            if (command.type == 8 && command.subtype == 5) return Kind.FILE_CONFIRM
            if (command.type != 7 || !command.hasNotification()) return Kind.GENERAL
            val notification = command.notification
            if (command.subtype == 0 && notification.hasNotification2() &&
                notification.notification2.hasNotification3()
            ) {
                val post = notification.notification2.notification3
                if (BandNotificationCommand.isCall(post)) {
                    return if (post.`package` == "phone" && post.id == 0) Kind.CALL else Kind.GENERAL
                }
                return Kind.NOTIFICATION
            }
            if (command.subtype == 1 && notification.hasNotificationDismiss()) {
                val dismiss = notification.notificationDismiss
                if (dismiss.notificationIdCount == 1) {
                    val id = dismiss.getNotificationId(0)
                    if (id.`package` == "phone" && id.id == 0) return Kind.CALL
                }
                return Kind.DISMISS
            }
            return Kind.GENERAL
        }

        private fun failure(code: String): IllegalStateException = IllegalStateException(code)

        private fun failed(code: String): CompletableFuture<XiaomiProto.Command> {
            val future = CompletableFuture<XiaomiProto.Command>()
            future.completeExceptionally(failure(code))
            return future
        }
    }

    fun interface Sender {
        @Throws(Exception::class)
        fun write(sequence: Int, command: XiaomiProto.Command)
    }

    private enum class Kind { GENERAL, NOTIFICATION, DISMISS, FILE_CONFIRM, CALL }

    private class Work(
        val command: XiaomiProto.Command,
        val responseType: Int,
        val responseSubtype: Int,
        val kind: Kind,
        val timeoutMillis: Long,
    ) {
        val result = CompletableFuture<XiaomiProto.Command>()
        var sequence = -1
        var acknowledged = false
        var response: XiaomiProto.Command? = null
        var timeout: ScheduledFuture<*>? = null

        fun wantsResponse(): Boolean = responseType >= 0
        fun ordinary(): Boolean = kind != Kind.FILE_CONFIRM && kind != Kind.CALL
    }

    private val lock = Any()
    private val timeoutMillis: Long
    private val quarantineNanos: Long
    private val worker: ScheduledThreadPoolExecutor
    private val deadlines: ScheduledThreadPoolExecutor

    // Admission order is also the eviction order. Includes queued AND in-flight work.
    private val work = ArrayList<Work>(CAPACITY + FILE_CAPACITY + 3)
    private val pending = arrayOfNulls<Work>(256)
    private val retiredUntil = LongArray(256)
    private var nextSequence: Int
    private var drainScheduled = false
    private var sequenceWake: ScheduledFuture<*>? = null
    private var closed = false

    init {
        if (initialSequence < 0 || initialSequence > 255) {
            throw IllegalArgumentException("LIVE_SEQUENCE_INVALID")
        }
        this.nextSequence = initialSequence
        this.timeoutMillis = if (timeoutMillis <= 0 ||
            timeoutMillis > TimeUnit.NANOSECONDS.toMillis(Long.MAX_VALUE / 4)
        ) {
            DEFAULT_TIMEOUT_MILLIS
        } else {
            timeoutMillis
        }
        this.quarantineNanos = TimeUnit.MILLISECONDS.toNanos(
            maxOf(DEFAULT_TIMEOUT_MILLIS, this.timeoutMillis),
        )
        worker = scheduler("band-live-command")
        deadlines = scheduler("band-live-deadline")
    }

    fun timeoutMillis(): Long = timeoutMillis

    /** Completes after the matching transport ACK. Device-side rendering is a later event. */
    fun send(command: XiaomiProto.Command?): CompletionStage<Void?> =
        submit(command, -1, -1, timeoutMillis).thenApply { null }

    /** Completes only after both the matching transport ACK and a successful semantic response. */
    @JvmOverloads
    fun request(
        command: XiaomiProto.Command?,
        type: Int,
        subtype: Int,
        responseTimeoutMillis: Long = timeoutMillis,
    ): CompletionStage<XiaomiProto.Command> {
        if (type < 0 || subtype < 0) return failed("LIVE_RESPONSE_KEY_INVALID")
        val deadline = if (responseTimeoutMillis <= 0) timeoutMillis else responseTimeoutMillis
        return submit(command, type, subtype, deadline)
    }

    private fun submit(
        command: XiaomiProto.Command?,
        type: Int,
        subtype: Int,
        responseTimeoutMillis: Long,
    ): CompletableFuture<XiaomiProto.Command> {
        if (command == null || !command.isInitialized) return failed("LIVE_COMMAND_INVALID")
        synchronized(lock) {
            if (closed) return failed("LIVE_QUEUE_CLOSED")
            if (coalescible(command)) {
                for (item in work) {
                    if (item.responseType == type &&
                        item.responseSubtype == subtype &&
                        item.command == command
                    ) {
                        return item.result
                    }
                }
            }
            val kind = classify(command)
            if (kind == Kind.CALL) {
                // One latest-state slot. A call packet already handed to Sender finishes on its own.
                for (index in work.size - 1 downTo 0) {
                    val item = work[index]
                    if (item.kind == Kind.CALL && item.sequence < 0) {
                        finishLocked(item, null, failure("LIVE_CALL_SUPERSEDED"))
                    }
                }
            } else if (kind == Kind.FILE_CONFIRM) {
                if (countLocked(Kind.FILE_CONFIRM) >= FILE_CAPACITY) {
                    return failed("LIVE_FILE_CONFIRM_QUEUE_FULL")
                }
            } else {
                var ordinary = 0
                var disposable: Work? = null
                for (item in work) {
                    if (item.ordinary()) ordinary++
                    if (disposable == null && item.kind == Kind.NOTIFICATION && item.sequence < 0) {
                        disposable = item
                    }
                }
                if (ordinary >= CAPACITY) {
                    if (disposable == null) return failed("LIVE_QUEUE_FULL")
                    finishLocked(disposable, null, failure("LIVE_NOTIFICATION_DROPPED"))
                }
            }
            val item = Work(command, type, subtype, kind, responseTimeoutMillis)
            work.add(item)
            item.timeout = deadlines.schedule({ expire(item) }, item.timeoutMillis, TimeUnit.MILLISECONDS)
            scheduleDrainLocked()
            return item.result
        }
    }

    fun onAck(sequence: Int) {
        if (sequence < 0 || sequence > 255) return
        synchronized(lock) {
            val item = pending[sequence] ?: return
            item.acknowledged = true
            if (!item.wantsResponse() || item.response != null) {
                finishLocked(item, item.response, null)
                scheduleDrainLocked()
            }
        }
    }

    fun onCommand(command: XiaomiProto.Command?) {
        if (command == null) return
        synchronized(lock) {
            for (item in work) {
                if (item.sequence >= 0 &&
                    item.wantsResponse() &&
                    item.responseType == command.type &&
                    item.responseSubtype == command.subtype
                ) {
                    if (command.hasStatus() && command.status != 0) {
                        finishLocked(item, null, failure("LIVE_COMMAND_REJECTED"))
                    } else {
                        if (item.response == null) item.response = command
                        if (item.acknowledged) finishLocked(item, item.response, null)
                    }
                    scheduleDrainLocked()
                    return
                }
            }
        }
    }

    /** The caller names the cancel reason with this exception. Only ordinary posts that have not been sent are cancelled. */
    fun cancelNotifications(cause: Throwable?) {
        cancelUnsent(false)
    }

    /** The caller names the cancel reason with this exception. Only phone state that has not been sent is cancelled. */
    fun cancelCall(cause: Throwable?) {
        cancelUnsent(true)
    }

    private fun cancelUnsent(calls: Boolean) {
        synchronized(lock) {
            for (index in work.size - 1 downTo 0) {
                val item = work[index]
                val matches = if (calls) {
                    item.kind == Kind.CALL
                } else {
                    item.kind == Kind.NOTIFICATION || item.kind == Kind.DISMISS
                }
                if (item.sequence < 0 && matches) {
                    finishLocked(
                        item,
                        null,
                        failure(if (calls) "LIVE_CALL_CANCELLED" else "LIVE_NOTIFICATION_CANCELLED"),
                    )
                }
            }
            scheduleDrainLocked()
        }
    }

    override fun close() = close(null)

    /** Caller/transport exception details are deliberately not propagated into public errors. */
    fun close(cause: Throwable?) = closeWithCode("LIVE_QUEUE_CLOSED")

    private fun closeWithCode(code: String) {
        synchronized(lock) {
            if (closed) return
            closed = true
            sequenceWake?.cancel(false)
            while (work.isNotEmpty()) finishLocked(work[work.size - 1], null, failure(code))
            worker.shutdownNow()
            deadlines.shutdownNow()
        }
    }

    private fun scheduleDrainLocked() {
        if (closed || drainScheduled || work.isEmpty()) return
        drainScheduled = true
        worker.execute(::drain)
    }

    private fun drain() {
        val selected: Work
        synchronized(lock) {
            drainScheduled = false
            if (closed) return
            val candidate = nextWorkLocked() ?: return
            val sequence = allocateSequenceLocked()
            if (sequence < 0) return
            // Install BOTH correlation records before Sender may synchronously provoke ingress.
            candidate.sequence = sequence
            pending[sequence] = candidate
            selected = candidate
        }
        try {
            sender.write(selected.sequence, selected.command)
        } catch (failure: Exception) {
            if (failure is SppDiagnosticClient.Failure && failure.code == "ICON_CHUNK_TIMEOUT") {
                synchronized(lock) {
                    if (!closed && work.contains(selected)) {
                        finishLocked(selected, null, failure)
                        scheduleDrainLocked()
                    }
                }
                return
            }
            closeWithCode("LIVE_TRANSPORT_WRITE_FAILED")
            return
        }
        synchronized(lock) {
            scheduleDrainLocked()
        }
    }

    private fun nextWorkLocked(): Work? {
        var selected: Work? = null
        var callsInFlight = 0
        for (item in work) {
            if (item.kind == Kind.CALL && item.sequence >= 0) callsInFlight++
        }
        for (item in work) {
            if (item.sequence >= 0 || (item.kind == Kind.CALL && callsInFlight >= CALL_IN_FLIGHT_CAPACITY)) {
                continue
            }
            if (item.wantsResponse() && responseBusyLocked(item)) continue
            if (selected == null || priority(item.kind) < priority(selected.kind)) selected = item
        }
        return selected
    }

    private fun responseBusyLocked(candidate: Work): Boolean {
        for (item in work) {
            if (item.sequence >= 0 &&
                item.wantsResponse() &&
                item.responseType == candidate.responseType &&
                item.responseSubtype == candidate.responseSubtype
            ) {
                return true
            }
        }
        return false
    }

    private fun allocateSequenceLocked(): Int {
        val sequence = nextSequence
        if (pending[sequence] != null) return -1
        val remaining = if (retiredUntil[sequence] == 0L) 0L else retiredUntil[sequence] - System.nanoTime()
        if (remaining <= 0) {
            nextSequence = (sequence + 1) and 255
            return sequence
        }
        // The peer expects contiguous eight-bit sequence numbers. A quarantined id stays in the sequence.
        if (sequenceWake == null) {
            sequenceWake = worker.schedule({
                synchronized(lock) {
                    sequenceWake = null
                    scheduleDrainLocked()
                }
            }, remaining, TimeUnit.NANOSECONDS)
        }
        return -1
    }

    private fun expire(item: Work) {
        synchronized(lock) {
            if (!work.contains(item)) return
            finishLocked(item, null, TimeoutException("LIVE_COMMAND_TIMEOUT"))
            scheduleDrainLocked()
        }
    }

    private fun finishLocked(item: Work, response: XiaomiProto.Command?, error: Throwable?) {
        work.remove(item)
        item.timeout?.cancel(false)
        if (item.sequence >= 0) {
            pending[item.sequence] = null
            retiredUntil[item.sequence] = System.nanoTime() + quarantineNanos
        }
        // User continuation runs after the lock, off the reader and the writer.
        ForkJoinPool.commonPool().execute {
            if (error == null) {
                item.result.complete(response)
            } else {
                item.result.completeExceptionally(error)
            }
        }
    }

    private fun countLocked(kind: Kind): Int {
        var count = 0
        for (item in work) {
            if (item.kind == kind) count++
        }
        return count
    }
}
