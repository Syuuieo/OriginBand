// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Stops an active local find-phone ring from its notification tap or action button. */
class FindPhoneStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action != FindPhone.ACTION_STOP_LOCAL) return
        FindPhone.stopFromPhone(context)
    }
}
