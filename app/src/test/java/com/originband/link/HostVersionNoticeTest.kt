// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link

import com.originband.link.host.HostIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostVersionNoticeTest {
    @Test
    fun verifiedBuildsStayQuiet() {
        val installed = listOf(
            app(HostIdentity.HEALTH_PACKAGE, "7.1.3.72", 71_372L),
            app(HostIdentity.MI_PACKAGE, "3.59.1", 359_001L),
        )
        assertEquals("", HostVersionNotice.fingerprint(installed))
        assertTrue(HostVersionNotice.accepted(HostIdentity.MI_PACKAGE, "3.59.1_20260906", 359_001L))
        assertTrue(HostVersionNotice.accepted(HostIdentity.HEALTH_PACKAGE, "7.1.3.72", 71_372L))
    }

    @Test
    fun aDifferentInstalledBuildIsNamedOnce() {
        val installed = listOf(
            app(HostIdentity.HEALTH_PACKAGE, "7.0.0", 7_000_000L),
            app(HostIdentity.MI_PACKAGE, "3.40.0", 340_000L),
        )
        val finger = HostVersionNotice.fingerprint(installed)
        assertEquals(finger, HostVersionNotice.fingerprint(installed))
        assertTrue(finger.contains(HostIdentity.MI_PACKAGE))
        assertTrue(finger.contains(HostIdentity.HEALTH_PACKAGE))
        val message = HostVersionNotice.message(installed)
        assertTrue(message.contains("小米运动健康 当前 3.40.0"))
        assertTrue(message.contains("vivo 健康 当前 7.0.0"))
    }

    private fun app(packageName: String, versionName: String, versionCode: Long): HostVersionNotice.Installed {
        return HostVersionNotice.Installed(packageName, versionName, versionCode, true)
    }
}
