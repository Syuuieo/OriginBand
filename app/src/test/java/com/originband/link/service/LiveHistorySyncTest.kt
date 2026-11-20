// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.service

import com.google.protobuf.ByteString
import com.originband.link.protocol.LiveCommandQueue
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BooleanSupplier
import java.util.function.Consumer

class LiveHistorySyncTest {
    @Test
    @Throws(Exception::class)
    fun acknowledgedButMissingFileTimesOutAndNextSyncRecovers() {
        exerciseRecovery(false)
    }

    @Test
    @Throws(Exception::class)
    fun rejectedDownloadDoesNotStrandFutureSync() {
        exerciseRecovery(true)
    }

    @Throws(Exception::class)
    private fun exerciseRecovery(rejection: Boolean) {
        val worker = Executors.newSingleThreadScheduledExecutor()
        val queueRef = AtomicReference<LiveCommandQueue>()
        val historyRef = AtomicReference<LiveHistorySync>()
        val requests = AtomicInteger()
        val outcomes: BlockingQueue<String> = LinkedBlockingQueue()
        try {
            LiveCommandQueue(0, if (rejection) 10_000 else 300) { sequence, command ->
                val current = queueRef.get()
                if (command.subtype == 1) {
                    val ids = if (requests.getAndIncrement() == 0) {
                        byteArrayOf(1, 0, 0, 0, 0, 4, 0)
                    } else {
                        ByteArray(0)
                    }
                    current.onCommand(
                        XiaomiProto.Command.newBuilder().setType(8).setSubtype(1)
                            .setHealth(
                                XiaomiProto.Health.newBuilder()
                                    .setActivityRequestFileIds(ByteString.copyFrom(ids))
                            ).build()
                    )
                } else if (rejection && command.subtype == 3) {
                    worker.execute {
                        historyRef.get().onCommand(
                            XiaomiProto.Command.newBuilder().setType(8).setSubtype(3).setStatus(1).build()
                        )
                    }
                }
                current.onAck(sequence)
            }.use { queue ->
                queueRef.set(queue)
                val history = LiveHistorySync(
                    queue,
                    worker,
                    Runnable { outcomes.add("complete") },
                    Consumer { outcomes.add(it) },
                    BooleanSupplier { true }
                )
                historyRef.set(history)
                worker.execute { history.request(false) }
                val failure = outcomes.poll(5, TimeUnit.SECONDS)
                assertNotNull("A missing or rejected file must release the sync round", failure)
                assertNotEquals("complete", failure)
                worker.execute { history.request(false) }
                assertEquals("complete", outcomes.poll(5, TimeUnit.SECONDS))
                assertEquals(2, requests.get())
            }
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    @Throws(Exception::class)
    fun anAlreadyStoredFileIsNotDownloadedAgain() {
        val worker = Executors.newSingleThreadScheduledExecutor()
        val queueRef = AtomicReference<LiveCommandQueue>()
        val historyRef = AtomicReference<LiveHistorySync>()
        val downloads = AtomicInteger()
        val id = byteArrayOf(1, 0, 0, 0, 0, 4, 0)
        val outcomes: BlockingQueue<String> = LinkedBlockingQueue()
        try {
            LiveCommandQueue(0, 5_000) { sequence, command ->
                val current = queueRef.get()
                if (command.subtype == 1) {
                    current.onCommand(
                        XiaomiProto.Command.newBuilder().setType(8).setSubtype(1)
                            .setHealth(
                                XiaomiProto.Health.newBuilder()
                                    .setActivityRequestFileIds(ByteString.copyFrom(id))
                            ).build()
                    )
                } else if (command.subtype == 3) {
                    downloads.incrementAndGet()
                    worker.execute { historyRef.get().saved(id) }
                }
                current.onAck(sequence)
            }.use { queue ->
                queueRef.set(queue)
                val history = LiveHistorySync(
                    queue,
                    worker,
                    Runnable { outcomes.add("complete") },
                    Consumer { outcomes.add(it) },
                    BooleanSupplier { true }
                )
                historyRef.set(history)
                worker.execute { history.request(false) }
                assertEquals("complete", outcomes.poll(5, TimeUnit.SECONDS))
                worker.execute { history.request(false) }
                assertEquals("complete", outcomes.poll(5, TimeUnit.SECONDS))
                assertEquals(1, downloads.get())
            }
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    @Throws(Exception::class)
    fun storageFailureReleasesTheRound() {
        val worker = Executors.newSingleThreadScheduledExecutor()
        val queueRef = AtomicReference<LiveCommandQueue>()
        val listed = AtomicInteger()
        val opens = AtomicInteger()
        val outcomes: BlockingQueue<String> = LinkedBlockingQueue()
        try {
            LiveCommandQueue(0, 5_000) { sequence, command ->
                val current = queueRef.get()
                if (command.subtype == 1) {
                    val ids = if (listed.getAndIncrement() == 0) {
                        byteArrayOf(1, 0, 0, 0, 0, 4, 0)
                    } else {
                        ByteArray(0)
                    }
                    current.onCommand(
                        XiaomiProto.Command.newBuilder().setType(8).setSubtype(1)
                            .setHealth(
                                XiaomiProto.Health.newBuilder()
                                    .setActivityRequestFileIds(ByteString.copyFrom(ids))
                            ).build()
                    )
                }
                current.onAck(sequence)
            }.use { queue ->
                queueRef.set(queue)
                val history = LiveHistorySync(
                    queue,
                    worker,
                    Runnable { outcomes.add("complete") },
                    Consumer { outcomes.add(it) },
                    BooleanSupplier {
                        if (opens.getAndIncrement() == 0) {
                            throw IllegalStateException("HEALTH_STORAGE_UNAVAILABLE")
                        }
                        true
                    }
                )
                worker.execute { history.request(false) }
                assertEquals("HEALTH_STORAGE_UNAVAILABLE", outcomes.poll(5, TimeUnit.SECONDS))
                worker.execute { history.request(false) }
                assertEquals("complete", outcomes.poll(5, TimeUnit.SECONDS))
            }
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    @Throws(Exception::class)
    fun archiveStorageFailureRetriesTodayAutomatically() {
        exerciseStorageRetry(archiveFailure = true, historicalFile = false)
    }

    @Test
    @Throws(Exception::class)
    fun archiveStorageFailureRetriesOlderDatesAutomatically() {
        exerciseStorageRetry(archiveFailure = true, historicalFile = true)
    }

    @Test
    @Throws(Exception::class)
    fun capacityStorageFailureRetriesTodayAutomatically() {
        exerciseStorageRetry(archiveFailure = false, historicalFile = false)
    }

    @Test
    @Throws(Exception::class)
    fun capacityStorageFailurePreservesHistoryScope() {
        exerciseStorageRetry(archiveFailure = false, historicalFile = true)
    }

    @Throws(Exception::class)
    private fun exerciseStorageRetry(archiveFailure: Boolean, historicalFile: Boolean) {
        val worker = Executors.newSingleThreadScheduledExecutor()
        val queueRef = AtomicReference<LiveCommandQueue>()
        val historyRef = AtomicReference<LiveHistorySync>()
        val downloads = AtomicInteger()
        val opens = AtomicInteger()
        val todayLists = AtomicInteger()
        val historyLists = AtomicInteger()
        val starts = AtomicInteger()
        val id = byteArrayOf(1, 0, 0, 0, 0, 4, 0)
        val outcomes: BlockingQueue<String> = LinkedBlockingQueue()
        try {
            LiveCommandQueue(0, 5_000) { sequence, command ->
                val current = queueRef.get()
                val subtype = command.subtype
                if (subtype == 1 || subtype == 2) {
                    if (subtype == 1) todayLists.incrementAndGet() else historyLists.incrementAndGet()
                    val ids = if ((subtype == 2) == historicalFile) id else ByteArray(0)
                    current.onCommand(
                        XiaomiProto.Command.newBuilder().setType(8).setSubtype(subtype)
                            .setHealth(
                                XiaomiProto.Health.newBuilder()
                                    .setActivityRequestFileIds(ByteString.copyFrom(ids))
                            ).build()
                    )
                } else if (subtype == 3) {
                    val download = downloads.incrementAndGet()
                    worker.execute {
                        if (archiveFailure && download == 1) {
                            historyRef.get().rejected("HISTORY_STORAGE_FAILED")
                        } else {
                            historyRef.get().saved(id)
                        }
                    }
                }
                current.onAck(sequence)
            }.use { queue ->
                queueRef.set(queue)
                val history = LiveHistorySync(
                    queue,
                    worker,
                    Runnable { starts.incrementAndGet() },
                    Runnable { outcomes.add("complete") },
                    Consumer { outcomes.add(it) },
                    BooleanSupplier {
                        if (!archiveFailure && opens.getAndIncrement() == 0) {
                            throw IllegalStateException("HEALTH_STORAGE_UNAVAILABLE")
                        }
                        true
                    },
                    10L
                )
                historyRef.set(history)
                worker.execute { history.request(true) }
                assertEquals(
                    if (archiveFailure) "HISTORY_STORAGE_FAILED" else "HEALTH_STORAGE_UNAVAILABLE",
                    outcomes.poll(5, TimeUnit.SECONDS)
                )
                assertEquals("complete", outcomes.poll(5, TimeUnit.SECONDS))
                assertEquals(2, todayLists.get())
                assertEquals(2, starts.get())
                assertEquals(if (historicalFile) 2 else 1, historyLists.get())
                assertEquals(if (archiveFailure) 2 else 1, downloads.get())
                worker.submit { history.close() }.get(5, TimeUnit.SECONDS)
            }
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    @Throws(Exception::class)
    fun persistentStorageFailureHasBoundedRetries() {
        exercisePendingRetry("exhaust")
    }

    @Test
    @Throws(Exception::class)
    fun closingSessionCancelsStorageRetry() {
        exercisePendingRetry("close")
    }

    @Test
    @Throws(Exception::class)
    fun manualSyncReplacesPendingStorageRetry() {
        exercisePendingRetry("manual")
    }

    @Throws(Exception::class)
    private fun exercisePendingRetry(action: String) {
        val worker = Executors.newSingleThreadScheduledExecutor()
        val queueRef = AtomicReference<LiveCommandQueue>()
        val listed = AtomicInteger()
        val outcomes: BlockingQueue<String> = LinkedBlockingQueue()
        try {
            LiveCommandQueue(0, 5_000) { sequence, command ->
                val current = queueRef.get()
                if (command.subtype == 1) {
                    val count = listed.incrementAndGet()
                    val ids = if ("manual" == action && count > 1) {
                        ByteArray(0)
                    } else {
                        byteArrayOf(1, 0, 0, 0, 0, 4, 0)
                    }
                    current.onCommand(
                        XiaomiProto.Command.newBuilder().setType(8).setSubtype(1)
                            .setHealth(
                                XiaomiProto.Health.newBuilder()
                                    .setActivityRequestFileIds(ByteString.copyFrom(ids))
                            ).build()
                    )
                }
                current.onAck(sequence)
            }.use { queue ->
                queueRef.set(queue)
                val history = LiveHistorySync(
                    queue,
                    worker,
                    Runnable { outcomes.add("complete") },
                    Consumer { outcomes.add(it) },
                    BooleanSupplier { throw IllegalStateException("HEALTH_STORAGE_UNAVAILABLE") },
                    if ("exhaust" == action) 10L else 100L
                )
                worker.execute { history.request(false) }
                assertEquals("HEALTH_STORAGE_UNAVAILABLE", outcomes.poll(5, TimeUnit.SECONDS))
                when (action) {
                    "exhaust" -> {
                        for (i in 0 until 3) {
                            assertEquals("HEALTH_STORAGE_UNAVAILABLE", outcomes.poll(5, TimeUnit.SECONDS))
                        }
                    }
                    "close" -> {
                        worker.submit { history.close() }.get(5, TimeUnit.SECONDS)
                    }
                    else -> {
                        worker.execute { history.request(false) }
                        assertEquals("complete", outcomes.poll(5, TimeUnit.SECONDS))
                    }
                }
                worker.schedule({}, 300, TimeUnit.MILLISECONDS).get(5, TimeUnit.SECONDS)
                assertEquals(
                    when (action) {
                        "exhaust" -> 4
                        "close" -> 1
                        else -> 2
                    },
                    listed.get()
                )
                assertNull(outcomes.poll())
                worker.submit { history.close() }.get(5, TimeUnit.SECONDS)
            }
        } finally {
            worker.shutdownNow()
        }
    }
}
