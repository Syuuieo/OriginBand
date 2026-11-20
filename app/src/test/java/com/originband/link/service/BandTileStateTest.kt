// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.service

import org.junit.Assert.assertEquals
import org.junit.Test

class BandTileStateTest {
    @Test
    fun unregisteredTileIsUnavailable() {
        val view = BandTileState.of(false, true, true, 40)
        assertEquals(BandTileState.UNAVAILABLE, view.state())
        assertEquals("在应用中添加", view.subtitle())
    }

    @Test
    fun connectedBatteryIsShown() {
        val view = BandTileState.of(true, true, true, 40)
        assertEquals(BandTileState.ACTIVE, view.state())
        assertEquals("已连接 · 40%", view.subtitle())
        assertEquals("已连接 · 0%", BandTileState.of(true, true, true, 0).subtitle())
        assertEquals("已连接 · 100%", BandTileState.of(true, true, true, 100).subtitle())
    }

    @Test
    fun connectedWithoutAReadingHasNoPercent() {
        val missing = BandTileState.of(true, true, true, -1)
        assertEquals(BandTileState.ACTIVE, missing.state())
        assertEquals("已连接", missing.subtitle())
        assertEquals("已连接", BandTileState.of(true, true, true, 101).subtitle())
    }

    @Test
    fun runningButNotConnectedIsConnecting() {
        val view = BandTileState.of(true, true, false, 40)
        assertEquals(BandTileState.INACTIVE, view.state())
        assertEquals("正在连接", view.subtitle())
    }

    @Test
    fun stoppedServiceIsDisconnected() {
        val view = BandTileState.of(true, false, true, 40)
        assertEquals(BandTileState.INACTIVE, view.state())
        assertEquals("点按连接", view.subtitle())
    }

    @Test
    fun clickWakesOnlyWhenTheBridgeMayStart() {
        assertEquals(BandTileState.Action.WAKE, BandTileState.click(true))
        assertEquals(BandTileState.Action.OPEN_APP, BandTileState.click(false))
    }
}
