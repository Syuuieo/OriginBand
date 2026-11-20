// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BandLogCommandTest {
    @Test
    fun exportRequestUsesSystemReportDataOnSubtype80() {
        val command = BandLogCommand.exportDeviceLog()
        assertEquals(2, command.type)
        assertEquals(80, command.subtype)
        assertTrue(command.system.hasReportData())
        assertEquals(XiaomiProto.ReportData.Type.DEVICE_LOG, command.system.reportData.type)
        assertEquals(command, XiaomiProto.Command.parseFrom(command.toByteArray()))
    }

    @Test
    fun resultStatusReadsDeviceLogResultOnSubtype81() {
        val success = XiaomiProto.Command.newBuilder()
            .setType(2)
            .setSubtype(81)
            .setSystem(
                XiaomiProto.System.newBuilder().setReportDataResult(
                    XiaomiProto.ReportData.Result.newBuilder()
                        .setType(XiaomiProto.ReportData.Type.DEVICE_LOG)
                        .setStatus(XiaomiProto.ReportData.Status.SUCCESS),
                ),
            )
            .build()
        assertEquals(0, BandLogCommand.resultStatus(success))
        assertEquals(
            -1,
            BandLogCommand.resultStatus(
                XiaomiProto.Command.newBuilder().setType(2).setSubtype(80).build(),
            ),
        )
    }
}
