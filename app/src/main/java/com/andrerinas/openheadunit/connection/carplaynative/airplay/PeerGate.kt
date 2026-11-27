package com.andrerinas.openheadunit.connection.carplaynative.airplay

import java.net.InetAddress

/**
 * The data, event and timing sockets answer only the host of the control connection. Java ignores
 * the scope of a link-local address in the comparison, so `fe80::1%tun0` and `fe80::1` are one host.
 */
object PeerGate {
    fun accepts(expected: InetAddress?, actual: InetAddress?): Boolean = expected != null && actual != null && expected == actual

    /** The iPhone side of the NCM link is a link-local address that is not ours. */
    fun isNcmPeer(peer: InetAddress, local: InetAddress): Boolean = peer.isLinkLocalAddress && peer != local
}
