// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class SessionLogTest {
    @Test
    fun onlyTheNewestThreeModuleStartsRemain() {
        val log = """
            old preamble
            --- module start 1 ---
            one
            --- module start 2 ---
            two
            --- module start 3 ---
            three
            --- module start 4 ---
            four
            """.trimIndent() + "\n"
        val kept = SessionLog.keepLastStarts(log, SessionLog.KEPT_STARTS)
        assertFalse(kept.contains("module start 1 "))
        assertFalse(kept.contains("\none\n"))
        assertTrue(kept.contains("--- module start 2 ---"))
        assertTrue(kept.contains("two"))
        assertTrue(kept.contains("--- module start 4 ---"))
        assertTrue(kept.contains("four"))
        assertEquals(3, count(kept))
    }

    @Test
    fun redactStripsAMacBeforeItCanReachTheLog() {
        val clean = SessionLog.redact("read failed AA:bb:cc:dd:ee:ff\nstill")
        assertFalse(clean.lowercase().contains("aa:bb"))
        assertFalse(clean.contains("\n"))
        assertEquals("read failed mac still", clean)
        assertEquals("gone mac", SessionLog.redact("gone aa-bb-cc-dd-ee-ff"))
    }

    @Test
    fun captureBundleKeepsNewestFilesWithinBothLimits() {
        val dir = Files.createTempDirectory("captures")
        try {
            val older = write(dir, "capture-old.jsonl", 40, 1_000L)
            val newer = write(dir, "capture-new.jsonl", 40, 3_000L)
            val huge = write(dir, "capture-huge.jsonl", 400, 2_000L)
            val empty = write(dir, "capture-empty.jsonl", 0, 4_000L)
            // Newest first; a zero-byte capture and one that would overflow the budget are skipped.
            assertEquals(
                listOf(newer, older),
                SessionLog.pickCaptures(arrayOf(older, newer, huge, empty), 3, 100),
            )
            assertEquals(
                listOf(newer),
                SessionLog.pickCaptures(arrayOf(older, newer, huge, empty), 3, 50),
            )
            assertEquals(2, SessionLog.pickCaptures(arrayOf(older, newer, huge), 2, 1_000).size)
            assertTrue(SessionLog.pickCaptures(arrayOf(huge), 3, 100).isEmpty())
            assertTrue(SessionLog.pickCaptures(null, 3, 100).isEmpty())
        } finally {
            dir.toFile().listFiles()?.forEach { it.delete() }
            Files.deleteIfExists(dir)
        }
    }

    private fun write(dir: Path, name: String, bytes: Int, modified: Long): File {
        val file = dir.resolve(name).toFile()
        Files.write(file.toPath(), ByteArray(bytes))
        assertTrue(file.setLastModified(modified))
        return file
    }

    @Test
    fun fewerThanThreeStartsAreLeftIntact() {
        val log = "--- module start 1 ---\nonly\n"
        assertEquals(log, SessionLog.keepLastStarts(log, 3))
    }

    private fun count(text: String): Int {
        var count = 0
        var from = text.indexOf(SessionLog.START_MARK, 0)
        while (from >= 0) {
            count++
            from = text.indexOf(SessionLog.START_MARK, from + SessionLog.START_MARK.length)
        }
        return count
    }
}
