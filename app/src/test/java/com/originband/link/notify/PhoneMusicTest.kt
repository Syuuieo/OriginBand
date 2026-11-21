// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMusicTest {
    @Test
    fun playingPausedAndIdleUseTheBandCodes() {
        assertEquals(1, PhoneMusic.bandState(PlaybackState.STATE_PLAYING))
        assertEquals(2, PhoneMusic.bandState(PlaybackState.STATE_PAUSED))
        assertEquals(2, PhoneMusic.bandState(PlaybackState.STATE_BUFFERING))
        assertEquals(0, PhoneMusic.bandState(PlaybackState.STATE_STOPPED))
        assertEquals(0, PhoneMusic.bandState(PlaybackState.STATE_NONE))
    }

    @Test
    fun shouldPublishTracksSongChangesAndDeduplicatesUnchangedMetadata() {
        assertFalse(
            PhoneMusic.shouldPublish(
                state = 1,
                lastState = 1,
                volume = 50,
                lastVolume = 50,
                pkg = "com.spotify.music",
                lastPackage = "com.spotify.music",
                track = "Song A",
                lastTrack = "Song A",
                artist = "Artist A",
                lastArtist = "Artist A",
                reason = "metadata",
            )
        )
        assertTrue(
            PhoneMusic.shouldPublish(
                state = 1,
                lastState = 1,
                volume = 50,
                lastVolume = 50,
                pkg = "com.spotify.music",
                lastPackage = "com.spotify.music",
                track = "Song B",
                lastTrack = "Song A",
                artist = "Artist A",
                lastArtist = "Artist A",
                reason = "metadata",
            )
        )
        assertTrue(
            PhoneMusic.shouldPublish(
                state = 1,
                lastState = 1,
                volume = 50,
                lastVolume = 50,
                pkg = "com.spotify.music",
                lastPackage = "com.spotify.music",
                track = "Song A",
                lastTrack = "Song A",
                artist = "Artist B",
                lastArtist = "Artist A",
                reason = "metadata",
            )
        )
        assertTrue(
            PhoneMusic.shouldPublish(
                state = 1,
                lastState = 1,
                volume = 60,
                lastVolume = 50,
                pkg = "com.spotify.music",
                lastPackage = "com.spotify.music",
                track = "Song A",
                lastTrack = "Song A",
                artist = "Artist A",
                lastArtist = "Artist A",
                reason = "volume",
            )
        )
        assertTrue(
            PhoneMusic.shouldPublish(
                state = 1,
                lastState = 1,
                volume = 50,
                lastVolume = 50,
                pkg = "com.spotify.music",
                lastPackage = "com.spotify.music",
                track = "Song A",
                lastTrack = "Song A",
                artist = "Artist A",
                lastArtist = "Artist A",
                reason = "refresh",
            )
        )
    }

    @Test
    fun shouldIgnoreDuplicateKeyDropsFastRepeatExceptVolume() {
        assertTrue(PhoneMusic.shouldIgnoreDuplicateKey(3, 3, 29L))
        assertFalse(PhoneMusic.shouldIgnoreDuplicateKey(3, 3, 300L))
        assertFalse(PhoneMusic.shouldIgnoreDuplicateKey(4, 3, 29L))
        assertFalse(PhoneMusic.shouldIgnoreDuplicateKey(5, 5, 29L))
        assertFalse(PhoneMusic.shouldIgnoreDuplicateKey(3, -1, 0L))
    }

    @Test
    fun steppedVolumeScalesWithMaxStreamStepsAndClamps() {
        assertEquals(10, PhoneMusic.steppedVolume(0, 150, true))
        assertEquals(150, PhoneMusic.steppedVolume(145, 150, true))
        assertEquals(0, PhoneMusic.steppedVolume(5, 150, false))
        assertEquals(6, PhoneMusic.steppedVolume(5, 15, true))
        assertEquals(4, PhoneMusic.steppedVolume(5, 15, false))
        assertEquals(0, PhoneMusic.steppedVolume(5, 0, true))
    }

    @Test
    fun resolvePlayPauseActionTogglesWhenBandStateIsOutOfSync() {
        assertEquals(0, PhoneMusic.resolvePlayPauseAction(0, 2))
        assertEquals(1, PhoneMusic.resolvePlayPauseAction(0, 1))
        assertEquals(0, PhoneMusic.resolvePlayPauseAction(1, 2))
        assertEquals(1, PhoneMusic.resolvePlayPauseAction(1, 1))
        assertEquals(3, PhoneMusic.resolvePlayPauseAction(3, 2))
        assertEquals(4, PhoneMusic.resolvePlayPauseAction(4, 1))
    }
}
