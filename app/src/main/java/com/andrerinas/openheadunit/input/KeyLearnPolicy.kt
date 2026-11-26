package com.andrerinas.openheadunit.input

import android.view.KeyEvent

/** Decides what the keymap screen hears and what the wire is spared while it is open. */
object KeyLearnPolicy {

    /** Intent extra that names the route a learned key arrived on. */
    const val EXTRA_ROUTE = "route"

    /** Copies of one press reach the screen within this window; it is learn-only. */
    const val COPY_WINDOW_MS = 300L

    /** Both edges, because assignment finishes on UP. Repeats would only repeat the toast. */
    fun feedsLearnScreen(action: Int, repeatCount: Int): Boolean {
        return repeatCount == 0 && (action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_UP)
    }

    /** A key pressed to learn it must not also drive the phone. Auto-resume is no press. */
    fun holdsFromWire(learning: Boolean, source: String): Boolean {
        return learning && source != "auto-resume"
    }

    /** The same code and action seen again inside the window is one press arriving twice. */
    fun isCopy(seen: Map<Pair<Int, Int>, Long>, keyCode: Int, action: Int, now: Long): Boolean {
        val last = seen[keyCode to action] ?: return false
        return now - last < COPY_WINDOW_MS
    }
}
