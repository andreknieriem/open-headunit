package com.andrerinas.openheadunit.connection.wifi

import com.andrerinas.openheadunit.App

abstract class WifiLauncher(val manager: WifiLauncherManager) {


    abstract val mode: WifiLauncherMode

    abstract fun hasSameStartConfiguration(launcher: WifiLauncher): Boolean

    abstract fun hasWifiDirect(): Boolean

    /**
     * Whether the phone sits on an access point this device is hosting.
     *
     * The exact complement of [hasWifiDirect] over the routes that host a network at all, and the
     * launcher-side twin of `UserExitHotspotPolicy.usesHeadUnitHotspot`, which answers the same
     * question from the settings because it is asked after the launcher has been stopped.
     */
    abstract fun hostsOwnAccessPoint(): Boolean

    abstract fun hasWirelessServer(): Boolean

    abstract fun hasLocalDiscovery(): Boolean

    /** [ticket]: the Stop ticket of the input that armed this launcher (see setActive). */
    abstract fun start(noInfoToasts: Boolean, ticket: Long)

    abstract fun stop(seq: WifiLauncherStopSequence)

    /** [ticket]: the Stop ticket of the input this restart serves; [save]: the Save it retries for. */
    open fun restartDiscovery(ticket: Long, save: com.andrerinas.openheadunit.connection.CommManager.ConnectionState.Disconnected? = null) {
        manager.startDiscovery(ticket = ticket, save = save)
    }


    protected val service get() = manager.service

    protected val settings get() = App.provide(service).settings
}
