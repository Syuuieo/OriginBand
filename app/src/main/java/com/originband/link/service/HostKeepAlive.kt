// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.service

import android.content.Context
import com.originband.link.data.SessionLog
import com.originband.link.host.HostIdentity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Health wakes the live service. The live service wakes health. Root is the background-start fallback. */
object HostKeepAlive {
    const val ROOT_GAP_MS = 15_000L
    private val bridgeRootAt = AtomicLong()
    private val healthRootAt = AtomicLong()
    private val root: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "OriginBandWake").apply { isDaemon = true }
    }

    /** Notification, music, or a health card read. No-op when the live service is already up. */
    @JvmStatic
    fun ensureBridge(context: Context?) {
        if (context != null) BandLiveService.ensureProcess(context)
    }

    /** Binder and broadcast threads return before su finishes. */
    @JvmStatic
    fun startBridgeAsync(context: Context?) {
        if (context == null) return
        val app = context.applicationContext
        if (!claim(bridgeRootAt, System.currentTimeMillis(), ROOT_GAP_MS)) return
        root.execute { runBridge(app) }
    }

    /** Package-replaced recovery. The caller is already off the main thread. */
    @JvmStatic
    fun startBridge(context: Context?) {
        if (context == null) return
        if (!claim(bridgeRootAt, System.currentTimeMillis(), ROOT_GAP_MS)) return
        runBridge(context.applicationContext)
    }

    @JvmStatic
    fun startHealthAsync(context: Context?, after: Runnable?) {
        if (context == null || !BandLiveService.mayWake(context) || !HostIdentity.hasHealthRpcService()) return
        val app = context.applicationContext
        if (!claim(healthRootAt, System.currentTimeMillis(), ROOT_GAP_MS)) return
        root.execute {
            val started = OwnershipController.rootStart(
                HostIdentity.HEALTH_PACKAGE,
                HostIdentity.HEALTH_RPC_SERVICE,
                false
            )
            SessionLog.line(app, if (started) "WAKE_HEALTH root" else "WAKE_HEALTH root-failed")
            if (started && after != null) after.run()
        }
    }

    /** One su thread. Callers on a binder or the main thread return before it finishes. */
    @JvmStatic
    fun runRoot(task: Runnable?) {
        if (task != null) root.execute(task)
    }

    @JvmStatic
    fun claim(last: AtomicLong, nowMs: Long, gapMs: Long): Boolean {
        while (true) {
            val previous = last.get()
            if (previous != 0L && nowMs - previous < gapMs) return false
            if (last.compareAndSet(previous, nowMs)) return true
        }
    }

    private fun runBridge(context: Context) {
        val started = OwnershipController.rootStart(
            "com.originband.link",
            "com.originband.link.service.BandLiveService",
            true
        )
        SessionLog.line(context, if (started) "WAKE_BRIDGE root" else "WAKE_BRIDGE root-failed")
    }
}
