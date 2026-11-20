// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifySwitchTest {
    @Test
    fun booleanAndNumericSwitchesBothCount() {
        assertTrue(NotifySwitch.on(true))
        assertFalse(NotifySwitch.on(false))
        assertTrue(NotifySwitch.on(1))
        assertFalse(NotifySwitch.on(0))
        assertFalse(NotifySwitch.on(null))
        assertFalse(NotifySwitch.on("true"))
    }
}
