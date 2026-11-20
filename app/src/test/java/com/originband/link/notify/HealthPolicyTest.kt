// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthPolicyTest {
    @Test
    fun unknownPolicyAllowsAndAClosedMainSwitchDoesNot() {
        assertNull(
            HealthPolicy.blockReason(
                false, false, false, emptySet(), emptySet(), null,
                emptyList(), "com.tencent.mm", true, false,
            ),
        )
        assertEquals(
            "health-main",
            HealthPolicy.blockReason(
                true, false, true, setOf("com.tencent.mm"),
                emptySet(), emptySet(), emptyList(), "com.tencent.mm", false, true,
            ),
        )
    }

    @Test
    fun screenOnPushBlocksOnlyAnUnlockedLitScreen() {
        assertEquals(
            "health-screen",
            HealthPolicy.blockReason(
                true, true, false, setOf("com.tencent.mm"),
                emptySet(), emptySet(), emptyList(), "com.tencent.mm", true, false,
            ),
        )
        assertNull(
            HealthPolicy.blockReason(
                true, true, false, setOf("com.tencent.mm"),
                emptySet(), emptySet(), emptyList(), "com.tencent.mm", true, true,
            ),
        )
        assertNull(
            HealthPolicy.blockReason(
                true, true, false, setOf("com.tencent.mm"),
                emptySet(), emptySet(), emptyList(), "com.tencent.mm", false, false,
            ),
        )
    }

    @Test
    fun aRowBeatsTheDefaultAndSmsFollowsTheFirstSmsRow() {
        assertNull(
            HealthPolicy.blockReason(
                true, true, true, setOf("com.tencent.mm"), emptySet(),
                emptySet(), emptyList(), "com.tencent.mm", true, false,
            ),
        )
        assertEquals(
            "health-package",
            HealthPolicy.blockReason(
                true, true, true, emptySet(),
                setOf("com.tencent.mm"), setOf("com.tencent.mm"), emptyList(),
                "com.tencent.mm", true, false,
            ),
        )
        assertEquals(
            "health-package",
            HealthPolicy.blockReason(
                true, true, true, emptySet(), emptySet(),
                setOf("com.android.mms"), listOf("com.android.mms", "com.google.android.apps.messaging"),
                "com.google.android.apps.messaging", true, false,
            ),
        )
        assertNull(
            HealthPolicy.blockReason(
                true, true, true, setOf("com.android.mms"), emptySet(),
                emptySet(), listOf("com.android.mms", "com.google.android.apps.messaging"),
                "com.google.android.apps.messaging", true, false,
            ),
        )
        assertNull(
            HealthPolicy.blockReason(
                true, true, true, emptySet(), emptySet(),
                setOf("com.tencent.mm"), emptyList(), "com.tencent.mm", true, false,
            ),
        )
        assertEquals(
            "health-package",
            HealthPolicy.blockReason(
                true, true, true, emptySet(), emptySet(),
                null, emptyList(), "com.example.missing", true, false,
            ),
        )
    }
}
