// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun parseLsposedTag() {
        val parsed = UpdateChecker.parseTag("2-0.2.0", "ignored")
        assertEquals(2, parsed.code)
        assertEquals("0.2.0", parsed.name)
    }

    @Test
    fun parseVPrefix() {
        val parsed = UpdateChecker.parseTag("v0.2.0", "fallback")
        assertEquals(0, parsed.code)
        assertEquals("0.2.0", parsed.name)
    }

    @Test
    fun comparesSemverAndReleaseJson() {
        assertTrue(UpdateChecker.isNewerVersion("1.0.2", 0, "1.0.1", 105))
        assertFalse(UpdateChecker.isNewerVersion("1.0.1", 0, "1.0.1", 105))
        assertFalse(UpdateChecker.isNewerVersion("1.0.0", 0, "1.0.1", 105))

        val json = """
            {
              "tag_name": "v1.1.0",
              "name": "OriginBand v1.1.0",
              "html_url": "https://github.com/Syuuieo/OriginBand/releases/tag/v1.1.0",
              "body": "新增 COUI 界面"
            }
        """.trimIndent()
        val result = UpdateChecker.parseReleaseJson(json, 105, "1.0.1")
        assertTrue(result.newer)
        assertEquals("v1.1.0", result.tag)
        assertEquals("1.1.0", result.versionName)
        assertTrue(result.notes.contains("v1.1.0"))
    }
}
