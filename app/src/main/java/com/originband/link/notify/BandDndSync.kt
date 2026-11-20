// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.notify

/**
 * The band's own 同步手机勿扰 switch, tracked for one link.
 *
 * <p>Enabling it is what subtype 15 with enabled 1 does, so the phone must know where the band
 * stands before it pushes the phone rule: the user turns the switch off on the band and the next
 * link used to turn it back on. The band stores the value, so a link answers once and the stored
 * copy covers a band that stays quiet.</p>
 *
 * <p>A silent band is not a switch-off: a firmware that ignores the query keeps whatever value
 * the last link stored.</p>
 */
class BandDndSync {
    /** What the service may do with the phone's DND right now. */
    enum class Next {
        ASK,
        MIRROR,
        HOLD,
    }

    private var syncEnabled = true
    private var asked = false
    private var answered = false

    /** Stored switch value from the previous link. Old installs have none, which reads as on. */
    fun seed(enabled: Boolean) {
        syncEnabled = enabled
    }

    fun on(): Boolean = syncEnabled

    /** A new link starts without an answer and asks again. */
    fun newLink() {
        asked = false
        answered = false
    }

    /** True while the link still has to hear from the band before it mirrors anything. */
    fun waiting(): Boolean = asked && !answered

    /** One phone-side DND opportunity: the first one in a link asks instead of pushing. */
    fun before(): Next {
        if (!asked) {
            asked = true
            answered = false
            return Next.ASK
        }
        return if (syncEnabled) Next.MIRROR else Next.HOLD
    }

    /**
     * The band reported its switch, or {@code null} when the ask went unanswered.
     *
     * @return true when the mirror should run now
     */
    fun answered(enabled: Boolean?): Boolean {
        val first = !answered
        answered = true
        if (enabled == null) return syncEnabled && first
        val changed = syncEnabled != enabled
        syncEnabled = enabled
        return syncEnabled && (first || changed)
    }
}
