package com.andrerinas.openheadunit.aap

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Whether the dimmer is on. It lives past the activity and resets with the session, on another thread. */
class DimState {
    private val _on = MutableStateFlow(false)
    val on: StateFlow<Boolean> = _on

    // Set while the night overlay has paused a dimmer that was on.
    private var heldForOverlay = false

    @Synchronized
    fun toggle() {
        heldForOverlay = false
        _on.value = !_on.value
    }

    @Synchronized
    fun suspendForOverlay() {
        if (!_on.value) return
        heldForOverlay = true
        _on.value = false
    }

    @Synchronized
    fun resumeAfterOverlay() {
        if (!heldForOverlay) return
        heldForOverlay = false
        _on.value = true
    }

    @Synchronized
    fun resetForNewSession() {
        heldForOverlay = false
        _on.value = false
    }
}
