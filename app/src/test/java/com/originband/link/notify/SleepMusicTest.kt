// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SleepMusicTest {
    @Test
    fun firstSampleIsBaselineAndOnlyAwakeToAsleepPauses() {
        val gate = SleepMusic()
        assertEquals(SleepMusic.Effect.BASELINE, gate.observe(true).effect())
        assertEquals(SleepMusic.Effect.UNCHANGED, gate.observe(true).effect())
        assertEquals(SleepMusic.Effect.WOKE, gate.observe(false).effect())
        assertEquals(SleepMusic.Effect.UNCHANGED, gate.observe(false).effect())
        val pause = gate.observe(true)
        assertEquals(SleepMusic.Effect.PAUSE, pause.effect())
        assertEquals(SleepMusic.Effect.UNCHANGED, gate.observe(true).effect())
        assertEquals(0, pause.generation())
    }

    @Test
    fun resetDropsTheEdgeUntilTheNextSample() {
        val gate = SleepMusic()
        gate.observe(false)
        assertEquals(1, gate.reset())
        val again = gate.observe(true)
        assertEquals(SleepMusic.Effect.BASELINE, again.effect())
        assertEquals(1, again.generation())
        assertEquals(1, gate.generation())
    }

    @Test
    fun basicAndPushedReportsUseTheWireValues() {
        assertEquals(true, SleepMusic.asleep(basic(true)))
        assertEquals(false, SleepMusic.asleep(basic(false)))
        assertEquals(true, SleepMusic.asleep(pushed(1)))
        assertEquals(false, SleepMusic.asleep(pushed(2)))
        assertNull(SleepMusic.asleep(pushed(0)))
        assertNull(SleepMusic.asleep(pushed(3)))
        assertNull(
            SleepMusic.asleep(
                XiaomiProto.Command.newBuilder().setType(2).setSubtype(79)
                    .setSystem(
                        XiaomiProto.System.newBuilder().setDeviceState(
                            XiaomiProto.DeviceState.newBuilder().setWearingState(1),
                        ),
                    )
                    .build(),
            ),
        )
        assertNull(SleepMusic.asleep(basic(true).toBuilder().setStatus(3).build()))
        assertNull(SleepMusic.asleep(XiaomiProto.Command.newBuilder().setType(2).setSubtype(1).build()))
        assertNull(SleepMusic.asleep(null))
        val query = SleepMusic.query()
        assertEquals(2, query.type)
        assertEquals(78, query.subtype)
        assertFalse(query.hasSystem())
    }

    @Test
    fun currentSleepFilePausesOnceAndAnAsleepBaselineHoldsThatNight() {
        val gate = SleepMusic()
        val now = 1_000_000_000_000L
        val start = now - 30 * 60_000L
        val end = now - 60_000L
        assertEquals(SleepMusic.Effect.UNCHANGED, gate.currentNight(start, end, now, now).effect())
        assertEquals(SleepMusic.Effect.PAUSE, gate.currentNight(start, end, start, now).effect())
        assertEquals(SleepMusic.Effect.UNCHANGED, gate.currentNight(start, end, start, now).effect())
        assertEquals(
            SleepMusic.Effect.UNCHANGED,
            gate.currentNight(start, now - SleepMusic.FILE_WINDOW_MS - 1, 0, now).effect(),
        )
        val next = start + 24 * 60 * 60_000L
        assertEquals(
            SleepMusic.Effect.PAUSE,
            gate.currentNight(next, next + 60_000L, 0, next + 60_000L).effect(),
        )
        val held = SleepMusic()
        assertEquals(SleepMusic.Effect.BASELINE, held.observe(true, now).effect())
        assertEquals(SleepMusic.Effect.UNCHANGED, held.currentNight(start, end, 0, now).effect())
        assertEquals(
            SleepMusic.Effect.PAUSE,
            held.currentNight(next, next + 60_000L, 0, next + 60_000L).effect(),
        )
        assertEquals(SleepMusic.Effect.BASELINE, SleepMusic().push(true).effect())
    }

    @Test
    fun anAwakeReportHoldsTheCurrentNightAndAFreshGateDoesNotPauseIt() {
        val gate = SleepMusic()
        val now = 1_000_000_000_000L
        val start = now - 30 * 60_000L
        val end = now - 60_000L
        assertEquals(SleepMusic.Effect.BASELINE, gate.observe(true, start).effect())
        assertEquals(SleepMusic.Effect.WOKE, gate.observe(false, now).effect())
        assertEquals(SleepMusic.Effect.UNCHANGED, gate.currentNight(start, end, 0, now).effect())
        val next = now + 20 * 60 * 60_000L
        assertEquals(
            SleepMusic.Effect.PAUSE,
            gate.currentNight(next, next + 60_000L, 0, next + 60_000L).effect(),
        )
        val restarted = SleepMusic()
        assertEquals(
            SleepMusic.Effect.UNCHANGED,
            restarted.currentNight(start, end, Long.MAX_VALUE, now).effect(),
        )
        assertEquals(SleepMusic.Effect.PAUSE, restarted.currentNight(start, end, start, now).effect())
        val stillAwake = SleepMusic()
        val arm = now
        assertEquals(SleepMusic.Effect.BASELINE, stillAwake.observe(false, arm).effect())
        assertEquals(SleepMusic.Effect.UNCHANGED, stillAwake.observe(false, arm + 3_600_000L).effect())
        assertEquals(
            SleepMusic.Effect.PAUSE,
            stillAwake.currentNight(arm + 30 * 60_000L, arm + 90 * 60_000L, arm, arm + 90 * 60_000L).effect(),
        )
    }

    companion object {
        private fun basic(asleep: Boolean): XiaomiProto.Command =
            XiaomiProto.Command.newBuilder().setType(2).setSubtype(78)
                .setSystem(
                    XiaomiProto.System.newBuilder().setBasicDeviceState(
                        XiaomiProto.BasicDeviceState.newBuilder()
                            .setIsCharging(false)
                            .setIsWorn(true)
                            .setIsUserAsleep(asleep),
                    ),
                )
                .build()

        private fun pushed(sleepState: Int): XiaomiProto.Command =
            XiaomiProto.Command.newBuilder().setType(2).setSubtype(79)
                .setSystem(
                    XiaomiProto.System.newBuilder().setDeviceState(
                        XiaomiProto.DeviceState.newBuilder().setSleepState(sleepState),
                    ),
                )
                .build()
    }
}
