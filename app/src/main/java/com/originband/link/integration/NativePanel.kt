// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.integration

import android.content.Context
import android.content.Intent
import com.originband.link.host.HostIdentity

/** Launches the host health app when requested; deep-link panel actions await Phase 2 vivo Health inspection. */
object NativePanel {
    const val BAND_MODEL = "OB19B1"

    @JvmStatic
    fun panelAction(action: String?): Boolean = false

    @JvmStatic
    fun launchHealthIntent(context: Context?): Intent? {
        if (context == null) return null
        return context.packageManager.getLaunchIntentForPackage(HostIdentity.HEALTH_PACKAGE)
    }

    @JvmStatic
    fun redirect(context: Context?, intent: Intent?): Boolean = false
}
