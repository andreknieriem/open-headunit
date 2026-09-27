package com.andrerinas.openheadunit.connection.wifi.direct

/**
 * Whether an interface name can be carrying this unit's WiFi Direct group.
 *
 * A blind scan of `/sys/class/net` answered with `wlan0`'s address while the group was up on
 * `p2p-wlan0-12`, and that address went to the phone as the group's BSSID and graded its identity.
 * An interface list is not ordered by usefulness, so the name is asked rather than the order trusted.
 */
object P2pInterfaceNamePolicy {

    /**
     * True only for the names a P2P interface carries: `p2p0`, `p2p-dev-wlan0`, `p2p-wlan0-N`.
     *
     * A station and a soft AP each have their own address on their own interface, so neither can
     * answer what a group's address is however well formed its MAC happens to be.
     */
    fun canCarryGroupAddress(name: String?): Boolean =
        name?.lowercase()?.contains("p2p") == true
}
