// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.WorkerThread
import com.originband.link.data.BandCatalog
import com.originband.link.data.BandStateRepository
import com.originband.link.data.DeviceImageStore
import com.originband.link.data.HealthRecordStore
import com.originband.link.data.LocalPrefs
import com.originband.link.data.SessionLog
import com.originband.link.host.HostIdentity
import com.originband.link.integration.DeviceCardProvider
import com.originband.link.integration.PhoneAlarmProvider
import com.originband.link.notify.BandDndSync
import com.originband.link.notify.BandNotificationListener
import com.originband.link.notify.FindPhone
import com.originband.link.notify.HealthListenerState
import com.originband.link.notify.NativeMusic
import com.originband.link.notify.NotificationIcons
import com.originband.link.notify.NotifyDedupe
import com.originband.link.notify.NotifyReplay
import com.originband.link.notify.PhoneCallMonitor
import com.originband.link.notify.PhoneDnd
import com.originband.link.notify.PhoneMusic
import com.originband.link.notify.SleepMusic
import com.originband.link.notify.WatchCallHeadset
import com.originband.link.notify.WatchCallRoute
import com.originband.link.protocol.BandDndCommand
import com.originband.link.protocol.BandHistoryParser
import com.originband.link.protocol.BandLogCommand
import com.originband.link.protocol.BandMusicCommand
import com.originband.link.protocol.BandNotificationCommand
import com.originband.link.protocol.BandSystemCommand
import com.originband.link.protocol.BandWeatherEncoder
import com.originband.link.protocol.LiveCommandQueue
import com.originband.link.protocol.NotificationIconPixels
import com.originband.link.protocol.NotificationIconUpload
import com.originband.link.protocol.SppDiagnosticClient
import com.originband.link.protocol.XiaomiMassReceive
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer
import kotlin.math.max
import kotlin.math.min
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

/** Keeps one authenticated SPP session for the whole NATIVE ownership period and reconnects after the link drops. */
class BandLiveService : Service() {
    @Volatile
    private var stopped = CountDownLatch(0)
    private val destroyed = CountDownLatch(1)
    private val coordinator: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "OplusBandCoordinator") }
    @Volatile
    private var commands: LiveCommandQueue? = null
    private var historySync: LiveHistorySync? = null
    @Volatile
    private var syncRequested = false
    @Volatile
    private var realtimeStatsStopPending = false

    /**
     * Last sign of life from the supervisor. ColorOS freezing the process, or a dead worker
     * thread, used to leave the service foreground on 「正在连接手环」 for hours with no retry.
     */
    @Volatile
    private var supervisorBeatMs = 0L
    private var nextBatteryAt = 0L
    private var nextHealthAt = 0L
    private var nextWeatherAt = 0L
    private lateinit var weatherSync: WeatherSync
    private lateinit var healthReplay: HealthReplay
    @Volatile
    private var sessionEpoch = 0L
    private var calls: PhoneCallMonitor? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile
    private var healthBound = false
    private val healthConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            healthHostStatus("READY")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            healthHostStatus("UNAVAILABLE")
        }

        override fun onBindingDied(name: ComponentName?) {
            unbindHealthHost()
            healthHostStatus("UNAVAILABLE")
            reviveHealth()
        }

        override fun onNullBinding(name: ComponentName?) {
            unbindHealthHost()
            healthHostStatus("UNAVAILABLE")
        }
    }
    private val iconPackages = ArrayDeque<String>()
    private val iconUploadBusy = AtomicBoolean()
    @Volatile
    private var iconPackage: String? = null
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OplusBandLive").apply { isDaemon = false }
    }
    private val running = AtomicBoolean()
    private val stopLock = Object()
    @Volatile
    private var stopRequested = false
    @Volatile
    private var lastDndSentNanos = 0L
    private var lastDndFilter = Int.MIN_VALUE
    private var dndSyncAtNanos = 0L
    private val dndSyncLock = Object()
    private val dndRulesAgain = Runnable { sendDndRulesAgain() }
    @Volatile
    private var dndPushPhone = false
    @Volatile
    private var dndBandKnown = false
    @Volatile
    private var dndBandOn = false
    private val dndAdopted = AtomicBoolean()

    /** The band's own 同步手机勿扰 switch. Off is the user's choice there, and it outlives a link. */
    private val dndSync = BandDndSync()
    private val dndAskFallback = Runnable { dndAskTimedOut() }

    /** Last value written to prefs, so a reply from every mirror does not hit the disk. */
    @Volatile
    private var dndSyncStored: Boolean? = null
    private val sleepMusic = SleepMusic()
    @Volatile
    private var sleepPauseOn = false
    @Volatile
    private var sleepArmedAtMs = Long.MAX_VALUE
    @Volatile
    private var bandAsleep: Boolean? = null
    @Volatile
    private var sleepStateUnsupported = false
    private var sleepStateMisses = 0
    @Volatile
    private var waitingForInitialSleepState = false
    @Volatile
    private var historySyncPending = false
    @Volatile
    private var pendingHistoryIncludesHistory = false
    @Volatile
    private var historyCooldownUntilMs = 0L
    private var historyStateDeadlineAt = 0L
    private var nextSleepStateAt = 0L
    private var sessionStartAtMs = 0L
    @Volatile
    private var lastNightFlushAtMs = 0L
    private var awakeSamples = 0
    private var nextNightStatsAt = 0L

    /*
     * The quiet tracker is owned by the single coordinator thread. volatile keeps reads safe for
     * diagnostics while all updates remain serialized with tick(), noteSleep(), and flush.
     */
    @Volatile
    private var nightHadQuiet = false
    private var quietStepsValue = Long.MIN_VALUE
    private var quietSinceMs = 0L
    private var quietCheckedAtMs = 0L
    private var quietBaselineSteps = -1L
    private var quietSampleAtMs = 0L
    @Volatile
    private var quietSignalStale = false
    @Volatile
    private var nightQuietResult = false
    private val wakeEvidence = NightWakeEvidence()
    private var wakeEvidenceCheckedAtMs = 0L
    @Volatile
    private var retryNow = false

    /** SPP needs the system bond. This keeps the re-pair budget for one bond loss. */
    private val bondRecovery = BondRecovery()
    private var bondGaveUpLogged = false

    /** True while the notice offers the 重新配对 action after the single automatic ask. */
    @Volatile
    private var bondRepairOffered = false

    /** The action on the currently posted notice, to keep the dedupe check honest. */
    private var bondRepairShown = false
    private var noticeTitle: String? = ""
    @Volatile
    private var client: SppDiagnosticClient? = null
    private var receiverRegistered = false
    private var dndReceiverRegistered = false

    private val bluetoothEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent == null) return
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED == intent.action) {
                noteBond(context, intent)
                return
            }
            if (BluetoothAdapter.ACTION_STATE_CHANGED != intent.action) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            SessionLog.line(context, "bt adapter state=$state/${adapterState(state)}")
            if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                val active = client
                active?.close()
                // The system sends this broadcast. Clear the card immediately; the socket
                // thread may still be blocked in a read when the adapter is powered off.
                try {
                    BandStateRepository(context).markSessionClosed()
                } catch (ignored: RuntimeException) {
                }
            } else if (state == BluetoothAdapter.STATE_ON) {
                synchronized(stopLock) {
                    retryNow = true
                    stopLock.notifyAll()
                }
            }
        }
    }

    private val dndEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED == intent.action) syncDnd()
        }
    }

    private val zenMode = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            syncDnd()
        }
    }

    private fun logFeatures() {
        val settings = getSharedPreferences("notification-settings", MODE_PRIVATE)
        val access = BandNotificationListener.accessGranted(this)
        val up = BandNotificationListener.listenerConnected()
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val health = HostIdentity.hasHealthNotificationListener() &&
                enabled != null && enabled.contains(
            "${HostIdentity.HEALTH_PACKAGE}/${HostIdentity.HEALTH_NOTIFICATION_LISTENER}"
        )
        SessionLog.line(
            this, "features local=" +
                    (if (up) "up" else if (access) "granted" else "absent") +
                    " healthListener=$health" +
                    " healthApproved=" + LocalPrefs.open(this, "notification-settings")
                .getBoolean("healthApproved", false) +
                    " healthLive=${HealthListenerState.connected()}" +
                    " notify=${settings.getBoolean("enabled", true)}" +
                    " packages=${(settings.getStringSet("packages", emptySet()) ?: emptySet()).size}" +
                    " calls=${settings.getBoolean("callsEnabled", false)}" +
                    " callOwner=${calls != null && calls!!.ownsCalls()}" +
                    " music=${PhoneMusic.attached()}"
        )
        SessionLog.line(
            this, "hosts " +
                    hostVersion(HostIdentity.CLOCK_PACKAGE) +
                    " " + hostVersion(HostIdentity.HEALTH_PACKAGE) +
                    " " + hostVersion(HostIdentity.DEVICES_PACKAGE) +
                    " " + hostVersion(packageName)
        )
    }

    private fun hostVersion(pkg: String?): String {
        val name = if (pkg == null) "" else pkg.substring(pkg.lastIndexOf('.') + 1)
        return try {
            val info = packageManager.getPackageInfo(pkg!!, 0)
            "$name=${info.versionName}/${info.longVersionCode}"
        } catch (missing: PackageManager.NameNotFoundException) {
            "$name=absent"
        }
    }

    private fun expireHeld() {
        val expired = HELD_NOTIFICATIONS.expire(SystemClock.elapsedRealtime())
        if (expired > 0) SessionLog.line(this, "NOTIFY_DROP reason=expired count=$expired")
    }

    private fun replayHeld() {
        val now = SystemClock.elapsedRealtime()
        val expired = HELD_NOTIFICATIONS.expire(now)
        if (expired > 0) SessionLog.line(this, "NOTIFY_DROP reason=expired count=$expired")
        for (held in HELD_NOTIFICATIONS.poll(now)) deliverHeld(held)
    }

    private fun deliverHeld(held: NotifyReplay.Held) {
        val command = held.command
        val queue = commands
        if (queue == null || !notificationSessionReady(this)) {
            held.fail(IllegalStateException("NOTIFICATION_SESSION_UNAVAILABLE"))
            SessionLog.line(this, "NOTIFY_DROP reason=service")
            return
        }
        if (duplicate(this, command)) {
            held.succeed()
            return
        }
        if (ordinaryPost(command) && PhoneDnd.blocksNotifications(PhoneDnd.currentFilter(this))) {
            SessionLog.line(this, "NOTIFY_SUPPRESSED_DND")
            held.succeed()
            return
        }
        try {
            val fitted = BandNotificationCommand.fitToPayload(command, notificationPayloadLimit())
            SessionLog.line(
                this,
                "NOTIFY_OUT type=${fitted.type} subtype=${fitted.subtype} bytes=${fitted.serializedSize}"
            )
            noteIconPackage(fitted)
            routeWatchCall(this, fitted)
            queue.send(fitted).whenComplete { _, error ->
                if (error == null) held.succeed()
                else held.fail(error)
            }
        } catch (tooLarge: IllegalArgumentException) {
            SessionLog.line(this, "NOTIFY_DROP reason=too-large")
            held.fail(IllegalStateException("NOTIFICATION_IDENTITY_TOO_LARGE"))
        }
    }

    override fun onCreate() {
        super.onCreate()
        SessionLog.start(this)
        SessionLog.line(this, "module start")
        DeviceImageStore.requestRefresh(this)
        dndSync.seed(bandSyncEnabled(this))
        dndSyncStored = dndSync.on()
        weatherSync = WeatherSync(this, { commands }, coordinator)
        healthReplay = HealthReplay(this, { status -> healthCollectionStatus(status) }, {
            try {
                coordinator.execute { if (!stopRequested && historySync != null) historySync!!.resume() }
            } catch (stopping: RejectedExecutionException) {
            }
        })
        calls = PhoneCallMonitor(this, coordinator)
        BandNotificationListener.ensureEnabled(this)
        HostKeepAlive.runRoot {
            try {
                OwnershipController(this).keepAwake()
                SessionLog.line(this, "KEEP_AWAKE")
            } catch (ignored: RuntimeException) {
            }
            if (getSharedPreferences("notification-settings", MODE_PRIVATE).getBoolean("callsEnabled", false) &&
                (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED)
            ) {
                OwnershipController.grantCallPermissions()
                calls?.refresh()
            }
            // ColourOS freezes this process through the framework cgroup freezer; root can undo
            // that within seconds and the guard shell survives the freeze itself.
            RootFreezeGuard.start(this)
        }
        instance = this
        sleepPauseOn = SleepMusic.enabled(this)
        var armed = SleepMusic.armedAt(this)
        if (sleepPauseOn && armed == Long.MAX_VALUE) {
            armed = System.currentTimeMillis()
            SleepMusic.rememberCutoff(this, armed)
        }
        sleepArmedAtMs = if (sleepPauseOn) armed else Long.MAX_VALUE
        coordinator.scheduleAtFixedRate({ tick() }, 1, 1, TimeUnit.MINUTES)
        coordinator.scheduleAtFixedRate({ expireHeld() }, 5, 5, TimeUnit.SECONDS)
        try {
            val bluetooth = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            bluetooth.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            registerReceiver(bluetoothEvents, bluetooth, RECEIVER_EXPORTED)
            receiverRegistered = true
        } catch (ignored: RuntimeException) {
        }
        try {
            registerReceiver(
                dndEvents,
                IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED),
                RECEIVER_EXPORTED
            )
            dndReceiverRegistered = true
        } catch (ignored: RuntimeException) {
        }
        try {
            val resolver = contentResolver
            resolver.registerContentObserver(
                Settings.Global.getUriFor("zen_mode"), false, zenMode
            )
            resolver.registerContentObserver(
                Settings.Secure.getUriFor("focusmode_switch"), false, zenMode
            )
            resolver.registerContentObserver(
                Settings.Secure.getUriFor("focusmode_switch_new"), false, zenMode
            )
            resolver.registerContentObserver(
                Settings.Secure.getUriFor("op_breath_mode_status"), false, zenMode
            )
        } catch (ignored: RuntimeException) {
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (diagnosticPaused || !getSystemService(UserManager::class.java).isUserUnlocked ||
            !BandStateRepository(this).isRegistered()
        ) {
            requestStop()
            stopSelf()
            return START_NOT_STICKY
        }
        synchronized(stopLock) {
            stopRequested = false
            stopLock.notifyAll()
        }
        if (intent != null && ACTION_SYNC == intent.action) syncRequested = true
        if (intent != null && ACTION_REPAIR == intent.action) {
            // The user tapped 「重新配对」, so one more pairing request is expected on the wrist.
            SessionLog.line(this, "bond repair manual")
            bondRecovery.manualRetry()
            repairBond()
            synchronized(stopLock) {
                retryNow = true
                stopLock.notifyAll()
            }
        }
        if (running.compareAndSet(false, true)) {
            stopped = CountDownLatch(1)
            show("正在连接手环")
            worker.execute { supervise() }
        }
        BandNotificationListener.ensureEnabled(this)
        return START_STICKY
    }

    /** ColorOS freezes a process whose task was swiped, even while this service is still running. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (stopRequested || !mayWake(this)) return
        SessionLog.line(this, "TASK_REMOVED keep")
        val title = if (noticeTitle.isNullOrBlank()) "正在连接手环" else noticeTitle!!
        noticeTitle = null
        show(title)
        BandNotificationListener.ensureEnabled(this)
        synchronized(stopLock) {
            retryNow = true
            stopLock.notifyAll()
        }
    }

    private fun requestStop() {
        synchronized(stopLock) {
            stopRequested = true
            stopLock.notifyAll()
        }
        val dropped = HELD_NOTIFICATIONS.failAll(IllegalStateException("NOTIFICATION_SESSION_UNAVAILABLE"))
        if (dropped > 0) SessionLog.line(this, "NOTIFY_DROP reason=stopped count=$dropped")
        val active = client
        active?.close()
    }

    private fun supervise() {
        supervisorBeatMs = SystemClock.elapsedRealtime()
        try {
            superviseLoop()
        } catch (fatal: Throwable) {
            // A worker thread that dies here takes every scheduled reconnect with it, while the
            // foreground notice still says 「正在连接手环」. Keep the service recoverable instead.
            SessionLog.line(
                this,
                "supervise crashed ${fatal.javaClass.simpleName}" +
                        (if (fatal.message == null) "" else " ${fatal.message}")
            )
            running.set(false)
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (ignored: RuntimeException) {
            }
            try {
                stopSelf()
            } catch (ignored: RuntimeException) {
            }
        }
    }

    private fun superviseLoop() {
        val repository = BandStateRepository(this)
        var backoff = INITIAL_BACKOFF_MS
        var status = "STARTING"
        try {
            try {
                repository.markSessionClosed()
            } catch (ignored: RuntimeException) {
            }
            while (!stopRequested) {
                supervisorBeatMs = SystemClock.elapsedRealtime()
                val owner = OwnershipController(this)
                if (!repository.isRegistered() || !owner.nativeReady()) {
                    status = "REGISTERED_NATIVE_DEVICE_REQUIRED registered=" +
                            repository.isRegistered() + " mode=" + owner.mode() +
                            " ready=" + owner.nativeReady()
                    Log.i("OplusBandBridge", status)
                    show("连接已暂停")
                    break
                }
                bindHealthHost()
                val stored = AtomicInteger()
                val authenticated = AtomicBoolean()
                val epoch = SESSION_IDS.incrementAndGet()
                sessionEpoch = epoch
                val active = SppDiagnosticClient(this) { code ->
                    if (stopRequested || epoch != sessionEpoch) return@SppDiagnosticClient
                    when (code) {
                        "HISTORY_FRAGMENT_RECEIVED" -> {
                            coordinator.execute {
                                if (epoch == sessionEpoch && historySync != null) historySync!!.progress()
                            }
                        }
                        "HISTORY_FILE_ARCHIVED" -> {
                            healthReplay.request()
                            stored.incrementAndGet()
                        }
                        "HISTORY_FORMAT_UNSUPPORTED" -> {
                            healthCollectionStatus(code)
                        }
                        "HISTORY_FILE_REJECTED", "HISTORY_STORAGE_FAILED", "HISTORY_CONFIRMATION_PENDING" -> {
                            coordinator.execute {
                                if (epoch == sessionEpoch && historySync != null) historySync!!.rejected(code)
                            }
                            healthCollectionStatus(code)
                        }
                    }
                }
                active.setSleepFiles { measurements ->
                    try {
                        coordinator.execute {
                            if (!stopRequested) noteSleepFile(measurements)
                        }
                    } catch (stopping: RejectedExecutionException) {
                    }
                }
                client = active
                try {
                    active.runLive({ result ->
                        authenticated.set(true)
                        bondRecovery.connected()
                        try {
                            repository.recordVerifiedDevice(
                                result.batteryPercent(), result.batteryState(), true,
                                result.firmware(), result.hardware(), result.sku()
                            )
                            DeviceImageStore.requestRefresh(this)
                        } catch (failure: Exception) {
                            throw IllegalStateException(failure.message, failure)
                        }
                        if (!repository.isRegistered() || stopRequested) return@runLive
                        writeStatus("CONNECTED")
                        show("手环已连接")
                    }, { queue ->
                        coordinator.execute {
                            if (client !== active || stopRequested) {
                                queue.close(IllegalStateException("SESSION_CLOSED"))
                                return@execute
                            }
                            commands = queue
                            replayHeld()
                            calls?.connected()
                            logFeatures()
                            BandNotificationListener.connectionChanged()
                            NativeMusic.requestRefresh(this)
                            val weatherEpoch = IntArray(1)
                            historySync = LiveHistorySync(
                                queue, coordinator,
                                { weatherEpoch[0] = weatherSync.deferForecast() }, {
                                    try {
                                        repository.recordSyncCompleted()
                                        clearStoragePause()
                                    } catch (unavailable: RuntimeException) {
                                        healthCollectionStatus("HEALTH_STORAGE_UNAVAILABLE")
                                    }
                                    weatherSync.releaseForecast(weatherEpoch[0])
                                }, { reason ->
                                    healthCollectionStatus(reason)
                                    weatherSync.releaseForecast(weatherEpoch[0])
                                }, { healthReplay.hasCapacity() }
                            )
                            val now = System.nanoTime()
                            sessionStartAtMs = System.currentTimeMillis()
                            loadSleepStateCapability(repository)
                            nextBatteryAt = now + TimeUnit.MINUTES.toNanos(BATTERY_POLL_MINUTES)
                            nextHealthAt = now + TimeUnit.MINUTES.toNanos(historyPeriodMinutes())
                            nextWeatherAt = now + TimeUnit.MINUTES.toNanos(30)
                            bandAsleep = null
                            awakeSamples = 0
                            quietStepsValue = Long.MIN_VALUE
                            quietCheckedAtMs = 0
                            quietBaselineSteps = -1
                            quietSampleAtMs = 0
                            quietSignalStale = false
                            nightQuietResult = false
                            nightHadQuiet = false
                            wakeEvidence.reset()
                            wakeEvidenceCheckedAtMs = 0
                            val night = isNightWindow()
                            val restartCooldown = night && lastNightFlushAtMs > 0 &&
                                    sessionStartAtMs >= lastNightFlushAtMs &&
                                    sessionStartAtMs - lastNightFlushAtMs <
                                    TimeUnit.MINUTES.toMillis(NIGHT_RESTART_COOLDOWN_MINUTES)
                            historyCooldownUntilMs = if (restartCooldown) {
                                sessionStartAtMs + TimeUnit.MINUTES.toMillis(NIGHT_RESTART_COOLDOWN_MINUTES)
                            } else {
                                0
                            }
                            historySyncPending = !restartCooldown
                            pendingHistoryIncludesHistory = !night
                            if (sleepStateUnsupported) {
                                waitingForInitialSleepState = false
                                historyStateDeadlineAt = 0
                            } else {
                                waitingForInitialSleepState = true
                                historyStateDeadlineAt = now + TimeUnit.SECONDS.toNanos(INITIAL_HISTORY_SYNC_WAIT_SECONDS)
                            }
                            if (restartCooldown) {
                                SessionLog.line(this, "HISTORY_SYNC restart cooldown active")
                            }
                            nextSleepStateAt = now + TimeUnit.MINUTES.toNanos(SLEEP_STATE_POLL_MINUTES)
                            nextNightStatsAt = now
                            syncRequested = false
                            maybeWakeCatchup(now)
                            healthReplay.request()
                            dndSync.newLink()
                            main.removeCallbacks(dndAskFallback)
                            if (!(night && restartCooldown)) {
                                queue.send(realtimeStats(REALTIME_STATS_START))
                                realtimeStatsStopPending = true
                                nextNightStatsAt = now + TimeUnit.MINUTES.toNanos(NIGHT_STATS_REFRESH_MINUTES)
                                coordinator.schedule({
                                    if (epoch == sessionEpoch) stopRealtimeStats()
                                }, REALTIME_STATS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            }
                            syncDnd()
                            requestSleepState()
                        }
                    }, { fileId ->
                        coordinator.execute {
                            if (client === active && !stopRequested && historySync != null) {
                                historySync!!.saved(fileId)
                            }
                        }
                    }, { command ->
                        if (command.type == 2) {
                            SessionLog.line(this@BandLiveService, describeSystem(command))
                            noteBandRules(command)
                            noteBandDndSync(command)
                            if (command.subtype == 109) adoptStoredBand()
                            applyBandManual(command)
                            coordinator.execute {
                                if (epoch == sessionEpoch) noteSleep(command)
                            }
                        } else if (command.type == 10 || command.type == 7 || command.type == 18) {
                            SessionLog.line(
                                this@BandLiveService,
                                "rx type=${command.type} subtype=${command.subtype} status=${command.status}"
                            )
                        }
                        if (command.type == 7) {
                            Log.i(
                                "OplusBandBridge",
                                "NOTIFY_IN subtype=${command.subtype} status=${command.status}"
                            )
                            if (command.subtype == 16 && command.hasNotification() &&
                                command.notification.hasNotificationIconQuery()
                            ) {
                                offerIcon(command.notification.notificationIconQuery.`package`)
                            }
                            if (command.subtype == 15 && command.hasNotification() &&
                                command.notification.hasNotificationIconRequest()
                            ) {
                                uploadIcon(active, command.notification.notificationIconRequest)
                            }
                            calls?.onBandCommand(command)
                        }
                        if (command.type == 2 && command.subtype == 17) {
                            Log.i(
                                "OriginBand",
                                "FIND_PHONE_RX hasSystem=${command.hasSystem()} " +
                                    "hasFind=${command.hasSystem() && command.system.hasFindDevice()} " +
                                    "val=${if (command.hasSystem()) command.system.findDevice else -1} " +
                                    "status=${command.status}",
                            )
                            main.post { FindPhone.onBandCommand(this@BandLiveService, command) }
                        }
                        if (command.type == 18) {
                            val detail = if (command.subtype == 2 && command.hasMusic() && command.music.hasMediaKey()) {
                                val mk = command.music.mediaKey
                                " key=${mk.key} vol=${mk.volume} hasVol=${mk.hasVolume()}"
                            } else {
                                ""
                            }
                            Log.i("OriginBand", "MUSIC_RX subtype=${command.subtype}$detail")
                            if (command.subtype == 0) {
                                SessionLog.line(this@BandLiveService, "MUSIC_REQ")
                                if (!PhoneMusic.attached()) {
                                    main.post { replyMusic() }
                                }
                            }
                            main.post { NativeMusic.onBandCommand(this@BandLiveService, command) }
                        }
                        if (command.type == 2 && command.subtype == 43) {
                            main.post { replySilent() }
                        }
                        if (command.type == 17 && command.subtype == 16) {
                            val op = if (command.hasSchedule() && command.schedule.hasPhoneAlarmOperation()) {
                                command.schedule.phoneAlarmOperation.opCode
                            } else {
                                -1
                            }
                            val alarmId = if (op >= 0 && command.schedule.phoneAlarmOperation.hasPhoneAlarm()) {
                                command.schedule.phoneAlarmOperation.phoneAlarm.id
                            } else {
                                -1
                            }
                            SessionLog.line(this@BandLiveService, "ALARM_FROM_BAND op=$op id=$alarmId")
                            if (op == 1 || op == 2) {
                                PhoneAlarmProvider.offer(op, alarmId)
                            }
                        }
                        weatherSync.onCommand(command)
                        if (command.type == 8 && command.subtype == 47 && command.hasHealth() &&
                            command.health.hasRealTimeStats()
                        ) {
                            val steps = command.health.realTimeStats.steps
                            coordinator.execute {
                                if (epoch != sessionEpoch || steps < 0 || steps > 200_000) return@execute
                                try {
                                    repository.recordDailySteps(steps.toLong(), System.currentTimeMillis(), true)
                                } catch (ignored: RuntimeException) {
                                }
                                if (steps > 0) stopRealtimeStats()
                            }
                        }
                        if (command.type == 8 && command.hasStatus() && command.status != 0) {
                            coordinator.execute {
                                if (epoch == sessionEpoch && historySync != null) historySync!!.onCommand(command)
                            }
                        }
                    })
                    status = "CLOSED files=${stored.get()}"
                } catch (failure: Exception) {
                    status = if (failure is SppDiagnosticClient.Failure) {
                        failure.code
                    } else {
                        failure.javaClass.simpleName
                    }
                    if ("OFFICIAL_PAIRING_REQUIRED" == status) repairBond()
                    show(statusCopy(status))
                } finally {
                    client = null
                    commands = null
                    realtimeStatsStopPending = false
                    try {
                        coordinator.execute {
                            if (epoch == sessionEpoch && commands == null && historySync != null) {
                                historySync!!.close()
                                historySync = null
                            }
                        }
                    } catch (stopping: RejectedExecutionException) {
                    }
                    weatherSync.onDisconnected()
                    calls?.disconnected()
                    BandNotificationListener.connectionChanged()
                    try {
                        repository.markSessionClosed()
                    } catch (ignored: RuntimeException) {
                    }
                }
                if (authenticated.get()) backoff = INITIAL_BACKOFF_MS
                if (stopRequested) break
                val delay = if (retryable(status)) backoff else QUIET_BACKOFF_MS
                if (!waitForRetry(delay)) break
                supervisorBeatMs = SystemClock.elapsedRealtime()
                if (!authenticated.get() && retryable(status)) {
                    backoff = min(backoff * 2, MAX_BACKOFF_MS)
                }
            }
        } finally {
            writeStatus(if (stopRequested) "STOPPED" else status)
            running.set(false)
            stopped.countDown()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun bindHealthHost() {
        if (healthBound || stopRequested) return
        try {
            if (!HostIdentity.hasHealthRpcService() || !HostIdentity.installed(this, HEALTH_PACKAGE)) {
                healthHostStatus("UNAVAILABLE")
                return
            }
            val healthService = ComponentName(HEALTH_PACKAGE, HostIdentity.HEALTH_RPC_SERVICE)
            val service = packageManager.getServiceInfo(healthService, 0)
            // Version drift is a one-time home-screen hint, not a reason to refuse the import bind.
            if (!service.enabled || !service.exported ||
                !service.applicationInfo.enabled || HEALTH_PACKAGE != service.processName ||
                (!service.permission.isNullOrBlank())
            ) {
                healthHostStatus("UNAVAILABLE")
                return
            }
            main.post {
                if (healthBound || stopRequested || !BandStateRepository(this).isRegistered()) return@post
                try {
                    healthBound = bindService(
                        Intent().setComponent(healthService),
                        healthConnection,
                        BIND_AUTO_CREATE
                    )
                    if (!healthBound) {
                        healthHostStatus("UNAVAILABLE")
                        reviveHealth()
                    }
                } catch (unavailable: RuntimeException) {
                    healthHostStatus("UNAVAILABLE")
                    reviveHealth()
                }
            }
        } catch (unavailable: PackageManager.NameNotFoundException) {
            healthHostStatus("UNAVAILABLE")
        }
    }

    private fun unbindHealthHost() {
        if (healthBound) {
            unbindService(healthConnection)
            healthBound = false
        }
    }

    private fun reviveHealth() {
        HostKeepAlive.startHealthAsync(this) {
            main.post {
                if (!stopRequested) bindHealthHost()
            }
        }
    }

    private fun healthHostStatus(status: String) {
        getSharedPreferences("live-service", MODE_PRIVATE).edit()
            .putString("healthHostStatus", status).apply()
        contentResolver.notifyChange(DeviceCardProvider.URI, null)
    }

    private fun healthCollectionStatus(status: String) {
        getSharedPreferences("live-service", MODE_PRIVATE).edit()
            .putString("healthCollectionStatus", status).apply()
        contentResolver.notifyChange(DeviceCardProvider.URI, null)
    }

    /** A later successful round clears a storage pause that already ended. */
    private fun clearStoragePause() {
        val current = getSharedPreferences("live-service", MODE_PRIVATE)
            .getString("healthCollectionStatus", "")
        if ("HEALTH_STORAGE_UNAVAILABLE" == current || "HISTORY_STORAGE_FAILED" == current) {
            healthCollectionStatus("")
        }
    }

    private fun nightTrackingEnabled(): Boolean = !sleepStateUnsupported

    private fun loadSleepStateCapability(repository: BandStateRepository) {
        val deviceId = repository.deviceId()
        sleepStateUnsupported = deviceId.isNotBlank() &&
                getSharedPreferences(LIVE_PREFS, MODE_PRIVATE)
                    .getBoolean(sleepUnsupportedKey(deviceId), false)
        sleepStateMisses = 0
        lastNightFlushAtMs = if (deviceId.isBlank()) {
            0
        } else {
            getSharedPreferences(LIVE_PREFS, MODE_PRIVATE).getLong(lastNightFlushKey(deviceId), 0)
        }
    }

    private fun persistSleepStateUnsupported(unsupported: Boolean) {
        try {
            val deviceId = BandStateRepository(this).deviceId()
            if (deviceId.isBlank()) return
            getSharedPreferences(LIVE_PREFS, MODE_PRIVATE).edit()
                .putBoolean(sleepUnsupportedKey(deviceId), unsupported).apply()
        } catch (ignored: RuntimeException) {
        }
    }

    @WorkerThread
    private fun noteSleepStateMiss() {
        SessionLog.line(this, "SLEEP_STATE query missed")
        Log.i("OplusBandBridge", "SLEEP_STATE_QUERY_FAILED")
        sleepStateMisses++
        if (sleepStateMisses < SLEEP_STATE_MISS_LIMIT || sleepStateUnsupported) return
        sleepStateUnsupported = true
        waitingForInitialSleepState = false
        historyStateDeadlineAt = 0
        persistSleepStateUnsupported(true)
        SessionLog.line(this, "SLEEP_STATE unsupported, switching to quiet heuristic")
    }

    @WorkerThread
    private fun resetQuietTracker() {
        quietStepsValue = Long.MIN_VALUE
        quietCheckedAtMs = 0
        quietBaselineSteps = -1
        quietSampleAtMs = 0
        quietSignalStale = false
        nightQuietResult = false
        nightHadQuiet = false
    }

    /**
     * The only place that advances the quiet tracker. It runs on the coordinator thread before
     * any history gate is evaluated, so gate reads stay side-effect free.
     */
    @WorkerThread
    private fun advanceQuietTracker() {
        if (!isSleepSuppressionWindow()) {
            resetQuietTracker()
            return
        }
        val nowMs = System.currentTimeMillis()
        if (quietCheckedAtMs > 0 && nowMs - quietCheckedAtMs < 60_000L) return
        quietCheckedAtMs = nowMs
        val steps: Long
        val stepsAt: Long
        try {
            val repository = BandStateRepository(this)
            steps = repository.stepsToday().toLong()
            stepsAt = repository.stepsAtMs()
        } catch (unavailable: RuntimeException) {
            val changed = !quietSignalStale
            if (changed) requestNightStatsRefreshNow()
            quietSignalStale = true
            nightQuietResult = true
            if (changed) {
                SessionLog.line(this, "SLEEP_QUIET stale=true quiet=true steps=unavailable")
            }
            return
        }
        if (steps < 0 || staleStepSample(nowMs, stepsAt)) {
            val changed = !quietSignalStale
            if (changed) requestNightStatsRefreshNow()
            quietSignalStale = true
            // Unknown is not awake. Keep history suppressed and request a fresh 8/45 sample.
            nightQuietResult = true
            if (changed) {
                SessionLog.line(this, "SLEEP_QUIET stale=true quiet=true steps=$steps")
            }
            return
        }
        val newSample = stepsAt != quietSampleAtMs
        val wasQuiet = nightQuietResult
        val counterReset = quietCounterReset(quietStepsValue, quietSampleAtMs, steps, stepsAt)
        quietSampleAtMs = stepsAt
        quietSignalStale = false
        if (quietStepsValue == Long.MIN_VALUE) {
            quietStepsValue = steps
            quietBaselineSteps = steps
            quietSinceMs = nowMs
            nightQuietResult = false
        } else if (counterReset) {
            // A daily reset is not movement. Preserve the established quiet period.
            quietStepsValue = steps
            quietBaselineSteps = steps
            nightQuietResult = quietElapsed(nowMs, quietSinceMs)
        } else if (steps != quietStepsValue) {
            if (quietStepNoise(steps, quietBaselineSteps)) {
                // A handful of pedometer ticks do not end sleep; keep the original start.
                quietStepsValue = steps
                nightQuietResult = quietElapsed(nowMs, quietSinceMs)
            } else {
                wakeEvidence.observe()
                quietStepsValue = steps
                quietBaselineSteps = steps
                quietSinceMs = nowMs
                nightQuietResult = false
            }
        } else {
            nightQuietResult = quietElapsed(nowMs, quietSinceMs)
        }
        if (newSample || wasQuiet != nightQuietResult) {
            SessionLog.line(this, "SLEEP_QUIET stale=false quiet=$nightQuietResult steps=$steps")
        }
    }

    private fun requestNightStatsRefreshNow() {
        nextNightStatsAt = System.nanoTime()
    }

    private fun nightQuietObserved(): Boolean =
        quietSuppressesHistory(nightQuietResult, quietSignalStale)

    private fun updateNightTransition(nowNanos: Long) {
        val suppressionWindow = isSleepSuppressionWindow()
        if (suppressionWindow) {
            advanceQuietTracker()
            val quiet = nightQuietObserved()
            if (quiet && !quietSignalStale) {
                if (!nightHadQuiet) {
                    wakeEvidence.beginEpisode(System.currentTimeMillis())
                    wakeEvidenceCheckedAtMs = 0
                    awakeSamples = 0
                }
                nightHadQuiet = true
            } else if (!quiet && nightHadQuiet) {
                nightHadQuiet = false
                // A real step sample ended quiet while still inside the suppression window. This
                // is an awake sync, so a full history list remains deferred until after 08:00.
                syncRequested = true
                nextHealthAt = nowNanos
                SessionLog.line(this, "HISTORY_SYNC quiet ended wake catch-up")
            }
        }
        if (!suppressionWindow) {
            resetQuietTracker()
            wakeEvidence.reset()
            wakeEvidenceCheckedAtMs = 0
            bandAsleep = null
            awakeSamples = 0
        }
    }

    /** A daytime session after a night flush gets one full history pass. */
    private fun maybeWakeCatchup(nowNanos: Long) {
        val hour = LocalTime.now().hour
        if (isNightWindow(hour) || lastNightFlushAtMs <= 0) return
        val nowMs = System.currentTimeMillis()
        if (nightStillSuppressed(hour, wakeEvidence(nowMs))) return
        if (nowMs < lastNightFlushAtMs) return
        try {
            val deviceId = BandStateRepository(this).deviceId()
            if (deviceId.isBlank()) return
            val today = LocalDate.now().toString()
            val key = "${WAKE_CATCHUP_DATE}_$deviceId"
            val prefs = getSharedPreferences(LIVE_PREFS, MODE_PRIVATE)
            if (today == prefs.getString(key, "")) return
            prefs.edit().putString(key, today).apply()
            syncRequested = true
            nextHealthAt = nowNanos
            SessionLog.line(this, "HISTORY_SYNC wake catch-up")
        } catch (ignored: RuntimeException) {
        }
    }

    private fun wakeEvidence(nowMs: Long): Boolean {
        if (wakeEvidence.observed()) return true
        if (!sleepStateUnsupported && java.lang.Boolean.FALSE == bandAsleep &&
            awakeSamples >= AWAKE_CONFIRM_SAMPLES
        ) {
            wakeEvidence.observe()
            return true
        }
        val hour = LocalTime.now().hour
        if (hour < NIGHT_END_HOUR) return false
        if (wakeEvidenceCheckedAtMs > 0 && nowMs - wakeEvidenceCheckedAtMs < 60_000L) return false
        wakeEvidenceCheckedAtMs = nowMs
        if (bandSleepClosed(nowMs)) wakeEvidence.observe()
        return wakeEvidence.observed()
    }

    /** The latest stored sleep interval is closed only when firmware wrote open == 0. */
    private fun bandSleepClosed(nowMs: Long): Boolean {
        return try {
            HealthRecordStore(this).use { store ->
                val deviceId = BandStateRepository(this).deviceId()
                if (deviceId.isBlank()) return false
                val rows = store.storedRecords(
                    deviceId, "sleep_interval",
                    nowMs - TimeUnit.HOURS.toMillis(12), nowMs
                )
                val latest = rows.maxByOrNull { it.endMs }
                latest != null && latest.complete && wakeEvidence.acceptsClosedInterval(latest.endMs)
            }
        } catch (unavailable: Exception) {
            SessionLog.line(
                this,
                "SLEEP_GATE sleep interval unavailable=${unavailable.javaClass.simpleName}"
            )
            false
        }
    }

    private fun persistNightCooldown() {
        lastNightFlushAtMs = System.currentTimeMillis()
        try {
            val deviceId = BandStateRepository(this).deviceId()
            if (deviceId.isNotBlank()) {
                getSharedPreferences(LIVE_PREFS, MODE_PRIVATE).edit()
                    .putLong(lastNightFlushKey(deviceId), lastNightFlushAtMs).apply()
            }
        } catch (ignored: RuntimeException) {
        }
    }

    /** Ends the live-stats stream once; a running stream keeps the band measuring heart rate. */
    private fun stopRealtimeStats() {
        if (!realtimeStatsStopPending) return
        realtimeStatsStopPending = false
        val queue = commands
        if (queue == null || stopRequested) return
        queue.send(realtimeStats(REALTIME_STATS_STOP))
    }

    private fun tick() {
        val queue = commands
        if (stopRequested || queue == null || historySync == null) return
        if (!healthBound) bindHealthHost()
        if (!BandStateRepository(this).isRegistered()) {
            requestStop()
            return
        }
        val now = System.nanoTime()
        updateNightTransition(now)
        val beatAge = SystemClock.elapsedRealtime() - supervisorBeatMs
        if (running.get() && supervisorBeatMs != 0L && beatAge > SUPERVISOR_STALL_MS) {
            // The coordinator survives a process freeze, so it can put the worker back after the
            // frozen supervisor thread died. Re-entry is harmless: SppDiagnosticClient admits one
            // live session at a time and the loser reports CONNECTION_ALREADY_ACTIVE.
            supervisorBeatMs = SystemClock.elapsedRealtime()
            SessionLog.line(this, "supervise stalled ${beatAge / 1000}s, restarting")
            if (isNightWindow()) {
                historySyncPending = false
                nextHealthAt = now + TimeUnit.MINUTES.toNanos(NIGHT_RESTART_COOLDOWN_MINUTES)
                historyCooldownUntilMs = System.currentTimeMillis() +
                        TimeUnit.MINUTES.toMillis(NIGHT_RESTART_COOLDOWN_MINUTES)
                persistNightCooldown()
                SessionLog.line(this, "HISTORY_SYNC night restart cooldown")
            }
            try {
                worker.execute { supervise() }
            } catch (stopping: RejectedExecutionException) {
            }
        }
        if (now >= nextBatteryAt) {
            nextBatteryAt = now + TimeUnit.MINUTES.toNanos(BATTERY_POLL_MINUTES)
            queue.request(
                XiaomiProto.Command.newBuilder().setType(2).setSubtype(1).build(), 2, 1
            ).whenComplete { _, error ->
                if (error != null) writeStatus("BATTERY_REFRESH_FAILED")
            }
        }
        if (isSleepSuppressionWindow() && running.get() && now >= nextNightStatsAt) {
            nextNightStatsAt = now + TimeUnit.MINUTES.toNanos(NIGHT_STATS_REFRESH_MINUTES)
            val statsQueue = commands
            if (statsQueue != null && !realtimeStatsStopPending) {
                statsQueue.send(realtimeStats(REALTIME_STATS_START))
                realtimeStatsStopPending = true
                val epoch = sessionEpoch
                coordinator.schedule({
                    if (epoch == sessionEpoch) stopRealtimeStats()
                }, REALTIME_STATS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }
        if (now >= nextSleepStateAt) {
            nextSleepStateAt = now + TimeUnit.MINUTES.toNanos(SLEEP_STATE_POLL_MINUTES)
            if (shouldPollSleepState(LocalTime.now().hour, nightTrackingEnabled())) {
                requestSleepState()
            }
        }
        if (waitingForInitialSleepState && now >= historyStateDeadlineAt) {
            waitingForInitialSleepState = false
            SessionLog.line(this, "HISTORY_SYNC sleep-state timeout, checking pending history")
        }
        // Pending work may become eligible on timeout or when the suppression window ends.
        flushPendingHistory()
        if (syncRequested || now >= nextHealthAt) {
            val historical = syncRequested && !isNightWindow()
            syncRequested = false
            nextHealthAt = now + TimeUnit.MINUTES.toNanos(historyPeriodMinutes())
            requestHistoryWhenAwake(historical)
        }
        if (now >= nextWeatherAt) {
            nextWeatherAt = now + TimeUnit.MINUTES.toNanos(30)
            weatherSync.sendIfChanged()
        }
        maybeWakeCatchup(now)
    }

    /** reason is the hidden unbond extra. 9 means some app removed the bond; 1 means auth failed. */
    private fun noteBond(context: Context, intent: Intent) {
        val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
        val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
        val reason = intent.getIntExtra("android.bluetooth.device.extra.REASON", -1)
        val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        var ours = "unknown"
        if (device != null) {
            try {
                ours = BandStateRepository(context).matchesAddress(device.address).toString()
            } catch (locked: RuntimeException) {
            }
        }
        SessionLog.line(
            context,
            "bond changed state=$state/${bondName(state)} prev=$previous/${bondName(previous)} " +
                    "reason=$reason/${bondReason(reason)} ours=$ours"
        )
        if (state == BluetoothDevice.BOND_NONE && previous == BluetoothDevice.BOND_BONDED) {
            // One greppable line: the bond a live SPP session needs went away, and this says who
            // the stack blamed. reason 9 is a local removal; 1 and 8 are the band refusing a key.
            SessionLog.line(context, "bond lost reason=${bondReason(reason)} ours=$ours")
        }
        if ("true" != ours) return
        // A lost or restored bond changes what the next attempt can do. Without this the worker
        // sat out the whole backoff after someone else re-paired the band.
        if (bondRecovery.note(state)) {
            synchronized(stopLock) {
                retryNow = true
                stopLock.notifyAll()
            }
        }
    }

    /**
     * SPP opens a secure RFCOMM socket and needs the system bond. ColourOS and Mi Fitness remove
     * that bond while the bridge owns the band, and the quiet retry then waited for a repair that
     * only the official app could start. Ask for the bond here instead, a bounded number of times.
     */
    private fun repairBond() {
        val address = try {
            BandStateRepository(this).address()
        } catch (locked: RuntimeException) {
            return
        }
        if (address.isNullOrBlank()) return
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) return
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (invalid: IllegalArgumentException) {
            return
        }
        val now = SystemClock.elapsedRealtime()
        val state = try {
            device.bondState
        } catch (denied: SecurityException) {
            return
        }
        val action = bondRecovery.decide(state, now)
        if (action == BondRecovery.READY) {
            bondGaveUpLogged = false
            return
        }
        if (action == BondRecovery.GAVE_UP) {
            if (!bondGaveUpLogged) {
                bondGaveUpLogged = true
                SessionLog.line(this, "bond repair gave-up asks=${bondRecovery.asks()} state=$state")
            }
            return
        }
        if (action != BondRecovery.ASK) return
        val started = try {
            device.createBond()
        } catch (denied: SecurityException) {
            false
        }
        bondRecovery.asked(now)
        bondGaveUpLogged = false
        SessionLog.line(
            this,
            "bond repair ask=${bondRecovery.asks()} state=$state started=$started"
        )
    }

    private fun statusCopy(status: String): String {
        if ("OFFICIAL_PAIRING_REQUIRED" == status) {
            val gaveUp = bondRecovery.gaveUp()
            bondRepairOffered = gaveUp
            return BondRecovery.notice(gaveUp)
        }
        bondRepairOffered = false
        return if (retryable(status)) "正在重连" else "稍后自动重连"
    }

    private fun waitForRetry(delayMs: Long): Boolean {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs)
        synchronized(stopLock) {
            while (!stopRequested && !retryNow) {
                val leftMs = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime())
                if (leftMs <= 0) return true
                try {
                    stopLock.wait(leftMs)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            if (stopRequested) return false
            retryNow = false
            return true
        }
    }

    private fun writeStatus(status: String) {
        try {
            FileWriter(File(noBackupFilesDir, "live-status.txt")).use { writer ->
                writer.write(status)
            }
        } catch (ignored: IOException) {
        }
    }

    private fun syncDnd() {
        val queue = commands
        if (stopRequested || queue == null) return
        val filter = PhoneDnd.currentFilter(this)
        val now = System.nanoTime()
        synchronized(dndSyncLock) {
            val elapsed = if (dndSyncAtNanos == 0L) -1L else now - dndSyncAtNanos
            val phoneChanged = lastDndFilter != Int.MIN_VALUE && lastDndFilter != filter
            if (PhoneDnd.repeatSync(lastDndFilter, elapsed, filter)) {
                return
            }
            dndPushPhone = phoneChanged
            lastDndFilter = filter
            dndSyncAtNanos = now
        }
        val detail = PhoneDnd.describe(this)
        Log.i("OplusBandBridge", "DND_SYNC $detail")
        SessionLog.line(this, "DND_SYNC $detail")
        when (dndSync.before()) {
            BandDndSync.Next.ASK -> askDndSync(queue)
            BandDndSync.Next.MIRROR -> mirrorDnd(queue)
            BandDndSync.Next.HOLD -> {
                SessionLog.line(this, "DND_SYNC_HOLD bandSync=off")
                Log.i("OplusBandBridge", "DND_SYNC_HOLD bandSync=off")
            }
        }
    }

    /**
     * A session starts by reading the band's own 同步手机勿扰 switch. Subtype 15 with enabled 1 is
     * this phone turning that switch on, so it must not go out before the answer is known: the
     * user turned it off on the band, and a plain reconnect used to switch it back on.
     */
    private fun askDndSync(queue: LiveCommandQueue) {
        sendQuietly(queue, BandDndCommand.querySync())
        main.removeCallbacks(dndAskFallback)
        main.postDelayed(dndAskFallback, DND_ASK_FALLBACK_MS)
    }

    /** Firmware that ignores subtype 14 keeps the stored switch value and gets the mirror anyway. */
    private fun dndAskTimedOut() {
        if (!dndSync.waiting()) return
        SessionLog.line(this, "DND_SYNC_SWITCH quiet")
        val queue = commands
        if (dndSync.answered(null) && queue != null && !stopRequested) mirrorDnd(queue)
    }

    /** Subtype 15 with enabled 1 both keeps the phone rule and leaves the switch on. */
    private fun mirrorDnd(queue: LiveCommandQueue) {
        if (!dndSync.on() || stopRequested) return
        // The band drops a rule list that arrives before sync_with_phone is on.
        // Transport ACK is earlier than that; wait for the subtype 15 body.
        queue.request(BandDndCommand.syncWithPhone(), 2, 15)
            .whenComplete { _, error ->
                if (error != null) {
                    Log.i("OplusBandBridge", "DND_SYNC_REJECTED subtype=15")
                }
                sendDndFollowup()
            }
    }

    /** The band's switch, either answering subtype 14 or pushed after the user moved it on the band. */
    private fun noteBandDndSync(command: XiaomiProto.Command) {
        val enabled = BandDndCommand.syncEnabled(command) ?: return
        val mirror = dndSync.answered(enabled)
        rememberDndSync(dndSync.on())
        main.removeCallbacks(dndAskFallback)
        SessionLog.line(
            this,
            "DND_SYNC_SWITCH bandSync=${if (dndSync.on()) "on" else "off"} mirror=$mirror"
        )
        if (!dndSync.on()) {
            // Nothing may reach the band again until the switch is back on there.
            main.removeCallbacks(dndRulesAgain)
            return
        }
        if (!mirror) return
        val queue = commands
        if (queue == null || stopRequested) return
        main.post { if (!stopRequested && commands === queue) mirrorDnd(queue) }
    }

    private fun rememberDndSync(enabled: Boolean) {
        if (dndSyncStored != null && dndSyncStored == enabled) return
        dndSyncStored = enabled
        try {
            LocalPrefs.open(this, FEATURES)
                .edit().putBoolean(DND_SYNC_OFF_KEY, !enabled).commit()
        } catch (ignored: RuntimeException) {
        }
    }

    private fun sendDndFollowup() {
        pushDndState(false)
    }

    private fun pushDndState(resend: Boolean) {
        val queue = commands
        if (stopRequested || queue == null) return
        if (!dndSync.on()) {
            SessionLog.line(this, "DND_SYNC_HOLD bandSync=off push")
            return
        }
        val phoneOn = PhoneDnd.blocksNotifications(PhoneDnd.currentFilter(this))
        if (!PhoneDnd.sendPhoneRule(dndPushPhone, phoneOn, dndBandKnown, dndBandOn)) {
            SessionLog.line(
                this,
                "DND_HOLD phone=$phoneOn changed=$dndPushPhone bandKnown=$dndBandKnown band=$dndBandOn"
            )
            if (!resend) sendQuietly(queue, BandDndCommand.queryRules())
            return
        }
        lastDndSentNanos = System.nanoTime()
        val activatedAt = BandDndCommand.activatedAt(System.currentTimeMillis())
        sendQuietly(queue, BandDndCommand.state(phoneOn))
        sendQuietly(queue, BandDndCommand.phoneSilent(phoneOn))
        sendQuietly(queue, BandDndCommand.phoneRules(phoneOn, activatedAt))
        if (resend) {
            sendQuietly(queue, BandDndCommand.queryRules())
            return
        }
        sendQuietly(queue, BandDndCommand.queryRules())
        main.removeCallbacks(dndRulesAgain)
        main.postDelayed(dndRulesAgain, 2000)
    }

    private fun replyMusic() {
        val queue = commands
        if (stopRequested || queue == null) return
        var command = lastMusic
        if (command == null) {
            val audio = getSystemService(AudioManager::class.java)
            var volume = 0
            if (audio != null) {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                volume = BandMusicCommand.percent(
                    audio.getStreamVolume(AudioManager.STREAM_MUSIC), max
                )
            }
            command = BandMusicCommand.nothing(volume)
        }
        val info = command.music.musicInfo
        SessionLog.line(this, "MUSIC_REPLY state=${info.state} volume=${info.volume}")
        sendQuietly(queue, command)
    }

    private fun replySilent() {
        val queue = commands
        if (stopRequested || queue == null) return
        val on = PhoneDnd.blocksNotifications(PhoneDnd.currentFilter(this))
        SessionLog.line(this, "SILENT_REQ on=$on")
        sendQuietly(queue, BandDndCommand.phoneSilent(on))
    }

    private fun applyBandManual(command: XiaomiProto.Command) {
        // With the band's switch off the two sides are independent, in both directions.
        if (!dndSync.on()) return
        val bandOn = BandDndCommand.pushedManualState(command) ?: return
        if (!PhoneDnd.acceptBandManual(
                bandOn, PhoneDnd.currentFilter(this),
                lastDndSentNanos, System.nanoTime()
            )
        ) return
        requestPhoneDnd(bandOn)
    }

    private fun requestPhoneDnd(on: Boolean) {
        main.post {
            if (!PhoneDnd.acceptBandManual(
                    on, PhoneDnd.currentFilter(this),
                    lastDndSentNanos, System.nanoTime()
                )
            ) return@post
            val attempt = PhoneDnd.apply(this, on)
            SessionLog.line(
                this,
                "DND_FROM_BAND on=$on applied=${attempt.applied()} policy=${attempt.policy()} via=${attempt.via()}"
            )
            if (attempt.applied()) return@post
            val intent = Intent(HostIdentity.ACTION_SET_DND)
                .setPackage(HostIdentity.HEALTH_PACKAGE)
                .putExtra("on", on)
            sendOrderedBroadcast(intent, null, object : BroadcastReceiver() {
                override fun onReceive(context: Context?, done: Intent?) {
                    val applied = resultCode == 1
                    SessionLog.line(
                        this@BandLiveService,
                        "DND_FROM_BAND_HOST on=$on applied=$applied via=$resultData"
                    )
                    if (!applied) askRootZen(on)
                }
            }, main, 0, null, null)
        }
    }

    private fun askRootZen(on: Boolean) {
        HostKeepAlive.runRoot {
            val wrote = OwnershipController.setZenMode(on)
            val applied = wrote && PhoneDnd.blocksNotifications(PhoneDnd.currentFilter(this)) == on
            SessionLog.line(this, "DND_FROM_BAND_ROOT on=$on wrote=$wrote applied=$applied")
        }
    }

    private fun historyMayRun(): Boolean {
        val hour = LocalTime.now().hour
        val nowMs = System.currentTimeMillis()
        val evidence = wakeEvidence(nowMs)
        if (nightStillSuppressed(hour, evidence)) return false
        val night = isNightWindow(hour)
        val cooldown = night && nowMs < historyCooldownUntilMs
        val quiet = sleepStateUnsupported && !waitingForInitialSleepState &&
                !cooldown && !evidence && nightQuietObserved()
        val stale = quietSignalStale && !evidence
        val gatedSleepState = if (evidence) java.lang.Boolean.FALSE else bandAsleep
        return shouldRequestHistory(
            night, sleepStateUnsupported, gatedSleepState,
            waitingForInitialSleepState, quiet, cooldown, stale
        )
    }

    private fun requestHistoryWhenAwake(historical: Boolean) {
        pendingHistoryIncludesHistory = pendingHistoryIncludesHistory or (historical && !isNightWindow())
        historySyncPending = true
        if (!historyMayRun()) {
            SessionLog.line(
                this,
                "HISTORY_SYNC deferred asleep=$bandAsleep waiting=$waitingForInitialSleepState " +
                        "quiet=${sleepStateUnsupported && nightQuietObserved()}"
            )
            return
        }
        flushPendingHistory()
    }

    private fun flushPendingHistory() {
        if (!historySyncPending || !historyMayRun() || historySync == null) return
        val historical = pendingHistoryIncludesHistory && !isNightWindow()
        historySyncPending = false
        pendingHistoryIncludesHistory = false
        historySync!!.request(historical)
        if (isNightWindow()) {
            nightHadQuiet = nightHadQuiet or nightQuietObserved()
            persistNightCooldown()
        }
        SessionLog.line(this, "HISTORY_SYNC resume includesHistory=$historical")
        if (historical) {
            bindHealthHost()
            nextWeatherAt = System.nanoTime() + TimeUnit.MINUTES.toNanos(30)
            weatherSync.refreshAndSend()
        }
    }

    private fun requestSleepState() {
        val queue = commands
        if (sleepStateUnsupported || stopRequested || queue == null ||
            !shouldPollSleepState(LocalTime.now().hour, nightTrackingEnabled())
        ) {
            return
        }
        val epoch = sessionEpoch
        queue.request(SleepMusic.query(), 2, 78).exceptionally {
            coordinator.execute {
                if (epoch == sessionEpoch && !stopRequested) noteSleepStateMiss()
            }
            null
        }
    }

    @WorkerThread
    private fun noteSleep(command: XiaomiProto.Command) {
        val asleep = SleepMusic.asleep(command) ?: return
        sleepStateMisses = 0
        if (sleepStateUnsupported) {
            sleepStateUnsupported = false
            persistSleepStateUnsupported(false)
        }
        val changed = asleep != bandAsleep
        bandAsleep = asleep
        waitingForInitialSleepState = false
        historyStateDeadlineAt = 0
        if (changed && historySyncPending) {
            SessionLog.line(
                this,
                "HISTORY_SYNC sleep state=${if (asleep) "SLEEPING" else "AWAKE"} pending=$historySyncPending"
            )
        }
        if (asleep) {
            if (changed) {
                wakeEvidence.beginEpisode(System.currentTimeMillis())
                wakeEvidenceCheckedAtMs = 0
            }
            awakeSamples = 0
        } else {
            awakeSamples++
            if (nightAwakeConfirmed(isNightWindow(), awakeSamples)) {
                flushPendingHistory()
            } else {
                SessionLog.line(this, "SLEEP_STATE awake streak=$awakeSamples (deferred)")
            }
        }
        if (!sleepPauseOn) return
        val now = System.currentTimeMillis()
        val report = if (command.subtype == 79) sleepMusic.push(asleep, now) else sleepMusic.observe(asleep, now)
        if ((report.effect() == SleepMusic.Effect.BASELINE && asleep) ||
            report.effect() == SleepMusic.Effect.WOKE
        ) {
            if (SleepMusic.rememberCutoff(this, now)) {
                sleepArmedAtMs = max(sleepArmedAtMs, now)
            }
        }
        applySleep(
            report,
            when (report.effect()) {
                SleepMusic.Effect.PAUSE -> "SLEEP_MUSIC pause"
                SleepMusic.Effect.BASELINE -> "SLEEP_MUSIC baseline asleep=$asleep"
                SleepMusic.Effect.WOKE -> "SLEEP_MUSIC awake"
                SleepMusic.Effect.UNCHANGED -> null
            }
        )
    }

    private fun noteSleepFile(measurements: List<BandHistoryParser.Measurement>?) {
        if (!sleepPauseOn || measurements == null) return
        val now = System.currentTimeMillis()
        for (measurement in measurements) {
            if ("sleep_interval" != measurement.kind) continue
            val report = sleepMusic.currentNight(measurement.startMs, measurement.endMs, sleepArmedAtMs, now)
            if (report.effect() != SleepMusic.Effect.PAUSE) continue
            applySleep(report, "SLEEP_MUSIC pause file")
        }
    }

    private fun applySleep(report: SleepMusic.Report, line: String?) {
        if (line == null) return
        SessionLog.line(this, line)
        Log.i("OplusBandBridge", line)
        if (report.effect() != SleepMusic.Effect.PAUSE) return
        val generation = report.generation()
        main.post {
            if (!sleepPauseOn || sleepMusic.generation() != generation) return@post
            NativeMusic.pause(this)
        }
    }

    private fun sendDndRulesAgain() {
        pushDndState(true)
    }

    private fun noteBandRules(command: XiaomiProto.Command) {
        val on = BandDndCommand.manualState(command) ?: return
        dndBandKnown = true
        dndBandOn = on
    }

    private fun adoptStoredBand() {
        if (!dndSync.on() || dndPushPhone || !dndBandKnown) return
        val phoneOn = PhoneDnd.blocksNotifications(PhoneDnd.currentFilter(this))
        if (dndBandOn == phoneOn || !dndAdopted.compareAndSet(false, true)) return
        requestPhoneDnd(dndBandOn)
    }

    private fun show(title: String) {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, "手环连接", NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
        val notice = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        // One shot: the action rides only on the notice that statusCopy just produced.
        val offered = bondRepairOffered
        bondRepairOffered = false
        if (offered) {
            val repair = Intent(this, BandLiveService::class.java).setAction(ACTION_REPAIR)
            notice.addAction(
                Notification.Action.Builder(
                    null, "重新配对",
                    PendingIntent.getService(
                        this, 0, repair,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                ).build()
            )
        }
        val built = notice.build()
        synchronized(this) {
            if (title == noticeTitle && offered == bondRepairShown) return
            startForeground(NOTICE, built, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            noticeTitle = title
            bondRepairShown = offered
        }
    }

    private fun offerIcon(queried: String?) {
        val pkg = resolveIconPackage(queried)
        if (!NotificationIcons.available(this, pkg)) {
            SessionLog.line(this, "ICON_SKIP pkg=$pkg")
            return
        }
        iconPackage = pkg
        sendSessionCommand(BandNotificationCommand.iconQueryReply(pkg))
        SessionLog.line(this, "ICON_QUERY pkg=$pkg")
    }

    private fun resolveIconPackage(queried: String?): String {
        if (queried.isNullOrBlank()) return ""
        synchronized(iconPackages) {
            for (full in iconPackages) if (full.startsWith(queried)) return full
        }
        return queried
    }

    private fun uploadIcon(active: SppDiagnosticClient, request: XiaomiProto.NotificationIconRequest) {
        if (request.status != 0) return
        val pkg = iconPackage
        val size = request.size
        val format = request.pixelFormat
        if (pkg.isNullOrBlank() || !iconUploadBusy.compareAndSet(false, true)) {
            SessionLog.line(this, "ICON_BUSY")
            return
        }
        coordinator.execute {
            try {
                val pixels = NotificationIcons.argb(this, pkg, size)
                val encoded = NotificationIconPixels.encode(pixels, size, size, format)
                val queue = commands
                if (queue == null || client !== active) {
                    iconUploadBusy.set(false)
                    return@execute
                }
                queue.request(NotificationIconUpload.request(encoded), 22, 0)
                    .whenComplete { ack, error -> deliverIcon(active, encoded, ack, error) }
            } catch (failure: RuntimeException) {
                iconUploadBusy.set(false)
                SessionLog.line(this, "ICON_ENCODE ${failure.javaClass.simpleName}")
            }
        }
    }

    private fun deliverIcon(
        active: SppDiagnosticClient,
        encoded: ByteArray,
        ack: XiaomiProto.Command?,
        error: Throwable?
    ) {
        try {
            if (error != null || client !== active) {
                iconUploadBusy.set(false)
                SessionLog.line(this, "ICON_UPLOAD_FAILED")
                return
            }
            val chunk = NotificationIconUpload.acceptedChunk(ack, active.notificationPayloadLimit())
            val parts = NotificationIconUpload.chunks(NotificationIconUpload.envelope(encoded), chunk)
            sendIconPart(active, parts, 0)
        } catch (failure: RuntimeException) {
            iconUploadBusy.set(false)
            SessionLog.line(this, "ICON_UPLOAD_FAILED")
        }
    }

    private fun sendIconPart(active: SppDiagnosticClient, parts: List<ByteArray>, index: Int) {
        if (client !== active) {
            iconUploadBusy.set(false)
            return
        }
        if (index >= parts.size) {
            iconUploadBusy.set(false)
            SessionLog.line(this, "ICON_UPLOAD parts=${parts.size}")
            return
        }
        active.sendDataChunk(parts[index]).whenComplete { _, error ->
            if (error != null) {
                iconUploadBusy.set(false)
                SessionLog.line(this, "ICON_UPLOAD_FAILED part=$index")
                return@whenComplete
            }
            sendIconPart(active, parts, index + 1)
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        unbindHealthHost()
        if (receiverRegistered) {
            unregisterReceiver(bluetoothEvents)
            receiverRegistered = false
        }
        if (dndReceiverRegistered) {
            unregisterReceiver(dndEvents)
            dndReceiverRegistered = false
        }
        try {
            contentResolver.unregisterContentObserver(zenMode)
        } catch (ignored: RuntimeException) {
        }
        requestStop()
        calls?.close()
        weatherSync.close()
        healthReplay.close()
        coordinator.execute {
            if (historySync != null) {
                historySync!!.close()
                historySync = null
            }
        }
        coordinator.shutdown()
        worker.shutdownNow()
        super.onDestroy()
        destroyed.countDown()
    }

    companion object {
        private const val ACTION_SYNC = "com.originband.link.SYNC"
        private const val ACTION_REPAIR = "com.originband.link.REPAIR"
        @Volatile
        private var instance: BandLiveService? = null
        @Volatile
        private var deviceLogReceiver: XiaomiMassReceive? = null
        @Volatile
        private var diagnosticPaused = false
        private const val SUPERVISOR_STALL_MS = 180_000L

        /** Daytime idle cadence. Night history requests are deliberately much less frequent. */
        private const val IDLE_POLL_MINUTES = 30L
        private const val NIGHT_POLL_MINUTES = 120L
        private const val NIGHT_START_HOUR = 22
        private const val NIGHT_END_HOUR = 8
        private const val NIGHT_HARD_END_HOUR = 14
        private const val QUIET_STEPS_MINUTES = 60L
        private const val QUIET_SAMPLE_MAX_AGE_MINUTES = 90L
        private const val QUIET_WAKE_STEPS = 30L
        private const val NIGHT_STATS_REFRESH_MINUTES = 60L
        private const val AWAKE_CONFIRM_SAMPLES = 2
        private const val SLEEP_STATE_MISS_LIMIT = 2
        private const val NIGHT_RESTART_COOLDOWN_MINUTES = 60L
        private const val LIVE_PREFS = "live-service"
        private const val LAST_NIGHT_FLUSH = "lastNightFlushAtMs"
        private const val WAKE_CATCHUP_DATE = "wakeCatchupDate"

        /**
         * The band reports the level itself and only pushes a change when its own counter moves, so
         * the phone copy is whatever the last request returned. The idle session is already up, and
         * one 4-byte request costs no extra link, so ask more often than the file list.
         */
        private const val BATTERY_POLL_MINUTES = 10L

        /**
         * Sleep is tracked independently from the music feature. History file lists close the band's
         * open sleep file, so that request must never run until the band is known to be awake.
         */
        private const val SLEEP_STATE_POLL_MINUTES = 5L
        private const val INITIAL_HISTORY_SYNC_WAIT_SECONDS = 20L
        private const val REALTIME_STATS_TIMEOUT_SECONDS = 30L

        /** 8/45 opens the band's live-stats stream. The band measures heart rate while that stream runs, so only the first snapshot is kept. */
        private const val REALTIME_STATS_START = 45

        /** 8/46 stops the live stream after the first snapshot. */
        private const val REALTIME_STATS_STOP = 46
        private val SESSION_IDS = AtomicLong()
        private const val HEALTH_PACKAGE = HostIdentity.HEALTH_PACKAGE
        private const val CHANNEL = "band-live"
        private val NOTIFICATIONS = NotifyDedupe()
        private val HELD_NOTIFICATIONS = NotifyReplay()
        private const val NOTICE = 7
        private const val INITIAL_BACKOFF_MS = 3_000L
        private const val MAX_BACKOFF_MS = 30_000L

        /** Config errors stay in this process. Exiting is what makes health launch the app. */
        private const val QUIET_BACKOFF_MS = 120_000L
        private const val FEATURES = "features"
        private const val DND_SYNC_OFF_KEY = "dndSyncOff"

        /** A band that does not answer subtype 14 still gets the mirror, just this much later. */
        private const val DND_ASK_FALLBACK_MS = 1_500L
        @Volatile
        private var lastMusic: XiaomiProto.Command? = null

        @JvmStatic
        fun start(context: Context) {
            admit(context)
            launch(context, false)
        }

        /** Package update runs this off the main thread and can wait for the root start. */
        @JvmStatic
        fun startBlocking(context: Context) {
            admit(context)
            launch(context, true)
        }

        /** Health card reads and notification IPC. Does nothing while this process already hosts the service. */
        @JvmStatic
        fun ensureProcess(context: Context) {
            val live = instance
            if (live != null && !live.stopRequested) return
            try {
                start(context)
            } catch (ignored: RuntimeException) {
            }
        }

        /** True while this process hosts the service and a stop has not been requested. */
        @JvmStatic
        fun isRunning(): Boolean {
            val live = instance
            return live != null && !live.stopRequested
        }

        @JvmStatic
        fun mayWake(context: Context?): Boolean {
            if (context == null || diagnosticPaused) return false
            if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return false
            if (!BandStateRepository(context).isRegistered()) return false
            return OwnershipController(context).nativeReady()
        }

        private fun admit(context: Context) {
            if (diagnosticPaused) throw IllegalStateException("DIAGNOSTIC_ACTIVE")
            if (!context.getSystemService(UserManager::class.java).isUserUnlocked) {
                throw IllegalStateException("USER_LOCKED")
            }
            if (!BandStateRepository(context).isRegistered()) {
                throw IllegalStateException("DEVICE_NOT_REGISTERED")
            }
            if (!OwnershipController(context).nativeReady()) {
                throw IllegalStateException("NATIVE_OWNERSHIP_REQUIRED")
            }
        }

        private fun launch(context: Context, waitForRoot: Boolean) {
            try {
                context.startForegroundService(Intent(context, BandLiveService::class.java))
            } catch (backgroundRejected: RuntimeException) {
                if (waitForRoot) HostKeepAlive.startBridge(context)
                else HostKeepAlive.startBridgeAsync(context)
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            val live = instance
            live?.requestStop()
            context.stopService(Intent(context, BandLiveService::class.java))
        }

        @JvmStatic
        @Throws(InterruptedException::class)
        fun pauseForDiagnostic(context: Context) {
            diagnosticPaused = true
            val live = instance
            stop(context)
            if (live != null && (!live.destroyed.await(10, TimeUnit.SECONDS) ||
                        !live.stopped.await(10, TimeUnit.SECONDS))
            ) {
                throw IllegalStateException("LIVE_SESSION_STILL_ACTIVE")
            }
            if (!SppDiagnosticClient.stopAllAndWait()) {
                throw IllegalStateException("DIAGNOSTIC_SOCKET_STILL_ACTIVE")
            }
        }

        @JvmStatic
        fun resumeAfterDiagnostic(context: Context) {
            diagnosticPaused = false
            if (BandStateRepository(context).isRegistered() && OwnershipController(context).nativeReady()) {
                start(context)
            }
        }

        /** Accepts a request without waiting for root checks, Bluetooth, or a Binder transaction. */
        @JvmStatic
        fun requestSync(context: Context): String {
            if (diagnosticPaused) return "OPEN_CONFIG_REQUIRED"
            if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return "OPEN_CONFIG_REQUIRED"
            if (!BandStateRepository(context).isRegistered()) return "DEVICE_NOT_REGISTERED"
            val ownership = LocalPrefs.open(context, "ownership")
            if ("NATIVE" != ownership.getString("mode", "OFFICIAL") ||
                !ownership.getBoolean("ownsDisable", false) ||
                ownership.getBoolean("officialRestored", false)
            ) {
                return "NATIVE_OWNERSHIP_REQUIRED"
            }
            val live = instance
            if (live != null && !live.stopRequested) {
                live.syncRequested = true
                synchronized(live.stopLock) {
                    live.retryNow = true
                    live.stopLock.notifyAll()
                }
                return try {
                    live.coordinator.execute { live.tick() }
                    "ACCEPTED"
                } catch (stopping: RejectedExecutionException) {
                    "OPEN_CONFIG_REQUIRED"
                }
            }
            return try {
                start(context)
                "ACCEPTED"
            } catch (denied: RuntimeException) {
                "OPEN_CONFIG_REQUIRED"
            }
        }

        /** Switch-on starts a new baseline. A sleep already underway keeps playing. */
        @JvmStatic
        fun sleepPauseChanged(context: Context) {
            val live = instance
            if (live == null || live.stopRequested) return
            live.sleepPauseOn = SleepMusic.enabled(context)
            live.sleepMusic.reset()
            live.sleepArmedAtMs = SleepMusic.armedAt(context)
            if (live.sleepPauseOn) {
                live.nextSleepStateAt = System.nanoTime()
                live.requestSleepState()
            }
        }

        @JvmStatic
        fun requestWeather(
            context: Context,
            sample: BandWeatherEncoder.Sample,
            inspect: Boolean
        ): CompletionStage<Void?> {
            val live = instance
            if (live == null || live.stopRequested || live.commands == null ||
                !BandStateRepository(context).isRegistered()
            ) {
                return CompletableFuture.failedFuture(IllegalStateException("LIVE_SESSION_REQUIRED"))
            }
            return if (inspect) live.weatherSync.inspectCities(sample) else live.weatherSync.send(sample)
        }

        /**
         * Requests the band's complete report-data log bundle and receives the reverse MASS stream.
         * The receiver is installed before report 2/80, so the first file fragment cannot race past us.
         */
        @JvmStatic
        fun requestDeviceLogExport(
            context: Context,
            observer: Consumer<XiaomiMassReceive.Progress>?
        ): CompletionStage<XiaomiMassReceive.Result> {
            val live = instance
            val client = live?.client
            val queue = live?.commands
            if (client == null || queue == null || live.stopRequested) {
                return CompletableFuture.failedFuture(IllegalStateException("LIVE_SESSION_REQUIRED"))
            }
            val directory = File(context.cacheDir, "device-logs/${System.currentTimeMillis()}")
            if (!directory.isDirectory && !directory.mkdirs()) {
                return CompletableFuture.failedFuture(IllegalStateException("DEVICE_LOG_DIRECTORY_FAILED"))
            }
            val safe = Consumer<XiaomiMassReceive.Progress> { progress ->
                if (observer == null) return@Consumer
                try {
                    observer.accept(progress)
                } catch (ignored: RuntimeException) {
                }
            }
            val receiver = XiaomiMassReceive(directory, safe)
            if (deviceLogReceiver != null) {
                return CompletableFuture.failedFuture(IllegalStateException("DEVICE_LOG_RECEIVE_ACTIVE"))
            }
            deviceLogReceiver = receiver
            try {
                client.beginDeviceLogReceive(receiver)
            } catch (unavailable: Exception) {
                deviceLogReceiver = null
                return CompletableFuture.failedFuture(unavailable)
            }
            SessionLog.line(context, "DEVICE_LOG_EXPORT request")
            return queue.request(BandLogCommand.exportDeviceLog(), 2, 81, 120_000L)
                .thenCompose { response ->
                    val status = BandLogCommand.resultStatus(response)
                    if (status != 0) {
                        client.cancelDeviceLogReceive(deviceLogStatus(status))
                        return@thenCompose CompletableFuture.failedFuture<XiaomiMassReceive.Result>(
                            IllegalStateException(deviceLogStatus(status))
                        )
                    }
                    SessionLog.line(context, "DEVICE_LOG_EXPORT receiving")
                    val completion = receiver.completion().toCompletableFuture()
                    completion.orTimeout(10, TimeUnit.MINUTES)
                    completion.whenComplete { result, error ->
                        if (error != null) {
                            client.cancelDeviceLogReceive("DEVICE_LOG_RECEIVE_FAILED")
                            SessionLog.line(
                                context,
                                "DEVICE_LOG_EXPORT failed ${error.javaClass.simpleName}"
                            )
                        } else {
                            SessionLog.line(
                                context,
                                "DEVICE_LOG_EXPORT complete bytes=${result.bytes()}"
                            )
                        }
                    }
                }.whenComplete { _, error ->
                    deviceLogReceiver = null
                    if (error != null) client.cancelDeviceLogReceive("DEVICE_LOG_EXPORT_FAILED")
                }
        }

        @JvmStatic
        fun deviceLogProgressSnapshot(): XiaomiMassReceive.Progress {
            val receiver = deviceLogReceiver
            return receiver?.progressSnapshot() ?: XiaomiMassReceive.Progress(0, 0, "", 0)
        }

        private fun deviceLogStatus(status: Int): String = when (status) {
            1 -> "DEVICE_LOG_NO_DATA"
            2 -> "DEVICE_LOG_DIRECT_UPLOAD_UNSUPPORTED"
            3 -> "DEVICE_LOG_DEVICE_BUSY"
            10 -> "DEVICE_LOG_DEVICE_REJECTED"
            else -> "DEVICE_LOG_RESULT_INVALID"
        }

        @JvmStatic
        fun refreshWeather(context: Context) {
            val live = instance
            if (live != null && !live.stopRequested && BandStateRepository(context).isRegistered()) {
                live.weatherSync.refreshAndSend()
            }
        }

        @JvmStatic
        fun notificationSessionReady(context: Context): Boolean {
            val live = instance
            if (live == null || live.stopRequested || live.commands == null ||
                !BandStateRepository(context).isRegistered()
            ) {
                return false
            }
            val owner = LocalPrefs.open(context, "ownership")
            return "NATIVE" == owner.getString("mode", "OFFICIAL") &&
                    owner.getBoolean("ownsDisable", false) &&
                    !owner.getBoolean("officialRestored", false)
        }

        private fun sessionReason(context: Context): String {
            val live = instance
            if (live == null || live.stopRequested || live.commands == null) return "service"
            val registered = BandStateRepository(context).isRegistered()
            val owner = LocalPrefs.open(context, "ownership")
            return "session registered=$registered" +
                    " mode=${owner.getString("mode", "OFFICIAL")}" +
                    " ownsDisable=${owner.getBoolean("ownsDisable", false)}" +
                    " restored=${owner.getBoolean("officialRestored", false)}"
        }

        @JvmStatic
        fun callsOwned(): Boolean {
            val live = instance
            return live != null && live.calls != null && live.calls!!.ownsCalls()
        }

        @JvmStatic
        fun noteCallAnswered() {
            val live = instance
            if (live != null && live.calls != null) live.calls!!.noteAnswered()
        }

        @JvmStatic
        fun notificationSessionId(): Long {
            val live = instance
            return if (live == null || live.stopRequested || live.commands == null) 0 else live.sessionEpoch
        }

        @JvmStatic
        fun notificationPayloadLimit(): Int {
            val live = instance
            val active = live?.client
            return if (active == null || live.commands == null) 0 else active.notificationPayloadLimit()
        }

        @JvmStatic
        fun sendSessionCommand(command: XiaomiProto.Command?): CompletionStage<Void?> {
            val live = instance
            val queue = live?.commands
            if (queue == null || command == null || !command.isInitialized) {
                return CompletableFuture.failedFuture(
                    IllegalStateException("NOTIFICATION_SESSION_UNAVAILABLE")
                )
            }
            if (command.type == 18 && command.subtype == 1) lastMusic = command
            return queue.send(command)
        }

        /** Starts or stops the band's find-watch alert on the active authenticated live session. */
        @JvmStatic
        fun findWatch(context: Context?, start: Boolean): String {
            if (context == null || diagnosticPaused) return "OPEN_CONFIG_REQUIRED"
            if (!BandStateRepository(context).isRegistered()) return "DEVICE_NOT_REGISTERED"
            if (notificationSessionId() == 0L) return "LIVE_SESSION_REQUIRED"
            return try {
                sendSessionCommand(BandSystemCommand.findWatch(start))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS)
                SessionLog.line(context, "bridge FIND_WATCH start=$start")
                "ACCEPTED"
            } catch (failed: Exception) {
                SessionLog.line(context, "bridge FIND_WATCH_FAILED start=$start")
                "FIND_WATCH_FAILED"
            }
        }

        @JvmStatic
        fun sendNotification(
            context: Context,
            command: XiaomiProto.Command?
        ): CompletionStage<Void?> {
            if (!notificationCommand(command)) {
                SessionLog.line(context, "NOTIFY_DROP reason=command")
                return CompletableFuture.failedFuture(
                    IllegalStateException("NOTIFICATION_SESSION_UNAVAILABLE")
                )
            }
            val validCommand = command!!
            val live = instance
            val queue = live?.commands
            val notification = validCommand.notification
            val call = validCommand.subtype == 0 && notification.hasNotification2() &&
                    BandNotificationCommand.isCall(notification.notification2.notification3)
            val clearCall = validCommand.subtype == 1 && notification.hasNotificationDismiss() &&
                    notification.notificationDismiss.notificationIdCount == 1 &&
                    "phone" == notification.notificationDismiss.getNotificationId(0).`package` &&
                    notification.notificationDismiss.getNotificationId(0).id == 0
            val settings = context.getSharedPreferences("notification-settings", MODE_PRIVATE)
            if (call && (!settings.getBoolean("callsEnabled", false) ||
                        context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) !=
                        PackageManager.PERMISSION_GRANTED)
            ) {
                SessionLog.line(context, "NOTIFY_DROP reason=call-permission")
                return CompletableFuture.failedFuture(IllegalStateException("CALL_PERMISSION_REQUIRED"))
            }
            if (!call && !clearCall) {
                val manager = context.getSystemService(NotificationManager::class.java)
                if (!settings.getBoolean("enabled", true) ||
                    !manager.isNotificationListenerAccessGranted(
                        ComponentName(context, BandNotificationListener::class.java)
                    )
                ) {
                    SessionLog.line(context, "NOTIFY_DROP reason=access")
                    return CompletableFuture.failedFuture(
                        IllegalStateException("NOTIFICATION_ACCESS_REQUIRED")
                    )
                }
            }
            if (queue == null || !notificationSessionReady(context)) {
                if (mayWake(context)) return holdNotification(context, validCommand)
                SessionLog.line(
                    context,
                    "NOTIFY_DROP reason=${if (queue == null) "service" else sessionReason(context)}"
                )
                return CompletableFuture.failedFuture(
                    IllegalStateException("NOTIFICATION_SESSION_UNAVAILABLE")
                )
            }
            if (duplicate(context, validCommand)) return CompletableFuture.completedFuture(null)
            if (suppressForDnd(validCommand, PhoneDnd.currentFilter(context))) {
                Log.i("OplusBandBridge", "NOTIFY_SUPPRESSED_DND")
                SessionLog.line(context, "NOTIFY_SUPPRESSED_DND")
                return CompletableFuture.completedFuture(null)
            }
            return try {
                val fitted = BandNotificationCommand.fitToPayload(
                    validCommand,
                    notificationPayloadLimit()
                )
                SessionLog.line(
                    context,
                    "NOTIFY_OUT type=${fitted.type} subtype=${fitted.subtype} bytes=${fitted.serializedSize}"
                )
                noteIconPackage(fitted)
                routeWatchCall(context, fitted)
                queue.send(fitted)
            } catch (tooLarge: IllegalArgumentException) {
                SessionLog.line(context, "NOTIFY_DROP reason=too-large")
                CompletableFuture.failedFuture(
                    IllegalStateException("NOTIFICATION_IDENTITY_TOO_LARGE")
                )
            }
        }

        /** OHealth already decided this notification may reach the band. */
        @JvmStatic
        fun forwardHostNotification(
            context: Context,
            command: XiaomiProto.Command?
        ): CompletionStage<Void?> {
            if (!notificationCommand(command)) {
                Log.i("OplusBandBridge", "NOTIFY_DROP reason=command")
                SessionLog.line(context, "NOTIFY_DROP reason=command")
                return CompletableFuture.failedFuture(
                    IllegalStateException("NOTIFICATION_SESSION_UNAVAILABLE")
                )
            }
            val validCommand = command!!
            val live = instance
            val queue = live?.commands
            if (queue == null || !notificationSessionReady(context)) {
                if (mayWake(context)) return holdNotification(context, validCommand)
                val reason = if (queue == null) "service" else sessionReason(context)
                Log.i("OplusBandBridge", "NOTIFY_DROP reason=$reason")
                SessionLog.line(context, "NOTIFY_DROP reason=$reason")
                return CompletableFuture.failedFuture(
                    IllegalStateException("NOTIFICATION_SESSION_UNAVAILABLE")
                )
            }
            if (duplicate(context, validCommand)) return CompletableFuture.completedFuture(null)
            if (ordinaryPost(validCommand) && PhoneDnd.blocksNotifications(PhoneDnd.currentFilter(context))) {
                Log.i("OplusBandBridge", "NOTIFY_SUPPRESSED_DND")
                SessionLog.line(context, "NOTIFY_SUPPRESSED_DND")
                return CompletableFuture.completedFuture(null)
            }
            return try {
                val fitted = BandNotificationCommand.fitToPayload(
                    validCommand,
                    notificationPayloadLimit()
                )
                Log.i(
                    "OplusBandBridge",
                    "NOTIFY_OUT type=${fitted.type} subtype=${fitted.subtype} " +
                            "bytes=${fitted.serializedSize} limit=${notificationPayloadLimit()}"
                )
                SessionLog.line(
                    context,
                    "NOTIFY_OUT type=${fitted.type} subtype=${fitted.subtype} bytes=${fitted.serializedSize}"
                )
                noteIconPackage(fitted)
                routeWatchCall(context, fitted)
                queue.send(fitted)
            } catch (tooLarge: IllegalArgumentException) {
                SessionLog.line(context, "NOTIFY_DROP reason=too-large")
                CompletableFuture.failedFuture(
                    IllegalStateException("NOTIFICATION_IDENTITY_TOO_LARGE")
                )
            }
        }

        /** Watches show the call over HFP. The notification packet still carries the name. */
        private fun routeWatchCall(context: Context, command: XiaomiProto.Command) {
            val notification = command.notification
            val call = command.subtype == 0 && notification.hasNotification2() &&
                    BandNotificationCommand.isCall(notification.notification2.notification3)
            val clearCall = command.subtype == 1 && notification.hasNotificationDismiss() &&
                    notification.notificationDismiss.notificationIdCount == 1 &&
                    "phone" == notification.notificationDismiss.getNotificationId(0).`package` &&
                    notification.notificationDismiss.getNotificationId(0).id == 0
            if (!call && !clearCall) return
            val model = LocalPrefs.open(context, "band-state").getString("modelId", "") ?: ""
            if (!BandCatalog.watchForm(model)) return
            val state = if (call) notification.notification2.notification3.callState else 0
            WatchCallHeadset.apply(context, WatchCallRoute.link(true, state))
        }

        private fun notificationCommand(command: XiaomiProto.Command?): Boolean =
            command != null && command.type == 7 &&
                    (command.subtype == 0 || command.subtype == 1) &&
                    command.hasNotification()

        private fun holdNotification(
            context: Context,
            command: XiaomiProto.Command
        ): CompletionStage<Void?> {
            SessionLog.line(context, "NOTIFY_HOLD")
            return HELD_NOTIFICATIONS.add(command, SystemClock.elapsedRealtime())
        }

        @JvmStatic
        fun suppressForDnd(command: XiaomiProto.Command, filter: Int): Boolean =
            ordinaryPost(command) && PhoneDnd.blocksNotifications(filter)

        private fun ordinaryPost(command: XiaomiProto.Command): Boolean {
            if (command.subtype != 0 || !command.hasNotification() ||
                !command.notification.hasNotification2()
            ) {
                return false
            }
            return !BandNotificationCommand.isCall(command.notification.notification2.notification3)
        }

        private fun duplicate(context: Context, command: XiaomiProto.Command?): Boolean {
            if (command == null || (!ordinaryPost(command) && !ordinaryDismiss(command))) return false
            if (NOTIFICATIONS.first(NotifyDedupe.identity(command), SystemClock.elapsedRealtime())) {
                return false
            }
            SessionLog.line(context, "NOTIFY_DROP reason=duplicate")
            return true
        }

        private fun ordinaryDismiss(command: XiaomiProto.Command): Boolean {
            if (command.subtype != 1 || !command.hasNotification() ||
                !command.notification.hasNotificationDismiss()
            ) {
                return false
            }
            val dismiss = command.notification.notificationDismiss
            if (dismiss.notificationIdCount != 1) return false
            val id = dismiss.getNotificationId(0)
            return "phone" != id.`package`
        }

        @JvmStatic
        fun cancelNotifications(context: Context?) {
            val live = instance
            val queue = live?.commands
            queue?.cancelNotifications(IllegalStateException("NOTIFICATION_CANCELLED"))
        }

        @JvmStatic
        fun cancelCall(context: Context?) {
            val live = instance
            val queue = live?.commands
            queue?.cancelCall(IllegalStateException("CALL_CANCELLED"))
        }

        @JvmStatic
        fun ownsCalls(): Boolean = instance?.calls?.ownsCalls() == true

        @JvmStatic
        fun refreshNotificationSettings(context: Context?) {
            BandNotificationListener.connectionChanged()
            PhoneMusic.onPreferenceChanged(context)
            val live = instance
            if (live != null && !live.stopRequested) live.calls?.refresh()
        }

        /** 45 starts the band's live-stats stream, 46 stops it, 47 is the periodic event. */
        private fun realtimeStats(subtype: Int): XiaomiProto.Command =
            XiaomiProto.Command.newBuilder().setType(8).setSubtype(subtype).build()

        @JvmStatic
        fun isNightWindow(hour: Int): Boolean =
            hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR

        private fun isNightWindow(): Boolean =
            isNightWindow(LocalTime.now().hour)

        @JvmStatic
        fun nightStillSuppressed(hour: Int, wakeEvidence: Boolean): Boolean {
            val window = hour >= NIGHT_START_HOUR || hour < NIGHT_HARD_END_HOUR
            return window && !wakeEvidence
        }

        private fun isSleepSuppressionWindow(hour: Int): Boolean =
            hour >= NIGHT_START_HOUR || hour < NIGHT_HARD_END_HOUR

        private fun isSleepSuppressionWindow(): Boolean =
            isSleepSuppressionWindow(LocalTime.now().hour)

        @JvmStatic
        fun shouldPollSleepState(hour: Int, nightTrackingEnabled: Boolean): Boolean =
            nightTrackingEnabled && isNightWindow(hour)

        @JvmStatic
        fun historyPeriodMinutes(hour: Int): Long =
            if (isNightWindow(hour)) NIGHT_POLL_MINUTES else IDLE_POLL_MINUTES

        private fun historyPeriodMinutes(): Long =
            historyPeriodMinutes(LocalTime.now().hour)

        @JvmStatic
        fun nightStatsRefreshMinutes(): Long = NIGHT_STATS_REFRESH_MINUTES

        @JvmStatic
        fun quietSampleMaxAgeMinutes(): Long = QUIET_SAMPLE_MAX_AGE_MINUTES

        @JvmStatic
        fun nightPollMinutes(): Long = NIGHT_POLL_MINUTES

        @JvmStatic
        fun sleepStateUnsupportedForCurrentDevice(context: Context): Boolean = try {
            val deviceId = BandStateRepository(context).deviceId()
            deviceId.isNotBlank() && context.getSharedPreferences(LIVE_PREFS, MODE_PRIVATE)
                .getBoolean(sleepUnsupportedKey(deviceId), false)
        } catch (unavailable: RuntimeException) {
            false
        }

        private fun sleepUnsupportedKey(deviceId: String): String =
            "sleepStateUnsupported_$deviceId"

        private fun lastNightFlushKey(deviceId: String): String =
            "${LAST_NIGHT_FLUSH}_$deviceId"

        @JvmStatic
        fun quietSuppressesHistory(quiet: Boolean, stale: Boolean): Boolean =
            quiet || stale

        @JvmStatic
        fun staleStepSample(nowMs: Long, stepsAtMs: Long): Boolean =
            stepsAtMs <= 0 || nowMs - stepsAtMs > TimeUnit.MINUTES.toMillis(QUIET_SAMPLE_MAX_AGE_MINUTES)

        @JvmStatic
        fun quietCounterReset(
            previousSteps: Long,
            previousAtMs: Long,
            steps: Long,
            stepsAtMs: Long
        ): Boolean {
            if (previousSteps == Long.MIN_VALUE) return false
            if (steps < previousSteps) return true
            if (previousAtMs <= 0 || stepsAtMs <= previousAtMs) return false
            val zone = ZoneId.systemDefault()
            return Instant.ofEpochMilli(previousAtMs).atZone(zone).toLocalDate() !=
                    Instant.ofEpochMilli(stepsAtMs).atZone(zone).toLocalDate()
        }

        @JvmStatic
        fun quietStepNoise(steps: Long, baselineSteps: Long): Boolean =
            baselineSteps >= 0 && steps >= baselineSteps && steps - baselineSteps < QUIET_WAKE_STEPS

        @JvmStatic
        fun quietElapsed(nowMs: Long, quietSinceMs: Long): Boolean =
            quietSinceMs > 0 && nowMs - quietSinceMs >= TimeUnit.MINUTES.toMillis(QUIET_STEPS_MINUTES)

        @JvmStatic
        fun shouldRequestHistory(
            night: Boolean,
            unsupported: Boolean,
            bandAsleep: Boolean?,
            waitingInitial: Boolean,
            quiet: Boolean,
            cooldownActive: Boolean,
            stale: Boolean
        ): Boolean {
            if (waitingInitial || cooldownActive) return false
            if (!unsupported) return java.lang.Boolean.TRUE != bandAsleep
            if (stale) return false
            return !quiet
        }

        @JvmStatic
        fun nightAwakeConfirmed(night: Boolean, awakeSamples: Int): Boolean =
            !night || awakeSamples >= AWAKE_CONFIRM_SAMPLES

        private fun retryable(code: String): Boolean {
            if (code.indexOf('_') < 0) return true
            return when (code) {
                "BLUETOOTH_DISABLED", "DIAGNOSTIC_TIMEOUT", "CANCELLED", "CONNECTION_ALREADY_ACTIVE",
                "USER_LOCKED", "EMPTY_SOCKET_READ", "CLOSED", "CONNECT_TIMEOUT" -> true
                // A missing bond fails before the socket exists, so retrying is cheap and the band
                // comes back within seconds of whoever restores the bond.
                "OFFICIAL_PAIRING_REQUIRED" -> true
                // Link loss is not a bad binding. Quiet backoff left the band down for two minutes.
                else -> code.startsWith("CLOSED") || code.startsWith("BLE_")
            }
        }

        private fun adapterState(state: Int): String = when (state) {
            BluetoothAdapter.STATE_OFF -> "off"
            BluetoothAdapter.STATE_TURNING_ON -> "turning-on"
            BluetoothAdapter.STATE_ON -> "on"
            BluetoothAdapter.STATE_TURNING_OFF -> "turning-off"
            else -> "other"
        }

        private fun bondName(state: Int): String = when (state) {
            BluetoothDevice.BOND_NONE -> "none"
            BluetoothDevice.BOND_BONDING -> "bonding"
            BluetoothDevice.BOND_BONDED -> "bonded"
            else -> "other"
        }

        private fun bondReason(reason: Int): String = when (reason) {
            -1 -> "absent"
            1 -> "auth-failed"
            2 -> "auth-rejected"
            3 -> "auth-canceled"
            4 -> "remote-down"
            5 -> "discovery"
            6 -> "auth-timeout"
            7 -> "repeated"
            8 -> "remote-auth-canceled"
            9 -> "removed"
            else -> "other"
        }

        /** The band's 同步手机勿扰 switch as this app last recorded it. Lab screens read it too. */
        @JvmStatic
        fun bandSyncEnabled(context: Context): Boolean =
            !LocalPrefs.open(context, FEATURES).getBoolean(DND_SYNC_OFF_KEY, false)

        private fun sendQuietly(queue: LiveCommandQueue, command: XiaomiProto.Command) {
            val subtype = command.subtype
            queue.send(command).exceptionally {
                Log.i("OplusBandBridge", "DND_SYNC_REJECTED subtype=$subtype")
                null
            }
        }

        private fun describeSystem(command: XiaomiProto.Command): String {
            val raw = command.toByteArray()
            val shown = min(raw.size, 120)
            val hex = StringBuilder(shown * 2)
            for (i in 0 until shown) {
                val b = raw[i].toInt()
                hex.append(Character.forDigit((b shr 4) and 0xf, 16))
                    .append(Character.forDigit(b and 0xf, 16))
            }
            val rules = StringBuilder()
            if (command.hasSystem() && command.system.hasPhoneZenRules()) {
                val list = command.system.phoneZenRules.ruleList
                rules.append(" rules=").append(list.size)
                for (rule in list) {
                    rules.append(" {").append(rule.name).append(" manual=").append(rule.manual)
                        .append(" state=").append(rule.state).append('}')
                }
            }
            return "rx type=2 subtype=${command.subtype} status=${command.status} bytes=${raw.size}$rules hex=$hex"
        }

        private fun noteIconPackage(command: XiaomiProto.Command) {
            val live = instance
            if (live == null || command.type != 7 || command.subtype != 0 ||
                !command.hasNotification() || !command.notification.hasNotification2() ||
                !command.notification.notification2.hasNotification3()
            ) {
                return
            }
            val pkg = command.notification.notification2.notification3.`package`
            if (pkg.isNullOrBlank() || "phone" == pkg) return
            synchronized(live.iconPackages) {
                live.iconPackages.remove(pkg)
                live.iconPackages.addLast(pkg)
                while (live.iconPackages.size > 32) live.iconPackages.removeFirst()
            }
        }
    }
}
