// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

/** Type 17 subtype 16 phone-alarm alert, dismiss, and snooze. Every connected model gets the same packet. */
object BandAlarmCommand {
    @JvmStatic
    fun operation(opCode: Int, alarmId: Int, alertTimeSec: Int, label: String?): XiaomiProto.Command {
        if (opCode != 0 && opCode != 1 && opCode != 2) {
            throw IllegalArgumentException("PHONE_ALARM_OP")
        }
        if (alarmId < 0) throw IllegalArgumentException("PHONE_ALARM_ID")
        val target = XiaomiProto.PhoneAlarmTarget.newBuilder()
            .setId(alarmId)
            .setLabel(label ?: "")
        if (alertTimeSec >= 0) target.setAlertTime(alertTimeSec)
        return XiaomiProto.Command.newBuilder()
            .setType(17)
            .setSubtype(16)
            .setSchedule(
                XiaomiProto.Schedule.newBuilder().setPhoneAlarmOperation(
                    XiaomiProto.PhoneAlarmOperation.newBuilder()
                        .setOpCode(opCode)
                        .setPhoneAlarm(target),
                ),
            )
            .build()
    }
}
