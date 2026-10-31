package com.andrerinas.openheadunit.connection.self

/**
 * What a loopback `Disconnected` may take down.
 *
 * A launcher's own failed dial arrives as a disconnect on a session that never existed. Reading
 * that as "the Self Mode session ended" stopped the wireless launcher mid-bring-up and churned the
 * P2P group, and on the legacy route closed the port 5288 server a pending launch still waits on.
 */
object SelfModeDisconnectPolicy {

    /**
     * Only a Self Mode session that actually ran ([selfModeActive]: the ended loopback attempt
     * reached its connection) owns the wireless launcher's teardown. While a launch is still
     * running or waiting for its phone ([launchInFlight]), the disconnect belongs to nobody else.
     */
    fun stopsWirelessLauncher(selfModeActive: Boolean, launchInFlight: Boolean): Boolean =
        selfModeActive && !launchInFlight
}
