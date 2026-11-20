// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLabelsTest {
    @Test
    fun packageIdIsNotAnApplicationName() {
        assertEquals("", AppLabels.prefer("com.tencent.mm", "com.tencent.mm"))
        assertEquals("", AppLabels.prefer("com.tencent.mm", "  "))
        assertEquals("微信", AppLabels.prefer("com.tencent.mm", "微信"))
    }
}
