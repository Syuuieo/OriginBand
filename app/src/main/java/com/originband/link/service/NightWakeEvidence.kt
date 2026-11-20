// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.service

/** Wake evidence and stored sleep intervals belong to the current sleep episode. */
class NightWakeEvidence {
    private var observed = false
    private var episodeStartedAtMs: Long = 0

    fun reset() {
        observed = false
        episodeStartedAtMs = 0
    }

    fun beginEpisode(nowMs: Long) {
        observed = false
        episodeStartedAtMs = nowMs
    }

    fun observe() {
        observed = true
    }

    fun observed(): Boolean = observed

    fun acceptsClosedInterval(endMs: Long): Boolean = endMs >= episodeStartedAtMs
}
