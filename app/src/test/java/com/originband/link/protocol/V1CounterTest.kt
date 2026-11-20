// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class V1CounterTest {
    @Test
    @Throws(Exception::class)
    fun lastTwoValuesPassAndTheNextClosesTheSession() {
        val client = SppDiagnosticClient(
            object : ContextWrapper(null) {
                override fun getApplicationContext(): Context = this
            },
        ) { }
        client.armV1Counter(0xfffe)
        assertEquals(0xfffe, client.takeV1Counter())
        assertEquals(0xffff, client.takeV1Counter())
        try {
            client.takeV1Counter()
            fail("counter past 0xffff reuses the CCM nonce")
        } catch (exhausted: SppDiagnosticClient.Failure) {
            assertEquals("V1_COUNTER_EXHAUSTED", exhausted.code)
        }
    }
}
