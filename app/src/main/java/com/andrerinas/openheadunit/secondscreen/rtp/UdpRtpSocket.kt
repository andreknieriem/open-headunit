package com.andrerinas.openheadunit.secondscreen.rtp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.annotation.RequiresApi
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

/**
 * One unconnected UDP socket, bound to the network that holds the destination when the release allows.
 * No connect, so a missing route fails each send, not the open, and a later send works once it returns.
 * An unconnected socket gets no ICMP port unreachable, so a gone receiver raises no error.
 */
class UdpRtpSocket(
    private val context: Context,
    private val octets: ByteArray,
    private val port: Int,
) : RtpSocket {

    override val target = "${octets.joinToString(".") { (it.toInt() and 0xFF).toString() }}:$port"

    private var socket: DatagramSocket? = null
    private var closed = false
    private var label = "default"
    private val packet = DatagramPacket(ByteArray(0), 0)

    @Volatile
    override var onDestinationNetwork = true
        private set

    /** The id of the network the socket is bound to, or null for the default route. */
    private var boundNetworkId: String? = null

    /** The id of the network whose last bind failed, or null. */
    private var failedBindNetworkId: String? = null

    /** A network to bind, or null for the default route, with its id and label. */
    private class Route(val network: Any?, val id: String?, val label: String)

    @Synchronized
    override fun open(): String {
        closed = false
        return openSocket(route())
    }

    // After close() a late rebind must not leave a socket open.
    @Synchronized
    override fun rebind(failedSinceBind: Boolean): String {
        if (closed) return label
        val route = route()
        if (RtpRebindPolicy.keepsSocket(failedSinceBind, route.id, boundNetworkId, failedBindNetworkId)) return label
        return openSocket(route)
    }

    // The new socket replaces the old one only once it exists, so a throw leaves the old one in use.
    private fun openSocket(route: Route): String {
        val s = DatagramSocket()
        var network = route.label
        var boundId: String? = null
        var failedId: String? = null
        try {
            if (route.network != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val error = bindSocket(route.network, s)
                if (error == null) {
                    boundId = route.id
                } else {
                    failedId = route.id
                    network = "default, bind failed: $error"
                }
            }
            packet.address = InetAddress.getByAddress(octets)
            packet.port = port
        } catch (e: Exception) {
            s.close()
            throw e
        }
        val old = socket
        socket = s
        label = network
        boundNetworkId = boundId
        failedBindNetworkId = failedId
        onDestinationNetwork = Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1 || boundId != null
        old?.close()
        return network
    }

    private fun route(): Route =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) Route(null, null, "default (API <22)")
        else chooseRoute()

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP_MR1)
    private fun chooseRoute(): Route = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networks = cm.allNetworks
        val candidates = networks.mapIndexed { i, n ->
            val link = cm.getLinkProperties(n)
            RtpNetworkPolicy.Candidate(
                i,
                link?.interfaceName ?: "?",
                cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
                link?.linkAddresses.orEmpty().filter { it.address is Inet4Address }
                    .map { RtpNetworkPolicy.LinkAddress(it.address.address, it.prefixLength) },
            )
        }
        val chosen = RtpNetworkPolicy.choose(octets, candidates)
        if (chosen == null) Route(null, null, "default")
        else Route(networks[chosen.index], networks[chosen.index].toString(), chosen.name)
    } catch (e: Exception) {
        Route(null, null, "default, network list failed: ${e.message}")
    }

    /** Null when the bind worked, else the reason. */
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP_MR1)
    private fun bindSocket(network: Any, s: DatagramSocket): String? = try {
        (network as Network).bindSocket(s)
        null
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    @Synchronized
    override fun send(buf: ByteArray, off: Int, len: Int) {
        val s = socket ?: return
        packet.setData(buf, off, len)
        s.send(packet)
    }

    @Synchronized
    override fun close() {
        closed = true
        socket?.close()
        socket = null
    }
}
