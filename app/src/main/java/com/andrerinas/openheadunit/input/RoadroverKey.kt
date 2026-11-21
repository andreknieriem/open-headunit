package com.andrerinas.openheadunit.input

import android.view.KeyEvent

/**
 * Wheel media keys on Roadrover / Autochips AC8317 units. The car info service sends next, previous
 * and seek as [ACTION] with a string [EXTRA_ACTION], never as key events.
 */
object RoadroverKey {

    const val ACTION = "com.roadrover.frontpane.keyevent.media"
    const val EXTRA_ACTION = "action"
    const val CARINFO_PACKAGE = "com.roadrover.carinfo"

    enum class Edge { CLICK, DOWN, UP }

    data class Event(val keyCode: Int, val edge: Edge)

    /** The key a broadcast's action word stands for, or null for a word this does not know. */
    fun eventFor(action: String): Event? = when (action) {
        "next", "next_saved", "search_next" -> Event(KeyEvent.KEYCODE_MEDIA_NEXT, Edge.CLICK)
        "prev", "search_pre" -> Event(KeyEvent.KEYCODE_MEDIA_PREVIOUS, Edge.CLICK)
        "play_pause" -> Event(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, Edge.CLICK)
        "ff_start" -> Event(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, Edge.DOWN)
        "ff_end" -> Event(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, Edge.UP)
        "fr_start" -> Event(KeyEvent.KEYCODE_MEDIA_REWIND, Edge.DOWN)
        "fr_end" -> Event(KeyEvent.KEYCODE_MEDIA_REWIND, Edge.UP)
        else -> null
    }
}
