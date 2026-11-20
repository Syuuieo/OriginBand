// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.originband.link.host.HostIdentity
import com.originband.link.protocol.BandSystemCommand
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

/** Asks the running health process to play or stop its own find-phone ring when wired. */
object FindPhone {
    const val ACTION = "com.originband.link.FIND_PHONE"
    const val PERMISSION = "com.originband.link.permission.FIND_PHONE"

    @JvmStatic
    fun onBandCommand(context: Context, command: XiaomiProto.Command?) {
        val action = BandSystemCommand.findPhone(command) ?: return
        if (action == BandSystemCommand.FindPhone.START) start(context) else stop(context)
    }

    @JvmStatic
    fun start(context: Context) {
        dispatch(context, true)
    }

    @JvmStatic
    fun stop(context: Context) {
        dispatch(context, false)
    }

    private fun dispatch(context: Context, start: Boolean) {
        if (!HostIdentity.hasHealthRpcService()) {
            Log.i("OriginBand", "FIND_PHONE native unavailable")
            return
        }
        val app = context.applicationContext ?: context
        val healthService = ComponentName(HostIdentity.HEALTH_PACKAGE, HostIdentity.HEALTH_RPC_SERVICE)
        val ring = Intent(ACTION).setPackage(HostIdentity.HEALTH_PACKAGE).putExtra("start", start)
        try {
            val bound = app.bindService(
                Intent().setComponent(healthService),
                object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        app.sendBroadcast(ring)
                        Log.i("OriginBand", if (start) "FIND_PHONE start" else "FIND_PHONE stop")
                        try {
                            app.unbindService(this)
                        } catch (_: RuntimeException) {
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {}
                },
                Context.BIND_AUTO_CREATE,
            )
            if (!bound) Log.i("OriginBand", "FIND_PHONE native unavailable")
        } catch (_: RuntimeException) {
            Log.i("OriginBand", "FIND_PHONE native unavailable")
        }
    }
}
