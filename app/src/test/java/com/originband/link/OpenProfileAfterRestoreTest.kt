// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenProfileAfterRestoreTest {
    @Test
    fun followsTheRestoreResultRatherThanTheStatusText() {
        assertTrue(MainActivity.openProfileAfterRestore(MainActivity.Change.RESTORE, true))
        assertFalse(MainActivity.openProfileAfterRestore(MainActivity.Change.RESTORE, false))
        assertFalse(MainActivity.openProfileAfterRestore(MainActivity.Change.REMOVE, true))
    }
}
