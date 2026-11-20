// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import com.originband.link.notify.PhoneDnd
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

/** Phone DND mirrored onto the band.
 * Subtype 15 keeps sync on, 23 is the old quiet flag, 110 is the phone rule list the band actually applies. */
object BandDndCommand {
    /** Subtype 15. enabled 1 keeps the phone rule, enabled 0 is what the band's own
     * 同步手机勿扰 switch writes: sync off, delete phone rule list. */
    @JvmStatic
    @JvmOverloads
    fun syncWithPhone(enabled: Boolean = true): XiaomiProto.Command =
        XiaomiProto.Command.newBuilder()
            .setType(2)
            .setSubtype(15)
            .setSystem(
                XiaomiProto.System.newBuilder().setMiscSettingSet(
                    XiaomiProto.MiscSettingSet.newBuilder().setDndSync(
                        XiaomiProto.DndSync.newBuilder().setEnabled(if (enabled) 1 else 0),
                    ),
                ),
            )
            .build()

    /** Subtype 14 setting 2 asks the band where its 同步手机勿扰 switch stands. */
    @JvmStatic
    fun querySync(): XiaomiProto.Command =
        XiaomiProto.Command.newBuilder()
            .setType(2)
            .setSubtype(14)
            .setSystem(
                XiaomiProto.System.newBuilder().setMiscSettingGet(
                    XiaomiProto.MiscSettingGet.newBuilder().setSetting(2),
                ),
            )
            .build()

    /** The switch value the band reports, either as the answer to {@link #querySync()} or
     * pushed after the user moved it on the band. Null when this packet carries no switch. */
    @JvmStatic
    fun syncEnabled(command: XiaomiProto.Command?): Boolean? {
        if (command == null || command.type != 2 || !command.hasSystem()) return null
        val system = command.system
        if (!system.hasMiscSettingSet() || !system.miscSettingSet.hasDndSync()) return null
        return system.miscSettingSet.dndSync.enabled != 0
    }

    @JvmStatic
    fun state(enabled: Boolean): XiaomiProto.Command =
        XiaomiProto.Command.newBuilder()
            .setType(2)
            .setSubtype(23)
            .setSystem(
                XiaomiProto.System.newBuilder().setDndStatus(
                    XiaomiProto.DoNotDisturb.newBuilder().setStatus(if (enabled) 0 else 2),
                ),
            )
            .build()

    /** Subtype 110. Mi Fitness names the phone's manual rule manual_zen_rule.
     * watch_manual is the band's own rule. Phone sync follows manual_zen_rule.
     * activatedAt is unix seconds. The band stores the same unit on watch_manual;
     * the low 32 bits of epoch millis are about ten times smaller and lose the comparison. */
    @JvmStatic
    fun phoneRules(enabled: Boolean, activatedAt: Int): XiaomiProto.Command =
        XiaomiProto.Command.newBuilder()
            .setType(2)
            .setSubtype(110)
            .setSystem(
                XiaomiProto.System.newBuilder().setPhoneZenRules(
                    XiaomiProto.PhoneZenRuleList.newBuilder().addRule(
                        XiaomiProto.PhoneZenRule.newBuilder()
                            .setManual(true)
                            .setName("manual_zen_rule")
                            .setState(if (enabled) 1 else 0)
                            .setConditionOverride(0)
                            .setLastActivation(activatedAt),
                    ),
                ),
            )
            .build()

    /** Subtype 44. Answers the band's silent-mode query and mirrors phone DND onto that flag. */
    @JvmStatic
    fun phoneSilent(silent: Boolean): XiaomiProto.Command =
        XiaomiProto.Command.newBuilder()
            .setType(2)
            .setSubtype(44)
            .setSystem(
                XiaomiProto.System.newBuilder().setPhoneSilentModeSet(
                    XiaomiProto.PhoneSilentModeSet.newBuilder().setPhoneSilentMode(
                        XiaomiProto.PhoneSilentMode.newBuilder().setSilent(silent),
                    ),
                ),
            )
            .build()

    /** Subtype 109. Asks the band for the zen rules it currently stores. */
    @JvmStatic
    fun queryRules(): XiaomiProto.Command =
        XiaomiProto.Command.newBuilder().setType(2).setSubtype(109).build()

    /** Null when this packet has neither the phone rule nor the band's own manual rule. */
    @JvmStatic
    fun manualState(command: XiaomiProto.Command?): Boolean? {
        if (command == null || !command.hasSystem() || !command.system.hasPhoneZenRules()) return null
        for (rule in command.system.phoneZenRules.ruleList) {
            if (!rule.manual) continue
            val name = rule.name
            if (name == "manual_zen_rule" || name == "watch_manual") return rule.state == 1
        }
        return null
    }

    /** Unix seconds. This uint32 holds values through 2038. */
    @JvmStatic
    fun activatedAt(epochMillis: Long): Int {
        val seconds = epochMillis / 1000L
        if (seconds <= 0 || seconds > Int.MAX_VALUE) {
            throw IllegalArgumentException("DND_ACTIVATION_TIME_INVALID")
        }
        return seconds.toInt()
    }

    /** Subtype 110 is a band toggle. Subtype 109 is the stored list: watch_manual stays off while the phone rule is on. */
    @JvmStatic
    fun pushedManualState(command: XiaomiProto.Command?): Boolean? {
        if (command == null || command.subtype != 110) return null
        return manualState(command)
    }

    /** Sync switch, the old quiet flag, then the phone rules. */
    @JvmStatic
    fun mirror(filter: Int): List<XiaomiProto.Command> {
        val on = PhoneDnd.blocksNotifications(filter)
        val now = activatedAt(System.currentTimeMillis())
        return listOf(syncWithPhone(), state(on), phoneRules(on, now), phoneSilent(on))
    }

    /** With the band's own switch off there is nothing to mirror: ask where it stands and stop. */
    @JvmStatic
    fun mirror(filter: Int, syncOn: Boolean): List<XiaomiProto.Command> =
        if (syncOn) mirror(filter) else listOf(querySync())
}
