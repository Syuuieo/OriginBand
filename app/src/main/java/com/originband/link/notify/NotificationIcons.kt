// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable

/** Rasterizes an installed app icon to the square size the band requested. */
object NotificationIcons {
    @JvmStatic
    fun available(context: Context?, packageName: String?): Boolean = icon(context, packageName) != null

    @JvmStatic
    fun argb(context: Context?, packageName: String?, size: Int): IntArray {
        if (size < 1 || size > 256) throw IllegalArgumentException("ICON_BITMAP")
        val drawable = icon(context, packageName) ?: throw IllegalArgumentException("ICON_PACKAGE")
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            val pixels = IntArray(size * size)
            bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
            return pixels
        } finally {
            bitmap.recycle()
        }
    }

    private fun icon(context: Context?, packageName: String?): Drawable? {
        if (context == null || packageName.isNullOrBlank()) return null
        return try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}
