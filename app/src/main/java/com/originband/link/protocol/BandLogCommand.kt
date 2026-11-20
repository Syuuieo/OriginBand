// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

/** Xiaomi report-data commands used by the full device-log exporter. */
object BandLogCommand {
    const val REQUEST_SUBTYPE = 80
    const val RESULT_SUBTYPE = 81

    /** System 2/80 with ReportData.Type.DEVICE_LOG. */
    @JvmStatic
    fun exportDeviceLog(): XiaomiProto.Command =
        XiaomiProto.Command.newBuilder()
            .setType(2)
            .setSubtype(REQUEST_SUBTYPE)
            .setSystem(
                XiaomiProto.System.newBuilder()
                    .setReportData(
                        XiaomiProto.ReportData.newBuilder()
                            .setType(XiaomiProto.ReportData.Type.DEVICE_LOG),
                    ),
            )
            .build()

    /**
     * Returns the device status from a 2/81 result, or -1 when the command is a different
     * response. SUCCESS is 0.
     */
    @JvmStatic
    fun resultStatus(command: XiaomiProto.Command?): Int {
        if (command == null || command.type != 2 || !command.hasSubtype() ||
            command.subtype != RESULT_SUBTYPE || !command.hasSystem() ||
            !command.system.hasReportDataResult()
        ) {
            return -1
        }
        val result = command.system.reportDataResult
        return if (result.type == XiaomiProto.ReportData.Type.DEVICE_LOG) {
            result.status.number
        } else {
            -1
        }
    }
}
