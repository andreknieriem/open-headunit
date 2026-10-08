package com.andrerinas.openheadunit.connection.wifi

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

internal object DiscoverySocketConnector {
    fun open(ip: String, port: Int, timeout: Int, localIpv4: String? = null, socket: Socket = Socket()): Socket {
        try {
            if (localIpv4 != null) socket.bind(InetSocketAddress(InetAddress.getByName(localIpv4), 0))
            socket.connect(InetSocketAddress(ip, port), timeout)
            return socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }
}
