package com.andrerinas.openheadunit.input

import android.view.KeyEvent

object ProjectionKeyPolicy {

    /**
     * BACK normally stays with the system, so the user can always leave projection. Once the
     * physical BACK key has a binding of its own, the user chose what it does, so it goes there.
     */
    fun shouldRouteBackKeyToProjection(bindings: Map<Int, InputAction>, keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_BACK &&
                bindings[KeyEvent.KEYCODE_BACK].let { it != null && it != InputAction.None }
    }

    fun isHomeKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_HOME
    }

    fun isVolumeKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
    }
}
