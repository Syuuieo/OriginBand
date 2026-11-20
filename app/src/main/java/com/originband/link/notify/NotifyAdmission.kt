// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

/** Package and screen rules shared by the listener and the health cache. */
object NotifyAdmission {
    /** An empty selection means every package. A non-empty selection is a real allowlist. */
    @JvmStatic
    fun packageAllowed(packages: Set<String>?, packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return packages.isNullOrEmpty() || packages.contains(packageName)
    }

    /** {@code screen_on_push} false blocks only while the screen is on and unlocked. */
    @JvmStatic
    fun screenBlocks(screenOnPush: Boolean, interactive: Boolean, locked: Boolean): Boolean =
        !screenOnPush && interactive && !locked
}
