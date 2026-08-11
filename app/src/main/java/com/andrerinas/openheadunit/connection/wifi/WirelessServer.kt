package com.andrerinas.openheadunit.connection.wifi

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.utils.AppLog
import java.net.ServerSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Coroutine-based server that listens for incoming TCP connections on port 5288.
 *
 * Registers the service over mDNS (NSD) as `_aawireless._tcp` so Android Auto
 * Wireless clients can discover it automatically. Each accepted socket is handed
 * off to [CommManager.connect] on the service coroutine scope. Only one connection
 * is allowed at a time; subsequent sockets are closed immediately.
 *
 * Uses [isActive] for cooperative cancellation. [stopServer] cancels the job and
 * closes the server socket to unblock the blocking [ServerSocket.accept] call.
 */
class WirelessServer(val registerNsd: Boolean, val service: AapService) {

    private var serverSocket: ServerSocket? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var job: Job? = null

    /**
     * Whether the TCP port the phone is told to dial is actually bound right now.
     *
     * start() only launches a coroutine; the bind happens inside it and can fail (the port
     * still held by a previous session is the usual way). Handing the phone credentials for a
     * port nothing is listening on produces the worst possible log: a clean handshake, a
     * successful WiFi join, and then silence.
     */
    @Volatile var isListening = false
        private set

    fun start() {
        val commManager = App.provide(service).commManager

        nsdManager = service.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsdManager == null) {
            AppLog.e("WirelessServer: NsdManager not available on this device.")
        } else if (registerNsd) {
            registerNsd()
        }

        job = service.serviceScope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(5288).apply { reuseAddress = true }
                isListening = true
                AppLog.i("Wireless Server listening on port 5288")
                logLocalNetworkInterfaces()

                while (isActive) {
                    AppLog.d("WirelessServer: Waiting for TCP connection on port 5288...")
                    val clientSocket = serverSocket?.accept() ?: break
                    AppLog.i("WirelessServer: Incoming connection detected from ${clientSocket.inetAddress}")
                    service.serviceScope.launch {
                        if (commManager.isConnected) {
                            AppLog.w("WirelessServer: Already connected, dropping client from ${clientSocket.inetAddress}")
                            withContext(Dispatchers.IO) {
                                try { clientSocket.close() } catch (e: Exception) {}
                            }
                        } else if (android.os.SystemClock.elapsedRealtime() < service.userExitCooldownUntil && !service.userExitedAA) {
                            // [FIX] User just exited AA — reject the instant reconnection.
                            AppLog.w("WirelessServer: Rejecting connection from ${clientSocket.inetAddress} — user exit cooldown active (${service.userExitCooldownUntil - android.os.SystemClock.elapsedRealtime()}ms remaining)")
                            withContext(Dispatchers.IO) {
                                try { clientSocket.close() } catch (e: Exception) {}
                            }
                        } else {
                            AppLog.i("WirelessServer: Accepted client connection from ${clientSocket.inetAddress}. Passing to CommManager...")
                            service.userExitedAA = false // Clear flag on genuine new connection
                            commManager.connect(clientSocket)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) AppLog.e("Wireless server error", e)
            } finally {
                isListening = false
                unregisterNsd()
                try { serverSocket?.close() } catch (e: Exception) {}
            }
        }
    }

    /** Logs all non-loopback IPv4 addresses; useful for debugging connectivity issues. */
    private fun logLocalNetworkInterfaces() {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        AppLog.i("Interface: ${iface.name}, IP: ${addr.hostAddress}")
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.e("Error logging interfaces", e)
        }
    }

    private fun registerNsd() {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "AAWireless"
            serviceType = "_aawireless._tcp"
            port = 5288
        }
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = AppLog.i("NSD Registered: ${info.serviceName}")
            override fun onRegistrationFailed(info: NsdServiceInfo, err: Int) = AppLog.e("NSD Reg Fail: $err")
            override fun onServiceUnregistered(info: NsdServiceInfo) = AppLog.i("NSD Unregistered")
            override fun onUnregistrationFailed(info: NsdServiceInfo, err: Int) = AppLog.e("NSD Unreg Fail: $err")
        }
        nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    private fun unregisterNsd() {
        registrationListener?.let { nsdManager?.unregisterService(it) }
        registrationListener = null
    }

    fun stopServer() {
        job?.cancel()
        job = null
        // Close the socket to unblock the accept() call in the coroutine.
        try { serverSocket?.close() } catch (e: Exception) {}
    }
}
