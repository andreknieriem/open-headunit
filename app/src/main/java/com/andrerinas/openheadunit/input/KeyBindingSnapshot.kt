package com.andrerinas.openheadunit.input

/**
 * Key bindings read once, not per keystroke. Call [reload] when the screen is back in front,
 * or a keymap edit made while it was away stays unseen.
 */
class KeyBindingSnapshot(private val load: () -> Map<Int, InputAction>) {
    private var loaded: Map<Int, InputAction>? = null

    /** Loads on the first read, so a key that arrives before [reload] still sees the bindings. */
    val current: Map<Int, InputAction>
        get() = loaded ?: load().also { loaded = it }

    fun reload() {
        loaded = load()
    }
}
