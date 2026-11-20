// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.service

/**
 * Decides what an SPP session does after the system bond is gone.
 *
 * Secure RFCOMM needs the bond, so when the stack removes it the socket dies and every
 * following attempt is refused with `OFFICIAL_PAIRING_REQUIRED`. The quiet two minute retry
 * kept the band down until someone re-paired inside 「小米运动健康」. Two things change here: a bond
 * broadcast is news for the worker, so the next attempt starts right away, and the bridge asks the
 * system for the bond itself instead of waiting for the official app to do it.
 */
class BondRecovery {
    private var asks = 0
    private var lastAskMs = Long.MIN_VALUE

    /**
     * A bond broadcast. True when it can change the outcome of the next attempt, which is also a
     * reason to wake the worker instead of waiting out the backoff. A restored bond also opens a
     * fresh request budget: the official app re-pairing is exactly what the next loss may need.
     */
    @Synchronized
    fun note(state: Int): Boolean {
        if (state == BONDED) {
            asks = 0
            lastAskMs = Long.MIN_VALUE
            return true
        }
        return state == NONE
    }

    /** What to do before the next socket attempt. */
    @Synchronized
    fun decide(state: Int, nowMs: Long): Int {
        if (state == BONDED) return READY
        if (state == BONDING) return WAIT
        if (asks >= MAX_ASKS) return GAVE_UP
        if (lastAskMs != Long.MIN_VALUE && nowMs - lastAskMs < ASK_GAP_MS) return WAIT
        return ASK
    }

    /** Records the `createBond()` call for [decide]. */
    @Synchronized
    fun asked(nowMs: Long) {
        asks++
        lastAskMs = nowMs
    }

    /** An authenticated session proves the bond is healthy, so the next loss gets a full budget. */
    @Synchronized
    fun connected() {
        asks = 0
        lastAskMs = Long.MIN_VALUE
    }

    /** The user tapped 「重新配对」, so the band prompt that follows is expected. */
    @Synchronized
    fun manualRetry() {
        asks = 0
        lastAskMs = Long.MIN_VALUE
    }

    @Synchronized
    fun asks(): Int = asks

    @Synchronized
    fun gaveUp(): Boolean = asks >= MAX_ASKS

    companion object {
        /** BluetoothDevice.BOND_NONE, BOND_BONDING, BOND_BONDED, repeated to keep this JVM-only. */
        const val NONE = 10
        const val BONDING = 11
        const val BONDED = 12

        /** The next attempt should call `createBond()`. */
        const val ASK = 0

        /** The system is pairing, or a fresh request would only duplicate the last one. */
        const val WAIT = 1

        /** The bond is back; the session can use SPP again. */
        const val READY = 2

        /** This loss already used its requests. A re-pair from another app is what restores it. */
        const val GAVE_UP = 3

        const val MAX_ASKS = 1
        const val ASK_GAP_MS = 20_000L

        /** Foreground notice while the bond is gone: one automatic ask, then hand it to the user. */
        @JvmStatic
        fun notice(gaveUp: Boolean): String {
            return if (gaveUp) "手环需要重新配对，点通知重试" else "正在重新配对"
        }
    }
}
