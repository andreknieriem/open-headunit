package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/**
 * What the WiFi button does on a unit whose Bluetooth handshake runs over the external module.
 *
 * There is no Android device to pick there, so the button arms whatever is not running; a stopped
 * launcher is rebuilt whole, because that is the only path that brings the hotspot back up.
 */
object ModuleRearmPolicy {

    enum class Action { REBUILD_LAUNCHER, START_HANDSHAKE, WAKE_PHONE }

    fun action(nativeLauncherStarted: Boolean, handshakeStarted: Boolean): Action = when {
        !nativeLauncherStarted -> Action.REBUILD_LAUNCHER
        !handshakeStarted -> Action.START_HANDSHAKE
        else -> Action.WAKE_PHONE
    }
}
