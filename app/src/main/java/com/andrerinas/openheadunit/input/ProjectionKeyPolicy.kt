package com.andrerinas.openheadunit.input

import android.view.KeyEvent

object ProjectionKeyPolicy {

    fun shouldRouteBackKeyToProjection(actionToPhysicalKeyCode: Map<Int, Int>, keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_BACK &&
                actionToPhysicalKeyCode.containsValue(KeyEvent.KEYCODE_BACK)
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