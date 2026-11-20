// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BandMusicCommandTest {
    companion object {
        private fun key(code: Int, volume: Int): XiaomiProto.Command =
            XiaomiProto.Command.newBuilder()
                .setType(18)
                .setSubtype(2)
                .setMusic(
                    XiaomiProto.Music.newBuilder().setMediaKey(
                        XiaomiProto.MediaKey.newBuilder().setKey(code).setVolume(volume),
                    ),
                )
                .build()
    }

    @Test
    fun playbackKeepsTrackAndNothingOmitsIt() {
        val playing = BandMusicCommand.playback(50, "测试音乐", "桥接", 12, 180, true)
        assertEquals(18, playing.type)
        assertEquals(1, playing.subtype)
        val info = playing.music.musicInfo
        assertEquals(1, info.state)
        assertEquals("测试音乐", info.track)
        assertEquals("桥接", info.artist)
        assertEquals(12, info.position)
        assertEquals(180, info.duration)

        val idle = BandMusicCommand.nothing(20)
        assertEquals(0, idle.music.musicInfo.state)
        assertFalse(idle.music.musicInfo.hasTrack())
    }

    @Test
    fun pausedPlaybackIsStateTwo() {
        assertEquals(
            2,
            BandMusicCommand.playback(1, "a", "b", 0, 1, false).music.musicInfo.state,
        )
    }

    @Test
    fun mediaKeysMapToActions() {
        assertEquals(BandMusicCommand.Action.PLAY, BandMusicCommand.action(key(0, 0), 40))
        assertEquals(BandMusicCommand.Action.PAUSE, BandMusicCommand.action(key(1, 0), 40))
        assertEquals(BandMusicCommand.Action.PREVIOUS, BandMusicCommand.action(key(3, 0), 40))
        assertEquals(BandMusicCommand.Action.NEXT, BandMusicCommand.action(key(4, 0), 40))
        assertEquals(BandMusicCommand.Action.VOLUME_UP, BandMusicCommand.action(key(5, 80), 40))
        assertEquals(BandMusicCommand.Action.VOLUME_DOWN, BandMusicCommand.action(key(5, 40), 40))
        assertNull(BandMusicCommand.action(key(2, 0), 40))
    }

    @Test
    fun percentRoundsAndRejectsEmptyRange() {
        assertEquals(33, BandMusicCommand.percent(5, 15))
        assertEquals(0, BandMusicCommand.percent(1, 0))
    }

    @Test
    fun fitKeepsSurrogatePairsAndFixedFields() {
        val track = "曲\ud83c\udfb5".repeat(80)
        val command = BandMusicCommand.playback(40, track, "桥接", 3, 9, true)
        val limit = command.serializedSize - 1
        val fitted = BandMusicCommand.fit(command, limit)
        val info = fitted.music.musicInfo
        assertTrue(fitted.serializedSize <= limit)
        assertEquals(1, info.state)
        assertEquals(40, info.volume)
        val kept = if (info.hasTrack()) info.track else ""
        assertEquals(kept, track.substring(0, kept.length))
        if (kept.isNotEmpty()) assertFalse(kept.last().isHighSurrogate())
    }
}
