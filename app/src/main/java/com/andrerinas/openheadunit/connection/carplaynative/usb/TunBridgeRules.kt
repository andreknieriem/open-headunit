package com.andrerinas.openheadunit.connection.carplaynative.usb

/** What the bridge does with one packet read from the tunnel. */
sealed class TunOutbound {
    /** Send this Ethernet frame. */
    class Frame(val frame: ByteArray, val multicast: Boolean) : TunOutbound()

    /** A unicast packet before the iPhone's MAC is known: drop it, TCP sends it again. */
    object WaitForPeer : TunOutbound()
}

/**
 * The two rewrites between the tunnel and the NCM link. A tunnel has no link layer, so outbound neighbor advertisements gain
 * our MAC, multicast goes to its 33:33 address at once, and unicast waits for the MAC of the first frame the iPhone sends.
 * Follows xcertplay (https://github.com/shilapi/xcertplay) `network/Ipv6NcmBridge.kt` `runTunToNcm`.
 */
object TunBridgeRules {
    fun outbound(tunPacket: ByteArray, hostMac: ByteArray, peerMac: ByteArray?): TunOutbound {
        val ipv6 = EthernetIpv6.addNeighborAdvertisementTargetMac(tunPacket, hostMac)
        val multicast = EthernetIpv6.multicastDestinationMac(ipv6)
        val destination = multicast ?: peerMac ?: return TunOutbound.WaitForPeer
        return TunOutbound.Frame(EthernetIpv6.build(hostMac, destination, ipv6), multicast != null)
    }
}
