// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

class LiveCommandQueueTest {
    companion object {
        private const val WAIT_SECONDS = 3L
        private const val SESSION_TIMEOUT_MS = 30_000L

        private fun command(type: Int, subtype: Int): XiaomiProto.Command =
            XiaomiProto.Command.newBuilder().setType(type).setSubtype(subtype).build()

        private fun notification(id: Int): XiaomiProto.Command = BandNotificationCommand.post(
            "com.example.test",
            "Test",
            "key-$id",
            id,
            "Test notification",
            "Test body",
            Instant.EPOCH,
            ZoneId.of("UTC"),
        )

        @Throws(Exception::class)
        private fun <T> await(stage: CompletionStage<T>): T =
            stage.toCompletableFuture().get(WAIT_SECONDS, TimeUnit.SECONDS)

        private fun assertFailed(stage: CompletionStage<*>) {
            val failure = assertThrows(Exception::class.java) { await(stage) }
            assertFalse(
                "The test wait elapsed rather than the queue reporting failure",
                failure is TimeoutException,
            )
        }

        private fun assertFailure(stage: CompletionStage<*>, cause: Class<out Throwable>) {
            // Cancellation may be direct; all other queue failures are wrapped by Future.get().
            var failure: Throwable? = assertThrows(Exception::class.java) { await(stage) }
            assertFalse(
                "The test wait elapsed rather than the queue reporting failure",
                failure is TimeoutException,
            )
            if (failure is ExecutionException) failure = failure.cause
            assertTrue("Unexpected queue failure: $failure", cause.isInstance(failure))
        }
    }

    private class Sent(val sequence: Int, val command: XiaomiProto.Command)

    private class Gate {
        val release = CountDownLatch(1)
    }

    private class Harness(initialSequence: Int = 0) : AutoCloseable {
        val writes: BlockingQueue<Sent> = LinkedBlockingQueue()
        val nextGate = AtomicReference<Gate?>()
        val queue: LiveCommandQueue
        private var activeGate: Gate? = null

        init {
            queue = LiveCommandQueue(initialSequence, SESSION_TIMEOUT_MS) { sequence, command ->
                val gate = nextGate.getAndSet(null)
                writes.add(Sent(sequence, command))
                if (gate != null && !gate.release.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                    throw IOException("Test sender gate was not released")
                }
            }
        }

        @Throws(InterruptedException::class)
        fun next(): Sent {
            val sent = writes.poll(WAIT_SECONDS, TimeUnit.SECONDS)
            assertNotNull("Expected a command to reach the transport", sent)
            return checkNotNull(sent)
        }

        fun pauseNextWrite(): Gate {
            val gate = Gate()
            activeGate = gate
            nextGate.set(gate)
            return gate
        }

        @Throws(Exception::class)
        fun barrier() {
            val marker = queue.send(command(99, 1))
            val sent = next()
            assertEquals(command(99, 1), sent.command)
            queue.onAck(sent.sequence)
            await(marker)
        }

        override fun close() {
            activeGate?.release?.countDown()
            queue.close()
        }
    }

    @Test
    @Throws(Exception::class)
    fun requestWaitsForResponseAfterItsAck() {
        Harness().use { h ->
            val response = command(2, 1).toBuilder().setStatus(0).build()
            val result = h.queue.request(command(2, 1), 2, 1)
            val sent = h.next()
            h.queue.onAck(sent.sequence)
            h.barrier()
            assertFalse(result.toCompletableFuture().isDone)
            h.queue.onCommand(response)
            assertEquals(response, await(result))
        }
    }

    @Test
    @Throws(Exception::class)
    fun requestWaitsForItsAckAfterResponseAndIgnoresUnrelatedTraffic() {
        Harness().use { h ->
            val response = command(10, 5).toBuilder().setStatus(0).build()
            val result = h.queue.request(command(10, 5), 10, 5)
            val sent = h.next()
            h.queue.onCommand(command(10, 4))
            h.queue.onAck((sent.sequence + 73) and 255)
            h.barrier()
            assertFalse(result.toCompletableFuture().isDone)
            h.queue.onCommand(response)
            h.barrier()
            assertFalse(result.toCompletableFuture().isDone)
            h.queue.onAck(sent.sequence)
            assertEquals(response, await(result))
        }
    }

    @Test
    @Throws(Exception::class)
    fun lateAckAcrossSequenceWrapCannotCompleteTheNextSend() {
        Harness(255).use { h ->
            val first = h.queue.send(command(3, 1))
            val previous = h.next()
            assertEquals(255, previous.sequence)
            h.queue.onAck(previous.sequence)
            await(first)

            val second = h.queue.send(command(3, 2))
            val current = h.next()
            assertEquals(0, current.sequence)
            h.queue.onAck(previous.sequence)
            h.barrier()
            assertFalse(second.toCompletableFuture().isDone)
            h.queue.onAck(current.sequence)
            await(second)
        }
    }

    @Test
    @Throws(Exception::class)
    fun ackRegisteredBeforeWriteCanArriveInsideSender() {
        val owner = AtomicReference<LiveCommandQueue?>()
        LiveCommandQueue(0, SESSION_TIMEOUT_MS) { sequence, outgoing ->
            val q = checkNotNull(owner.get())
            q.onCommand(outgoing)
            q.onAck(sequence)
        }.use { queue ->
            owner.set(queue)
            val request = command(2, 1)
            assertEquals(request, await(queue.request(request, 2, 1)))
        }
    }

    @Test
    @Throws(Exception::class)
    fun disconnectFailsAckWaitResponseWaitAndUnsentWork() {
        Harness().use { h ->
            val responseWait = h.queue.request(command(2, 1), 2, 1)
            h.queue.onAck(h.next().sequence)
            val ackWait = h.queue.send(command(3, 1))
            h.next()
            val gate = h.pauseNextWrite()
            val writing = h.queue.send(command(3, 2))
            h.next()
            val unsent = h.queue.send(command(3, 3))
            val disconnect = IOException("test disconnect")
            try {
                h.queue.close(disconnect)
            } finally {
                gate.release.countDown()
            }
            assertFailed(responseWait)
            assertFailed(ackWait)
            assertFailed(writing)
            assertFailed(unsent)
            assertFailed(h.queue.send(command(3, 4)))
        }
    }

    @Test
    @Throws(Exception::class)
    fun readerCanAckFileConfirmationWhileResponseConsumerWaitsForIt() {
        val reader = Executors.newSingleThreadExecutor()
        try {
            Harness().use { h ->
                val file = h.queue.request(command(8, 4), 8, 4)
                val request = h.next()
                val savedAndConfirmed = file.thenApply { response ->
                    try {
                        await(h.queue.send(command(8, 5)))
                        response
                    } catch (e: Exception) {
                        throw IllegalStateException(e)
                    }
                }
                val response = command(8, 4)
                reader.submit(Runnable {
                    h.queue.onAck(request.sequence)
                    h.queue.onCommand(response)
                }).get(WAIT_SECONDS, TimeUnit.SECONDS)
                val confirmation = h.next()
                assertEquals(command(8, 5), confirmation.command)
                reader.submit(Runnable { h.queue.onAck(confirmation.sequence) })
                    .get(WAIT_SECONDS, TimeUnit.SECONDS)
                assertEquals(response, await(savedAndConfirmed))
            }
        } finally {
            reader.shutdownNow()
            assertTrue(reader.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS))
        }
    }

    @Test
    @Throws(Exception::class)
    fun reservedFileSlotsAreReleasedSoNinthAndLaterFilesAreAccepted() {
        Harness().use { h ->
            val confirmations = ArrayList<CompletionStage<Void?>>()
            val sent = ArrayList<Sent>()
            for (i in 0 until 8) {
                confirmations.add(h.queue.send(command(8, 5)))
                sent.add(h.next())
            }
            for (i in 0 until 8) {
                h.queue.onAck(sent[i].sequence)
                await(confirmations[i])
                val nextFile = h.queue.send(command(8, 5))
                val next = h.next()
                h.queue.onAck(next.sequence)
                await(nextFile)
            }
        }
    }

    @Test
    @Throws(Exception::class)
    fun congestionDropsOnlyOldestUnsentNotificationAndPrioritizesLatestCallClear() {
        Harness().use { h ->
            val gate = h.pauseNextWrite()
            val notifications = ArrayList<CompletionStage<Void?>>()
            notifications.add(h.queue.send(notification(1)))
            val first = h.next()
            try {
                for (id in 2..64) {
                    notifications.add(h.queue.send(notification(id)))
                }
                val ringing = h.queue.send(
                    BandNotificationCommand.incomingCall("Test call", Instant.EPOCH, ZoneId.of("UTC")),
                )
                val newest = h.queue.send(notification(65))
                val clear = h.queue.send(BandNotificationCommand.endCall())
                gate.release.countDown()
                assertFailure(notifications[1], Throwable::class.java)
                assertFailure(ringing, Throwable::class.java)
                assertFalse(notifications[0].toCompletableFuture().isDone)
                val next = h.next()
                assertEquals(BandNotificationCommand.endCall(), next.command)
                h.queue.onAck(next.sequence)
                await(clear)
                h.queue.onAck(first.sequence)
                await(notifications[0])
                for (id in 3..65) {
                    val remaining = h.next()
                    assertEquals(notification(id), remaining.command)
                    h.queue.onAck(remaining.sequence)
                }
                for (i in 2 until notifications.size) await(notifications[i])
                await(newest)
            } finally {
                gate.release.countDown()
            }
        }
    }

    @Test
    @Throws(Exception::class)
    fun fullQueueOfNonDisposableWorkRejectsNotificationWithoutLosingPendingCommands() {
        Harness().use { h ->
            val gate = h.pauseNextWrite()
            val pending = ArrayList<CompletionStage<Void?>>()
            pending.add(h.queue.send(command(3, 0)))
            val first = h.next()
            try {
                for (i in 1 until 64) pending.add(h.queue.send(command(3, i)))
                val rejected = h.queue.send(notification(1))
                gate.release.countDown()
                assertFailure(rejected, Throwable::class.java)
                h.queue.onAck(first.sequence)
                for (i in 1 until 64) {
                    val sent = h.next()
                    assertEquals(command(3, i), sent.command)
                    h.queue.onAck(sent.sequence)
                }
                for (future in pending) await(future)
            } finally {
                gate.release.countDown()
            }
        }
    }

    @Test
    @Throws(Exception::class)
    fun semanticRejectionFailsOnlyMatchingRequestAndConnectionRemainsUsable() {
        Harness().use { h ->
            val rejected = h.queue.request(command(10, 5), 10, 5)
            val weather = h.next()
            val battery = h.queue.request(command(2, 1), 2, 1)
            val batterySent = h.next()
            h.queue.onAck(weather.sequence)
            h.queue.onCommand(command(10, 5).toBuilder().setStatus(7).build())
            assertFailure(rejected, Throwable::class.java)
            assertFalse(battery.toCompletableFuture().isDone)
            h.queue.onCommand(command(2, 1))
            h.queue.onAck(batterySent.sequence)
            assertEquals(command(2, 1), await(battery))
            h.barrier()
        }
    }

    @Test
    @Throws(Exception::class)
    fun duplicateHealthWeatherAndBatteryRequestsShareOneOutstandingExchange() {
        for (request in arrayOf(command(8, 1), command(10, 5), command(2, 1))) {
            Harness().use { h ->
                val first = h.queue.request(request, request.type, request.subtype)
                val sent = h.next()
                val duplicate = h.queue.request(request, request.type, request.subtype)
                h.barrier()
                assertFalse(first.toCompletableFuture().isDone)
                assertFalse(duplicate.toCompletableFuture().isDone)
                h.queue.onCommand(request)
                h.queue.onAck(sent.sequence)
                assertEquals(request, await(first))
                assertEquals(request, await(duplicate))
                h.barrier()
            }
        }
    }

    @Test
    @Throws(Exception::class)
    fun missingAckTimesOutObservably() {
        val writes: BlockingQueue<Int> = LinkedBlockingQueue()
        LiveCommandQueue(0, 200) { sequence, _ -> writes.add(sequence) }.use { queue ->
            val result = queue.send(command(3, 1))
            assertNotNull(writes.poll(WAIT_SECONDS, TimeUnit.SECONDS))
            assertFailure(result, TimeoutException::class.java)
        }
    }
}
