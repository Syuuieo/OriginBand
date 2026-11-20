// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleNotifyQueueTest {
    @Test
    fun secondSubscribeWaitsForDescriptorCallback() {
        val queue = BleNotifyQueue(2)
        assertEquals(0, queue.start())
        assertEquals(-1, queue.start())
        assertFalse(queue.complete())
        assertTrue(queue.confirmed())
        assertEquals(1, queue.start())
        assertEquals(-1, queue.start())
        assertTrue(queue.confirmed())
        assertTrue(queue.complete())
        assertEquals(-1, queue.start())
    }

    @Test
    fun singleCharacteristicCompletesAfterItsCallback() {
        val queue = BleNotifyQueue(1)
        assertEquals(0, queue.start())
        assertFalse(queue.complete())
        assertTrue(queue.confirmed())
        assertTrue(queue.complete())
        assertFalse(queue.confirmed())
    }
}
