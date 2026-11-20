// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MacIdsTest {
    @Test
    fun sameAddressMatchesAcrossSeparatorAndCase() {
        assertEquals("aabbccddeeff", MacIds.normalize("AA:BB:CC:DD:EE:FF"))
        assertTrue(MacIds.same("aa:bb:cc:dd:ee:ff", "AA-BB-CC-DD-EE-FF"))
        assertTrue(MacIds.same("aabbccddeeff", "AA:BB:CC:DD:EE:FF"))
        assertFalse(MacIds.same("aa:bb:cc:dd:ee:ff", "aa:bb:cc:dd:ee:f0"))
        assertFalse(MacIds.same("aa:bb:cc:dd:ee:ff", ""))
        assertFalse(MacIds.same("not-a-mac", "not-a-mac"))
    }
}
