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

    /** A car key route sends its press to CommManager itself, so its key broadcast must not send it again. */
    fun broadcastSendsKey(route: String?): Boolean = route?.startsWith(CAR_KEY_ROUTE) != true

    private const val CAR_KEY_ROUTE = "carkey"

    fun isHomeKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_HOME
    }

    fun isVolumeKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
    }

    /** A locked unit leaves every volume key to the system or firmware, so its binding does not run too. */
    fun lockKeepsKey(keyCode: Int, locked: Boolean): Boolean = locked && isVolumeKey(keyCode)

    /**
     * A volume key stays on the head unit on every route, unless the user sends volume to the
     * phone or bound this physical key. The mute command is a deliberate exception.
     */
    fun isLocalKey(keyCode: Int, bindings: Map<Int, InputAction>, volumeToPhone: Boolean, source: String): Boolean {
        return isVolumeKey(keyCode) && !volumeToPhone && !isBound(bindings, keyCode) && source != "remote-mute"
    }

    /**
     * The head unit's firmware must act on this key, so the receiver leaves the broadcast alive.
     * A locked unit keeps every volume key, as the projection screen does; the keymap screen keeps none.
     */
    fun firmwareKeepsKey(
        keyCode: Int?, bindings: Map<Int, InputAction>, volumeToPhone: Boolean, locked: Boolean, learning: Boolean,
    ): Boolean {
        if (keyCode == null || learning) return false
        return lockKeepsKey(keyCode, locked) || isLocalKey(keyCode, bindings, volumeToPhone, "carkey")
    }

    // Routes where a user pressed a physical key. A notification button or a command is not one.
    private val physicalSources = setOf("projection", "mediasession", "carkey", "key-broadcast", "remote-keycode")

    /** The user chose Do nothing for this physical key, and it came from a physical route. */
    fun isIgnored(bindings: Map<Int, InputAction>, keyCode: Int, source: String): Boolean {
        return bindings[keyCode] == InputAction.Ignore &&
            (source in physicalSources || source.startsWith("carkey-"))
    }

    private fun isBound(bindings: Map<Int, InputAction>, keyCode: Int): Boolean =
        bindings[keyCode].let { it != null && it != InputAction.None }
}
