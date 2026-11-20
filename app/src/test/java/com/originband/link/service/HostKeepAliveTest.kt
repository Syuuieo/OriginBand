// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.service

import com.originband.link.host.HostIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class HostKeepAliveTest {
    @Test
    fun rootStartListsOnlyConfiguredHosts() {
        assertEquals(
            "am start-foreground-service --user 0 -n " +
                    "com.originband.link/com.originband.link.service.BandLiveService",
            OwnershipController.rootStartCommand(
                "com.originband.link",
                "com.originband.link.service.BandLiveService",
                true
            )
        )
        assertNull(
            OwnershipController.rootStartCommand(
                HostIdentity.HEALTH_PACKAGE,
                "com.example.Missing",
                true
            )
        )
        assertNull(
            OwnershipController.rootStartCommand(
                "com.originband.link",
                "com.originband.link.service.BandLiveService",
                false
            )
        )
        assertNull(OwnershipController.rootStartCommand("com.example", "com.example.Evil", true))
    }

    @Test
    fun rootWakeClaimsOncePerGap() {
        val last = AtomicLong()
        assertTrue(HostKeepAlive.claim(last, 1_000L, HostKeepAlive.ROOT_GAP_MS))
        assertFalse(HostKeepAlive.claim(last, 1_000L + HostKeepAlive.ROOT_GAP_MS - 1, HostKeepAlive.ROOT_GAP_MS))
        assertTrue(HostKeepAlive.claim(last, 1_000L + HostKeepAlive.ROOT_GAP_MS, HostKeepAlive.ROOT_GAP_MS))
    }
}
