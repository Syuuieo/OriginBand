// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import kotlin.math.roundToInt

/** Type 18 music snapshots and band media keys. Volume on key 5 is an absolute 0–100 percent. */
object BandMusicCommand {
    enum class Action { PLAY, PAUSE, PREVIOUS, NEXT, VOLUME_UP, VOLUME_DOWN }

    @JvmStatic
    fun nothing(volume: Int): XiaomiProto.Command =
        XiaomiProto.Command.newBuilder()
            .setType(18)
            .setSubtype(1)
            .setMusic(
                XiaomiProto.Music.newBuilder().setMusicInfo(
                    XiaomiProto.MusicInfo.newBuilder()
                        .setState(0)
                        .setVolume(clampPercent(volume)),
                ),
            )
            .build()

    @JvmStatic
    fun playback(
        volume: Int,
        track: String?,
        artist: String?,
        positionSeconds: Int,
        durationSeconds: Int,
        playing: Boolean,
    ): XiaomiProto.Command {
        val info = XiaomiProto.MusicInfo.newBuilder()
            .setState(if (playing) 1 else 2)
            .setVolume(clampPercent(volume))
            .setTrack(track ?: "")
            .setArtist(artist ?: "")
            .setPosition(maxOf(0, positionSeconds))
            .setDuration(maxOf(0, durationSeconds))
        return XiaomiProto.Command.newBuilder()
            .setType(18)
            .setSubtype(1)
            .setMusic(XiaomiProto.Music.newBuilder().setMusicInfo(info))
            .build()
    }

    /** Shrinks track, then artist, by whole Unicode code points. State and volume stay. */
    @JvmStatic
    fun fit(command: XiaomiProto.Command, limit: Int): XiaomiProto.Command {
        if (limit <= 0) throw IllegalArgumentException("MUSIC_PAYLOAD_LIMIT")
        if (command.serializedSize <= limit) return command
        if (!command.hasMusic() || !command.music.hasMusicInfo()) {
            throw IllegalArgumentException("MUSIC_PAYLOAD_LIMIT")
        }
        val original = command.music.musicInfo
        val data = original.toBuilder().clearTrack().clearArtist()
        if (replaceInfo(command, data.build()).serializedSize > limit) {
            throw IllegalArgumentException("MUSIC_PAYLOAD_LIMIT")
        }
        fitField(command, data, original.track, track = true, limit = limit)
        fitField(command, data, original.artist, track = false, limit = limit)
        return replaceInfo(command, data.build())
    }

    @JvmStatic
    fun percent(level: Int, max: Int): Int {
        if (max <= 0) return 0
        return clampPercent((100f * level / max).roundToInt())
    }

    /** Key 5 at or below {@code currentPercent} is down. An absent volume field is proto2 0. */
    @JvmStatic
    fun action(command: XiaomiProto.Command?, currentPercent: Int): Action? {
        if (command == null || command.type != 18 || command.subtype != 2 ||
            !command.hasMusic() || !command.music.hasMediaKey()
        ) {
            return null
        }
        val key = command.music.mediaKey
        return when (key.key) {
            0 -> Action.PLAY
            1 -> Action.PAUSE
            3 -> Action.PREVIOUS
            4 -> Action.NEXT
            5 -> if (key.volume > currentPercent) Action.VOLUME_UP else Action.VOLUME_DOWN
            else -> null
        }
    }

    private fun clampPercent(value: Int): Int = value.coerceIn(0, 100)

    private fun fitField(
        command: XiaomiProto.Command,
        data: XiaomiProto.MusicInfo.Builder,
        text: String,
        track: Boolean,
        limit: Int,
    ) {
        var low = 0
        var high = text.codePointCount(0, text.length)
        while (low < high) {
            val count = low + (high - low + 1) / 2
            val prefix = text.substring(0, text.offsetByCodePoints(0, count))
            if (track) data.setTrack(prefix) else data.setArtist(prefix)
            if (replaceInfo(command, data.build()).serializedSize <= limit) {
                low = count
            } else {
                high = count - 1
            }
        }
        val prefix = text.substring(0, text.offsetByCodePoints(0, low))
        if (track) {
            if (low == 0) data.clearTrack() else data.setTrack(prefix)
        } else if (low == 0) {
            data.clearArtist()
        } else {
            data.setArtist(prefix)
        }
    }

    private fun replaceInfo(command: XiaomiProto.Command, info: XiaomiProto.MusicInfo): XiaomiProto.Command =
        command.toBuilder().setMusic(command.music.toBuilder().setMusicInfo(info)).build()
}
