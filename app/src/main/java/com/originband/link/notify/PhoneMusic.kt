// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.util.Log
import com.originband.link.data.SessionLog
import com.originband.link.protocol.BandMusicCommand
import com.originband.link.service.BandLiveService

/**
 * Reads the phone's media session from this app's own notification listener.
 * OPPO Health starts its watcher after its listener connects. This ROM leaves that listener disconnected.
 */
object PhoneMusic {
    private const val TAG = "OplusBandBridge"
    private var listener: BandNotificationListener? = null
    private var main: Handler? = null
    private var controller: MediaController? = null
    private var lastState = Int.MIN_VALUE
    private var lastVolume = Int.MIN_VALUE
    private var lastPackage = ""
    private var lastDrop = ""

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish("state")

        override fun onMetadataChanged(metadata: MediaMetadata?) = publish("metadata")

        override fun onSessionDestroyed() {
            controller = null
            publish("destroyed")
        }
    }

    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { active ->
        controllers(active)
    }

    private val volume = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = publish("volume")
    }

    /** Playback state 3 is playing. 2 and 6 stay paused. Everything else is idle. */
    @JvmStatic
    fun bandState(playbackState: Int): Int {
        if (playbackState == PlaybackState.STATE_PLAYING) return 1
        if (playbackState == PlaybackState.STATE_PAUSED ||
            playbackState == PlaybackState.STATE_BUFFERING
        ) {
            return 2
        }
        return 0
    }

    @JvmStatic
    fun attached(): Boolean = listener != null

    @JvmStatic
    fun attach(service: BandNotificationListener, handler: Handler) {
        listener = service
        main = handler
        val manager = service.getSystemService(MediaSessionManager::class.java)
        if (manager == null) {
            drop("no-manager", "", 0, 0)
            return
        }
        val component = ComponentName(service, BandNotificationListener::class.java)
        try {
            manager.addOnActiveSessionsChangedListener(sessions, component, handler)
            controllers(manager.getActiveSessions(component))
        } catch (_: SecurityException) {
            drop("denied", "", 0, 0)
            return
        }
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        service.registerReceiver(volume, filter, Context.RECEIVER_NOT_EXPORTED)
        Log.i(TAG, "MUSIC_WATCH attached")
        SessionLog.line(service, "MUSIC_WATCH attached")
    }

    @JvmStatic
    fun detach() {
        val service = listener
        listener = null
        if (service == null) return
        val manager = service.getSystemService(MediaSessionManager::class.java)
        if (manager != null) {
            try {
                manager.removeOnActiveSessionsChangedListener(sessions)
            } catch (_: RuntimeException) {
            }
        }
        try {
            service.unregisterReceiver(volume)
        } catch (_: RuntimeException) {
        }
        controller?.unregisterCallback(callback)
        controller = null
        lastState = Int.MIN_VALUE
        Log.i(TAG, "MUSIC_WATCH detached")
    }

    /** @return false when this process is not the connected listener, so the caller can try Health */
    @JvmStatic
    fun handle(key: Int, volumePercent: Int, refresh: Boolean): Boolean {
        val handler = main
        if (listener == null || handler == null) return false
        handler.post {
            if (refresh) publish("refresh") else applyKey(key, volumePercent)
        }
        return true
    }

    private fun controllers(active: List<MediaController?>?) {
        val count = active?.size ?: 0
        var next: MediaController? = null
        var rank = -1
        if (active != null) {
            for (candidate in active) {
                if (candidate == null) continue
                val state = candidate.playbackState
                val score = when {
                    state == null -> 0
                    bandState(state.state) == 1 -> 3
                    bandState(state.state) == 2 -> 2
                    else -> 1
                }
                if (score > rank) {
                    rank = score
                    next = candidate
                }
            }
        }
        if (controller !== next) {
            controller?.unregisterCallback(callback)
            controller = next
            val handler = main
            if (next != null && handler != null) next.registerCallback(callback, handler)
        }
        val chosen = next?.packageName ?: "none"
        Log.i(TAG, "MUSIC_SESSIONS count=$count chosen=$chosen")
        listener?.let { SessionLog.line(it, "MUSIC_SESSIONS count=$count chosen=$chosen") }
        publish("sessions")
    }

    private fun publish(reason: String) {
        val service = listener ?: return
        val current = controller
        val playback = current?.playbackState
        val state = if (playback == null) 0 else bandState(playback.state)
        val volume = volumePercent(service)
        val pkg = current?.packageName ?: ""
        if (!BandLiveService.notificationSessionReady(service)) {
            drop("session", pkg, state, volume)
            return
        }
        val limit = BandLiveService.notificationPayloadLimit()
        if (limit <= 0) {
            drop("limit", pkg, state, volume)
            return
        }
        if (state == lastState && volume == lastVolume && pkg == lastPackage && reason != "refresh") {
            return
        }
        val metadata = current?.metadata
        val track = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val positionMs = playback?.position ?: 0L
        val durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val position = if (positionMs <= 0L) 0 else minOf(Int.MAX_VALUE.toLong(), positionMs / 1000L).toInt()
        val duration = if (durationMs <= 0L) 0 else minOf(Int.MAX_VALUE.toLong(), durationMs / 1000L).toInt()
        try {
            val command = if (state == 0) {
                BandMusicCommand.nothing(volume)
            } else {
                BandMusicCommand.playback(volume, track, artist, position, duration, state == 1)
            }
            BandLiveService.sendSessionCommand(BandMusicCommand.fit(command, limit))
            lastState = state
            lastVolume = volume
            lastPackage = pkg
            lastDrop = ""
            val line = "MUSIC_OUT reason=$reason pkg=${pkg.ifBlank { "none" }} state=$state volume=$volume"
            Log.i(TAG, line)
            SessionLog.line(service, line)
        } catch (failure: RuntimeException) {
            Log.i(TAG, "MUSIC_SEND_FAILED ${failure.javaClass.simpleName}")
            SessionLog.line(service, "MUSIC_SEND_FAILED ${failure.javaClass.simpleName}")
        }
    }

    private fun applyKey(key: Int, requestedPercent: Int) {
        val service = listener ?: return
        val current = controller
        val pkg = current?.packageName ?: "none"
        try {
            if (key == 5) {
                val audio = service.getSystemService(AudioManager::class.java)
                if (audio == null) {
                    Log.i(TAG, "MUSIC_KEY key=5 missed pkg=$pkg")
                    return
                }
                val currentPercent = BandMusicCommand.percent(
                    audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                    audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                )
                audio.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (requestedPercent > currentPercent) {
                        AudioManager.ADJUST_RAISE
                    } else {
                        AudioManager.ADJUST_LOWER
                    },
                    0,
                )
            } else if (current == null) {
                Log.i(TAG, "MUSIC_KEY key=$key missed pkg=none")
                return
            } else {
                val controls = current.transportControls
                when (key) {
                    0 -> controls.play()
                    1 -> controls.pause()
                    3 -> controls.skipToPrevious()
                    4 -> controls.skipToNext()
                    else -> {
                        Log.i(TAG, "MUSIC_KEY key=$key ignored pkg=$pkg")
                        return
                    }
                }
            }
            Log.i(TAG, "MUSIC_KEY key=$key pkg=$pkg")
            SessionLog.line(service, "MUSIC_KEY key=$key pkg=$pkg")
        } catch (failure: RuntimeException) {
            Log.i(TAG, "MUSIC_KEY_FAILED key=$key ${failure.javaClass.simpleName}")
        }
    }

    private fun volumePercent(context: Context): Int {
        val audio = context.getSystemService(AudioManager::class.java) ?: return 0
        return BandMusicCommand.percent(
            audio.getStreamVolume(AudioManager.STREAM_MUSIC),
            audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
        )
    }

    private fun drop(reason: String, pkg: String?, state: Int, volume: Int) {
        val line = "$reason $pkg $state $volume"
        if (line == lastDrop) return
        lastDrop = line
        val cleanPkg = if (pkg.isNullOrBlank()) "none" else pkg
        val message = "MUSIC_DROP reason=$reason pkg=$cleanPkg state=$state volume=$volume"
        Log.i(TAG, message)
        listener?.let { SessionLog.line(it, message) }
    }
}
