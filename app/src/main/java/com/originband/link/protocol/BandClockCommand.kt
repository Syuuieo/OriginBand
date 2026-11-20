/* Copyright (C) 2023-2024 Andreas Shimokawa, José Rebelo, LuK1337, Yoran Vulker.
 * Clock wire semantics adapted from Gadgetbridge XiaomiSystemService.java at
 * commit 75f923904f8504b03fabdee0987fd1c269a92278.
 */
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import java.time.Instant
import java.time.ZoneId

/** Builds the observed type 2/subtype 3 clock update with explicit quarter-hour offsets. */
object BandClockCommand {
    @JvmStatic
    fun at(instant: Instant?, zone: ZoneId?, use24Hour: Boolean): XiaomiProto.Command {
        if (instant == null || zone == null) throw IllegalArgumentException("CLOCK_SOURCE_MISSING")
        val local = instant.atZone(zone)
        val standard = zone.rules.getStandardOffset(instant).totalSeconds
        val daylight = local.offset.totalSeconds - standard
        if (standard % 900 != 0 || daylight % 900 != 0) {
            throw IllegalArgumentException("TIMEZONE_OFFSET_UNSUPPORTED")
        }
        val clock = XiaomiProto.Clock.newBuilder()
            .setDate(
                XiaomiProto.Date.newBuilder()
                    .setYear(local.year)
                    .setMonth(local.monthValue)
                    .setDay(local.dayOfMonth),
            )
            .setTime(
                XiaomiProto.Time.newBuilder()
                    .setHour(local.hour)
                    .setMinute(local.minute)
                    .setSecond(local.second)
                    .setMillisecond(local.nano / 1_000_000),
            )
            .setTimezone(
                XiaomiProto.TimeZone.newBuilder()
                    .setZoneOffset(standard / 900)
                    .setDstOffset(daylight / 900)
                    .setName(zone.id),
            )
            .setIsNot24Hour(!use24Hour)
        return XiaomiProto.Command.newBuilder()
            .setType(2)
            .setSubtype(3)
            .setSystem(XiaomiProto.System.newBuilder().setClock(clock))
            .build()
    }
}
