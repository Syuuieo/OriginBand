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
import android.view.KeyEvent
import com.originband.link.data.SessionLog
import com.originband.link.protocol.BandMusicCommand
import com.originband.link.service.BandLiveService
import com.originband.link.service.OwnershipController
import java.util.concurrent.Executors

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
    private var lastTrack = ""
    private var lastArtist = ""
    private var lastDrop = ""
    private var lastKey = -1
    private var lastKeyAtMs = 0L
    private val volumeWorker = Executors.newSingleThreadExecutor()

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
    fun shouldPublish(
        state: Int,
        lastState: Int,
        volume: Int,
        lastVolume: Int,
        pkg: String,
        lastPackage: String,
        track: String,
        lastTrack: String,
        artist: String,
        lastArtist: String,
        reason: String,
    ): Boolean = reason == "refresh" ||
        state != lastState ||
        volume != lastVolume ||
        pkg != lastPackage ||
        track != lastTrack ||
        artist != lastArtist

    /** Drops rapid duplicate key events within 300 ms, except repeated volume adjustments. */
    @JvmStatic
    fun shouldIgnoreDuplicateKey(key: Int, lastKey: Int, elapsedMs: Long): Boolean =
        key != 5 && key == lastKey && elapsedMs in 0L until 300L

    /** Steps volume proportionally to stream max (e.g. 10/150 or 1/15) and clamps to [0, max]. */
    @JvmStatic
    fun steppedVolume(current: Int, max: Int, raise: Boolean): Int {
        if (max <= 0) return 0
        val step = maxOf(1, (max + 7) / 15)
        return (current + if (raise) step else -step).coerceIn(0, max)
    }

    /**
     * Resolves band play (0) / pause (1) against current playback state (1 = playing, else paused/idle)
     * so a button press toggles playback even if the band's play/pause icon state is out of sync.
     */
    @JvmStatic
    fun resolvePlayPauseAction(key: Int, currentBandState: Int): Int = when (key) {
        0 -> if (currentBandState == 1) 1 else 0
        1 -> if (currentBandState != 1) 0 else 1
        else -> key
    }

    private fun enabled(context: Context): Boolean =
        context.getSharedPreferences(BandNotificationListener.SETTINGS, Context.MODE_PRIVATE)
            .getBoolean("musicEnabled", true)

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
        lastVolume = Int.MIN_VALUE
        lastPackage = ""
        lastTrack = ""
        lastArtist = ""
        lastKey = -1
        lastKeyAtMs = 0L
        Log.i(TAG, "MUSIC_WATCH detached")
    }

    /** @return false when this process is not the connected listener, so the caller can try Health */
    @JvmStatic
    fun handle(key: Int, volumePercent: Int, refresh: Boolean): Boolean {
        val service = listener
        val handler = main
        if (service == null || handler == null) return false
        if (!enabled(service)) return true
        handler.post {
            if (refresh) publish("refresh") else applyKey(key, volumePercent)
        }
        return true
    }

    @JvmStatic
    fun pauseForSleep(): Boolean {
        val handler = main
        if (listener == null || handler == null) return false
        handler.post { applyKey(1, 0) }
        return true
    }

    @JvmStatic
    fun onPreferenceChanged(context: Context?) {
        val handler = main
        if (listener == null || handler == null) return
        handler.post { publish("refresh") }
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
        if (!enabled(service)) {
            if (lastState != 0 && lastState != Int.MIN_VALUE &&
                BandLiveService.notificationSessionReady(service)
            ) {
                val limit = BandLiveService.notificationPayloadLimit()
                if (limit > 0) {
                    try {
                        val clear = BandMusicCommand.nothing(volumePercent(service))
                        BandLiveService.sendSessionCommand(BandMusicCommand.fit(clear, limit))
                    } catch (_: RuntimeException) {
                    }
                }
                lastState = 0
                lastTrack = ""
                lastArtist = ""
                lastPackage = ""
            }
            return
        }
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
        val metadata = current?.metadata
        val track = firstMetadataText(
            metadata,
            MediaMetadata.METADATA_KEY_TITLE,
            MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
        )
        val artist = firstMetadataText(
            metadata,
            MediaMetadata.METADATA_KEY_ARTIST,
            MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
            MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
        )
        if (!shouldPublish(
                state,
                lastState,
                volume,
                lastVolume,
                pkg,
                lastPackage,
                track,
                lastTrack,
                artist,
                lastArtist,
                reason,
            )
        ) {
            return
        }
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
                .whenComplete { _, err ->
                    if (err != null) {
                        Log.w(TAG, "MUSIC_TX_ERR ${err.javaClass.simpleName}: ${err.message}")
                    }
                }
            lastState = state
            lastVolume = volume
            lastPackage = pkg
            lastTrack = track
            lastArtist = artist
            lastDrop = ""
            val line =
                "MUSIC_OUT reason=$reason pkg=${pkg.ifBlank { "none" }} state=$state volume=$volume trackLen=${track.length}"
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
        val now = System.currentTimeMillis()
        if (shouldIgnoreDuplicateKey(key, lastKey, now - lastKeyAtMs)) {
            Log.i(TAG, "MUSIC_KEY_DEDUP key=$key pkg=$pkg")
            return
        }
        try {
            if (key == 5) {
                val audio = service.getSystemService(AudioManager::class.java)
                if (audio == null) {
                    Log.i(TAG, "MUSIC_KEY key=5 missed pkg=$pkg")
                    return
                }
                val currentLevel = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                val maxLevel = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val currentPercent = BandMusicCommand.percent(currentLevel, maxLevel)
                val raise = requestedPercent > currentPercent ||
                    (requestedPercent >= 100 && currentPercent >= 100)
                val target = steppedVolume(currentLevel, maxLevel, raise)
                var after = currentLevel
                try {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
                    after = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                } catch (_: SecurityException) {
                }
                if (target != currentLevel && after == currentLevel) {
                    val direction = if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                    try {
                        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
                        after = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    } catch (_: SecurityException) {
                    }
                    if (after == currentLevel && current != null) {
                        try {
                            current.adjustVolume(direction, AudioManager.FLAG_SHOW_UI)
                            after = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                        } catch (_: RuntimeException) {
                        }
                    }
                }
                Log.i(
                    TAG,
                    "MUSIC_VOL req=$requestedPercent cur=$currentLevel/$maxLevel target=$target after=$after",
                )
                if (target != currentLevel && after == currentLevel) {
                    volumeWorker.execute {
                        val ok = OwnershipController.setMediaVolume(target)
                        Log.i(TAG, "MUSIC_VOL_ROOT target=$target ok=$ok")
                        main?.post { publish("volume") }
                    }
                }
                lastKey = key
                lastKeyAtMs = now
                publish("volume")
            } else if (current == null) {
                val keyCode = if (key == 0 || key == 1) KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE else mediaKeyCode(key)
                val audio = service.getSystemService(AudioManager::class.java)
                if (keyCode == null || audio == null) {
                    Log.i(TAG, "MUSIC_KEY key=$key missed pkg=none")
                    return
                }
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                lastKey = key
                lastKeyAtMs = now
            } else {
                val currentBandState = current.playbackState?.state?.let { bandState(it) } ?: 0
                val actionKey = resolvePlayPauseAction(key, currentBandState)
                val controls = current.transportControls
                when (actionKey) {
                    0 -> controls.play()
                    1 -> controls.pause()
                    3 -> controls.skipToPrevious()
                    4 -> controls.skipToNext()
                    else -> {
                        Log.i(TAG, "MUSIC_KEY key=$key ignored pkg=$pkg")
                        return
                    }
                }
                lastKey = key
                lastKeyAtMs = now
            }
            Log.i(TAG, "MUSIC_KEY key=$key pkg=$pkg")
            SessionLog.line(service, "MUSIC_KEY key=$key pkg=$pkg")
        } catch (failure: RuntimeException) {
            Log.i(TAG, "MUSIC_KEY_FAILED key=$key ${failure.javaClass.simpleName}")
        }
    }

    private fun firstMetadataText(metadata: MediaMetadata?, vararg keys: String): String {
        if (metadata == null) return ""
        for (key in keys) {
            val value = metadata.getString(key)?.trim()
            if (!value.isNullOrEmpty()) return value
        }
        return ""
    }

    private fun mediaKeyCode(key: Int): Int? = when (key) {
        0 -> KeyEvent.KEYCODE_MEDIA_PLAY
        1 -> KeyEvent.KEYCODE_MEDIA_PAUSE
        3 -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        4 -> KeyEvent.KEYCODE_MEDIA_NEXT
        else -> null
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
