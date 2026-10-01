package com.nuvio.app.core.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * nuvio-rr fork, Phase 9 E5: "Reduce motion": no hero auto-advance, no GIF animation, no image crossfade (fewer
 * idle frames too). Off by default; stored per PC by the desktop app (bound at startup), memory only elsewhere.
 */
object ReduceMotion {
    private val state = MutableStateFlow(false)
    private var save: (Boolean) -> Unit = {}

    val enabled: StateFlow<Boolean>
        get() = state

    fun bind(initial: Boolean, save: (Boolean) -> Unit) {
        state.value = initial
        this.save = save
    }

    fun set(value: Boolean) {
        state.value = value
        save(value)
    }
}
