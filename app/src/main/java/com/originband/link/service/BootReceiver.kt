// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log
import com.originband.link.data.BandStateRepository
import com.originband.link.integration.DeviceCardProvider
import com.originband.link.protocol.SppDiagnosticClient

/** After boot, unlock, or a package replace, resume the live link while this module still owns the band. A locked user returns first. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (Intent.ACTION_BOOT_COMPLETED != action
            && Intent.ACTION_USER_UNLOCKED != action
            && Intent.ACTION_MY_PACKAGE_REPLACED != action
        ) return
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return
        val pending = goAsync()
        val restore = Thread({
            try {
                context.contentResolver.notifyChange(DeviceCardProvider.URI, null)
                val owner = OwnershipController(context)
                val repository = BandStateRepository(context)
                if (repository.isRegistered() && owner.nativeReady()) {
                    BandLiveService.startBlocking(context)
                } else {
                    repository.markSessionClosed()
                    BandLiveService.stop(context)
                    SppDiagnosticClient.stopAllAndWait()
                }
            } catch (_: RuntimeException) {
                Log.i("OplusBandBridge", "BOOT_OWNERSHIP_RECOVERY_REQUIRED")
            } finally {
                pending.finish()
            }
        }, "OplusBandBootRecovery")
        restore.isDaemon = true
        restore.start()
    }
}
