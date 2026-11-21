// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

/** Band system commands. Find-phone is type 2 subtype 17, not the find-watch ack on 18. */
object BandSystemCommand {
    enum class FindPhone { START, STOP }

    /** Absent {@code findDevice} is proto2 0 via {@code getFindDevice()}, so it starts the ring. */
    @JvmStatic
    fun findPhone(command: XiaomiProto.Command?): FindPhone? {
        if (command == null || command.type != 2 || command.subtype != 17 || !command.hasSystem()) {
            return null
        }
        return if (command.system.findDevice == 0) FindPhone.START else FindPhone.STOP
    }

    /** Reports to the band that the phone stopped ringing so its Find Phone screen exits. */
    @JvmStatic
    fun findPhoneStop(): XiaomiProto.Command =
        XiaomiProto.Command.newBuilder()
            .setType(2)
            .setSubtype(17)
            .setSystem(XiaomiProto.System.newBuilder().setFindDevice(1))
            .build()

    /** Find-watch is type 2 subtype 18. Zero starts the band ring, one stops it. */
    @JvmStatic
    fun findWatch(start: Boolean): XiaomiProto.Command =
        XiaomiProto.Command.newBuilder()
            .setType(2)
            .setSubtype(18)
            .setSystem(XiaomiProto.System.newBuilder().setFindDevice(if (start) 0 else 1))
            .build()
}
