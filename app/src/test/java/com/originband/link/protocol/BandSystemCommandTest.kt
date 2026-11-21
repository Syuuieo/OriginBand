// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BandSystemCommandTest {
    companion object {
        private fun system(subtype: Int, system: XiaomiProto.System.Builder): XiaomiProto.Command =
            XiaomiProto.Command.newBuilder().setType(2).setSubtype(subtype).setSystem(system).build()
    }

    @Test
    fun findDeviceZeroStarts() {
        val command = system(17, XiaomiProto.System.newBuilder().setFindDevice(0))
        assertEquals(BandSystemCommand.FindPhone.START, BandSystemCommand.findPhone(command))
    }

    @Test
    fun findDeviceOneStops() {
        val command = system(17, XiaomiProto.System.newBuilder().setFindDevice(1))
        assertEquals(BandSystemCommand.FindPhone.STOP, BandSystemCommand.findPhone(command))
    }

    @Test
    fun findWatchSubtypeIsIgnored() {
        val command = system(18, XiaomiProto.System.newBuilder().setFindDevice(0))
        assertNull(BandSystemCommand.findPhone(command))
    }

    @Test
    fun missingSystemIsIgnored() {
        val command = XiaomiProto.Command.newBuilder().setType(2).setSubtype(17).build()
        assertNull(BandSystemCommand.findPhone(command))
    }

    @Test
    fun findWatchUsesSubtypeEighteen() {
        val start = BandSystemCommand.findWatch(true)
        val stop = BandSystemCommand.findWatch(false)
        assertEquals(2, start.type)
        assertEquals(18, start.subtype)
        assertEquals(0, start.system.findDevice)
        assertEquals(1, stop.system.findDevice)
    }

    @Test
    fun findPhoneStopUsesSubtypeSeventeenAndValueOne() {
        val command = BandSystemCommand.findPhoneStop()
        assertEquals(2, command.type)
        assertEquals(17, command.subtype)
        assertEquals(1, command.system.findDevice)
        assertEquals(BandSystemCommand.FindPhone.STOP, BandSystemCommand.findPhone(command))
    }
}
