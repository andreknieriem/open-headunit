package com.andrerinas.openheadunit.connection.carkey.ola

import android.view.KeyEvent

/** Turns one STREAM_MUSIC step on the Ola dash into the volume key for the phone. */
object OlaVolumeStepPolicy {

    /** The key for a change from [from] to [to], or null when the change is not a press. */
    fun keyFor(from: Int, to: Int, mid: Int): Int? {
        if (from < 0 || to < 0 || to == from) return null
        // Landing on the middle is the receiver's own snap-back, or its set at session start.
        if (to == mid) return null
        return if (to > from) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN
    }
}
