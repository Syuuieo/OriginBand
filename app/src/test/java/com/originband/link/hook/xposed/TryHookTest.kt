// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.hook.xposed

import org.junit.Assert.assertEquals
import org.junit.Test

class TryHookTest {
    @Test
    fun aFailedInstallDoesNotStopTheNextOne() {
        var ran = 0
        HookRuntime.tryHook("TEST_HOOK") { ran++ }
        HookRuntime.tryHook("TEST_HOOK_MISSING") {
            throw NoSuchMethodException("reconnectDevice")
        }
        HookRuntime.tryHook("TEST_HOOK_NEXT") { ran++ }
        assertEquals(2, ran)
    }
}
