// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.service

import com.originband.link.ui.Copy

/** Quick settings labels. Adding the tile wakes the bridge and leaves the link up. */
object BandTileState {
    const val UNAVAILABLE = 0 // Tile.STATE_UNAVAILABLE
    const val INACTIVE = 1    // Tile.STATE_INACTIVE
    const val ACTIVE = 2      // Tile.STATE_ACTIVE

    data class View(@JvmField val state: Int, @JvmField val subtitle: String) {
        fun state(): Int = state
        fun subtitle(): String = subtitle
    }

    @JvmStatic
    fun of(registered: Boolean, serviceUp: Boolean, connected: Boolean, battery: Int): View {
        val subtitle = Copy.tileSubtitle(registered, serviceUp, connected, battery)
        if (!registered) return View(UNAVAILABLE, subtitle)
        if (serviceUp && connected) return View(ACTIVE, subtitle)
        return View(INACTIVE, subtitle)
    }

    enum class Action { WAKE, OPEN_APP }

    @JvmStatic
    fun click(mayWake: Boolean): Action {
        return if (mayWake) Action.WAKE else Action.OPEN_APP
    }
}
