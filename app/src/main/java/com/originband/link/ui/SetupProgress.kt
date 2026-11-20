// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.ui

/** First-run step from root, binding, SPP profile, registration, and account stores. */
class SetupProgress(
    @JvmField val hasRoot: Boolean,
    @JvmField val hasBinding: Boolean,
    @JvmField val hasProfile: Boolean,
    @JvmField val registered: Boolean,
    @JvmField val nativeOwned: Boolean,
    @JvmField val accountConfirmed: Boolean,
) {
    enum class Step { ROOT, IMPORT, PROFILE, ADD, DONE }

    fun current(): Step {
        if (!hasRoot) return Step.ROOT
        if (!hasBinding) return Step.IMPORT
        if (!registered || !nativeOwned) return Step.ADD
        return Step.DONE
    }

    fun showChecklist(): Boolean = current() != Step.DONE

    fun primaryLabel(): String = Copy.setupLabel(current())

    fun primaryHint(): String = Copy.setupHint(current())

    /** 1-based position among the three visible steps; DONE reports 3. */
    fun visibleIndex(): Int = when (current()) {
        Step.ROOT -> 1
        Step.IMPORT -> 2
        else -> 3
    }
}
