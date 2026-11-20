// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class LocalPrefsFileTest {
    @Rule
    @JvmField
    val folder = TemporaryFolder()

    @Test
    fun readsAFileLargerThanTheOld32KibCap() {
        val json = JSONObject().apply {
            put("mode", "NATIVE")
            put("pad", "x".repeat(40_000))
        }
        val file = folder.newFile("ownership.json")
        assertTrue(LocalPrefs.writeJson(file, json))
        assertTrue(file.length() > 32_768)
        val stored = LocalPrefs.readStored(file)
        assertTrue(stored.present)
        assertEquals("NATIVE", stored.json.getString("mode"))
        assertEquals(40_000, stored.json.getString("pad").length)
    }

    @Test
    fun keepsABrokenFileFromLookingMissing() {
        val file = folder.newFile("ownership.json")
        Files.write(file.toPath(), "{".toByteArray(StandardCharsets.UTF_8))
        val stored = LocalPrefs.readStored(file)
        assertTrue(stored.present)
        assertEquals(0, stored.json.length())
        assertEquals("{", String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8))
    }

    @Test
    fun treatsAMissingFileAsAbsent() {
        val stored = LocalPrefs.readStored(File(folder.root, "absent.json"))
        assertFalse(stored.present)
    }
}
