// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import android.Manifest
import android.app.KeyguardManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import com.google.protobuf.ByteString
import com.originband.link.data.BandStateRepository
import com.originband.link.data.BindingShape
import com.originband.link.data.BindingStore
import com.originband.link.data.MiProductImage
import com.originband.link.data.RawFitnessFileStore
import com.originband.link.data.SessionLog
import com.originband.link.data.TransportObservation
import com.originband.link.service.OwnershipController
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import org.json.JSONObject
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/** One authenticated SPP session. Live mode stays open until closed. An activity file is confirmed after it is stored. */
class SppDiagnosticClient(
    context: Context,
    private val progress: Consumer<String>,
) : AutoCloseable {
    companion object {
        private val ACTIVE = AtomicReference<SppDiagnosticClient?>()

        @Volatile
        private var activeCompletion: CountDownLatch? = null

        private const val SERVICE = "00001101-0000-1000-8000-00805f9b34fb"

        /** RFCOMM connect budget. A band in range answers in a few seconds. */
        private const val CONNECT_TIMEOUT_MS = 20_000L

        private val CONTROL_REGEX = Regex("[\\p{Cntrl}]")
        private val REASON_REGEX = Regex("[A-Z][A-Z0-9_]{1,90}")

        @JvmStatic
        fun stopAllAndWait(): Boolean {
            val current = ACTIVE.get() ?: return true
            val finished = activeCompletion ?: return false
            current.close()
            return try {
                finished.await(10, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }

        /** BLE validates MTU fragments in its codec; SPP limits complete packet payloads. */
        @JvmStatic
        fun commandFitsPayloadLimit(framing: Int, payloadLength: Int, peerPayloadLimit: Int): Boolean =
            framing == 2 || payloadLength.toLong() + (if (framing == 0) 2 else 0) <= peerPayloadLimit

        private fun safe(value: String?): String {
            if (value == null) return ""
            val clean = value.replace(CONTROL_REGEX, "")
            return if (clean.length > 120) clean.substring(0, 120) else clean
        }

        /** Keeps the socket message. A bare class name cannot tell a remote reset from a local close. */
        private fun failureText(error: Exception): String {
            if (error is Failure) return error.code
            val text = StringBuilder(error.javaClass.simpleName)
            val message = SessionLog.redact(error.message)
            if (message.isNotEmpty()) text.append(" msg=").append(message)
            val cause = error.cause
            if (cause != null && cause !== error) {
                text.append(" cause=").append(cause.javaClass.simpleName)
                val causeMessage = SessionLog.redact(cause.message)
                if (causeMessage.isNotEmpty()) text.append(" causeMsg=").append(causeMessage)
            }
            return text.toString()
        }

        private fun bondName(state: Int): String = when (state) {
            BluetoothDevice.BOND_NONE -> "none"
            BluetoothDevice.BOND_BONDING -> "bonding"
            BluetoothDevice.BOND_BONDED -> "bonded"
            else -> "other"
        }

        @Throws(Failure::class)
        private fun readBattery(command: XiaomiProto.Command): BandBatteryState = try {
            BandBatteryState.parse(command)
        } catch (error: IllegalArgumentException) {
            throw Failure(error.message ?: "BATTERY_VALUE_INVALID")
        }
    }

    data class Result(
        @JvmField val batteryPercent: Int,
        @JvmField val batteryState: Int?,
        @JvmField val firmware: String,
        @JvmField val hardware: String,
        @JvmField val sku: String,
    ) {
        fun batteryPercent(): Int = batteryPercent
        fun batteryState(): Int? = batteryState
        fun firmware(): String = firmware
        fun hardware(): String = hardware
        fun sku(): String = sku
    }

    class Failure(@JvmField val code: String) : Exception(code)

    private class Inputs(
        val address: String,
        val region: String,
        val key: ByteArray,
        val capability: Int,
        val transport: String,
        val binding: JSONObject,
        val observation: JSONObject,
    )

    private val context: Context = context.applicationContext ?: context
    private val decoder = SppV2Codec.Decoder()
    private val v1Decoder = SppV1Codec.Decoder()
    private val pending = ArrayDeque<SppV2Codec.Frame>()
    private val v1Pending = ArrayDeque<SppV1Codec.Packet>()
    private val readBuffer = ByteArray(4096)
    private val receivedPayloads = arrayOfNulls<ByteArray>(256)
    private val receivedAt = IntArray(256) { -1 }
    private var socket: BluetoothSocket? = null
    private var ble: BleGattClient? = null
    private var framing = 0
    private var v1EncryptCounter = 1

    /** The on-wire header is two bytes. The CCM nonce includes this counter and the session key, so counting from 1 again after 0xffff repeats a nonce. */
    @Volatile
    private var closed = false

    @Volatile
    private var timedOut = false

    @Volatile
    private var linkStop: String? = null

    private val writeLock = Any()
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var session: XiaomiSessionCrypto.Session? = null
    private var sentSequence = 0
    private val receive = SppV2Codec.ReceiveCursor()
    private var frameCount = 0
    private var authenticated = false

    /** This session opened an RFCOMM socket without the system bond. See {@link #run}. */
    private var insecureAttempt = false
    private var peerPayloadLimit = 0
    private var deadlineSeconds = 30
    private var onState: Consumer<Result>? = null
    private var onLiveReady: Consumer<LiveCommandQueue>? = null
    private var onFileStored: Consumer<ByteArray>? = null
    private var onLiveCommand: Consumer<XiaomiProto.Command>? = null
    private var onSleepFile: Consumer<List<BandHistoryParser.Measurement>>? = null

    @Volatile
    private var liveCommands: LiveCommandQueue? = null

    @Volatile
    private var deviceLogReceiver: XiaomiMassReceive? = null

    private var verifiedDevice: Result? = null
    private var liveHold = false
    private val heldActivity = ArrayDeque<ByteArray>()
    private var history: BandHistoryParser? = null
    private var historyDeviceId: String? = null
    private var rawHistory: RawFitnessFileStore? = null
    private var observedLocations: XiaomiProto.WeatherLocations? = null
    private val iconChunks = IdentityHashMap<XiaomiProto.Command, ByteArray>()

    fun notificationPayloadLimit(): Int =
        if (liveCommands == null || closed) 0 else maxOf(0, peerPayloadLimit - 2)

    /** Registers the one active full device-log MASS receiver before report 2/80 is sent. */
    @Synchronized
    @Throws(Failure::class)
    fun beginDeviceLogReceive(receiver: XiaomiMassReceive?) {
        if (receiver == null || liveCommands == null || closed || !liveHold) {
            throw Failure("DEVICE_LOG_SESSION_UNAVAILABLE")
        }
        if (deviceLogReceiver != null) throw Failure("DEVICE_LOG_RECEIVE_ACTIVE")
        deviceLogReceiver = receiver
    }

    @Synchronized
    fun cancelDeviceLogReceive(code: String?) {
        val receiver = deviceLogReceiver
        deviceLogReceiver = null
        receiver?.fail(code)
    }

    /** One data-channel part. The queue owns the sequence so it stays aligned with commands. */
    fun sendDataChunk(chunk: ByteArray?): CompletionStage<Void?> {
        val queue = liveCommands
        if (queue == null || closed || chunk == null || chunk.isEmpty()) {
            return CompletableFuture.failedFuture(Failure("ICON_UPLOAD_UNAVAILABLE"))
        }
        val token = XiaomiProto.Command.newBuilder().setType(22).setSubtype(1).build()
        synchronized(iconChunks) { iconChunks[token] = chunk.clone() }
        return queue.send(token).whenComplete { _, _ ->
            synchronized(iconChunks) { iconChunks.remove(token) }
        }
    }

    /**
     * Next V1 counter for this session. 0xffff is the last frame: another frame would reuse a CCM
     * nonce under the same session key. {@code V1_COUNTER_EXHAUSTED} closes the link, and the next
     * connection authenticates again with the counter back at 1.
     */
    @Throws(Failure::class)
    fun takeV1Counter(): Int {
        if (v1EncryptCounter < 1 || v1EncryptCounter > 0xffff) throw Failure("V1_COUNTER_EXHAUSTED")
        return v1EncryptCounter++
    }

    fun armV1Counter(next: Int) {
        v1EncryptCounter = next
    }

    @JvmOverloads
    @Throws(Exception::class)
    fun run(weather: BandWeatherEncoder.Sample? = null): Result = run(weather, false)

    /** Stays authenticated until {@link #close()} or the RFCOMM link fails. Handshake still has a 45 second deadline. */
    @Throws(Exception::class)
    fun runLive(
        ready: Consumer<Result>?,
        commandsReady: Consumer<LiveCommandQueue>?,
        fileStored: Consumer<ByteArray>?,
        commandReceived: Consumer<XiaomiProto.Command>?,
    ): Result {
        onState = ready
        onLiveReady = commandsReady
        onFileStored = fileStored
        onLiveCommand = commandReceived
        deadlineSeconds = 45
        return try {
            run(null, false)
        } finally {
            onState = null
            onLiveReady = null
            onFileStored = null
            onLiveCommand = null
            onSleepFile = null
            liveHold = false
            deadlineSeconds = 30
        }
    }

    fun setSleepFiles(listener: Consumer<List<BandHistoryParser.Measurement>>?) {
        onSleepFile = listener
    }

    @Throws(Exception::class)
    private fun run(weather: BandWeatherEncoder.Sample?, inspectCities: Boolean): Result {
        if (weather != null) BandWeatherEncoder.validateForecast(weather)
        OwnershipController.beginNativeSession()
        return try {
            runLocked(weather, inspectCities)
        } catch (failure: Failure) {
            // An insecure link that dies before the handshake completes means the band wants
            // an encrypted link; repairing the system bond is the fix, not another socket.
            if (insecureAttempt && !authenticated &&
                failure.code != "OFFICIAL_PAIRING_REQUIRED" &&
                failure.code != "CANCELLED" &&
                failure.code != "BLUETOOTH_DISABLED"
            ) {
                SessionLog.line(context, "insecure rfcomm died before auth ${failure.code}")
                throw Failure("OFFICIAL_PAIRING_REQUIRED")
            }
            throw failure
        } finally {
            insecureAttempt = false
            OwnershipController.endNativeSession()
        }
    }

    @Throws(Exception::class)
    private fun runLocked(weather: BandWeatherEncoder.Sample?, inspectCities: Boolean): Result {
        val binding = loadInputs()
        val watchdog = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "OplusBandDiagnosticDeadline").apply { isDaemon = true }
        }
        val deadline = watchdog.schedule({
            timedOut = true
            close()
        }, deadlineSeconds.toLong(), TimeUnit.SECONDS)
        val phoneNonce = ByteArray(16)
        val finished = CountDownLatch(1)
        if (!ACTIVE.compareAndSet(null, this)) {
            deadline.cancel(false)
            watchdog.shutdownNow()
            binding.key.fill(0)
            throw Failure("CONNECTION_ALREADY_ACTIVE")
        }
        activeCompletion = finished
        try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled) throw Failure("BLUETOOTH_DISABLED")
            val device = adapter.getRemoteDevice(binding.address)
            // SPP opens a secure RFCOMM socket and needs the system bond. GATT auth is the
            // captured key; ColorOS drops the BLE bond after 「小米运动健康」(Mi Fitness) is frozen.
            val bond = device.bondState
            val gatt = binding.transport == "GATT"
            // A missing system bond must not cost the user a watch confirm prompt every time
            // ColorOS drops it. Session auth is the imported binding key at the application
            // layer, so the RFCOMM link itself can run insecure and pair exactly never.
            // If the band refuses the insecure socket, the failure maps back to the pairing
            // path and the service decides whether to ask for a bond.
            val insecure = !gatt && bond != BluetoothDevice.BOND_BONDED
            if (insecure) {
                insecureAttempt = true
                SessionLog.line(context, "bond missing, trying insecure rfcomm state=$bond")
            }
            if (gatt && bond != BluetoothDevice.BOND_BONDED) {
                SessionLog.line(context, "gatt without system bond state=$bond")
            }
            progress.accept("CONNECTING")
            if (gatt) {
                framing = 2
                peerPayloadLimit = 512
                val client = BleGattClient(context, device, binding.binding)
                synchronized(this) {
                    if (closed) throw Failure("CANCELLED")
                    ble = client
                }
                client.connect()
                SessionLog.line(
                    context,
                    "version framing=2 transport=GATT sdk=${safe(binding.observation.optString("versionName"))}" +
                        " queue=${safe(binding.observation.optString("queueClass"))}",
                )
            } else {
                val created = if (insecure) {
                    device.createInsecureRfcommSocketToServiceRecord(UUID.fromString(SERVICE))
                } else {
                    device.createRfcommSocketToServiceRecord(UUID.fromString(SERVICE))
                }
                synchronized(this) {
                    if (closed) {
                        created.close()
                        throw Failure("CANCELLED")
                    }
                    socket = created
                }
                try {
                    connectWithTimeout(created)
                } catch (refused: Exception) {
                    if (insecure) {
                        // The band wants an encrypted link after all. Let the service repair
                        // the system bond instead of retrying an insecure socket forever.
                        SessionLog.line(context, "insecure rfcomm refused $refused")
                        throw Failure("OFFICIAL_PAIRING_REQUIRED")
                    }
                    throw refused
                }
                val inStream = created.inputStream
                val outStream = created.outputStream
                input = inStream
                output = outStream
                outStream.write(SppNegotiation.versionRequest(0))
                val version = try {
                    readV1Packet()
                } catch (_: IllegalArgumentException) {
                    throw Failure("PROTOCOL_VERSION_UNSUPPORTED")
                }
                if (version.channel() != SppV1Codec.CHANNEL_VERSION || version.payload().isEmpty()) {
                    throw Failure("PROTOCOL_VERSION_UNSUPPORTED")
                }
                if (TransportObservation.useV1Framing(
                        binding.observation,
                        version.payload()[0].toInt() and 0xff,
                    )
                ) {
                    framing = 1
                    peerPayloadLimit = 2048
                } else {
                    framing = 0
                    sendFrame(SppNegotiation.startSessionRequest())
                    val configuration = readFrame()
                    val config = SppV2Codec.parseSessionConfig(configuration)
                    val configVersion = config.version()
                    val maxPacketSize = config.maxPacketSize()
                    val txWindow = config.txWindow()
                    if (config.opcode() != 2 ||
                        configVersion == null ||
                        configVersion.size != 3 ||
                        maxPacketSize == null ||
                        maxPacketSize < 128 ||
                        txWindow == null ||
                        txWindow < 1
                    ) {
                        throw Failure("SESSION_CONFIGURATION_UNSUPPORTED")
                    }
                    peerPayloadLimit = maxPacketSize - SppV2Codec.HEADER_LENGTH
                }
                SessionLog.line(
                    context,
                    "version framing=$framing major=${version.payload()[0].toInt() and 0xff}" +
                        " bytes=${version.payload().size}" +
                        " queue=${safe(binding.observation.optString("queueClass"))}" +
                        " sdk=${safe(binding.observation.optString("versionName"))}",
                )
            }
            SecureRandom().nextBytes(phoneNonce)
            progress.accept("AUTHENTICATING")
            sendCommand(
                XiaomiProto.Command.newBuilder().setType(1).setSubtype(26)
                    .setAuth(
                        XiaomiProto.Auth.newBuilder().setPhoneNonce(
                            XiaomiProto.PhoneNonce.newBuilder().setNonce(ByteString.copyFrom(phoneNonce)),
                        ),
                    ).build(),
            )
            val challenge = awaitCommand(1, 26)
            if (!challenge.hasAuth() ||
                !challenge.auth.hasWatchNonce() ||
                challenge.hasStatus() ||
                challenge.auth.hasStatus()
            ) {
                throw Failure("AUTH_CHALLENGE_REJECTED")
            }
            val watchNonce = challenge.auth.watchNonce
            val derived = XiaomiSessionCrypto.derive(binding.key, phoneNonce, watchNonce.nonce.toByteArray())
            session = derived
            binding.key.fill(0)
            if (!derived.verifyWatchProof(watchNonce.hmac.toByteArray())) {
                throw Failure("CREDENTIAL_REFRESH_REQUIRED")
            }
            val phoneInfo = XiaomiProto.AuthDeviceInfo.newBuilder()
                .setUnknown1(0)
                .setPhoneApiLevel(Build.VERSION.SDK_INT.toFloat())
                .setPhoneName(Build.MODEL)
                .setUnknown3(TransportObservation.withZenRuleSync(binding.capability))
                .setRegion(binding.region)
                .build()
            val confirmation = XiaomiProto.AuthStep3.newBuilder()
                .setEncryptedNonces(ByteString.copyFrom(derived.phoneProof()))
                .setEncryptedDeviceInfo(ByteString.copyFrom(derived.encryptPhoneInfo(phoneInfo.toByteArray())))
            // The actual captured V2 command has no Auth.userId field.
            sendCommand(
                XiaomiProto.Command.newBuilder().setType(1).setSubtype(27)
                    .setAuth(XiaomiProto.Auth.newBuilder().setAuthStep3(confirmation)).build(),
            )
            val confirmed = awaitCommand(1, 27)
            if (!confirmed.hasAuth() ||
                !confirmed.auth.hasAuthStep4() ||
                confirmed.auth.authStep4.unknown1 != 1 ||
                confirmed.hasStatus() ||
                confirmed.auth.hasStatus()
            ) {
                throw Failure("DEVICE_AUTHENTICATION_REJECTED")
            }
            derived.activate()
            authenticated = true
            progress.accept("DEVICE_CONFIRMED_AUTHENTICATION")
            sendCommand(XiaomiProto.Command.newBuilder().setType(2).setSubtype(2).build())
            val infoResponse = awaitCommand(2, 2)
            if (!infoResponse.hasSystem() || !infoResponse.system.hasDeviceInfo()) {
                throw Failure("DEVICE_INFO_MISSING")
            }
            val info = infoResponse.system.deviceInfo
            if (info.model.isBlank() || info.firmware.isBlank()) {
                throw Failure("FIRMWARE_OR_MODEL_UNSUPPORTED")
            }
            progress.accept("DEVICE_INFO_MATCHED")
            sendCommand(XiaomiProto.Command.newBuilder().setType(2).setSubtype(1).build())
            val batteryResponse = awaitCommand(2, 1)
            val battery = readBattery(batteryResponse)
            sendObservedClock()
            if (weather != null) {
                if (inspectCities) {
                    inspectWeatherCities(weather)
                    progress.accept("WEATHER_CITIES_READY")
                } else {
                    progress.accept("WEATHER_CITY_CHECK")
                    sendWeather(weather)
                    progress.accept("WEATHER_TRANSPORT_ACKED")
                }
            }
            val result = Result(
                battery.level(),
                battery.chargerState(),
                info.firmware,
                info.model,
                MiProductImage.sku(info.serialNumber),
            )
            verifiedDevice = result
            val stateListener = onState
            if (stateListener != null) {
                stateListener.accept(result)
                prepareHistory()
                liveHold = true
                val timeout = 10_000L
                val queue = LiveCommandQueue(sentSequence, timeout, ::writeLiveCommand)
                liveCommands = queue
                flushHeldActivity()
                onLiveReady?.accept(queue)
                progress.accept("LIVE_SESSION_OPEN")
                deadline.cancel(false)
                holdOpen()
                progress.accept("LIVE_SESSION_CLOSED")
            }
            return result
        } catch (error: Exception) {
            SessionLog.line(context, "session failed ${failureText(error)}${bondNow(binding)}")
            if (timedOut) throw Failure("DIAGNOSTIC_TIMEOUT")
            throw error
        } finally {
            deadline.cancel(false)
            watchdog.shutdownNow()
            close()
            session?.close()
            binding.key.fill(0)
            phoneNonce.fill(0)
            decoder.reset()
            v1Decoder.reset()
            for (payload in receivedPayloads) {
                payload?.fill(0)
            }
            observedLocations = null
            while (true) {
                val held = heldActivity.poll() ?: break
                held.fill(0)
            }
            ACTIVE.compareAndSet(this, null)
            finished.countDown()
        }
    }

    @Throws(Exception::class)
    private fun loadInputs(): Inputs {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            throw Failure("NEARBY_PERMISSION_REQUIRED")
        }
        if (context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true) {
            throw Failure("USER_LOCKED")
        }
        if (!OwnershipController(context).nativeReady()) throw Failure("NATIVE_OWNERSHIP_REQUIRED")
        val binding = BindingStore(context).read() ?: throw Failure("UNPROVISIONED")
        val observation = TransportObservation.liveProfile(context, binding.optString("model"))
        if (!TransportObservation.supportsLive(observation, binding.optString("model"))) {
            throw Failure("OBSERVED_PROFILE_REQUIRED")
        }
        if (observation.optBoolean("authOobPresent") ||
            observation.optBoolean("authAppDeviceIdPresent") ||
            binding.optString("oob", "").isNotEmpty()
        ) {
            throw Failure("OOB_BRANCH_UNSUPPORTED")
        }
        val problem = BindingShape.reject(binding)
        if (problem != null) throw Failure(problem)
        val address = binding.getString("address")
        val region = binding.getString("region")
        val token = binding.getString("token")
        val key = ByteArray(16)
        var i = 0
        while (i < token.length) {
            key[i / 2] = token.substring(i, i + 2).toInt(16).toByte()
            i += 2
        }
        return Inputs(
            address = address,
            region = region,
            key = key,
            capability = observation.getInt("appCapability"),
            transport = observation.optString("transport"),
            binding = binding,
            observation = observation,
        )
    }

    @Throws(Exception::class)
    private fun readExactly(size: Int): ByteArray {
        val inStream = checkNotNull(input)
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = inStream.read(bytes, offset, size - offset)
            if (count < 0) throw EOFException()
            if (count == 0) throw Failure("EMPTY_SOCKET_READ")
            offset += count
        }
        return bytes
    }

    @Throws(Exception::class)
    private fun readFrame(): SppV2Codec.Frame {
        val inStream = checkNotNull(input)
        while (pending.isEmpty()) {
            val count = inStream.read(readBuffer)
            if (count < 0) throw EOFException()
            if (count == 0) throw Failure("EMPTY_SOCKET_READ")
            pending.addAll(decoder.feed(readBuffer, 0, count))
        }
        if (!liveHold && ++frameCount > 512) throw Failure("UNEXPECTED_FRAME_VOLUME")
        return pending.removeFirst()
    }

    @Throws(Exception::class)
    private fun sendFrame(frame: SppV2Codec.Frame) {
        synchronized(writeLock) {
            val outStream = checkNotNull(output)
            outStream.write(SppV2Codec.encode(frame))
            outStream.flush()
        }
    }

    @Throws(Exception::class)
    private fun nextCommand(): XiaomiProto.Command? {
        if (framing == 0) {
            while (true) {
                val frame = readFrame()
                if (frame.type() == SppV2Codec.TYPE_ACK) {
                    liveCommands?.onAck(frame.sequence())
                    continue
                }
                if (frame.type() == SppV2Codec.TYPE_NAK) {
                    SessionLog.line(context, "rx spp2 nak seq=${frame.sequence()}")
                    continue
                }
                return decodeAndAck(frame)
            }
        }
        if (framing == 2) return readBle()
        return decodeV1(readV1Packet())
    }

    /**
     * m66nfc 1.8.124 keeps a live BLE session through silence. The firmware logs
     * an explicit "miwear app disconnect" packet, or a link supervision loss, which
     * surfaces here as BLE_DISCONNECTED. ios_keep_alive_adv only restarts advertising
     * while disconnected. A 30s read limit was tearing down every idle session.
     */
    @Throws(Exception::class)
    private fun readBle(): XiaomiProto.Command? {
        while (true) {
            if (timedOut) throw Failure("DIAGNOSTIC_TIMEOUT")
            // close() clears the field. The handshake watchdog does that from another thread.
            val link = ble ?: throw Failure(if (closed) "CANCELLED" else "BLE_DISCONNECTED")
            try {
                return decodeBle(link.take(if (liveHold) 30_000L else 15_000L))
            } catch (failure: Failure) {
                if (timedOut) throw Failure("DIAGNOSTIC_TIMEOUT")
                if (!liveHold || closed || failure.code != "BLE_READ_TIMEOUT") throw failure
            }
        }
    }

    @Throws(Exception::class)
    private fun readV1Packet(): SppV1Codec.Packet {
        val inStream = checkNotNull(input)
        while (v1Pending.isEmpty()) {
            val count = inStream.read(readBuffer)
            if (count < 0) throw EOFException()
            if (count == 0) throw Failure("EMPTY_SOCKET_READ")
            v1Pending.addAll(v1Decoder.feed(readBuffer, 0, count))
        }
        if (!liveHold && ++frameCount > 512) throw Failure("UNEXPECTED_FRAME_VOLUME")
        return v1Pending.removeFirst()
    }

    @Throws(Exception::class)
    private fun decodeV1(packet: SppV1Codec.Packet): XiaomiProto.Command? {
        if (packet.channel() == SppV1Codec.CHANNEL_VERSION) return null
        var payload = packet.payload()
        if (packet.channel() == SppV1Codec.CHANNEL_FITNESS) {
            var fitness = payload
            if (packet.dataType() == SppV1Codec.DATA_ENCRYPTED) {
                try {
                    fitness = checkNotNull(session).decryptV1(payload)
                } catch (_: RuntimeException) {
                    SessionLog.line(context, "rx spp1 fitness decrypt failed len=${payload.size}")
                    throw Failure("V1_DECRYPT_FAILED")
                }
            }
            acceptV1Activity(fitness)
            if (fitness !== payload) fitness.fill(0)
            return null
        }
        if (packet.channel() == SppV1Codec.CHANNEL_MASS) {
            var mass = payload
            if (packet.dataType() == SppV1Codec.DATA_ENCRYPTED) {
                mass = checkNotNull(session).decryptV1(payload)
            }
            acceptDeviceLogMass(mass)
            if (mass !== payload) mass.fill(0)
            return null
        }
        if (packet.channel() != SppV1Codec.CHANNEL_PROTO_RX &&
            packet.channel() != SppV1Codec.CHANNEL_PROTO_TX
        ) {
            return null
        }
        if (packet.dataType() == SppV1Codec.DATA_ENCRYPTED) {
            try {
                payload = checkNotNull(session).decryptV1(payload)
            } catch (_: RuntimeException) {
                SessionLog.line(
                    context,
                    "rx decrypt failed channel=${packet.channel()} dataType=${packet.dataType()}" +
                        " len=${packet.payload().size}",
                )
                throw Failure("V1_DECRYPT_FAILED")
            }
        }
        return publishParsed(payload)
    }

    @Throws(Exception::class)
    private fun decodeBle(packet: BleGattClient.Rx?): XiaomiProto.Command? {
        if (packet == null || packet.payload.isEmpty()) return null
        val payload = packet.payload
        if (packet.mass) {
            var plain = payload
            try {
                if (authenticated) plain = decryptBle(payload)
                acceptDeviceLogMass(plain)
            } catch (_: Exception) {
                SessionLog.line(context, "rx ble mass rejected len=${payload.size}")
                deviceLogReceiver?.fail("MASS_PACKET_INVALID")
            } finally {
                if (plain !== payload) plain.fill(0)
            }
            return null
        }
        if (packet.activity) {
            var plain = payload
            try {
                if (authenticated) plain = decryptBle(payload)
                acceptV1Activity(plain)
            } catch (_: Exception) {
                SessionLog.line(context, "rx ble activity decrypt failed len=${payload.size}")
            } finally {
                if (plain !== payload) plain.fill(0)
            }
            return null
        }
        var plain = payload
        return try {
            if (authenticated) plain = decryptBle(payload)
            publishParsed(plain)
        } catch (failed: Exception) {
            SessionLog.line(context, "rx ble command rejected len=${payload.size} ${failed.javaClass.simpleName}")
            null
        }
    }

    /** Watch single-frame replies put an encryption flag in front of ciphertext. Nonce counter stays 0. */
    @Throws(Exception::class)
    private fun decryptBle(payload: ByteArray): ByteArray {
        val currentSession = checkNotNull(session)
        return try {
            currentSession.decryptV1(payload)
        } catch (_: RuntimeException) {
            if (payload.size > 5 && payload[0] == 1.toByte()) {
                try {
                    return currentSession.decryptV1(payload.copyOfRange(1, payload.size))
                } catch (_: RuntimeException) {
                    SessionLog.line(context, "rx ble decrypt failed len=${payload.size}")
                    throw Failure("V1_DECRYPT_FAILED")
                }
            }
            SessionLog.line(context, "rx ble decrypt failed len=${payload.size}")
            throw Failure("V1_DECRYPT_FAILED")
        }
    }

    @Throws(Exception::class)
    private fun publishParsed(plaintext: ByteArray): XiaomiProto.Command {
        val command = XiaomiProto.Command.parseFrom(plaintext)
        if (command.type == 10 &&
            command.hasSubtype() &&
            command.subtype == 5 &&
            command.hasWeather() &&
            command.weather.hasLocations()
        ) {
            observedLocations = command.weather.locations
        }
        publishBattery(command)
        return command
    }

    private fun acceptV1Activity(payload: ByteArray?) {
        if (payload == null) return
        // The fitness characteristic is notified as soon as the link is up, which is before
        // device info has created the parser and before file confirms can be sent. The BLE
        // payload ACK is already out, so dropping the bytes here makes the band wait for a
        // type-8 confirm that never comes and then push the file again on the next link.
        val parser = history
        if (parser == null || liveCommands == null) {
            if (heldActivity.size >= 32) {
                SessionLog.line(context, "rx activity held dropped len=${payload.size}")
                return
            }
            heldActivity.add(payload.copyOf())
            SessionLog.line(context, "rx activity held len=${payload.size}")
            return
        }
        val result = try {
            val parsed = parser.acceptFragment(payload)
            progress.accept("HISTORY_FRAGMENT_RECEIVED")
            parsed
        } catch (malformed: IllegalArgumentException) {
            parser.reset()
            progress.accept("HISTORY_FILE_REJECTED")
            SessionLog.line(context, "rx spp1 activity rejected len=${payload.size} ${malformed.message}")
            return
        }
        if (result != null) {
            val fileId = archiveActivity(result)
            if (fileId != null) ackActivity(fileId)
        }
    }

    private fun flushHeldActivity() {
        while (true) {
            val next = heldActivity.poll() ?: break
            try {
                acceptV1Activity(next)
            } finally {
                next.fill(0)
            }
        }
    }

    private fun acceptDeviceLogMass(payload: ByteArray?) {
        val receiver = deviceLogReceiver
        if (receiver == null) {
            SessionLog.line(context, "rx mass without device-log receiver bytes=${payload?.size ?: 0}")
            return
        }
        receiver.accept(payload)
        if (receiver.completion().toCompletableFuture().isDone) deviceLogReceiver = null
    }

    @Throws(Exception::class)
    private fun sendCommand(command: XiaomiProto.Command) {
        writeCommand(sentSequence, command)
        sentSequence = (sentSequence + 1) and 255
    }

    @Throws(Exception::class)
    private fun writeLiveCommand(sequence: Int, command: XiaomiProto.Command) {
        try {
            if (closed) throw Failure("CANCELLED")
            writeCommand(sequence, command)
            // SPP V1 and BLE have no transport ACK. Gadgetbridge completes the write
            // itself; request() still waits for the matching protobuf response.
            // sentSequence is the handshake cursor and is not advanced by live writes.
            if (framing != 0) liveCommands?.onAck(sequence)
        } catch (failure: Exception) {
            // A missed icon chunk ACK must not drop the link. The radio is still delivering
            // commands during that wait; closing here is the Band 8 notification disconnect.
            if (failure is Failure && failure.code == "ICON_CHUNK_TIMEOUT") throw failure
            linkStop = if (failure is Failure) failure.code else failure.javaClass.simpleName
            SessionLog.line(context, "link stop $linkStop")
            close()
            throw failure
        }
    }

    @Throws(Exception::class)
    private fun writeCommand(sequence: Int, command: XiaomiProto.Command) {
        val chunk = synchronized(iconChunks) { iconChunks.remove(command) }
        if (chunk != null) {
            try {
                writeIconChunk(sequence, chunk)
            } catch (failure: Failure) {
                if (failure.code == "BLE_CHUNK_ACK_TIMEOUT") throw Failure("ICON_CHUNK_TIMEOUT")
                throw failure
            }
            return
        }
        var bytes = command.toByteArray()
        val plainLength = bytes.size
        OwnershipController.beginNativeSession()
        try {
            if (!OwnershipController(context).nativeReady()) throw Failure("NATIVE_OWNERSHIP_LOST")
            if (framing == 2) {
                var counter = 0
                if (authenticated) {
                    counter = takeV1Counter()
                    bytes = checkNotNull(session).encryptV1(bytes, counter)
                }
                // BLE V1 carries complete commands through MTU-sized chunks. The 512-byte
                // notification budget applies to notification construction; the codec validates
                // the chunk count for larger commands such as daily and hourly forecasts.
                if (!commandFitsPayloadLimit(framing, bytes.size, peerPayloadLimit)) {
                    throw Failure("COMMAND_EXCEEDS_NEGOTIATED_SIZE")
                }
                SessionLog.line(
                    context,
                    "tx ble type=${command.type} subtype=${command.subtype}" +
                        " auth=$authenticated counter=$counter plain=$plainLength cipher=${bytes.size}",
                )
                val client = ble ?: throw Failure("BLE_DISCONNECTED")
                client.write(bytes, authenticated, counter)
                return
            }
            if (framing == 1) {
                var dataType = SppV1Codec.DATA_PLAIN
                var counter = 0
                if (!authenticated && command.type == 1 && command.subtype >= 17) {
                    dataType = SppV1Codec.DATA_AUTH
                } else if (authenticated) {
                    dataType = SppV1Codec.DATA_ENCRYPTED
                    counter = takeV1Counter()
                    bytes = SppV1Codec.sealEncrypted(counter, checkNotNull(session).encryptV1(bytes, counter))
                }
                if (!commandFitsPayloadLimit(framing, bytes.size, peerPayloadLimit)) {
                    throw Failure("COMMAND_EXCEEDS_NEGOTIATED_SIZE")
                }
                SessionLog.line(
                    context,
                    "tx spp1 type=${command.type} subtype=${command.subtype}" +
                        " dataType=$dataType counter=$counter plain=$plainLength wire=${bytes.size}",
                )
                synchronized(writeLock) {
                    val outStream = checkNotNull(output)
                    outStream.write(SppV1Codec.encode(SppV1Codec.protobuf(sequence, dataType, bytes)))
                    outStream.flush()
                }
                return
            }
            if (authenticated) bytes = checkNotNull(session).encryptV2(bytes)
            if (!commandFitsPayloadLimit(framing, bytes.size, peerPayloadLimit)) {
                throw Failure("COMMAND_EXCEEDS_NEGOTIATED_SIZE")
            }
            SessionLog.line(
                context,
                "tx spp2 type=${command.type} subtype=${command.subtype}" +
                    " auth=$authenticated plain=$plainLength wire=${bytes.size}",
            )
            synchronized(writeLock) {
                sendFrame(
                    SppV2Codec.dataFrame(
                        sequence,
                        SppV2Codec.CHANNEL_PROTOBUF,
                        if (authenticated) SppV2Codec.OPCODE_ENCRYPTED else SppV2Codec.OPCODE_PLAINTEXT,
                        bytes,
                    ),
                )
            }
        } finally {
            OwnershipController.endNativeSession()
        }
    }

    /** SPP data channel is plaintext. BLE reuses the command characteristic framing on the upload characteristic. */
    @Throws(Exception::class)
    private fun writeIconChunk(sequence: Int, chunk: ByteArray) {
        OwnershipController.beginNativeSession()
        try {
            if (!OwnershipController(context).nativeReady()) throw Failure("NATIVE_OWNERSHIP_LOST")
            if (framing == 2) {
                var counter = 0
                var body = chunk
                if (authenticated) {
                    counter = takeV1Counter()
                    body = checkNotNull(session).encryptV1(chunk, counter)
                }
                SessionLog.line(context, "tx ble icon part bytes=${chunk.size} counter=$counter")
                val client = ble ?: throw Failure("BLE_DISCONNECTED")
                client.writeUpload(body, authenticated, counter)
                return
            }
            if (chunk.size + 2 > peerPayloadLimit) throw Failure("ICON_CHUNK")
            if (framing == 1) {
                SessionLog.line(context, "tx spp1 icon part bytes=${chunk.size}")
                synchronized(writeLock) {
                    val outStream = checkNotNull(output)
                    outStream.write(
                        SppV1Codec.encode(
                            SppV1Codec.Packet(
                                SppV1Codec.CHANNEL_MASS,
                                true,
                                false,
                                SppV1Codec.OPCODE_SEND,
                                sequence,
                                SppV1Codec.DATA_PLAIN,
                                chunk,
                            ),
                        ),
                    )
                    outStream.flush()
                }
                return
            }
            SessionLog.line(context, "tx spp2 icon part bytes=${chunk.size}")
            sendFrame(
                SppV2Codec.dataFrame(
                    sequence,
                    SppV2Codec.CHANNEL_DATA,
                    SppV2Codec.OPCODE_PLAINTEXT,
                    chunk,
                ),
            )
        } finally {
            OwnershipController.endNativeSession()
        }
    }

    private fun bondNow(binding: Inputs): String {
        return try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: return " bond=adapter-null"
            val state = adapter.getRemoteDevice(binding.address).bondState
            " bond=$state/${bondName(state)}"
        } catch (_: RuntimeException) {
            " bond=unread"
        }
    }

    @Throws(Exception::class)
    private fun awaitCommand(type: Int, subtype: Int): XiaomiProto.Command {
        while (true) {
            val command = nextCommand()
            if (command != null && command.type == type &&
                command.hasSubtype() && command.subtype == subtype
            ) {
                return command
            }
        }
    }

    @Throws(Exception::class)
    private fun decodeAndAck(frame: SppV2Codec.Frame): XiaomiProto.Command? {
        if (frame.type() != SppV2Codec.TYPE_DATA) throw Failure("UNEXPECTED_SESSION_FRAME")
        val sequence = frame.sequence()
        val rawPayload = frame.payload()
        if (peerPayloadLimit <= 0 || rawPayload.size > peerPayloadLimit) {
            throw Failure("RECEIVE_SIZE_EXCEEDS_NEGOTIATED_LIMIT")
        }
        val channel = if (rawPayload.isEmpty()) -1 else rawPayload[0].toInt() and 0x0f
        val expected = receive.expected()
        val prevPayload = receivedPayloads[sequence]
        if (!frame.frx() &&
            channel != 7 &&
            channel != 10 &&
            sequence != expected &&
            receivedAt[sequence] >= 0 &&
            receive.count() - receivedAt[sequence] <= 32 &&
            prevPayload != null &&
            prevPayload.contentEquals(rawPayload)
        ) {
            sendFrame(SppV2Codec.Frame(SppV2Codec.TYPE_ACK, sequence, ByteArray(0)))
            return null
        }
        val decision = receive.offer(sequence, frame.frx(), channel)
        if (decision == SppV2Codec.ReceiveCursor.Decision.DROP) {
            SessionLog.line(context, "rx spp2 seq=$sequence expected=$expected channel=$channel action=drop")
            return null
        }
        if (decision == SppV2Codec.ReceiveCursor.Decision.NAK) {
            SessionLog.line(context, "rx spp2 seq=$sequence expected=$expected channel=$channel action=nak")
            sendFrame(SppV2Codec.Frame(SppV2Codec.TYPE_NAK, expected, ByteArray(0)))
            return null
        }
        if (sequence != expected) {
            SessionLog.line(
                context,
                "rx spp2 seq=$sequence expected=$expected channel=$channel frx=${frame.frx()} action=resync",
            )
        }
        val data = SppV2Codec.parseData(frame)
        if (data.flags() != 0) throw Failure("UNSUPPORTED_DATA_CHANNEL")
        if (data.channel() == SppV2Codec.CHANNEL_ACTIVITY) {
            acceptActivity(data, sequence, rawPayload)
            return null
        }
        if (data.channel() == SppV2Codec.CHANNEL_DATA) {
            val plain = when (data.opcode()) {
                SppV2Codec.OPCODE_ENCRYPTED -> {
                    if (!authenticated) throw Failure("ENCRYPTION_STATE_MISMATCH")
                    checkNotNull(session).decryptV2(data.payload())
                }
                SppV2Codec.OPCODE_PLAINTEXT -> data.payload()
                else -> throw Failure("UNSUPPORTED_DATA_CHANNEL")
            }
            try {
                acceptDeviceLogMass(plain)
            } finally {
                plain.fill(0)
            }
            receivedPayloads[sequence]?.fill(0)
            receivedPayloads[sequence] = rawPayload
            receivedAt[sequence] = receive.count() - 1
            sendFrame(SppV2Codec.Frame(SppV2Codec.TYPE_ACK, sequence, ByteArray(0)))
            return null
        }
        if (data.channel() != SppV2Codec.CHANNEL_PROTOBUF) {
            throw Failure("UNSUPPORTED_DATA_CHANNEL")
        }
        val expectedOpcode = if (authenticated) SppV2Codec.OPCODE_ENCRYPTED else SppV2Codec.OPCODE_PLAINTEXT
        if (data.opcode() != expectedOpcode) throw Failure("ENCRYPTION_STATE_MISMATCH")
        val plaintext = if (authenticated) checkNotNull(session).decryptV2(data.payload()) else data.payload()
        val command = XiaomiProto.Command.parseFrom(plaintext)
        if (command.type == 10 &&
            command.hasSubtype() &&
            command.subtype == 5 &&
            command.hasWeather() &&
            command.weather.hasLocations()
        ) {
            observedLocations = command.weather.locations
        }
        receivedPayloads[sequence]?.fill(0)
        receivedPayloads[sequence] = rawPayload
        receivedAt[sequence] = receive.count() - 1
        sendFrame(SppV2Codec.Frame(SppV2Codec.TYPE_ACK, sequence, ByteArray(0)))
        publishBattery(command)
        return command
    }

    @Throws(Exception::class)
    private fun prepareHistory() {
        val binding = BindingStore(context).readIdentity()
        var identity = binding.did()
        if (identity.isBlank()) identity = binding.address().replace(":", "")
        historyDeviceId = binding.deviceId()
        val device = checkNotNull(verifiedDevice)
        history = BandHistoryParser(device.firmware, historyDeviceId, identity)
        rawHistory = RawFitnessFileStore(context)
    }

    @Throws(Exception::class)
    private fun acceptActivity(data: SppV2Codec.Data, sequence: Int, rawPayload: ByteArray) {
        var result: BandHistoryParser.FileResult? = null
        val parser = history
        if (parser == null) {
            SessionLog.line(context, "rx spp2 activity before history seq=$sequence")
        } else if (data.opcode() != SppV2Codec.OPCODE_ENCRYPTED) {
            throw Failure("UNSUPPORTED_DATA_CHANNEL")
        } else {
            val plain = checkNotNull(session).decryptV2(data.payload())
            try {
                result = parser.acceptFragment(plain)
                progress.accept("HISTORY_FRAGMENT_RECEIVED")
            } catch (_: IllegalArgumentException) {
                parser.reset()
                progress.accept("HISTORY_FILE_REJECTED")
            } finally {
                plain.fill(0)
            }
        }
        val fileId = if (result == null) null else archiveActivity(result)
        receivedPayloads[sequence]?.fill(0)
        receivedPayloads[sequence] = rawPayload
        receivedAt[sequence] = receive.count() - 1
        sendFrame(SppV2Codec.Frame(SppV2Codec.TYPE_ACK, sequence, ByteArray(0)))
        if (fileId != null) ackActivity(fileId)
    }

    /** Persist before any band confirm. Unsupported files are still acked so the band can send the next one. */
    private fun archiveActivity(result: BandHistoryParser.FileResult): ByteArray? {
        val fileId = try {
            val store = checkNotNull(rawHistory)
            val device = checkNotNull(verifiedDevice)
            val deviceId = checkNotNull(historyDeviceId)
            store.persist(result, deviceId, device.firmware, System.currentTimeMillis())
            val id = result.copyFileId()
            progress.accept("HISTORY_FILE_ARCHIVED")
            id
        } catch (storageFailed: Exception) {
            progress.accept("HISTORY_STORAGE_FAILED")
            val reason = storageFailed.message
            val suffix = if (reason != null && REASON_REGEX.matches(reason)) " $reason" else ""
            SessionLog.line(context, "history storage failed ${storageFailed.javaClass.simpleName}$suffix")
            return null
        }
        if (result.parseStatus == "PARSED") {
            try {
                summarizeSport(result.measurements)
            } catch (_: RuntimeException) {
                progress.accept("HEALTH_SNAPSHOT_PAUSED")
            }
            val sleep = onSleepFile
            if (sleep != null) {
                for (measurement in result.measurements) {
                    if (measurement.kind == "sleep_interval") {
                        sleep.accept(result.measurements)
                        break
                    }
                }
            }
        } else {
            progress.accept("HISTORY_FORMAT_UNSUPPORTED")
            SessionLog.line(context, "history ${result.parseStatus}")
        }
        return fileId
    }

    /** Type 8 subtype 5. SPP V1 and BLE have no transport ACK, so this confirm is what releases the next file. */
    private fun ackActivity(fileId: ByteArray) {
        val commands = liveCommands ?: return
        commands.send(
            XiaomiProto.Command.newBuilder().setType(8).setSubtype(5)
                .setHealth(
                    XiaomiProto.Health.newBuilder()
                        .setActivitySyncAckFileIds(ByteString.copyFrom(fileId)),
                ).build(),
        ).whenComplete { _, error ->
            if (error == null) {
                progress.accept("HISTORY_FILE_STORED")
                onFileStored?.accept(fileId)
            } else {
                progress.accept("HISTORY_CONFIRMATION_PENDING")
            }
        }
    }

    private fun summarizeSport(measurements: List<BandHistoryParser.Measurement>) {
        val zone = ZoneId.systemDefault()
        val dayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        var heart = -1
        var heartAt = -1L
        for (measurement in measurements) {
            val value = measurement.value ?: continue
            if (measurement.kind != "heart_rate" ||
                !(measurement.measurementMode == "continuous" || measurement.measurementMode == "manual") ||
                measurement.startMs <= heartAt
            ) {
                continue
            }
            heart = value.toInt()
            heartAt = measurement.startMs
        }
        val repository = BandStateRepository(context)
        val steps = BandHistoryParser.todaySteps(measurements, dayStart, dayEnd)
        if (steps != null) repository.recordDailySteps(steps.steps, steps.measuredAtMs, steps.authoritative)
        if (heartAt >= 0) repository.recordSport(-1, heart, heartAt)
    }

    @Throws(Exception::class)
    private fun inspectWeatherCities(weather: BandWeatherEncoder.Sample) {
        if (observedLocations == null) {
            sendCommand(XiaomiProto.Command.newBuilder().setType(10).setSubtype(5).build())
            awaitCommand(10, 5)
        }
        val locations = observedLocations ?: throw Failure("WEATHER_CITY_SELECTION_REQUIRED")
        val prefs = context.getSharedPreferences("weather-city-review", 0).edit()
            .putString("sourceKey", weather.locationKey)
            .putString("sourceCity", weather.cityName)
            .putString("sourcePlace", weather.locationName)
        var index = 0
        for (city in locations.locationList) {
            if (!BandWeatherEncoder.acceptableCityCode(city.code) || !city.hasName() ||
                city.name.isBlank()
            ) {
                continue
            }
            prefs.putString("bandCode$index", city.code).putString("bandName$index", city.name)
            index++
            if (index == 4) break
        }
        prefs.putInt("bandCount", index)
        for (extra in index until 4) prefs.remove("bandCode$extra").remove("bandName$extra")
        if (!prefs.commit()) throw Failure("WEATHER_CITY_STORAGE_FAILED")
    }

    @Throws(Exception::class)
    private fun sendWeather(weather: BandWeatherEncoder.Sample) {
        if (observedLocations == null) {
            sendCommand(XiaomiProto.Command.newBuilder().setType(10).setSubtype(5).build())
            awaitCommand(10, 5)
        }
        val bound = try {
            val association = context.getSharedPreferences("weather-city-review", Context.MODE_PRIVATE)
            BandWeatherEncoder.bindExplicitCity(
                weather,
                association.getString("confirmedSourceKey", ""),
                association.getString("confirmedSourceCity", ""),
                association.getString("confirmedSourcePlace", ""),
                association.getString("confirmedBandCode", ""),
                association.getString("confirmedBandName", ""),
                observedLocations,
            )
        } catch (invalid: IllegalArgumentException) {
            throw Failure(invalid.message ?: "WEATHER_CITY_CONFIRMATION_INVALID")
        }
        val commands = BandWeatherEncoder.encode(bound)
        progress.accept("WEATHER_SENDING")
        // Preserve the other configured cities; 10/6 would replace the whole list.
        for (command in commands) {
            val expectedAck = sentSequence
            sendCommand(command)
            awaitTransportAck(expectedAck)
        }
    }

    @Throws(Exception::class)
    private fun sendObservedClock() {
        val clockAck = sentSequence
        sendCommand(
            BandClockCommand.at(Instant.now(), ZoneId.systemDefault(), DateFormat.is24HourFormat(context)),
        )
        awaitTransportAck(clockAck)
        progress.accept("CLOCK_ACKED")
    }

    @Throws(Exception::class)
    private fun awaitTransportAck(sequence: Int) {
        if (framing != 0) return
        while (true) {
            val frame = readFrame()
            if (frame.type() == SppV2Codec.TYPE_ACK) {
                if (frame.sequence() == sequence) return
                continue
            }
            if (frame.type() == SppV2Codec.TYPE_NAK) {
                SessionLog.line(context, "rx spp2 nak seq=${frame.sequence()}")
                continue
            }
            val command = decodeAndAck(frame)
            if (command != null && command.type == 10 && command.hasStatus() && command.status != 0) {
                throw Failure("WEATHER_DEVICE_REJECTED")
            }
        }
    }

    @Throws(Exception::class)
    private fun holdOpen() {
        liveHold = true
        linkStop = null
        OwnershipController.endNativeSession()
        try {
            while (!closed) {
                if (framing == 0) {
                    val frame = readFrame()
                    when (frame.type()) {
                        SppV2Codec.TYPE_ACK -> liveCommands?.onAck(frame.sequence())
                        SppV2Codec.TYPE_NAK -> SessionLog.line(context, "rx spp2 nak seq=${frame.sequence()}")
                        else -> {
                            val command = decodeAndAck(frame)
                            if (command != null) {
                                onLiveCommand?.accept(command)
                                liveCommands?.onCommand(command)
                            }
                        }
                    }
                } else {
                    val command = nextCommand()
                    if (command != null) {
                        // Transport ack already happened in writeLiveCommand. sentSequence is stale here.
                        onLiveCommand?.accept(command)
                        liveCommands?.onCommand(command)
                    }
                }
            }
            // close() wakes the reader with an empty packet, so a failed write used to
            // leave this loop without a trace and the supervisor treated it as a clean end.
            val stop = linkStop
            if (stop != null) throw Failure(stop)
        } catch (error: Exception) {
            val stop = linkStop
            if (stop != null) throw Failure(stop)
            if (!closed) throw error
        } finally {
            OwnershipController.beginNativeSession()
        }
    }

    @Throws(Exception::class)
    private fun publishBattery(command: XiaomiProto.Command?) {
        val stateListener = onState
        val device = verifiedDevice
        if (!liveHold ||
            stateListener == null ||
            device == null ||
            command == null ||
            command.type != 2 ||
            !command.hasSubtype() ||
            command.subtype != 1
        ) {
            return
        }
        val battery = readBattery(command)
        stateListener.accept(
            Result(
                battery.level(),
                battery.chargerState(),
                device.firmware,
                device.hardware,
                device.sku,
            ),
        )
    }

    /**
     * An RFCOMM connect carries no timeout of its own: after the adapter cycles, the call can sit
     * in the stack for a minute while the band is reachable again. A watchdog closes the socket
     * instead, so the retry loop runs again right away.
     */
    @Throws(Exception::class)
    private fun connectWithTimeout(target: BluetoothSocket) {
        val failure = AtomicReference<Throwable?>()
        val connector = Thread({
            try {
                target.connect()
            } catch (error: Throwable) {
                failure.set(error)
            }
        }, "band-spp-connect")
        connector.isDaemon = true
        connector.start()
        connector.join(CONNECT_TIMEOUT_MS)
        if (connector.isAlive) {
            try {
                target.close()
            } catch (_: Throwable) {
            }
            SessionLog.line(context, "spp connect timeout")
            throw Failure("CONNECT_TIMEOUT")
        }
        val error = failure.get()
        if (error is Exception) throw error
        if (error != null) throw IllegalStateException(error)
    }

    override fun close() {
        cancelDeviceLogReceive("MASS_RECEIVE_CANCELLED")
        liveCommands?.close(IllegalStateException("SESSION_CLOSED"))
        val closing: BluetoothSocket?
        val closingGatt: BleGattClient?
        synchronized(this) {
            closed = true
            closing = socket
            socket = null
            closingGatt = ble
            ble = null
        }
        if (closing != null) {
            try {
                closing.close()
            } catch (_: Exception) {
                /* Socket closure cannot expose credentials. */
            }
        }
        closingGatt?.close()
    }
}
