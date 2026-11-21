package com.andrerinas.openheadunit.secondscreen.rtp

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
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
    private val packet = DatagramPacket(ByteArray(0), 0)

    override fun open(): String {
        val s = DatagramSocket()
        var label = "default"
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
                label = "default (API <22)"
            } else {
                label = bindToDestinationNetwork(s)
            }
            packet.address = InetAddress.getByAddress(octets)
            packet.port = port
        } catch (e: Exception) {
            s.close()
            throw e
        }
        socket = s
        return label
    }

    private fun bindToDestinationNetwork(s: DatagramSocket): String = try {
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
        if (chosen == null) {
            "default"
        } else {
            networks[chosen.index].bindSocket(s)
            chosen.name
        }
    } catch (e: Exception) {
        "default, bind failed: ${e.message}"
    }

    override fun send(buf: ByteArray, off: Int, len: Int) {
        val s = socket ?: return
        packet.setData(buf, off, len)
        s.send(packet)
    }

    override fun close() {
        socket?.close()
        socket = null
    }
}
