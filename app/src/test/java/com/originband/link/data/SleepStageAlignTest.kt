// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepStageAlignTest {
    @Test
    fun newerFileKeepsTheNightAndAnOlderFileDoesNotDeleteIt() {
        val night = listOf(interval(0, 100))
        val claimed = ArrayList<SleepStageAlign.Interval>()
        val newer = SleepStageAlign.withStages(SleepStageAlign.claim(night, claimed), listOf(interval(0, 40)))
        claimed.addAll(newer)
        assertEquals(1, newer.size)
        assertTrue(SleepStageAlign.claim(night, claimed).isEmpty())
    }

    @Test
    fun newerFileWithoutStagesLeavesTheNightToTheOlderFile() {
        val night = listOf(interval(0, 100))
        val open = SleepStageAlign.withStages(SleepStageAlign.claim(night, emptyList()), emptyList())
        assertTrue(open.isEmpty())
        assertEquals(1, SleepStageAlign.claim(night, open).size)
    }

    @Test
    fun separateNightsStayWithTheirOwnFiles() {
        val claimed = ArrayList<SleepStageAlign.Interval>()
        claimed.addAll(
            SleepStageAlign.withStages(
                SleepStageAlign.claim(listOf(interval(1_000, 2_000)), emptyList()),
                listOf(interval(1_000, 1_400)),
            ),
        )
        val older = SleepStageAlign.claim(listOf(interval(0, 100)), claimed)
        assertEquals(1, older.size)
        assertEquals(0L, older[0].startMs())
    }

    companion object {
        private fun interval(start: Long, end: Long): SleepStageAlign.Interval =
            SleepStageAlign.Interval(start, end)
    }
}
