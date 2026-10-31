package com.andrerinas.openheadunit.connection.wifi

import com.andrerinas.openheadunit.connection.ConnectionOperation
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

    /** [operation] is the original input authority that armed this launcher. */
    abstract fun start(noInfoToasts: Boolean, operation: ConnectionOperation)

    abstract fun stop(seq: WifiLauncherStopSequence)

    /** A restart carries the original ticket and, when present, the exact Save it serves. */
    open fun restartDiscovery(operation: ConnectionOperation) {
        manager.startDiscovery(operation = operation)
    }



    protected val service get() = manager.service

    protected val settings get() = App.provide(service).settings
}
