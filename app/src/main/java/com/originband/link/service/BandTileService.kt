// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.service

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.TileService
import com.originband.link.R
import com.originband.link.data.BandCatalog
import com.originband.link.data.BandStateRepository
import com.originband.link.data.LocalPrefs
import com.originband.link.host.HostIdentity
import com.originband.link.integration.DeviceCardProvider
import kotlin.math.roundToInt

/** SystemUI binding this tile starts the bridge. A tap leaves the connection up. */
class BandTileService : TileService() {
    private var observer: ContentObserver? = null
    private var glyph: Bitmap? = null
    private var glyphWatch = false

    override fun onStartListening() {
        super.onStartListening()
        if (BandLiveService.mayWake(this)) BandLiveService.ensureProcess(this)
        if (observer == null) {
            val created = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    render()
                }
            }
            observer = try {
                contentResolver.registerContentObserver(DeviceCardProvider.URI, false, created)
                created
            } catch (_: RuntimeException) {
                null
            }
        }
        render()
    }

    override fun onStopListening() {
        observer?.let {
            contentResolver.unregisterContentObserver(it)
            observer = null
        }
        super.onStopListening()
    }

    override fun onClick() {
        val open = Intent(this, BandTileActivity::class.java)
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        val pending = PendingIntent.getActivity(
            this,
            0,
            open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        startActivityAndCollapse(pending)
    }

    private fun render() {
        val tile = qsTile ?: return
        var registered = false
        var connected = false
        var battery = -1
        var model = ""
        try {
            registered = BandStateRepository(this).isRegistered()
            if (registered) {
                val state = LocalPrefs.open(this, "band-state")
                connected = state.getBoolean("connected", false)
                battery = state.getInt("battery", -1)
                model = state.getString("modelId", "")
            }
        } catch (_: RuntimeException) {
            registered = false
        }
        val view = BandTileState.of(
            registered,
            BandLiveService.isRunning(),
            connected,
            battery
        )
        val watch = BandCatalog.watchForm(model)
        if (glyph == null || glyphWatch != watch) {
            glyph = tileGlyph(watch)
            glyphWatch = watch
        }
        glyph?.let { tile.icon = Icon.createWithBitmap(it) }
        tile.state = view.state()
        tile.subtitle = view.subtitle()
        tile.updateTile()
    }

    /** Host ROM draws its own band and watch pictures. The source canvas is mostly empty, so fill the tile. */
    private fun tileGlyph(watch: Boolean): Bitmap? {
        val source = systemGlyph(watch)
            ?: return BitmapFactory.decodeResource(
                resources,
                if (watch) R.drawable.ic_qs_watch else R.drawable.ic_qs_band
            )
        return enlarge(source)
    }

    private fun systemGlyph(watch: Boolean): Bitmap? {
        return try {
            val devices = createPackageContext(DEVICES, 0)
            val id = devices.resources.getIdentifier(
                if (watch) "default_watch" else "default_wristband",
                "drawable",
                DEVICES
            )
            if (id == 0) null else BitmapFactory.decodeResource(devices.resources, id)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    companion object {
        private const val DEVICES = HostIdentity.DEVICES_PACKAGE

        /** Scale the opaque pixels until the height fills the tile. Strap tips past the edge are clipped. */
        private fun enlarge(source: Bitmap): Bitmap {
            val width = source.width
            val height = source.height
            val pixels = IntArray(width * height)
            source.getPixels(pixels, 0, width, 0, 0, width, height)
            var left = width
            var top = height
            var right = -1
            var bottom = -1
            for (y in 0 until height) {
                for (x in 0 until width) {
                    if ((pixels[y * width + x] ushr 24) <= 16) continue
                    if (x < left) left = x
                    if (y < top) top = y
                    if (x > right) right = x
                    if (y > bottom) bottom = y
                }
            }
            if (right < left || bottom < top) return source
            val crop = Bitmap.createBitmap(source, left, top, right - left + 1, bottom - top + 1)
            val size = 216
            val scale = (size * 1.10f) / crop.height
            val scaledWidth = maxOf(1, (crop.width * scale).roundToInt())
            val scaledHeight = maxOf(1, (crop.height * scale).roundToInt())
            val scaled = Bitmap.createScaledBitmap(crop, scaledWidth, scaledHeight, true)
            val canvas = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            Canvas(canvas).drawBitmap(
                scaled,
                (size - scaledWidth) / 2f,
                (size - scaledHeight) / 2f,
                null
            )
            if (crop !== source) crop.recycle()
            if (scaled !== crop) scaled.recycle()
            return canvas
        }
    }
}
