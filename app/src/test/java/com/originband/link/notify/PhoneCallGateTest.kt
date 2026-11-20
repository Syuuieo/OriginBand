// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

import com.originband.link.notify.PhoneCallGate.State.IDLE
import com.originband.link.notify.PhoneCallGate.State.OFFHOOK
import com.originband.link.notify.PhoneCallGate.State.RINGING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

class PhoneCallGateTest {
    @Test
    fun dualSimStaysIncomingUntilTheLastRingEnds() {
        val sender = Sender()
        val gate = connected(sender)
        gate.onState(1, RINGING)
        gate.onState(2, RINGING)
        sender.ack(0)
        assertTrue(gate.isRingingDelivered())
        gate.onState(1, OFFHOOK)
        assertEquals(listOf("incoming"), sender.events)
        gate.onState(2, IDLE)
        assertEquals(listOf("incoming", "cancel", "active"), sender.events)
        gate.onState(1, IDLE)
        assertEquals(listOf("incoming", "cancel", "active", "cancel", "clear"), sender.events)
        sender.ack(2)
        assertFalse(gate.isRingingDelivered())
    }

    @Test
    fun outgoingDialIsNotAnIncomingAlert() {
        val sender = Sender()
        val gate = connected(sender)
        gate.onState(1, OFFHOOK)
        gate.onState(2, OFFHOOK)
        assertEquals(listOf("outgoing"), sender.events)
        gate.onState(1, IDLE)
        gate.onState(2, IDLE)
        assertEquals(listOf("outgoing", "cancel", "clear"), sender.events)
    }

    @Test
    fun answeringAnOutgoingCallReplacesTheAlertWithTheActiveScreen() {
        val sender = Sender()
        val gate = connected(sender)
        gate.onState(1, OFFHOOK)
        assertEquals(listOf("outgoing"), sender.events)
        assertTrue(gate.markAnswered())
        assertEquals(listOf("outgoing", "cancel", "active"), sender.events)
        assertFalse(gate.markAnswered())
        gate.onState(1, IDLE)
        assertEquals(listOf("outgoing", "cancel", "active", "cancel", "clear"), sender.events)
    }

    @Test
    fun answeringReplacesTheRingWithTheActiveScreen() {
        val sender = Sender()
        val gate = connected(sender)
        gate.onState(1, RINGING)
        assertFalse(gate.isRingingDelivered())
        gate.onState(1, OFFHOOK)
        assertEquals(listOf("incoming", "cancel", "active"), sender.events)
        sender.ack(0)
        assertFalse(gate.isRingingDelivered())
        sender.ack(1)
        assertFalse(gate.isRingingDelivered())
    }

    @Test
    fun disableClearsCurrentAlertAndDoesNotRetainOldCallState() {
        val sender = Sender()
        val gate = connected(sender)
        gate.onState(1, RINGING)
        sender.ack(0)
        gate.setEnabled(false)
        gate.onState(2, RINGING)
        gate.setEnabled(false)
        gate.setEnabled(true)
        assertEquals(listOf("incoming", "cancel", "clear"), sender.events)
        assertFalse(gate.isRingingDelivered())
        gate.replaceStates(mapOf(1 to IDLE, 2 to IDLE))
        assertEquals(listOf("incoming", "cancel", "clear"), sender.events)
    }

    @Test
    fun reconnectShowsALiveCallAndDoesNotReplayAnEndedOne() {
        val sender = Sender()
        val gate = connected(sender)
        gate.onState(1, RINGING)
        gate.disconnected()
        gate.onState(1, RINGING)
        gate.connected(mapOf(1 to IDLE, 2 to OFFHOOK))
        sender.ack(0)
        assertFalse(gate.isRingingDelivered())
        assertEquals(listOf("incoming", "cancel", "active"), sender.events)
        gate.disconnected()
        gate.connected(mapOf(1 to IDLE, 2 to RINGING))
        assertEquals(listOf("incoming", "cancel", "active", "cancel", "incoming"), sender.events)
        sender.ack(2)
        assertTrue(gate.isRingingDelivered())
    }

    @Test
    fun failedSubmissionIsObservableAndCannotBecomeDelivery() {
        val sender = Sender()
        val gate = connected(sender)
        gate.onState(1, RINGING)
        val failure = IllegalStateException("OFFLINE")
        sender.pending[0].completeExceptionally(failure)
        assertSame(failure, gate.lastFailure())
        assertFalse(gate.isRingingDelivered())
        gate.onState(1, RINGING)
        assertEquals(listOf("incoming"), sender.events)
        gate.onState(1, IDLE)
        assertEquals(listOf("incoming", "cancel", "clear"), sender.events)
        sender.ack(1)
        assertNull(gate.lastFailure())
    }

    @Test
    fun immediateRejectionIsObservableWithoutEscapingCallback() {
        val rejected = IllegalStateException("NOT_READY")
        val gate = PhoneCallGate(
            object : PhoneCallGate.Sender {
                override fun send(phase: PhoneCallGate.Phase): CompletionStage<Void?> = throw rejected
                override fun cancelQueuedRing() {}
            },
        )
        gate.setEnabled(true)
        gate.connected(mapOf(1 to RINGING))
        assertSame(rejected, gate.lastFailure())
        assertFalse(gate.isRingingDelivered())
    }

    @Test
    fun subscriptionRemovalAndLatestTransitionPreserveClearOrdering() {
        val sender = Sender()
        val gate = connected(sender)
        gate.onState(1, RINGING)
        gate.replaceStates(mapOf(2 to IDLE))
        gate.onState(1, RINGING)
        gate.onState(2, RINGING)
        sender.ack(1)
        assertFalse(gate.isRingingDelivered())
        sender.ack(2)
        assertTrue(gate.isRingingDelivered())
        assertEquals(listOf("incoming", "cancel", "clear", "incoming"), sender.events)
    }

    private class Sender : PhoneCallGate.Sender {
        val events = ArrayList<String>()
        val pending = ArrayList<CompletableFuture<Void?>>()

        override fun send(phase: PhoneCallGate.Phase): CompletionStage<Void?> {
            events.add(
                when (phase) {
                    PhoneCallGate.Phase.INCOMING -> "incoming"
                    PhoneCallGate.Phase.ACTIVE -> "active"
                    PhoneCallGate.Phase.OUTGOING -> "outgoing"
                    PhoneCallGate.Phase.NONE -> "clear"
                },
            )
            val result = CompletableFuture<Void?>()
            pending.add(result)
            return result
        }

        override fun cancelQueuedRing() {
            events.add("cancel")
        }

        fun ack(index: Int) {
            pending[index].complete(null)
        }
    }

    companion object {
        private fun connected(sender: Sender): PhoneCallGate =
            PhoneCallGate(sender).apply {
                setEnabled(true)
                connected(mapOf(1 to IDLE, 2 to IDLE))
            }
    }
}
