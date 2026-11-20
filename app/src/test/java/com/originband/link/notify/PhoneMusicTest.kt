// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
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
}
