// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.service

import com.originband.link.host.HostIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RootStartupGrantTest {
    @Test
    fun commandUsesInstalledApkAndFixedPackages() {
        assertEquals(
            "CLASSPATH=/data/app/~~a1B2==/com.originband.link-x_Y==/base.apk" +
                    " /system/bin/app_process /system/bin" +
                    " com.originband.link.service.RootStartupGrant" +
                    " com.originband.link " + HostIdentity.HEALTH_PACKAGE,
            OwnershipController.startupGrantCommand(
                "/data/app/~~a1B2==/com.originband.link-x_Y==/base.apk"
            )
        )
    }

    @Test
    fun rejectsPathsThatCouldCarryShellText() {
        assertNull(OwnershipController.startupGrantCommand(null))
        assertNull(OwnershipController.startupGrantCommand("/data/app/x/base.apk; reboot"))
        assertNull(OwnershipController.startupGrantCommand("/data/app/x y/base.apk"))
        assertNull(OwnershipController.startupGrantCommand("/data/app/../../system/base.apk"))
        assertNull(OwnershipController.startupGrantCommand("/sdcard/base.apk"))
    }

    @Test
    fun onlyMissingPackagesAreChanged() {
        assertEquals(listOf("b"), RootStartupGrant.missing(listOf("a", "b"), listOf("a", "c")))
    }
}
