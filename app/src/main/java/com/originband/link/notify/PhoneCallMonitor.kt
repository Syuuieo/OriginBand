// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Process
import android.os.UserManager
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import com.originband.link.data.BandStateRepository
import com.originband.link.data.LocalPrefs
import com.originband.link.data.SessionLog
import com.originband.link.protocol.BandNotificationCommand
import com.originband.link.service.BandLiveService
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CompletionStage
import java.util.concurrent.ScheduledExecutorService

/** Runs on the service's serial coordinator. The live session owns the transport. */
class PhoneCallMonitor(
    context: Context,
    private val coordinator: ScheduledExecutorService,
) : AutoCloseable {
    private val context: Context = context.applicationContext ?: context
    private val repository = BandStateRepository(this.context)
    private val subscriptions: SubscriptionManager? = this.context.getSystemService(SubscriptionManager::class.java)
    private val telephony: TelephonyManager? = this.context.getSystemService(TelephonyManager::class.java)
    private val appOps: AppOpsManager? = this.context.getSystemService(AppOpsManager::class.java)
    private val settings: SharedPreferences =
        this.context.getSharedPreferences("notification-settings", Context.MODE_PRIVATE)
    private val listeners = HashMap<Int, CallListener>()
    private val gate: PhoneCallGate
    private var subscriptionListenerRegistered = false
    private var gateConnected = false
    private var sessionConnected = false

    @Volatile
    private var caller = "来电"
    private var savedRingerMode = -1

    @Volatile
    private var closed = false

    @Volatile
    private var monitorFailure: String? = null

    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "callsEnabled" || key == "callsHangup" || key == "callsSmsReply" ||
                key == "mode" || key == "ownsDisable" || key == "officialRestored" ||
                key == "registered"
            ) {
                refresh()
            }
        }
    private val permissionListener = AppOpsManager.OnOpChangedListener { _, _ -> refresh() }
    private val subscriptionListener =
        object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() {
                refresh()
            }
        }

    init {
        gate = PhoneCallGate(
            object : PhoneCallGate.Sender {
                override fun send(phase: PhoneCallGate.Phase): CompletionStage<Void?> {
                    SessionLog.line(this@PhoneCallMonitor.context, "CALL_PHASE $phase")
                    if (phase == PhoneCallGate.Phase.NONE) restoreRinger()
                    return BandLiveService.forwardHostNotification(
                        this@PhoneCallMonitor.context,
                        commandFor(phase),
                    )
                }

                override fun cancelQueuedRing() {
                    BandLiveService.cancelCall(this@PhoneCallMonitor.context)
                }
            },
        )
        settings.registerOnSharedPreferenceChangeListener(preferenceListener)
        appOps?.startWatchingMode(
            AppOpsManager.OPSTR_READ_PHONE_STATE,
            this.context.packageName,
            permissionListener,
        )
        appOps?.startWatchingMode(
            AppOpsManager.OPSTR_ANSWER_PHONE_CALLS,
            this.context.packageName,
            permissionListener,
        )
        appOps?.startWatchingMode(
            AppOpsManager.OPSTR_SEND_SMS,
            this.context.packageName,
            permissionListener,
        )
        refresh()
    }

    fun refresh() {
        if (!closed) coordinator.execute { refreshNow(false) }
    }

    fun connected() {
        if (!closed) {
            coordinator.execute {
                if (closed) return@execute
                sessionConnected = true
                refreshNow(true)
            }
        }
    }

    fun disconnected() {
        if (!closed) {
            coordinator.execute {
                sessionConnected = false
                disconnectGate()
            }
        }
    }

    fun noteAnswered() {
        if (!closed) {
            coordinator.execute {
                if (gate.markAnswered()) SessionLog.line(context, "CALL_ANSWERED")
            }
        }
    }

    /** Status carries no call content. Delivered means the band accepted the packet. */
    fun lastFailureCode(): String? {
        if (monitorFailure != null) return monitorFailure
        return if (gate.lastFailure() == null) null else "CALL_SUBMISSION_FAILED"
    }

    fun onBandCommand(command: XiaomiProto.Command?) {
        if (closed || command == null || command.type != 7) return
        coordinator.execute { handleBandCommand(command) }
    }

    private fun handleBandCommand(command: XiaomiProto.Command) {
        if (closed) return
        val subtype = command.subtype
        SessionLog.line(context, "CALL_IN subtype=$subtype")
        if (subtype == 5) {
            silence()
            return
        }
        if (subtype == 2 || isCallDismiss(command)) {
            Log.i("OplusBandBridge", "CALL_HANGUP_FROM_BAND subtype=$subtype")
            SessionLog.line(context, "CALL_HANGUP subtype=$subtype")
            hangup()
            return
        }
        if (subtype == 13 && command.hasNotification() && command.notification.hasNotificationReply()) {
            replySms(command.notification.notificationReply)
        }
    }

    private fun hangupEnabled(): Boolean =
        !closed &&
            context.getSystemService(UserManager::class.java)?.isUserUnlocked == true &&
            repository.isRegistered() &&
            LocalPrefs.open(context, "ownership").getString("mode", "OFFICIAL") == "NATIVE" &&
            context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED

    private fun hangup() {
        if (!hangupEnabled()) {
            Log.i(
                "OplusBandBridge",
                "CALL_HANGUP_SKIPPED granted=" +
                    (
                        context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) ==
                            PackageManager.PERMISSION_GRANTED
                        ),
            )
            return
        }
        try {
            val telecom = context.getSystemService(TelecomManager::class.java)
            @Suppress("DEPRECATION")
            val ended = telecom != null && telecom.endCall()
            Log.i("OplusBandBridge", "CALL_HANGUP_PHONE ended=$ended")
        } catch (_: SecurityException) {
            Log.i("OplusBandBridge", "CALL_HANGUP_DENIED")
        }
    }

    private fun silence() {
        try {
            val telecom = context.getSystemService(TelecomManager::class.java)
            telecom?.silenceRinger()
            SessionLog.line(context, "CALL_IGNORE silence")
        } catch (_: SecurityException) {
            val audio = context.getSystemService(AudioManager::class.java)
            if (audio == null) {
                SessionLog.line(context, "CALL_IGNORE denied")
                return
            }
            val mode = audio.ringerMode
            if (mode != AudioManager.RINGER_MODE_SILENT) {
                savedRingerMode = mode
                audio.ringerMode = AudioManager.RINGER_MODE_SILENT
            }
            SessionLog.line(context, "CALL_IGNORE ringer")
        }
    }

    private fun restoreRinger() {
        if (savedRingerMode < 0) return
        val audio = context.getSystemService(AudioManager::class.java)
        val mode = savedRingerMode
        savedRingerMode = -1
        if (audio != null) {
            try {
                audio.ringerMode = mode
            } catch (_: SecurityException) {
                SessionLog.line(context, "CALL_RINGER_RESTORE denied")
            }
        }
    }

    private fun commandFor(phase: PhoneCallGate.Phase): XiaomiProto.Command {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        return when (phase) {
            PhoneCallGate.Phase.INCOMING -> {
                val title = BandNotificationListener.currentIncomingCallTitle(context)
                if (!title.isNullOrBlank()) caller = title
                BandNotificationCommand.call(caller, null, BandNotificationCommand.CALL_INCOMING, now, zone, false)
            }
            PhoneCallGate.Phase.ACTIVE -> BandNotificationCommand.call(
                if (caller == "来电") "通话中" else caller,
                null,
                BandNotificationCommand.CALL_ACTIVE,
                now,
                zone,
                false,
            )
            PhoneCallGate.Phase.OUTGOING -> BandNotificationCommand.call(
                "去电",
                null,
                BandNotificationCommand.CALL_OUTGOING,
                now,
                zone,
                false,
            )
            PhoneCallGate.Phase.NONE -> BandNotificationCommand.endCall()
        }
    }

    fun ownsCalls(): Boolean = !closed && callsEnabled() && eligible()

    private fun callsEnabled(): Boolean = settings.getBoolean("callsEnabled", false)

    private fun smsReplyEnabled(): Boolean =
        hangupEnabled() &&
            context.checkSelfPermission(Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    private fun replySms(reply: XiaomiProto.NotificationReply) {
        var sent = false
        try {
            val number = reply.number
            val message = reply.message
            if (smsReplyEnabled() && BandNotificationCommand.usableNumber(number) &&
                !message.isNullOrBlank() && message.length <= 400
            ) {
                @Suppress("DEPRECATION")
                SmsManager.getDefault().sendTextMessage(number.trim(), null, message, null, null)
                sent = true
            }
        } catch (_: RuntimeException) {
        }
        BandLiveService.sendSessionCommand(BandNotificationCommand.smsReplyAck(sent))
        if (sent) hangup()
    }

    private fun eligible(): Boolean {
        val ownership = LocalPrefs.open(context, "ownership")
        return !closed &&
            context.getSystemService(UserManager::class.java)?.isUserUnlocked == true &&
            context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
            phoneStateAllowed() &&
            repository.isRegistered() &&
            ownership.getString("mode", "OFFICIAL") == "NATIVE" &&
            ownership.getBoolean("ownsDisable", false) &&
            !ownership.getBoolean("officialRestored", false)
    }

    private fun phoneStateAllowed(): Boolean {
        val ops = appOps ?: return false
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(
            AppOpsManager.OPSTR_READ_PHONE_STATE,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED ||
            mode == AppOpsManager.MODE_DEFAULT ||
            mode == AppOpsManager.MODE_FOREGROUND
    }

    private fun refreshNow(freshSession: Boolean) {
        if (closed) return
        if (!eligible() || !callsEnabled()) {
            if (!BandLiveService.notificationSessionReady(context)) disconnectGate()
            gate.setEnabled(false)
            stopListening()
            SessionLog.line(context, "CALL_MONITOR off eligible=${eligible()} enabled=${callsEnabled()}")
            return
        }
        try {
            if (subscriptions == null || telephony == null) {
                throw UnsupportedOperationException("TELEPHONY_UNAVAILABLE")
            }
            if (!subscriptionListenerRegistered) {
                subscriptions.addOnSubscriptionsChangedListener(coordinator, subscriptionListener)
                subscriptionListenerRegistered = true
            }
            val active = subscriptions.activeSubscriptionInfoList
            val ids = HashSet<Int>()
            if (active != null) {
                for (info in active) ids.add(info.subscriptionId)
            }
            val iterator = listeners.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (!ids.contains(entry.key)) {
                    unregister(entry.value)
                    iterator.remove()
                }
            }
            for (id in ids) {
                if (listeners.containsKey(id)) continue
                val subManager = telephony.createForSubscriptionId(id) ?: continue
                val listener = CallListener(id, subManager)
                listeners[id] = listener
                subManager.registerTelephonyCallback(
                    TelephonyManager.INCLUDE_LOCATION_DATA_NONE,
                    coordinator,
                    listener,
                )
            }
            // Unlike getCallState(), this query belongs to this exact SIM. Call it from the coordinator, outside callbacks.
            val current = HashMap<Int, PhoneCallGate.State>()
            for (listener in listeners.values) {
                current[listener.id] = stateOf(listener.manager.callStateForSubscription)
            }
            gate.setEnabled(true)
            if (sessionConnected && BandLiveService.notificationSessionReady(context) &&
                (freshSession || !gateConnected)
            ) {
                gate.connected(current)
                gateConnected = true
            } else {
                gate.replaceStates(current)
            }
            monitorFailure = null
        } catch (failure: RuntimeException) {
            monitorFailure = if (failure is SecurityException) {
                "PHONE_PERMISSION_REQUIRED"
            } else {
                "PHONE_STATE_UNAVAILABLE"
            }
            gate.setEnabled(false)
            stopListening()
        }
    }

    private fun disconnectGate() {
        gateConnected = false
        gate.disconnected()
    }

    private fun stopListening() {
        for (listener in listeners.values) unregister(listener)
        listeners.clear()
        if (subscriptionListenerRegistered) {
            try {
                subscriptions?.removeOnSubscriptionsChangedListener(subscriptionListener)
            } catch (_: RuntimeException) {
                // The subscription service may already be unavailable.
            }
            subscriptionListenerRegistered = false
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        settings.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        appOps?.stopWatchingMode(permissionListener)
        coordinator.execute {
            disconnectGate()
            gate.setEnabled(false)
            stopListening()
        }
    }

    private fun unregister(listener: CallListener) {
        try {
            listener.manager.unregisterTelephonyCallback(listener)
        } catch (_: RuntimeException) {
            // Permission revocation must still release our references.
        }
    }

    private inner class CallListener(
        val id: Int,
        val manager: TelephonyManager,
    ) : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            if (closed || listeners[id] !== this) return
            if (!eligible() || !callsEnabled()) {
                refreshNow(false)
                return
            }
            gate.onState(id, stateOf(state))
        }
    }

    companion object {
        private fun isCallDismiss(command: XiaomiProto.Command): Boolean {
            if (command.subtype != 1 || !command.hasNotification() ||
                !command.notification.hasNotificationDismiss()
            ) {
                return false
            }
            val dismiss = command.notification.notificationDismiss
            if (dismiss.notificationIdCount != 1) return false
            val id = dismiss.getNotificationId(0)
            return id.`package` == "phone" && id.id == 0
        }

        private fun stateOf(state: Int): PhoneCallGate.State =
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> PhoneCallGate.State.RINGING
                TelephonyManager.CALL_STATE_OFFHOOK -> PhoneCallGate.State.OFFHOOK
                else -> PhoneCallGate.State.IDLE
            }
    }
}
