// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

import android.content.Context
import android.content.pm.PackageManager

/** Human application label. A package id is not a name the band should show. */
object AppLabels {
    /** Empty when the package is invisible or its label is only the package id. */
    @JvmStatic
    fun label(context: Context?, packageName: String?): String {
        if (context == null || packageName.isNullOrBlank()) return ""
        return try {
            val packages = context.packageManager
            val info = packages.getApplicationInfo(packageName, 0)
            val loaded = prefer(packageName, info.loadLabel(packages))
            if (loaded.isNotBlank()) loaded else prefer(packageName, packages.getApplicationLabel(info))
        } catch (_: PackageManager.NameNotFoundException) {
            ""
        }
    }

    /** A label equal to the package id is the lookup failure, not the application name. */
    @JvmStatic
    fun prefer(packageName: String?, label: CharSequence?): String {
        val clean = NotificationRelay.sanitize(label)
        if (clean.isBlank() || packageName == null || clean == packageName) return ""
        return clean
    }
}
