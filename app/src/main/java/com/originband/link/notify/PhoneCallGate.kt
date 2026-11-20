// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

import java.util.concurrent.CompletionStage

/** In-memory, per-subscription state only; submitted commands are not delivery evidence. */
class PhoneCallGate(private val sender: Sender) {
    enum class State { IDLE, RINGING, OFFHOOK }

    /** Band call screen. Incoming wins over an answered line; outgoing is a dial the phone started. */
    enum class Phase { NONE, INCOMING, ACTIVE, OUTGOING }

    interface Sender {
        fun send(phase: Phase): CompletionStage<Void?>
        fun cancelQueuedRing()
    }

    private class Line {
        var phase = Phase.NONE
    }

    private val lines = HashMap<Int, Line>()
    private var enabled = false
    private var connected = false
    private var phase = Phase.NONE
    private var delivered = Phase.NONE
    private var revision = 0L
    private var lastFailure: Throwable? = null

    @Synchronized
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (!value) {
            lines.clear()
            reconcile()
            delivered = Phase.NONE
        }
    }

    /** Each session starts from a fresh snapshot. The previous session's call states stay behind. */
    @Synchronized
    fun connected(currentStates: Map<Int, State>) {
        revision++
        connected = true
        phase = Phase.NONE
        delivered = Phase.NONE
        lines.clear()
        if (enabled) apply(currentStates)
        reconcile()
    }

    @Synchronized
    fun disconnected() {
        revision++
        connected = false
        phase = Phase.NONE
        delivered = Phase.NONE
        lines.clear()
        sender.cancelQueuedRing()
    }

    /** Replacing the complete set makes subscription removal an atomic aggregate transition. */
    @Synchronized
    fun replaceStates(currentStates: Map<Int, State>) {
        if (!enabled) {
            lines.clear()
            reconcile()
            return
        }
        apply(currentStates)
        reconcile()
    }

    @Synchronized
    fun onState(subscriptionId: Int, state: State) {
        if (!enabled) return
        val line = lines[subscriptionId] ?: return
        line.phase = transition(line.phase, state, false)
        reconcile()
    }

    /** OFFHOOK stays the same when the remote side answers. The in-call notification is what changes. */
    @Synchronized
    fun markAnswered(): Boolean {
        if (!enabled) return false
        var changed = false
        for (line in lines.values) {
            if (line.phase == Phase.OUTGOING) {
                line.phase = Phase.ACTIVE
                changed = true
            }
        }
        if (changed) reconcile()
        return changed
    }

    @Synchronized
    fun isRingingDelivered(): Boolean = delivered == Phase.INCOMING

    @Synchronized
    fun lastFailure(): Throwable? = lastFailure

    private fun apply(currentStates: Map<Int, State>) {
        val next = HashMap<Int, Line>()
        for ((subId, telephony) in currentStates) {
            val previous = lines[subId]
            val line = Line()
            line.phase = transition(previous?.phase ?: Phase.NONE, telephony, previous == null)
            next[subId] = line
        }
        lines.clear()
        lines.putAll(next)
    }

    private fun aggregate(): Phase {
        var incoming = false
        var active = false
        var outgoing = false
        for (line in lines.values) {
            when (line.phase) {
                Phase.INCOMING -> incoming = true
                Phase.ACTIVE -> active = true
                Phase.OUTGOING -> outgoing = true
                Phase.NONE -> Unit
            }
        }
        if (incoming) return Phase.INCOMING
        if (active) return Phase.ACTIVE
        if (outgoing) return Phase.OUTGOING
        return Phase.NONE
    }

    private fun reconcile() {
        val next = if (enabled && connected) aggregate() else Phase.NONE
        if (next == phase) return
        if (phase != Phase.NONE) sender.cancelQueuedRing()
        phase = next
        val token = ++revision
        lastFailure = null
        try {
            sender.send(next).whenComplete { _, failure ->
                synchronized(this@PhoneCallGate) {
                    if (token != revision) return@whenComplete
                    if (failure != null) lastFailure = failure else delivered = next
                }
            }
        } catch (failure: RuntimeException) {
            lastFailure = failure
        }
    }

    companion object {
        private fun transition(previous: Phase, telephony: State, snapshot: Boolean): Phase =
            when (telephony) {
                State.RINGING -> Phase.INCOMING
                State.IDLE -> Phase.NONE
                State.OFFHOOK -> when (previous) {
                    Phase.INCOMING, Phase.ACTIVE -> Phase.ACTIVE
                    Phase.OUTGOING -> Phase.OUTGOING
                    Phase.NONE -> if (snapshot) Phase.ACTIVE else Phase.OUTGOING
                }
            }
    }
}
