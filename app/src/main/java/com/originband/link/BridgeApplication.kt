// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link

import android.app.Application
import android.content.Context
import com.originband.link.data.LegacyPrefsMigration

class BridgeApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Content providers open preferences before Application.onCreate runs.
        LegacyPrefsMigration.run(base)
    }
}
