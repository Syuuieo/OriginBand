// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

/** A verified percentage plus independently optional charger telemetry. */
class BandBatteryState(val level: Int, chargerState: Int?) {
    // Official DeviceModelExtKt.updateDeviceStatus accepts charge status 0 alongside a level.
    // Keep absent, zero and future charger values unknown while retaining the verified level.
    val chargerState: Int? = if (chargerState != null && chargerState in 1..3) chargerState else null

    init {
        if (level < 0 || level > 100) throw IllegalArgumentException("BATTERY_VALUE_INVALID")
    }

    fun level(): Int = level

    fun chargerState(): Int? = chargerState

    /** Null represents unknown; states 2 (idle) and 3 (full) are confirmed false. */
    fun charging(): Boolean? = chargerState?.let { it == 1 }

    operator fun component1(): Int = level

    operator fun component2(): Int? = chargerState

    fun copy(level: Int = this.level, chargerState: Int? = this.chargerState): BandBatteryState =
        BandBatteryState(level, chargerState)

    override fun equals(other: Any?): Boolean =
        this === other || (other is BandBatteryState && level == other.level && chargerState == other.chargerState)

    override fun hashCode(): Int = 31 * level + (chargerState ?: 0)

    override fun toString(): String = "BandBatteryState(level=$level, chargerState=$chargerState)"

    companion object {
        @JvmStatic
        fun parse(command: XiaomiProto.Command?): BandBatteryState {
            if (command == null || !command.hasSystem() || !command.system.hasPower() ||
                !command.system.power.hasBattery()
            ) {
                throw IllegalArgumentException("BATTERY_MISSING")
            }
            val battery = command.system.power.battery
            if (!battery.hasLevel()) throw IllegalArgumentException("BATTERY_VALUE_INVALID")
            return BandBatteryState(battery.level, if (battery.hasState()) battery.state else null)
        }
    }
}
