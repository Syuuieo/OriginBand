// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import com.originband.link.notify.PhoneDnd
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BandDndCommandTest {
    @Test
    fun enabledAndDisabledUseTheWatchStatusCodes() {
        val on = BandDndCommand.state(true)
        val off = BandDndCommand.state(false)
        assertEquals(2, on.type)
        assertEquals(23, on.subtype)
        assertEquals(0, on.system.dndStatus.status)
        assertEquals(2, off.system.dndStatus.status)
    }

    @Test
    fun syncWithPhoneEnablesTheSwitchAndDoesNotSetStatus() {
        val command = BandDndCommand.syncWithPhone()
        assertEquals(2, command.type)
        assertEquals(15, command.subtype)
        assertEquals(1, command.system.miscSettingSet.dndSync.enabled)
        assertFalse(command.system.hasDndStatus())
    }

    @Test
    fun syncWithPhoneCanCarryTheBandsOwnOffPosition() {
        val off = BandDndCommand.syncWithPhone(false)
        assertEquals(2, off.type)
        assertEquals(15, off.subtype)
        assertEquals(0, off.system.miscSettingSet.dndSync.enabled)
    }

    @Test
    fun querySyncAsksForTheDndSwitch() {
        val command = BandDndCommand.querySync()
        assertEquals(2, command.type)
        assertEquals(14, command.subtype)
        assertEquals(2, command.system.miscSettingGet.setting)
        assertFalse(command.system.hasMiscSettingSet())
    }

    @Test
    fun syncEnabledReadsOnlyPacketsThatCarryTheSwitch() {
        assertEquals(true, BandDndCommand.syncEnabled(BandDndCommand.syncWithPhone(true)))
        assertEquals(false, BandDndCommand.syncEnabled(BandDndCommand.syncWithPhone(false)))
        // The band pushes the same body when the user moves the switch; the subtype is not the tell.
        assertEquals(
            false,
            BandDndCommand.syncEnabled(
                BandDndCommand.syncWithPhone(false).toBuilder().setSubtype(14).build(),
            ),
        )
        assertNull(BandDndCommand.syncEnabled(BandDndCommand.querySync()))
        assertNull(BandDndCommand.syncEnabled(BandDndCommand.state(true)))
        assertNull(BandDndCommand.syncEnabled(BandDndCommand.phoneRules(true, 1)))
        assertNull(BandDndCommand.syncEnabled(null))
    }

    @Test
    fun phoneRulesCarryTheManualZenRule() {
        val on = BandDndCommand.phoneRules(true, 1_700_000_000)
        val off = BandDndCommand.phoneRules(false, 1_700_000_100)
        assertEquals(2, on.type)
        assertEquals(110, on.subtype)
        assertEquals(1, on.system.phoneZenRules.ruleCount)
        val rule = on.system.phoneZenRules.getRule(0)
        assertTrue(rule.manual)
        assertEquals("manual_zen_rule", rule.name)
        assertEquals(1, rule.state)
        assertEquals(0, rule.conditionOverride)
        assertEquals(1_700_000_000, rule.lastActivation)
        assertFalse(rule.hasSchedule())
        assertEquals(0, off.system.phoneZenRules.getRule(0).state)
    }

    @Test
    fun manualStateReadsOnlyTheWatchManualRule() {
        assertEquals(true, BandDndCommand.manualState(BandDndCommand.phoneRules(true, 1)))
        assertEquals(false, BandDndCommand.manualState(BandDndCommand.phoneRules(false, 1)))
        assertNull(BandDndCommand.manualState(BandDndCommand.state(true)))
        assertNull(BandDndCommand.manualState(null))
    }

    @Test
    fun activationTimeIsUnixSecondsNotMillisLowBits() {
        assertEquals(1_791_184_800, BandDndCommand.activatedAt(1_791_184_800_123L))
        assertNotEquals(1_791_184_800_123L.toInt(), BandDndCommand.activatedAt(1_791_184_800_123L))
    }

    @Test
    fun storedRuleListIsNotABandToggle() {
        val stored = XiaomiProto.Command.newBuilder()
            .setType(2)
            .setSubtype(109)
            .setSystem(
                XiaomiProto.System.newBuilder().setPhoneZenRules(
                    XiaomiProto.PhoneZenRuleList.newBuilder().addRule(
                        XiaomiProto.PhoneZenRule.newBuilder()
                            .setManual(true)
                            .setName("watch_manual")
                            .setState(0),
                    ),
                ),
            )
            .build()
        assertNull(BandDndCommand.pushedManualState(stored))
        assertEquals(
            false,
            BandDndCommand.pushedManualState(stored.toBuilder().setSubtype(110).build()),
        )
        assertEquals(true, BandDndCommand.pushedManualState(BandDndCommand.phoneRules(true, 1)))
        assertNull(BandDndCommand.pushedManualState(null))
    }

    @Test
    fun mirrorWithSyncOffOnlyReadsTheSwitchBack() {
        val packets = BandDndCommand.mirror(PhoneDnd.NONE, false)
        assertEquals(1, packets.size)
        assertEquals(14, packets[0].subtype)
        assertEquals(2, packets[0].system.miscSettingGet.setting)
        assertEquals(4, BandDndCommand.mirror(PhoneDnd.NONE, true).size)
    }

    @Test
    fun mirrorAlwaysSendsTheSwitchThenTheLiveStatus() {
        val before = BandDndCommand.activatedAt(System.currentTimeMillis())
        for (filter in intArrayOf(PhoneDnd.NONE, PhoneDnd.ALL, PhoneDnd.UNKNOWN)) {
            val packets = BandDndCommand.mirror(filter)
            assertEquals(4, packets.size)
            val sync = packets[0]
            val state = packets[1]
            val rules = packets[2]
            val silent = packets[3]
            assertEquals(15, sync.subtype)
            assertEquals(1, sync.system.miscSettingSet.dndSync.enabled)
            assertEquals(23, state.subtype)
            val on = filter == PhoneDnd.NONE
            assertEquals(if (on) 0 else 2, state.system.dndStatus.status)
            assertEquals(110, rules.subtype)
            val rule = rules.system.phoneZenRules.getRule(0)
            assertEquals("manual_zen_rule", rule.name)
            assertEquals(if (on) 1 else 0, rule.state)
            assertTrue(rule.manual)
            assertTrue(rule.lastActivation - before >= 0)
            assertTrue(rule.lastActivation - before < 5)
            assertEquals(1, rules.system.phoneZenRules.ruleCount)
            assertEquals(44, silent.subtype)
            assertEquals(on, silent.system.phoneSilentModeSet.phoneSilentMode.silent)
        }
    }
}
