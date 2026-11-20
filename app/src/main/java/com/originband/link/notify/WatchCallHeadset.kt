// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import com.originband.link.data.LocalPrefs
import com.originband.link.data.SessionLog

/** Phone-side HFP gateway for watch-form devices. Bands never reach this. */
object WatchCallHeadset {
    private val LOCK = Any()
    private var app: Context? = null
    private var adapter: BluetoothAdapter? = null
    private var headset: BluetoothHeadset? = null
    private var wanted = WatchCallRoute.Link.NONE
    private var receiverRegistered = false
    private var proxyRequested = false

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile != BluetoothProfile.HEADSET) return
            synchronized(LOCK) {
                headset = proxy as? BluetoothHeadset
                applyNow()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HEADSET) return
            synchronized(LOCK) { headset = null }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            synchronized(LOCK) { applyNow() }
        }
    }

    @JvmStatic
    fun apply(context: Context?, link: WatchCallRoute.Link?) {
        if (context == null || link == null) return
        synchronized(LOCK) {
            val application = context.applicationContext ?: context
            app = application
            wanted = link
            SessionLog.line(application, "CALL_HFP $link")
            if (application.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                SessionLog.line(application, "CALL_HFP denied")
                return
            }
            if (headset == null) {
                ensureProxy()
                return
            }
            applyNow()
        }
    }

    private fun ensureProxy() {
        val context = app ?: return
        if (proxyRequested) return
        val manager = context.getSystemService(BluetoothManager::class.java)
        val btAdapter = manager?.adapter
        adapter = btAdapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            SessionLog.line(context, "CALL_HFP unavailable")
            return
        }
        if (!receiverRegistered) {
            val filter = IntentFilter(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED).apply {
                addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
            }
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            receiverRegistered = true
        }
        proxyRequested = btAdapter.getProfileProxy(context, listener, BluetoothProfile.HEADSET)
        if (!proxyRequested) SessionLog.line(context, "CALL_HFP unavailable")
    }

    private fun applyNow() {
        val context = app ?: return
        val profile = headset ?: return
        val mac = LocalPrefs.open(context, "band-state").getString("mac", "")
        if (mac.isBlank()) {
            SessionLog.line(context, "CALL_HFP no-device")
            return
        }
        val device = try {
            val currentAdapter = adapter ?: context.getSystemService(BluetoothManager::class.java)?.adapter?.also {
                adapter = it
            } ?: return
            currentAdapter.getRemoteDevice(mac)
        } catch (_: IllegalArgumentException) {
            SessionLog.line(context, "CALL_HFP no-device")
            return
        }
        try {
            invoke(
                "setActiveDevice",
                arrayOf(BluetoothDevice::class.java),
                if (wanted == WatchCallRoute.Link.NONE) arrayOf(null) else arrayOf(device),
            )
            val state = profile.getConnectionState(device)
            if (wanted == WatchCallRoute.Link.NONE) {
                if (profile.isAudioConnected(device)) invoke("disconnectAudio", emptyArray(), emptyArray())
                SessionLog.line(context, "CALL_HFP audio=release state=$state")
                return
            }
            if (state != BluetoothProfile.STATE_CONNECTED) {
                val started = invoke("connect", arrayOf(BluetoothDevice::class.java), arrayOf(device)) == true
                SessionLog.line(context, "CALL_HFP profile=$started state=$state")
                return
            }
            if (wanted == WatchCallRoute.Link.HANDSET && !profile.isAudioConnected(device)) {
                val audio = invoke("connectAudio", emptyArray(), emptyArray()) == true
                SessionLog.line(context, "CALL_HFP audio=$audio")
                return
            }
            SessionLog.line(context, "CALL_HFP profile=up audio=${profile.isAudioConnected(device)}")
        } catch (_: SecurityException) {
            SessionLog.line(context, "CALL_HFP denied")
        } catch (failure: RuntimeException) {
            SessionLog.line(context, "CALL_HFP failed ${failure.javaClass.simpleName}")
        }
    }

    /** These gateway methods are hidden. The phone asks the watch to accept HFP, then moves SCO. */
    private fun invoke(name: String, types: Array<Class<*>>, args: Array<Any?>): Any? =
        try {
            val method = BluetoothHeadset::class.java.getMethod(name, *types)
            method.invoke(headset, *args)
        } catch (_: ReflectiveOperationException) {
            SessionLog.line(app, "CALL_HFP $name-unavailable")
            false
        }
}
