// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.originband.link.data.SessionLog
import com.originband.link.host.HostIdentity
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

/** Asks the running health music controller to refresh or handle a band key when a host RPC service is configured. */
object NativeMusic {
    const val ACTION = "com.originband.link.MUSIC"

    @JvmStatic
    fun onBandCommand(context: Context, command: XiaomiProto.Command?) {
        if (command == null || command.type != 18) return
        val subtype = command.subtype
        if (subtype == 0) {
            if (!PhoneMusic.handle(0, 0, true)) dispatch(context, 0, 0, true)
        } else if (subtype == 2 && command.hasMusic() && command.music.hasMediaKey()) {
            val key = command.music.mediaKey
            if (!PhoneMusic.handle(key.key, key.volume, false)) {
                dispatch(context, key.key, key.volume, false)
            }
        }
    }

    @JvmStatic
    fun requestRefresh(context: Context) {
        if (!PhoneMusic.handle(0, 0, true)) dispatch(context, 0, 0, true)
    }

    /** Same pause key the band sends. Listener first, then the Health controller. */
    @JvmStatic
    fun pause(context: Context) {
        if (!PhoneMusic.pauseForSleep()) dispatch(context, 1, 0, false)
    }

    private fun dispatch(context: Context, key: Int, volume: Int, refresh: Boolean) {
        if (!HostIdentity.hasHealthRpcService()) return
        val app = context.applicationContext ?: context
        val healthService = ComponentName(HostIdentity.HEALTH_PACKAGE, HostIdentity.HEALTH_RPC_SERVICE)
        val event = Intent(ACTION).setPackage(HostIdentity.HEALTH_PACKAGE)
            .putExtra("refresh", refresh)
            .putExtra("key", key)
            .putExtra("volume", volume)
        try {
            val bound = app.bindService(
                Intent().setComponent(healthService),
                object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        app.sendBroadcast(event)
                        if (!refresh) Log.i("OriginBand", "MUSIC_KEY $key")
                        try {
                            app.unbindService(this)
                        } catch (_: RuntimeException) {
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {}
                },
                Context.BIND_AUTO_CREATE,
            )
            if (!bound) {
                Log.i("OriginBand", "MUSIC native unavailable")
                SessionLog.line(context, "MUSIC_NATIVE unavailable")
            }
        } catch (_: RuntimeException) {
            Log.i("OriginBand", "MUSIC native unavailable")
            SessionLog.line(context, "MUSIC_NATIVE unavailable")
        }
    }
}
