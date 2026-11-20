// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.service

import org.junit.Assert.assertEquals
import org.junit.Test

class OwnershipShellQuoteTest {
    @Test
    fun quotesRedirectsForSuDashC() {
        assertEquals(
            "'pm disable-user --user 0 com.mi.health'",
            OwnershipController.shellQuote("pm disable-user --user 0 com.mi.health")
        )
    }

    @Test
    fun wrapsPlainCommands() {
        assertEquals("'id -u'", OwnershipController.shellQuote("id -u"))
    }

    @Test
    fun zenCommandUsesTheNotificationService() {
        assertEquals("cmd notification set_dnd off", OwnershipController.zenCommand(false))
        assertEquals("cmd notification set_dnd priority", OwnershipController.zenCommand(true))
        assertEquals(
            "cmd notification allow_dnd com.originband.link",
            OwnershipController.allowDndCommand()
        )
    }
}
