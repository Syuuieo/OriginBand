// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ImportWindowTest {
    companion object {
        private const val SELECTED = "AA:BB:CC:DD:EE:FF"
    }

    @Test
    fun onlySelectedDeviceCanUseTheCurrentWindow() {
        val window = ImportWindow()
        window.open(SELECTED, 1000)
        val nonce = window.nonce()
        assertThrows(SecurityException::class.java) { window.authorize(nonce, "11:22:33:44:55:66", 1001) }
        assertThrows(SecurityException::class.java) { window.authorize("wrong", SELECTED, 1001) }
        window.authorize(nonce, SELECTED, 1001)
        window.close()
        assertThrows(SecurityException::class.java) { window.authorize(nonce, SELECTED, 1002) }
        window.open(SELECTED, 1003)
        assertThrows(SecurityException::class.java) { window.authorize(nonce, SELECTED, 1004) }
    }

    @Test
    fun exactDeadlineAndProcessRestartRejectImport() {
        val window = ImportWindow()
        window.open(SELECTED, 500)
        val nonce = window.nonce()
        window.authorize(nonce, SELECTED, 120499)
        assertThrows(SecurityException::class.java) { window.authorize(nonce, SELECTED, 120500) }
        assertFalse(window.isOpen(120500))
        assertThrows(SecurityException::class.java) { ImportWindow().authorize(nonce, SELECTED, 600) }
    }
}
