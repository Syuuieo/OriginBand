// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import com.originband.link.data.SessionLog
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Official-app GATT UUIDs when present; otherwise Band 8 fe95 command characteristics. */
class BleGattClient(
    private val context: Context,
    private val device: BluetoothDevice,
    binding: JSONObject,
) : AutoCloseable {
    companion object {
        @JvmField
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        @JvmField
        val FE95: UUID = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb")

        @JvmField
        val CMD_READ: UUID = UUID.fromString("00000051-0000-1000-8000-00805f9b34fb")

        @JvmField
        val CMD_WRITE: UUID = UUID.fromString("00000052-0000-1000-8000-00805f9b34fb")

        @JvmField
        val ACTIVITY: UUID = UUID.fromString("00000053-0000-1000-8000-00805f9b34fb")

        @JvmField
        val UPLOAD: UUID = UUID.fromString("00000055-0000-1000-8000-00805f9b34fb")

        private const val ATT_PAYLOAD = 20

        private fun uuid(uuids: JSONObject?, key: String, fallback: UUID): UUID {
            if (uuids == null) return fallback
            val value = uuids.optString(key, "")
            if (value.isBlank()) return fallback
            return try {
                UUID.fromString(value)
            } catch (_: IllegalArgumentException) {
                fallback
            }
        }

        private fun notifies(characteristic: BluetoothGattCharacteristic?): Boolean {
            if (characteristic == null) return false
            val props = characteristic.properties
            return (props and (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0
        }
    }

    class Rx(
        @JvmField val payload: ByteArray,
        @JvmField val activity: Boolean,
        @JvmField val mass: Boolean,
    )

    private class PendingWrite(
        val target: BluetoothGattCharacteristic,
        val frame: ByteArray,
    ) {
        val finished = CountDownLatch(1)

        @Volatile
        var error: String? = null
    }

    private val serviceUuid: UUID
    private val readUuid: UUID
    private val writeUuid: UUID
    private val activityUuid: UUID
    private val uploadUuid: UUID

    // The reader blocks inside a GATT write during the handshake while fitness
    // notifications are already arriving. A short queue aborts that sync.
    private val incoming: BlockingQueue<Rx> = ArrayBlockingQueue(128)
    private val transportAcks: BlockingQueue<ByteArray> = ArrayBlockingQueue(8)
    private val failure = AtomicReference<String?>()
    private val ready = CountDownLatch(1)
    private val subscribeStarted = AtomicBoolean()

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var writer: BluetoothGattCharacteristic? = null

    @Volatile
    private var upload: BluetoothGattCharacteristic? = null

    @Volatile
    private var writerNotifies = false

    @Volatile
    private var closed = false

    @Volatile
    private var servicesReady = false

    @Volatile
    private var mtuSeen = false

    @Volatile
    private var maxWrite = ATT_PAYLOAD

    private val commands = BleV1Codec.Reassembler()
    private val activity = BleV1Codec.Reassembler()
    private val mass = BleV1Codec.Reassembler()
    private val writeLock = Any()
    private val pendingWrites = LinkedBlockingQueue<PendingWrite>()
    private val currentWrite = AtomicReference<PendingWrite?>()
    private val subscribe = arrayOfNulls<BluetoothGattCharacteristic>(4)
    private var notifies: BleNotifyQueue? = null
    private var previousNotification: ByteArray? = null
    private var previousAt = 0L

    @Volatile
    private var writerThread: Thread? = null

    init {
        val uuids = binding.optJSONObject("privateUUID")
        serviceUuid = uuid(uuids, "service", FE95)
        readUuid = uuid(uuids, "protoRX", CMD_READ)
        writeUuid = uuid(uuids, "protoTX", CMD_WRITE)
        activityUuid = uuid(uuids, "fitness", ACTIVITY)
        uploadUuid = uuid(uuids, "mass", UPLOAD)
    }

    @Throws(Exception::class)
    fun connect() {
        val thread = Thread(::drainWrites, "ble-gatt-write").apply {
            isDaemon = true
        }
        writerThread = thread
        thread.start()
        val created = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw SppDiagnosticClient.Failure("BLE_CONNECT_FAILED")
        gatt = created
        if (!ready.await(20, TimeUnit.SECONDS)) throw SppDiagnosticClient.Failure("BLE_READY_TIMEOUT")
        val error = failure.get()
        if (error != null) throw SppDiagnosticClient.Failure(error)
    }

    @Throws(Exception::class)
    fun writeUpload(payload: ByteArray, encrypted: Boolean, counter: Int) {
        val target = upload ?: throw SppDiagnosticClient.Failure("ICON_UPLOAD_UNSUPPORTED")
        writeCharacteristic(target, payload, encrypted, counter)
    }

    @Throws(Exception::class)
    fun write(payload: ByteArray, encrypted: Boolean, counter: Int) {
        val target = writer
        if (target == null || gatt == null) throw SppDiagnosticClient.Failure("BLE_DISCONNECTED")
        writeCharacteristic(target, payload, encrypted, counter)
    }

    @Throws(Exception::class)
    private fun writeCharacteristic(
        target: BluetoothGattCharacteristic,
        payload: ByteArray,
        encrypted: Boolean,
        counter: Int,
    ) {
        if (gatt == null) throw SppDiagnosticClient.Failure("BLE_DISCONNECTED")
        val budget = writeBudget()
        val frames = BleV1Codec.encodeOutgoing(payload, budget, encrypted, counter)
        SessionLog.line(context, "ble mtu=$budget frames=${frames.size} wire=${frames[0].size}")
        if (frames.size == 1) {
            writeRaw(target, frames[0])
            return
        }
        if (!writerNotifies) throw SppDiagnosticClient.Failure("BLE_CHUNK_ACK_UNSUPPORTED")
        transportAcks.clear()
        writeRaw(target, frames[0])
        if (!BleV1Codec.chunkAck(awaitChunkAck(1), 1)) {
            throw SppDiagnosticClient.Failure("BLE_CHUNK_ACK_TIMEOUT")
        }
        for (i in 1 until frames.size) writeRaw(target, frames[i])
        if (!BleV1Codec.chunkAck(awaitChunkAck(0), 0)) {
            throw SppDiagnosticClient.Failure("BLE_CHUNK_ACK_TIMEOUT")
        }
    }

    @Throws(Exception::class)
    fun take(timeoutMs: Long): Rx {
        var error = failure.get()
        if (error != null) throw SppDiagnosticClient.Failure(error)
        val payload = incoming.poll(timeoutMs, TimeUnit.MILLISECONDS)
        if (payload != null) {
            // close() and a dead link both enqueue an empty wake-up. The failure is the reason.
            error = failure.get()
            if (error != null && payload.payload.isEmpty()) throw SppDiagnosticClient.Failure(error)
            return payload
        }
        if (closed) throw SppDiagnosticClient.Failure("CANCELLED")
        error = failure.get()
        if (error != null) throw SppDiagnosticClient.Failure(error)
        throw SppDiagnosticClient.Failure("BLE_READ_TIMEOUT")
    }

    override fun close() {
        closed = true
        val link = gatt
        gatt = null
        if (link != null) {
            try {
                link.disconnect()
            } catch (_: Exception) {
            }
            try {
                link.close()
            } catch (_: Exception) {
            }
        }
        failPending("CANCELLED")
        writerThread?.interrupt()
        incoming.offer(Rx(ByteArray(0), false, false))
        transportAcks.offer(ByteArray(0))
    }

    private fun writeBudget(): Int {
        val value = maxWrite
        return if (value < 8) ATT_PAYLOAD else value
    }

    @Throws(Exception::class)
    private fun awaitChunkAck(subtype: Int): ByteArray {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            val error = failure.get()
            if (error != null) throw SppDiagnosticClient.Failure(error)
            if (closed) throw SppDiagnosticClient.Failure("CANCELLED")
            val left = deadline - System.nanoTime()
            if (left <= 0) throw SppDiagnosticClient.Failure("BLE_CHUNK_ACK_TIMEOUT")
            val value = transportAcks.poll(left, TimeUnit.NANOSECONDS)
            if (value != null && BleV1Codec.chunkAck(value, subtype)) return value
        }
    }

    @Throws(Exception::class)
    private fun writeRaw(target: BluetoothGattCharacteristic, frame: ByteArray) {
        val op = enqueue(target, frame)
        if (!op.finished.await(5, TimeUnit.SECONDS)) throw SppDiagnosticClient.Failure("BLE_WRITE_FAILED")
        val opError = op.error
        if (opError != null) throw SppDiagnosticClient.Failure(opError)
    }

    private fun ack(source: BluetoothGattCharacteristic, frame: ByteArray) {
        try {
            enqueue(source, frame)
        } catch (error: Exception) {
            SessionLog.line(context, "ble ack failed ${error.javaClass.simpleName}")
        }
    }

    @Throws(Exception::class)
    private fun enqueue(target: BluetoothGattCharacteristic?, frame: ByteArray): PendingWrite {
        if (target == null || closed) throw SppDiagnosticClient.Failure("BLE_DISCONNECTED")
        val op = PendingWrite(target, frame)
        pendingWrites.put(op)
        return op
    }

    private fun drainWrites() {
        while (!closed) {
            val op = try {
                pendingWrites.take()
            } catch (_: InterruptedException) {
                if (closed) return
                continue
            }
            if (op.error != null) {
                op.finished.countDown()
                continue
            }
            currentWrite.set(op)
            try {
                if (!submit(op)) {
                    op.error = "BLE_WRITE_FAILED"
                } else if (!op.finished.await(400, TimeUnit.MILLISECONDS) &&
                    currentWrite.compareAndSet(op, null) &&
                    op.error == null
                ) {
                    // The callback missed its wait, so currentWrite is cleared. A late
                    // onCharacteristicWrite no longer matches this operation, and the caller sees a failure.
                    op.error = "BLE_WRITE_FAILED"
                    SessionLog.line(context, "ble write callback missing")
                }
            } catch (error: Exception) {
                op.error = if (error is SppDiagnosticClient.Failure) error.code else "BLE_WRITE_FAILED"
            } finally {
                currentWrite.compareAndSet(op, null)
                op.finished.countDown()
            }
        }
    }

    @Throws(Exception::class)
    private fun submit(op: PendingWrite): Boolean {
        synchronized(writeLock) {
            val link = gatt
            if (link == null || closed) throw SppDiagnosticClient.Failure("BLE_DISCONNECTED")
            val props = op.target.properties
            val type = if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            op.target.writeType = type
            @Suppress("DEPRECATION")
            op.target.value = op.frame
            for (attempt in 0 until 6) {
                @Suppress("DEPRECATION")
                if (link.writeCharacteristic(op.target)) return true
                if (closed) throw SppDiagnosticClient.Failure("CANCELLED")
                Thread.sleep(25L * (attempt + 1))
            }
            SessionLog.line(context, "ble write rejected")
            return false
        }
    }

    private fun failPending(code: String) {
        val inflight = currentWrite.get()
        if (inflight != null) inflight.error = code
        while (true) {
            val op = pendingWrites.poll() ?: break
            op.error = code
            op.finished.countDown()
        }
        inflight?.finished?.countDown()
    }

    private fun alreadySubscribed(candidate: BluetoothGattCharacteristic, count: Int): Boolean {
        val uuid = candidate.uuid
        for (i in 0 until count) {
            val existing = subscribe[i]
            if (existing != null && uuid == existing.uuid) return true
        }
        return false
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                gatt.discoverServices()
                return
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                fail("BLE_DISCONNECTED")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("BLE_DISCOVER_FAILED")
                return
            }
            val service = gatt.getService(serviceUuid) ?: gatt.getService(FE95)
            if (service == null) {
                fail("BLE_SERVICE_MISSING")
                return
            }
            val reader = service.getCharacteristic(readUuid)
            val tx = service.getCharacteristic(writeUuid)
            writer = tx
            val massChar = service.getCharacteristic(uploadUuid)
            upload = massChar
            val fitness = service.getCharacteristic(activityUuid)
            if (reader == null || tx == null) {
                fail("BLE_CHARACTERISTIC_MISSING")
                return
            }
            writerNotifies = notifies(tx) && tx.uuid != reader.uuid
            var count = 0
            if (writerNotifies) subscribe[count++] = tx
            subscribe[count++] = reader
            if (fitness != null && fitness.uuid != reader.uuid) subscribe[count++] = fitness
            // Icon chunks are written here. The band ACKs that same characteristic, not protoTX.
            if (massChar != null && notifies(massChar) && !alreadySubscribed(massChar, count)) {
                subscribe[count++] = massChar
            }
            notifies = BleNotifyQueue(count)
            servicesReady = true
            if (mtuSeen || !gatt.requestMtu(512)) {
                if (!mtuSeen) SessionLog.line(context, "mtu request rejected")
                startSubscribe(gatt)
            } else {
                val fallback = Thread({
                    try {
                        if (!ready.await(3, TimeUnit.SECONDS) && !closed) startSubscribe(gatt)
                    } catch (_: InterruptedException) {
                    }
                }, "ble-mtu-fallback")
                fallback.isDaemon = true
                fallback.start()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && mtu >= 23) {
                maxWrite = mtu - 3
                SessionLog.line(context, "mtu=$mtu")
            } else {
                SessionLog.line(context, "mtu status=$status value=$mtu")
            }
            mtuSeen = true
            if (servicesReady) startSubscribe(gatt)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            onValue(characteristic, value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            onValue(characteristic, characteristic.value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic?,
            status: Int,
        ) {
            val op = currentWrite.getAndSet(null) ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                SessionLog.line(context, "ble write status=$status")
                op.error = "BLE_WRITE_FAILED"
            }
            op.finished.countDown()
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor?,
            status: Int,
        ) {
            if (closed || notifies == null || descriptor == null || CCCD != descriptor.uuid) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                SessionLog.line(context, "cccd status=$status")
                fail("BLE_NOTIFY_FAILED")
                return
            }
            val queue = notifies ?: return
            if (!queue.confirmed()) return
            subscribeNext(gatt)
        }
    }

    private fun onValue(characteristic: BluetoothGattCharacteristic?, value: ByteArray?) {
        if (closed || characteristic == null || value == null || value.isEmpty()) return
        val now = System.nanoTime()
        val prev = previousNotification
        if (prev != null && now - previousAt < 5_000_000L && prev.contentEquals(value)) return
        previousNotification = value.copyOf()
        previousAt = now
        val uuid = characteristic.uuid
        try {
            if (writeUuid == uuid) {
                // Single-frame writes do not wait for the payload ACK (type 3). Those ACKs
                // are identical and arrive once per command. Queueing them filled the 8-slot
                // ack queue and aborted every Band 8 session after the handshake burst.
                // Only a chunked write is waiting, and it only needs chunk ACKs (type 1).
                // The icon path writes the upload characteristic, so its ACK is not on protoTX.
                if (BleV1Codec.control(value, BleV1Codec.TYPE_CHUNK_ACK) && !transportAcks.offer(value)) {
                    SessionLog.line(context, "ble chunk ack dropped")
                }
                return
            }
            val activityChar = activityUuid == uuid
            val massChar = uploadUuid == uuid
            if (massChar && BleV1Codec.control(value, BleV1Codec.TYPE_CHUNK_ACK)) {
                if (!transportAcks.offer(value)) SessionLog.line(context, "ble chunk ack dropped")
                return
            }
            val reassembler = when {
                massChar -> mass
                activityChar -> activity
                else -> commands
            }
            if (BleV1Codec.control(value, BleV1Codec.TYPE_CHUNK_START)) {
                ack(characteristic, BleV1Codec.CHUNK_START_ACK)
            }
            val payload = reassembler.accept(value)
            if (payload != null && payload.isNotEmpty()) {
                ack(
                    characteristic,
                    if (BleV1Codec.control(value, BleV1Codec.TYPE_SINGLE)) {
                        BleV1Codec.PAYLOAD_ACK
                    } else {
                        BleV1Codec.CHUNK_END_ACK
                    },
                )
                val suffix = when {
                    activityChar -> " activity"
                    massChar -> " mass"
                    else -> ""
                }
                SessionLog.line(context, "rx ble bytes=${payload.size}$suffix")
                // The band already has the ACK. A full queue drops this packet before the caller, so the session stops instead of continuing with a gap.
                if (!incoming.offer(Rx(payload, activityChar, massChar))) overflow()
            }
        } catch (_: IllegalArgumentException) {
            fail("BLE_FRAME_INVALID")
        }
    }

    private fun startSubscribe(link: BluetoothGatt) {
        if (!subscribeStarted.compareAndSet(false, true)) return
        subscribeNext(link)
    }

    private fun subscribeNext(link: BluetoothGatt) {
        val queue = notifies
        if (closed || queue == null) {
            if (!closed) fail("BLE_NOTIFY_FAILED")
            return
        }
        val index = queue.start()
        if (index < 0) {
            if (queue.complete()) {
                // Low power intervals were dropping this firmware's link during the connect
                // burst. Stay fast through activity sync, then let the interval relax.
                link.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                val idle = Thread({
                    try {
                        Thread.sleep(60_000)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    if (!closed && gatt === link) {
                        link.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)
                    }
                }, "ble-idle-priority")
                idle.isDaemon = true
                idle.start()
                ready.countDown()
            }
            return
        }
        val characteristic = subscribe[index]
        if (characteristic == null || !link.setCharacteristicNotification(characteristic, true)) {
            fail("BLE_NOTIFY_FAILED")
            return
        }
        val descriptor = characteristic.getDescriptor(CCCD)
        if (descriptor == null) {
            if (!queue.confirmed()) {
                fail("BLE_NOTIFY_FAILED")
                return
            }
            subscribeNext(link)
            return
        }
        val props = characteristic.properties
        val enable = if ((props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        val queued = link.writeDescriptor(descriptor, enable)
        if (queued != BluetoothStatusCodes.SUCCESS) {
            SessionLog.line(context, "cccd write rejected status=$queued")
            fail("BLE_NOTIFY_FAILED")
        }
    }

    private fun overflow() {
        SessionLog.line(context, "ble rx overflow")
        fail("BLE_RX_OVERFLOW")
    }

    private fun fail(code: String) {
        if (!failure.compareAndSet(null, code)) return
        ready.countDown()
        // Wake a live read. Otherwise it sits out the rest of its poll after the link is already dead.
        incoming.offer(Rx(ByteArray(0), false, false))
    }
}
