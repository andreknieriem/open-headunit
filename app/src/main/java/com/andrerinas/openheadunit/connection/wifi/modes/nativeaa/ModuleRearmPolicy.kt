package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/**
 * What the WiFi button does on a unit whose Bluetooth handshake runs over the external module.
 *
 * There is no Android device to pick there, so the button arms whatever is not running; a stopped
 * launcher is rebuilt whole, because that is the only path that brings the hotspot back up.
 */
object ModuleRearmPolicy {

    enum class Action { REBUILD_LAUNCHER, START_HANDSHAKE, WAKE_PHONE }

    /**
     * [launcherRefusedModule]: its start skipped the network on a refusal that has since been re-asked.
     * [measuringDaemon]: the handshake is still asking the daemon, so starting it again would ask twice.
     */
    fun action(
        nativeLauncherStarted: Boolean,
        launcherRefusedModule: Boolean,
        handshakeStarted: Boolean,
        measuringDaemon: Boolean = false
    ): Action = when {
        !nativeLauncherStarted || launcherRefusedModule -> Action.REBUILD_LAUNCHER
        measuringDaemon -> Action.WAKE_PHONE
        !handshakeStarted -> Action.START_HANDSHAKE
        else -> Action.WAKE_PHONE
    }
}
