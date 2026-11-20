// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.originband.link.MainActivity

/** User-initiated tile tap. Wakes the bridge or opens it, then leaves no screen behind. */
class BandTileActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            if (BandTileState.click(BandLiveService.mayWake(this)) == BandTileState.Action.WAKE) {
                try {
                    BandLiveService.start(this)
                } catch (_: RuntimeException) {
                }
            } else {
                startActivity(Intent(this, MainActivity::class.java))
            }
        } finally {
            finish()
        }
    }
}
