// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import com.originband.link.data.LocalPrefs
import com.originband.link.data.SessionLog
import com.originband.link.protocol.BandNotificationCommand
import com.originband.link.service.BandLiveService
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.function.IntSupplier

/**
 * System notification listener. Admission checks the package and visibility first.
 * {@code healthBlock} reads the title and text, so the Health policy step happens as the extras are read.
 */
class BandNotificationListener : NotificationListenerService() {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var listenerConnected = false
    private lateinit var relay: NotificationRelay
    private lateinit var settings: SharedPreferences
    private var appliedSession = 0L
    private var appliedEnabled = false
    private var appliedBody = false
    private var appliedPackages: Set<String> = emptySet()
    private val hold = NotifyHold()
    private var lastWake = ""
    private var lastSkip = ""

    private val settingsChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "observedPackages" || key.startsWith("policy") || key.startsWith("health")) {
            return@OnSharedPreferenceChangeListener
        }
        if (key == "musicEnabled") {
            main.post { PhoneMusic.onPreferenceChanged(this@BandNotificationListener) }
            return@OnSharedPreferenceChangeListener
        }
        main.post(::resetSession)
    }

    private val lockChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            relay.cancelPending()
            BandLiveService.cancelNotifications(this@BandNotificationListener)
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = getSharedPreferences(SETTINGS, MODE_PRIVATE)
        relay = NotificationRelay(
            object : NotificationRelay.Sender {
                override fun send(command: XiaomiProto.Command): CompletionStage<Void?> =
                    BandLiveService.sendNotification(this@BandNotificationListener, command)
            },
            IntSupplier { BandLiveService.notificationPayloadLimit() },
            Executor { command -> main.post(command) },
        )
        settings.registerOnSharedPreferenceChangeListener(settingsChanged)
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(lockChanged, filter, RECEIVER_NOT_EXPORTED)
        instance = this
    }

    override fun onListenerConnected() {
        listenerConnected = true
        wakeLive()
        resetSession()
        PhoneMusic.attach(this, main)
    }

    override fun onListenerDisconnected() {
        PhoneMusic.detach()
        appliedSession = 0
        listenerConnected = false
        relay.disconnected()
        BandLiveService.cancelNotifications(this)
        if (!BandLiveService.isRunning()) return
        main.postDelayed(
            {
                if (listenerConnected || !BandLiveService.isRunning() || !accessGranted(this)) {
                    return@postDelayed
                }
                try {
                    requestRebind(ComponentName(this, BandNotificationListener::class.java))
                    SessionLog.line(this, "NOTIFY_LISTENER rebind")
                } catch (_: RuntimeException) {
                }
            },
            1_000L,
        )
    }

    private fun sessionAllowed(): Boolean =
        listenerConnected && accessGranted(this) && BandLiveService.notificationSessionReady(this)

    private fun notificationsAllowed(): Boolean =
        sessionAllowed() && settings.getBoolean("enabled", true)

    private fun wakeLive() {
        try {
            BandLiveService.start(this)
        } catch (failure: RuntimeException) {
            val name = failure.javaClass.simpleName
            if (name == lastWake) return
            lastWake = name
            SessionLog.line(this, "NOTIFY_WAKE $name")
        }
    }

    private fun resetSession() {
        val enabled = settings.getBoolean("enabled", true)
        val body = settings.getBoolean("showBody", true)
        val storedPackages = settings.getStringSet("packages", emptySet()) ?: emptySet()
        val packages: Set<String> = java.util.Set.copyOf(storedPackages)
        val session = if (notificationsAllowed()) BandLiveService.notificationSessionId() else 0L
        if (session == appliedSession && enabled == appliedEnabled && body == appliedBody &&
            packages == appliedPackages
        ) {
            return
        }
        appliedSession = session
        appliedEnabled = enabled
        appliedBody = body
        appliedPackages = packages
        relay.disconnected()
        BandLiveService.cancelNotifications(this)
        relay.configure(enabled, packages, body)
        if (!enabled) hold.clear()
        if (!notificationsAllowed()) return
        val baseline = HashMap<String, Long>()
        try {
            val active = activeNotifications
            if (active != null) {
                for (item in active) {
                    observePackage(item.packageName)
                    if (hold.contains(item.key)) continue
                    baseline[item.key] = item.postTime
                    if (baseline.size > NotificationRelay.CAPACITY) break
                }
            }
            relay.connected(baseline)
            if (relay.lastFailureCode() != "NOTIFICATION_BASELINE_CAPACITY") {
                for (event in hold.drain()) relay.posted(event)
            }
        } catch (_: SecurityException) {
            relay.disconnected()
        }
    }

    override fun onNotificationPosted(item: StatusBarNotification?, rankings: RankingMap?) {
        if (!listenerConnected || item == null) return
        observePackage(item.packageName) // Package names only, even while disabled.
        val posted = item.notification
        if (posted != null && Notification.CATEGORY_CALL == posted.category &&
            CallPresentation.connected(posted)
        ) {
            BandLiveService.noteCallAnswered()
        }
        if (!accessGranted(this)) {
            skip("access", item.packageName)
            return
        }
        if (!settings.getBoolean("enabled", true)) {
            skip("switch", item.packageName)
            hold.clear()
            relay.disconnected()
            BandLiveService.cancelNotifications(this)
            return
        }
        if (PhoneAlarmNotice.ringing(item)) {
            if (!BandLiveService.notificationSessionReady(this)) wakeLive()
            PhoneAlarmNotice.posted(this, item)
            return
        }
        val call = item.notification
        if (call != null && Notification.CATEGORY_CALL == call.category) {
            if (!BandLiveService.notificationSessionReady(this)) wakeLive()
            val dialer = getSystemService(TelecomManager::class.java)?.defaultDialerPackage
            if (dialer != null && dialer == item.packageName && BandLiveService.ownsCalls()) {
                skip("call", item.packageName)
                return
            }
            if (CallPresentation.connected(call)) {
                skip("call-active", item.packageName)
                hold.remove(item.key)
                relay.removed(item.key)
                return
            }
            val callsOn = settings.getBoolean("callsEnabled", false)
            val allowed = callsOn ||
                NotifyAdmission.packageAllowed(settings.getStringSet("packages", emptySet()), item.packageName)
            if (!allowed || call.visibility == Notification.VISIBILITY_SECRET || HealthListenerState.connected()) {
                skip(if (HealthListenerState.connected()) "health-live" else "call-filtered", item.packageName)
                hold.remove(item.key)
                relay.removed(item.key)
                return
            }
            val callRanking = Ranking()
            val hasRanking = rankings != null && rankings.getRanking(item.key, callRanking)
            val callImportance = if (hasRanking) callRanking.importance else NotificationManager.IMPORTANCE_HIGH
            val callEvent = event(
                item = item,
                n = call,
                ranking = callRanking,
                importanceOverride = callImportance,
                bypassAllowlist = callsOn,
            )
            if (!BandLiveService.notificationSessionReady(this)) {
                hold.put(callEvent)
                skip("session", item.packageName)
                wakeLive()
                return
            }
            hold.remove(item.key)
            val appName = callEvent.appName()
            SessionLog.line(
                this,
                "NOTIFY_CALL pkg=${item.packageName} app=${if (appName == item.packageName) "package" else "label"}",
            )
            relay.posted(callEvent)
            return
        }
        if (HealthListenerState.connected()) {
            skip("health-live", item.packageName)
            return
        }
        val n = item.notification
        val ranking = Ranking()
        if (!admit(item, n, rankings, ranking) || n == null) {
            skip(skipReason(item, n, rankings, ranking), item.packageName)
            hold.remove(item.key)
            relay.removed(item.key)
            return
        }
        val event = event(item, n, ranking)
        if (!BandLiveService.notificationSessionReady(this)) {
            hold.put(event)
            skip("session", item.packageName)
            wakeLive()
            return
        }
        hold.remove(item.key)
        val appName = event.appName()
        SessionLog.line(
            this,
            "NOTIFY_POST pkg=${item.packageName} app=${if (appName == item.packageName) "package" else "label"}",
        )
        relay.posted(event)
    }

    private fun admit(
        item: StatusBarNotification,
        notification: Notification?,
        rankings: RankingMap?,
        ranking: Ranking,
    ): Boolean {
        if (packageName == item.packageName) return false
        if (PhoneAlarmNotice.CLOCK == item.packageName) return false
        if (!NotifyAdmission.packageAllowed(settings.getStringSet("packages", emptySet()), item.packageName)) {
            return false
        }
        if (localScreenBlocked()) return false
        if (healthBlock(item, notification) != null) return false
        if (notification == null) return false
        if (notification.visibility == Notification.VISIBILITY_SECRET) return false
        if (rankings == null || !rankings.getRanking(item.key, ranking)) return false
        if (ranking.importance <= NotificationManager.IMPORTANCE_LOW) return false
        return true
    }

    private fun localScreenBlocked(): Boolean {
        if (settings.getBoolean("screenOnPush", true)) return false
        val power = getSystemService(PowerManager::class.java)
        val interactive = power != null && power.isInteractive
        return NotifyAdmission.screenBlocks(false, interactive, locked(this))
    }

    private fun healthBlock(item: StatusBarNotification, notification: Notification?): String? {
        val mms = mmsPackages()
        val power = getSystemService(PowerManager::class.java)
        val interactive = power != null && power.isInteractive
        val policyStore = LocalPrefs.open(this, SETTINGS)
        val policy = HealthPolicy.blockReason(
            policyStore.getBoolean("policyKnown", false),
            policyStore.getBoolean("policyMain", true),
            policyStore.getBoolean("policyScreenOnPush", true),
            splitSet(policyStore.getString("policyOpenList", "")),
            splitSet(policyStore.getString("policyClosedList", "")),
            if (policyStore.getBoolean("policyDefaultsKnown", false)) {
                splitSet(policyStore.getString("policyDefaultsList", ""))
            } else {
                null
            },
            mms,
            item.packageName,
            interactive,
            locked(this),
        )
        if (policy != null || notification == null) return policy
        val sms = mms.any { item.packageName == it }
        val extras = notification.extras
        val template = extras?.getCharSequence(Notification.EXTRA_TEMPLATE, "")?.toString()
            ?.takeIf { it.isNotBlank() }
        val progress = extras?.getInt(Notification.EXTRA_PROGRESS_MAX, 0) ?: 0
        val title = extra(notification, Notification.EXTRA_TITLE)
        val text = extra(notification, Notification.EXTRA_TEXT)
        return HealthContentFilter.blockReason(
            extras == null,
            template,
            notification.flags,
            notification.group,
            notification.category,
            progress,
            item.tag,
            item.key,
            title,
            text,
            notification.channelId,
            item.packageName,
            sms,
        )
    }

    private fun mmsPackages(): List<String> {
        val stored = LocalPrefs.open(this, SETTINGS).getString("policyMmsOrder", "")
        if (stored.isBlank()) return emptyList()
        return stored.split('\n').dropLastWhile { it.isEmpty() }
    }

    private fun event(
        item: StatusBarNotification,
        n: Notification,
        ranking: Ranking,
        importanceOverride: Int = ranking.importance,
        bypassAllowlist: Boolean = false,
    ): NotificationRelay.Event {
        val isLocked = locked(this)
        var visible = n
        var bodyAllowed = settings.getBoolean("showBody", true)
        if (isLocked && n.visibility == Notification.VISIBILITY_PRIVATE) {
            val publicVersion = n.publicVersion
            if (publicVersion != null && publicVersion.visibility != Notification.VISIBILITY_SECRET) {
                visible = publicVersion
            } else {
                bodyAllowed = false
            }
        }
        val title = extra(visible, Notification.EXTRA_TITLE)
        var body = if (bodyAllowed) extra(visible, Notification.EXTRA_BIG_TEXT) else ""
        if (bodyAllowed && body.isBlank()) body = extra(visible, Notification.EXTRA_TEXT)
        var appName = AppLabels.label(this, item.packageName)
        if (appName.isBlank()) appName = item.packageName
        return NotificationRelay.Event(
            item.packageName,
            appName,
            item.key,
            title,
            body,
            if (visible !== n) title else null,
            if (visible !== n) body else null,
            item.postTime,
            n.visibility,
            isLocked,
            false,
            false,
            importanceOverride,
            bypassAllowlist,
        )
    }

    private fun skipReason(
        item: StatusBarNotification,
        notification: Notification?,
        rankings: RankingMap?,
        ranking: Ranking,
    ): String {
        if (packageName == item.packageName) return "self"
        if (PhoneAlarmNotice.CLOCK == item.packageName) return "clock"
        if (!NotifyAdmission.packageAllowed(settings.getStringSet("packages", emptySet()), item.packageName)) {
            return "package"
        }
        if (localScreenBlocked()) return "screen"
        val health = healthBlock(item, notification)
        if (health != null) return health
        if (notification == null) return "empty"
        if (notification.visibility == Notification.VISIBILITY_SECRET) return "secret"
        if (rankings == null || !rankings.getRanking(item.key, ranking)) return "ranking"
        if (ranking.importance <= NotificationManager.IMPORTANCE_LOW) return "importance"
        return "filtered"
    }

    private fun skip(reason: String, pkg: String?) {
        val line = "$reason|$pkg"
        if (line == lastSkip) return
        lastSkip = line
        val cleanPkg = if (pkg.isNullOrBlank()) "none" else pkg
        SessionLog.line(this, "NOTIFY_SKIP reason=$reason pkg=$cleanPkg")
    }

    override fun onNotificationRemoved(item: StatusBarNotification?, rankings: RankingMap?, reason: Int) {
        PhoneAlarmNotice.removed(this, item)
        if (item != null) {
            hold.remove(item.key)
            relay.removed(item.key)
        }
    }

    override fun onNotificationRankingUpdate(rankings: RankingMap?) {
        if (!notificationsAllowed()) {
            relay.disconnected()
            BandLiveService.cancelNotifications(this)
            return
        }
        if (rankings == null) return
        // A lower ranking withdraws a notification that was already delivered. The active baseline stays.
        for (key in rankings.orderedKeys) {
            val ranking = Ranking()
            if (rankings.getRanking(key, ranking) && ranking.importance <= NotificationManager.IMPORTANCE_LOW) {
                hold.remove(key)
                relay.removed(key)
            }
        }
    }

    private fun observePackage(packageName: String?) {
        if (packageName == null || this.packageName == packageName) return
        val observed = settings.getStringSet("observedPackages", emptySet()) ?: emptySet()
        if (observed.contains(packageName) || observed.size >= NotificationRelay.CAPACITY) return
        val copy = HashSet(observed)
        copy.add(packageName)
        settings.edit().putStringSet("observedPackages", copy).apply()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        listenerConnected = false
        relay.disconnected()
        BandLiveService.cancelNotifications(this)
        settings.unregisterOnSharedPreferenceChangeListener(settingsChanged)
        unregisterReceiver(lockChanged)
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val SETTINGS = "notification-settings"

        @Volatile
        private var instance: BandNotificationListener? = null

        @JvmStatic
        fun accessGranted(context: Context): Boolean {
            val component = ComponentName(context, BandNotificationListener::class.java)
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager != null && manager.isNotificationListenerAccessGranted(component)) return true
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            )
            if (enabled.isNullOrBlank()) return false
            val flat = component.flattenToString()
            val shortName = component.flattenToShortString()
            for (item in enabled.split(':')) {
                if (flat == item || shortName == item) return true
            }
            return false
        }

        @JvmStatic
        fun listenerSettingsIntent(context: Context): Intent =
            Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS").putExtra(
                "android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME",
                ComponentName(context, BandNotificationListener::class.java).flattenToString(),
            )

        @JvmStatic
        fun listenerConnected(): Boolean {
            val current = instance
            return current != null && current.listenerConnected
        }

        /** The manifest disables the service so the system hides it until native mode. */
        @JvmStatic
        fun ensureEnabled(context: Context) {
            val packages = context.packageManager
            val component = ComponentName(context, BandNotificationListener::class.java)
            if (packages.getComponentEnabledSetting(component) !=
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            ) {
                packages.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
            if (!listenerConnected()) {
                try {
                    requestRebind(component)
                } catch (_: RuntimeException) {
                }
            }
        }

        @JvmStatic
        fun connectionChanged() {
            val current = instance
            current?.main?.post(current::resetSession)
        }

        private fun splitSet(stored: String?): Set<String> {
            val values = HashSet<String>()
            if (stored.isNullOrBlank()) return values
            for (item in stored.split('\n')) {
                if (item.isNotBlank()) values.add(item)
            }
            return values
        }

        /** Title of the SIM call that is ringing now. The number is {@link #currentIncomingCallNumber}, and that read requires the SMS permission. */
        @JvmStatic
        fun currentIncomingCallTitle(context: Context): String? {
            val current = instance
            if (current == null || !current.sessionAllowed() ||
                !current.settings.getBoolean("callsEnabled", false) ||
                context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return null
            }
            val telecom = context.getSystemService(TelecomManager::class.java)
            val dialer = telecom?.defaultDialerPackage ?: return null
            return try {
                val active = current.activeNotifications ?: return null
                var latest: StatusBarNotification? = null
                for (item in active) {
                    val n = item.notification
                    if (dialer == item.packageName && n != null &&
                        Notification.CATEGORY_CALL == n.category &&
                        n.visibility != Notification.VISIBILITY_SECRET &&
                        (latest == null || item.postTime > latest.postTime)
                    ) {
                        latest = item
                    }
                }
                if (latest == null) return null
                var n = latest.notification ?: return null
                if (locked(context) && n.visibility == Notification.VISIBILITY_PRIVATE) {
                    val publicVersion = n.publicVersion
                    if (publicVersion != null) {
                        if (publicVersion.visibility == Notification.VISIBILITY_SECRET) return null
                        n = publicVersion
                    }
                }
                val title = extra(n, Notification.EXTRA_TITLE)
                title.ifBlank { null }
            } catch (_: SecurityException) {
                null
            }
        }

        @JvmStatic
        fun currentIncomingCallNumber(context: Context): String? {
            if (context.checkSelfPermission(Manifest.permission.SEND_SMS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return null
            }
            val current = instance
            if (current == null || !current.sessionAllowed() ||
                !current.settings.getBoolean("callsSmsReply", false)
            ) {
                return null
            }
            val telecom = context.getSystemService(TelecomManager::class.java)
            val dialer = telecom?.defaultDialerPackage ?: return null
            return try {
                val active = current.activeNotifications ?: return null
                for (item in active) {
                    val n = item.notification
                    if (dialer != item.packageName || n == null ||
                        Notification.CATEGORY_CALL != n.category
                    ) {
                        continue
                    }
                    val text = extra(n, Notification.EXTRA_TEXT)
                    if (BandNotificationCommand.usableNumber(text)) return text
                    val title = extra(n, Notification.EXTRA_TITLE)
                    if (BandNotificationCommand.usableNumber(title)) return title
                }
                null
            } catch (_: SecurityException) {
                null
            }
        }

        private fun extra(notification: Notification, key: String): String =
            if (notification.extras == null) {
                ""
            } else {
                NotificationRelay.sanitize(notification.extras.getCharSequence(key))
            }

        private fun locked(context: Context): Boolean {
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            return keyguard == null || keyguard.isDeviceLocked
        }
    }
}
