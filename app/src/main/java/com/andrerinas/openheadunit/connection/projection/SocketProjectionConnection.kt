package com.andrerinas.openheadunit.connection.projection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.andrerinas.openheadunit.connection.ConnectionAdmission
import com.andrerinas.openheadunit.connection.wifi.modes.helper.NearbySocket
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SocketProjectionConnection(
    private val ip: String,
    private val port: Int,
    private val context: Context,
    private val admission: ConnectionAdmission) :
    ProjectionConnection {

    private val stateLock = Any()
    @Volatile private var retired = false
    @Volatile private var output: OutputStream? = null
    @Volatile private var input: DataInputStream? = null
    private var transport: Socket
    // Socket#isConnected might return true, even if server has closed the connection
    // Only way to capture it is to check for exceptions, E.g. "SocketException: Broken pipe"
    @Volatile private var transportBroken: Boolean = false
    // Only true for genuine Nearby tunnels, where the handshake's AA-16.4+ settle delay
    // and stale-byte drain must be skipped because every byte from the start matters.
    // Regular WirelessServer-accepted sockets (Hotspot/WiFi Direct/Headunit Server) and
    // manual-IP connections are NOT single-message and need those handshake safeguards.
    private var singleMessage: Boolean = false

    init {
        transport = Socket()
    }

    constructor(socket: Socket, context: Context, admission: ConnectionAdmission) :
        this(socket.inetAddress.hostAddress ?: "", socket.port, context, admission) {
        this.transport = socket
        this.singleMessage = socket is NearbySocket
        // Even an already-connected socket publishes its streams only through connect(),
        // while the original request and this local candidate still own that effect.
    }

    override val type = ProjectionConnection.Type.WIFI

    override val isConnected: Boolean
        get() = transport.isConnected && !transportBroken && !retired

    // Nearby exposes a synthetic loopback address for a remote phone. Retain the route on
    // this connection object so a later attempted endpoint cannot change session ownership.
    internal val isLoopbackPeer: Boolean
        get() = !singleMessage && !LoopbackBindPolicy.needsNetworkBinding(ip)

    override val isSingleMessage: Boolean
        get() = singleMessage

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        val candidate = transport
        var published = false
        try {
            if (!mayOpen(candidate)) return@withContext false
            if (!candidate.isConnected) {
                // Loopback never leaves the device, so there is no interface to choose for it,
                // and the search below is not free: with no WiFi present it waits out its full
                // timeout on every attempt. See LoopbackBindPolicy.
                if (LoopbackBindPolicy.needsNetworkBinding(ip)) {
                    val cm =
                        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        var netToBind: Network? = null
                        try {
                            var wifiNetwork: Network? = null

                            // 1. Try synchronous scan of existing networks first (instant and reliable if connected)
                            val networks = cm.allNetworks
                            for (net in networks) {
                                val caps = cm.getNetworkCapabilities(net)
                                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                                    wifiNetwork = net
                                    AppLog.i("Found active WiFi/P2P network via synchronous scan: $net")
                                    break
                                }
                            }

                            // 2. Fallback to callback if not found synchronously (with increased 1500ms timeout)
                            if (wifiNetwork == null) {
                                val request = NetworkRequest.Builder()
                                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                                    .build()
                                val latch = CountDownLatch(1)
                                val callback = object : ConnectivityManager.NetworkCallback() {
                                    override fun onAvailable(network: Network) {
                                        wifiNetwork = network
                                        latch.countDown()
                                    }
                                }
                                try {
                                    if (!mayOpen(candidate)) return@withContext false
                                    cm.registerNetworkCallback(request, callback)
                                    latch.await(1500, TimeUnit.MILLISECONDS)
                                } finally {
                                    try {
                                        cm.unregisterNetworkCallback(callback)
                                    } catch (_: Exception) {
                                    }
                                }
                            }

                            if (wifiNetwork != null) {
                                netToBind = wifiNetwork
                                AppLog.i("Found active WiFi/P2P network for binding: $wifiNetwork")
                            } else {
                                val activeNet = cm.activeNetwork
                                if (activeNet != null) {
                                    val caps = cm.getNetworkCapabilities(activeNet)
                                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                                        AppLog.i("Active network is cellular. Skipping binding to prevent EHOSTUNREACH.")
                                    } else {
                                        netToBind = activeNet
                                        AppLog.i("Active network is not cellular: $activeNet. Using for binding.")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            AppLog.w("Error scanning networks for binding", e)
                            // Fallback to active network on failure
                            netToBind = cm.activeNetwork
                        }

                        if (netToBind != null) {
                            try {
                                if (!mayOpen(candidate)) return@withContext false
                                netToBind.bindSocket(candidate)
                                AppLog.i("Bound socket to network: $netToBind")
                            } catch (e: Exception) {
                                AppLog.w("Failed to bind socket to network $netToBind", e)
                            }
                        }
                    } else {
                        // Legacy API < 23 (Lollipop & KitKat & JB)
                        @Suppress("DEPRECATION")
                        if (cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI)?.isConnected == true) {
                            try {
                                val addr = InetAddress.getByName(ip)
                                val b = addr.address
                                val ipInt = ((b[3].toInt() and 0xFF) shl 24) or
                                    ((b[2].toInt() and 0xFF) shl 16) or
                                    ((b[1].toInt() and 0xFF) shl 8) or
                                    (b[0].toInt() and 0xFF)
                                // cm.requestRouteToHost(ConnectivityManager.TYPE_WIFI, ipInt)
                                // Use reflection because requestRouteToHost is removed in newer SDKs
                                val m = cm.javaClass.getMethod(
                                    "requestRouteToHost",
                                    Int::class.javaPrimitiveType,
                                    Int::class.javaPrimitiveType
                                )
                                if (!mayOpen(candidate)) return@withContext false
                                m.invoke(cm, ConnectivityManager.TYPE_WIFI, ipInt)
                                AppLog.i("Legacy: Requested route to host $ip")
                            } catch (e: Exception) {
                                AppLog.w("Legacy: Failed requestRouteToHost", e)
                            }
                        }
                    }
                }

                if (!mayOpen(candidate)) return@withContext false
                applyLowLatencySocketOptions(candidate, beforeConnect = true)
                // Chinese Headunit Mediatek Correction
                try {
                    // Network discovery, DNS and binding can all block before the first dial.
                    // None grants a revoked request permission to start a later TCP connection.
                    val endpoint = InetSocketAddress(ip, port)
                    if (!mayOpen(candidate)) return@withContext false
                    candidate.connect(endpoint, 5000)
                } catch (e: Throwable) {
                    val errorMessage = e.message ?: e.toString()
                    if (errorMessage.contains("com.mediatek.cta.CtaHttp") || errorMessage.contains("CtaHttp")) {
                        AppLog.e("OHU_DEBUG: MediaTek crash intercepted.")
                    } else {
                        throw IOException(e)
                    }
                }
                // Chinese Headunit Mediatek Correction
            }
            // WiFi needs tolerance for retransmissions,
            // power-save wakes, and bufferbloat. 1s was causing readFully to timeout
            // mid-header, desynchronizing the stream ("Failed to read full header").
            if (!mayOpen(candidate)) return@withContext false
            candidate.soTimeout = 10000
            applyLowLatencySocketOptions(candidate, beforeConnect = false)
            // Raw DataInputStream — no BufferedInputStream wrapper.
            // BufferedInputStream + readFully + timeout = internal buffer state corruption.
            val openedInput = DataInputStream(candidate.getInputStream())
            val openedOutput = candidate.getOutputStream()
            admission.run {
                synchronized(stateLock) {
                    if (!retired && transport === candidate) {
                        input = openedInput
                        output = openedOutput
                        published = true
                    }
                }
            }
            return@withContext published
        } catch (e: IOException) {
            AppLog.e(e)
            return@withContext false
        } finally {
            if (!published) retire(candidate)
        }
    }

    /** Lifecycle -> producer/arbiter -> this candidate; external socket calls stay outside. */
    private fun mayOpen(candidate: Socket): Boolean {
        var current = false
        admission.run { synchronized(stateLock) { current = !retired && transport === candidate } }
        return current
    }

    private fun retire(candidate: Socket) {
        val close = synchronized(stateLock) {
            if (retired || transport !== candidate) false else {
                retired = true
                input = null
                output = null
                true
            }
        }
        // An unconnected socket may be waiting for a network or inside connect(). Closing it
        // also retires those paths; a late result can never republish this candidate's streams.
        if (close) try {
            candidate.close()
        } catch (e: IOException) {
            AppLog.e(e)
        }
    }

    override fun disconnect() = retire(transport)

    override fun sendBlocking(buf: ByteArray, length: Int, timeout: Int): Int {
        val out = output ?: return -1

        return try {
            out.write(buf, 0, length)
            out.flush()
            length
        } catch (e: SocketException) {
            handleBrokenTransport(e)
            -1
        } catch (e: IOException) {
            AppLog.e(e)
            -1
        }
    }

    override fun recvBlocking(buf: ByteArray, length: Int, timeout: Int, readFully: Boolean): Int {
        val inp = input ?: return -1
        return try {
            // Dynamically apply the caller's timeout so handshake (short timeouts)
            // and streaming (long timeouts) both work correctly on the same socket.
            try { transport.soTimeout = timeout } catch (_: Exception) {}
            if (readFully) {
                inp.readFully(buf, 0, length)
                length
            } else {
                inp.read(buf, 0, length)
            }
        } catch (_: SocketTimeoutException) {
            // With raw DataInputStream (no BufferedInputStream), timeout during
            // small reads (4-byte header) virtually never causes partial consumption.
            // Let the caller decide if this is fatal based on context.
            0
        } catch (e: SocketException) {
            handleBrokenTransport(e)
            -1
        } catch (_: IOException) {
            -1
        }
    }

    private fun handleBrokenTransport(e: SocketException) {
        AppLog.e("Socket broken (server disconnected ungracefully?)", e)
        transportBroken = true

        // Force close the stream/socket so the OS frees the file descriptor
        // If you don't do this, you will eventually cause a memory/handle leak
        try {
            output?.close()
        } catch (_: IOException) {
        } finally {
            input = null
            output = null
        }
    }

    private fun applyLowLatencySocketOptions(candidate: Socket, beforeConnect: Boolean) {
        try { candidate.tcpNoDelay = true } catch (_: Exception) {}
        try { candidate.keepAlive = true } catch (_: Exception) {}
        try { candidate.reuseAddress = true } catch (_: Exception) {}
        try { candidate.trafficClass = 0x10 } catch (_: Exception) {} // IPTOS_LOWDELAY
        try { candidate.setPerformancePreferences(0, 2, 1) } catch (_: Exception) {}

        // Keep TCP queues bounded for projection. Large default buffers can hide Wi-Fi
        // jitter but also allow old video data to accumulate before the decoder.
        try { candidate.receiveBufferSize = LOW_LATENCY_SOCKET_BUFFER_BYTES } catch (_: Exception) {}
        try { candidate.sendBufferSize = LOW_LATENCY_SOCKET_BUFFER_BYTES } catch (_: Exception) {}

        if (!beforeConnect) {
            AppLog.i(
                "Socket low-latency options: tcpNoDelay=%s, recvBuf=%d, sendBuf=%d",
                candidate.tcpNoDelay,
                candidate.receiveBufferSize,
                candidate.sendBufferSize
            )
        }
    }

    companion object {
        private const val DEF_BUFFER_LENGTH = 131080
        private const val LOW_LATENCY_SOCKET_BUFFER_BYTES = 256 * 1024
    }
}
