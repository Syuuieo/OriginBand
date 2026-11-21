// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import com.originband.link.R
import com.originband.link.data.SessionLog
import com.originband.link.host.HostIdentity
import com.originband.link.protocol.BandSystemCommand
import com.originband.link.service.BandLiveService
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

/**
 * Rings the phone locally (alarm volume + vibration + ongoing stop notification) when no external
 * health RPC service is configured, or delegates to the configured host service.
 */
object FindPhone {
    const val ACTION = "com.originband.link.FIND_PHONE"
    const val PERMISSION = "com.originband.link.permission.FIND_PHONE"
    const val ACTION_STOP_LOCAL = "com.originband.link.ACTION_STOP_FIND_PHONE"

    private const val CHANNEL_ID = "find-phone"
    private const val NOTIFICATION_ID = 1009
    private const val AUTO_STOP_MS = 60_000L
    private const val MIN_ALARM_RATIO = 0.8f

    private val main: Handler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var ringing = false

    @Volatile
    private var stateListener: Runnable? = null

    private var player: MediaPlayer? = null
    private var fallbackTone: Ringtone? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var savedAlarmVolume = -1

    private val autoStop = Runnable {
        appContext?.let { stopFromPhone(it) }
    }

    @JvmStatic
    fun targetAlarmVolume(current: Int, max: Int): Int {
        if (max <= 0) return 0
        val floor = ((max * MIN_ALARM_RATIO) + 0.5f).toInt().coerceIn(1, max)
        return maxOf(current, floor)
    }

    @JvmStatic
    fun isRinging(): Boolean = ringing

    @JvmStatic
    fun setStateListener(listener: Runnable?) {
        stateListener = listener
    }

    @JvmStatic
    fun onBandCommand(context: Context, command: XiaomiProto.Command?) {
        val action = BandSystemCommand.findPhone(command) ?: return
        if (action == BandSystemCommand.FindPhone.START) start(context) else stop(context)
    }

    @JvmStatic
    fun start(context: Context) {
        if (HostIdentity.hasHealthRpcService()) {
            dispatchRemote(context, true)
            return
        }
        startLocal(context)
    }

    @JvmStatic
    fun stop(context: Context) {
        if (HostIdentity.hasHealthRpcService()) {
            dispatchRemote(context, false)
            return
        }
        stopLocal(context)
    }

    /** Stops local ringing and informs the band so its Find Phone screen exits too. */
    @JvmStatic
    fun stopFromPhone(context: Context) {
        val app = context.applicationContext ?: context
        stop(app)
        if (BandLiveService.notificationSessionReady(app)) {
            BandLiveService.sendSessionCommand(BandSystemCommand.findPhoneStop())
                .whenComplete { _, err ->
                    if (err != null) {
                        Log.w("OriginBand", "FIND_PHONE_STOP_TX_ERR ${err.javaClass.simpleName}")
                    }
                }
        }
    }

    @Synchronized
    private fun startLocal(context: Context) {
        val app = context.applicationContext ?: context
        appContext = app
        main.removeCallbacks(autoStop)
        main.postDelayed(autoStop, AUTO_STOP_MS)
        if (ringing) {
            postNotification(app)
            return
        }
        requestAlarmFocus(app)
        raiseAlarmVolume(app)
        startAlarmAudio(app)
        Log.i("OriginBand", "FIND_PHONE_AUDIO player=${player != null} tone=${fallbackTone != null}")
        startVibration(app)
        postNotification(app)
        ringing = true
        notifyStateChanged()
        Log.i("OriginBand", "FIND_PHONE start")
        SessionLog.line(app, "FIND_PHONE start")
    }

    @Synchronized
    private fun stopLocal(context: Context) {
        val app = context.applicationContext ?: context
        main.removeCallbacks(autoStop)
        stopAudio()
        abandonAlarmFocus(app)
        restoreAlarmVolume(app)
        stopVibration(app)
        cancelNotification(app)
        val wasRinging = ringing
        ringing = false
        notifyStateChanged()
        if (wasRinging) {
            Log.i("OriginBand", "FIND_PHONE stop")
            SessionLog.line(app, "FIND_PHONE stop")
        }
    }

    private fun notifyStateChanged() {
        val listener = stateListener ?: return
        main.post(listener)
    }

    private fun raiseAlarmVolume(app: Context) {
        try {
            val audio = app.getSystemService(AudioManager::class.java) ?: return
            if (audio.isVolumeFixed) return
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val current = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            val target = targetAlarmVolume(current, max)
            if (current < target) {
                savedAlarmVolume = current
                audio.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
            }
        } catch (_: RuntimeException) {
        }
    }

    private fun restoreAlarmVolume(app: Context) {
        val previous = savedAlarmVolume
        if (previous < 0) return
        savedAlarmVolume = -1
        try {
            val audio = app.getSystemService(AudioManager::class.java) ?: return
            if (!audio.isVolumeFixed) {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, previous, 0)
            }
        } catch (_: RuntimeException) {
        }
    }

    private fun requestAlarmFocus(app: Context) {
        try {
            val audio = app.getSystemService(AudioManager::class.java) ?: return
            abandonAlarmFocus(audio)
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(alarmAudioAttributes())
                .build()
            audioFocusRequest = request
            audio.requestAudioFocus(request)
        } catch (_: RuntimeException) {
        }
    }

    private fun abandonAlarmFocus(app: Context) {
        try {
            val audio = app.getSystemService(AudioManager::class.java) ?: return
            abandonAlarmFocus(audio)
        } catch (_: RuntimeException) {
        }
    }

    private fun abandonAlarmFocus(audio: AudioManager) {
        val request = audioFocusRequest ?: return
        audioFocusRequest = null
        audio.abandonAudioFocusRequest(request)
    }

    private fun alarmAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    private fun candidateUris(): List<Uri> =
        listOfNotNull(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            Settings.System.DEFAULT_ALARM_ALERT_URI,
        ).distinct()

    private fun startAlarmAudio(app: Context) {
        stopAudio()
        val attributes = alarmAudioAttributes()
        for (uri in candidateUris()) {
            var created: MediaPlayer? = null
            try {
                created = MediaPlayer()
                created.setAudioAttributes(attributes)
                created.setDataSource(app, uri)
                created.isLooping = true
                created.prepare()
                created.start()
                player = created
                return
            } catch (_: Exception) {
                try {
                    created?.release()
                } catch (_: RuntimeException) {
                }
            }
            try {
                val tone = RingtoneManager.getRingtone(app, uri)
                if (tone != null) {
                    tone.audioAttributes = attributes
                    tone.isLooping = true
                    tone.play()
                    fallbackTone = tone
                    return
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun stopAudio() {
        val currentPlayer = player
        player = null
        if (currentPlayer != null) {
            try {
                if (currentPlayer.isPlaying) currentPlayer.stop()
            } catch (_: RuntimeException) {
            }
            try {
                currentPlayer.release()
            } catch (_: RuntimeException) {
            }
        }
        val currentTone = fallbackTone
        fallbackTone = null
        if (currentTone != null) {
            try {
                if (currentTone.isPlaying) currentTone.stop()
            } catch (_: RuntimeException) {
            }
        }
    }

    private fun startVibration(app: Context) {
        try {
            val manager = app.getSystemService(VibratorManager::class.java) ?: return
            val vibrator = manager.defaultVibrator
            if (!vibrator.hasVibrator()) return
            val effect = VibrationEffect.createWaveform(longArrayOf(0L, 600L, 350L, 600L, 650L), 0)
            val attrs = VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_ALARM)
                .build()
            vibrator.vibrate(effect, attrs)
        } catch (_: RuntimeException) {
        }
    }

    private fun stopVibration(app: Context) {
        try {
            app.getSystemService(VibratorManager::class.java)?.defaultVibrator?.cancel()
        } catch (_: RuntimeException) {
        }
    }

    private fun stopPendingIntent(app: Context): PendingIntent {
        val intent = Intent(app, FindPhoneStopReceiver::class.java).setAction(ACTION_STOP_LOCAL)
        return PendingIntent.getBroadcast(
            app,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun postNotification(app: Context) {
        try {
            val manager = app.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(CHANNEL_ID, "查找手机", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
            val stopIntent = stopPendingIntent(app)
            val action = Notification.Action.Builder(null, "停止响铃", stopIntent).build()
            val built = Notification.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_qs_band)
                .setContentTitle("正在查找手机")
                .setContentText("点按停止响铃")
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(true)
                .setContentIntent(stopIntent)
                .addAction(action)
                .build()
            manager.notify(NOTIFICATION_ID, built)
        } catch (_: RuntimeException) {
        }
    }

    private fun cancelNotification(app: Context) {
        try {
            app.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        } catch (_: RuntimeException) {
        }
    }

    private fun dispatchRemote(context: Context, start: Boolean) {
        val app = context.applicationContext ?: context
        val healthService = ComponentName(HostIdentity.HEALTH_PACKAGE, HostIdentity.HEALTH_RPC_SERVICE)
        val ring = Intent(ACTION).setPackage(HostIdentity.HEALTH_PACKAGE).putExtra("start", start)
        try {
            val bound = app.bindService(
                Intent().setComponent(healthService),
                object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        app.sendBroadcast(ring)
                        Log.i("OriginBand", if (start) "FIND_PHONE start" else "FIND_PHONE stop")
                        try {
                            app.unbindService(this)
                        } catch (_: RuntimeException) {
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {}
                },
                Context.BIND_AUTO_CREATE,
            )
            if (!bound) Log.i("OriginBand", "FIND_PHONE native unavailable")
        } catch (_: RuntimeException) {
            Log.i("OriginBand", "FIND_PHONE native unavailable")
        }
    }
}
