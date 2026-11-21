// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

import org.junit.Assert.assertEquals
import org.junit.Test

class FindPhoneTest {
    @Test
    fun targetAlarmVolumeRaisesQuietVolumeToHighFloorAndPreservesLouder() {
        assertEquals(0, FindPhone.targetAlarmVolume(0, 0))
        assertEquals(12, FindPhone.targetAlarmVolume(2, 15))
        assertEquals(15, FindPhone.targetAlarmVolume(15, 15))
        assertEquals(14, FindPhone.targetAlarmVolume(14, 15))
        assertEquals(1, FindPhone.targetAlarmVolume(0, 1))
    }
}
