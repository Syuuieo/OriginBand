// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BandBatteryStateTest {
    companion object {
        private fun response(battery: XiaomiProto.Battery.Builder): XiaomiProto.Command =
            XiaomiProto.Command.newBuilder()
                .setType(2)
                .setSubtype(1)
                .setSystem(
                    XiaomiProto.System.newBuilder()
                        .setPower(XiaomiProto.Power.newBuilder().setBattery(battery)),
                )
                .build()
    }

    @Test
    fun levelOnlyResponseKeepsChargerUnknown() {
        // Wire round trip reproduces an older peer sending just the optional level field.
        val wire = response(XiaomiProto.Battery.newBuilder().setLevel(67)).toByteArray()
        val state = BandBatteryState.parse(XiaomiProto.Command.parseFrom(wire))
        assertEquals(67, state.level)
        assertNull(state.chargerState)
        assertNull(state.charging())
    }

    @Test
    fun zeroAndFutureStatusKeepVerifiedPercentage() {
        for (value in intArrayOf(0, 4, 255, -1)) {
            val state = BandBatteryState.parse(
                response(XiaomiProto.Battery.newBuilder().setLevel(81).setState(value)),
            )
            assertEquals(81, state.level)
            assertNull(state.chargerState)
            assertNull(state.charging())
        }
    }

    @Test
    fun knownStatusesHaveExplicitChargingSemantics() {
        for (value in 1..3) {
            val state = BandBatteryState.parse(
                response(XiaomiProto.Battery.newBuilder().setLevel(100).setState(value)),
            )
            assertEquals(value, state.chargerState)
            assertEquals(value == 1, state.charging())
        }
    }

    @Test
    fun validatesPresenceAndRangeIndependentlyOfStatus() {
        assertEquals(
            "BATTERY_MISSING",
            assertThrows(IllegalArgumentException::class.java) {
                BandBatteryState.parse(XiaomiProto.Command.newBuilder().setType(2).setSubtype(1).build())
            }.message,
        )
        assertEquals(
            "BATTERY_VALUE_INVALID",
            assertThrows(IllegalArgumentException::class.java) {
                BandBatteryState.parse(response(XiaomiProto.Battery.newBuilder().setState(1)))
            }.message,
        )
        for (value in intArrayOf(-1, 101, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                BandBatteryState.parse(response(XiaomiProto.Battery.newBuilder().setLevel(value)))
            }
            assertThrows(IllegalArgumentException::class.java) {
                BandBatteryState(value, null)
            }
        }
        assertEquals(
            0,
            BandBatteryState.parse(response(XiaomiProto.Battery.newBuilder().setLevel(0))).level,
        )
        assertEquals(100, BandBatteryState(100, null).level)
    }
}
