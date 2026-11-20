// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

/** OPPO Health's notification switches, as last pushed by the Health process. */
object HealthPolicy {
    /**
     * @return null when the post may continue. {@code health-main}, {@code health-screen}
     *         or {@code health-package} when Health would not send it.
     *         An unknown policy allows everything.
     */
    @JvmStatic
    fun blockReason(
        known: Boolean,
        main: Boolean,
        screenOnPush: Boolean,
        open: Set<String>?,
        closed: Set<String>?,
        defaults: Set<String>?,
        mms: List<String>?,
        packageName: String?,
        interactive: Boolean,
        locked: Boolean,
    ): String? {
        if (packageName.isNullOrBlank()) return "health-package"
        if (!known) return null
        if (!main) return "health-main"
        if (!screenOnPush && interactive && !locked) return "health-screen"
        if (!packageOn(open, closed, defaults, mms, packageName)) return "health-package"
        return null
    }

    /** A row wins over the default whitelist. An SMS package follows the first SMS row. */
    @JvmStatic
    fun packageOn(
        open: Set<String>?,
        closed: Set<String>?,
        defaults: Set<String>?,
        mms: List<String>?,
        packageName: String?,
    ): Boolean {
        if (packageName == null) return false
        var row = row(open, closed, packageName)
        if (row == null && mms != null && mms.contains(packageName)) {
            for (candidate in mms) {
                row = row(open, closed, candidate)
                if (row != null) break
            }
        }
        if (row != null) return row
        return defaults != null && defaults.contains(packageName)
    }

    private fun row(open: Set<String>?, closed: Set<String>?, packageName: String): Boolean? {
        if (open != null && open.contains(packageName)) return true
        if (closed != null && closed.contains(packageName)) return false
        return null
    }
}
