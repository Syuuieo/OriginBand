// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyPrefsMigrationTest {
    @Test
    fun keepsOnlyPrefsFilesUnderDataMisc() {
        val listing = "/data/misc/apexdata/09d9/prefs/com.originband.link/notification-settings.xml\n" +
            "Permission denied\n" +
            "/data/misc/x/prefs/com.originband.link/../evil.xml\n" +
            "/data/data/other/shared_prefs/a.xml\n"
        assertEquals(
            listOf("/data/misc/apexdata/09d9/prefs/com.originband.link/notification-settings.xml"),
            LegacyPrefsMigration.lines(listing),
        )
    }
}
