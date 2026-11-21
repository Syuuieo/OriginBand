// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

import com.originband.link.ui.CopyStyleTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationSettingsPolicyTest {
    @Test
    fun popularInstalledPreservesCatalogOrder() {
        val installed = setOf("com.example.other", "com.tencent.mobileqq", "com.tencent.mm")
        assertEquals(
            listOf("com.tencent.mm", "com.tencent.mobileqq"),
            NotificationSettingsPolicy.popularInstalled(installed).toList(),
        )
    }

    @Test
    fun togglePackageReturnsFreshSetWithoutMutatingInput() {
        val initial = setOf("com.tencent.mm")
        val added = NotificationSettingsPolicy.togglePackage(initial, "com.tencent.mobileqq", true)
        assertEquals(setOf("com.tencent.mm"), initial)
        assertEquals(setOf("com.tencent.mm", "com.tencent.mobileqq"), added)

        val removed = NotificationSettingsPolicy.togglePackage(added, "com.tencent.mm", false)
        assertEquals(setOf("com.tencent.mm", "com.tencent.mobileqq"), added)
        assertEquals(setOf("com.tencent.mobileqq"), removed)
    }

    @Test
    fun allowlistAndHomeSummariesFollowPositiveCopyStyle() {
        val outputs = listOf(
            NotificationSettingsPolicy.allowlistSummary(0),
            NotificationSettingsPolicy.allowlistSummary(3),
            NotificationSettingsPolicy.homeSummary(false, true, true, true),
            NotificationSettingsPolicy.homeSummary(true, false, false, false),
            NotificationSettingsPolicy.homeSummary(true, true, false, false),
            NotificationSettingsPolicy.homeSummary(true, true, true, true),
        )
        assertEquals("全部应用", outputs[0])
        assertEquals("已选 3 个应用", outputs[1])
        assertEquals("待开启通知使用权", outputs[2])
        assertEquals("按需开启通知、来电与音乐联动", outputs[3])
        assertEquals("通知 已开启", outputs[4])
        assertEquals("通知 · 来电 · 音乐 已开启", outputs[5])
        for (text in outputs) {
            assertNull("Unexpected avoided copy in: $text", CopyStyleTest.firstAvoided(text))
        }
    }
}
