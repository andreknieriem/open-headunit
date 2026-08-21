package com.andrerinas.openheadunit.connection.wifi.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.utils.AppLog
import java.net.ServerSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface

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
class WirelessServer(
    val registerNsd: Boolean,
    val service: AapService,
    val history: WirelessServerHistory) {

    companion object {

        /** Bind attempts before the wireless server gives up and reports the port unusable. */
        private const val BIND_ATTEMPTS = 3

        /** Gap between them. A port released by a peer that just left frees within this. */
        private const val BIND_RETRY_DELAY_MS = 700L
    }

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


    /**
     * Whether the coroutine that owns the bind is still running.
     *
     * [isListening] alone cannot separate "binding, give it a moment" from "died and will never
     * bind"; both are false. Replacing a server on the strength of that would tear down one that
     * was about to succeed, and the replacement would then race it for the same port.
     */
    val isAlive: Boolean get() = job?.isActive == true

    fun start() {
        val commManager = App.provide(service).commManager

        nsdManager = service.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsdManager == null) {
            AppLog.e("WirelessServer: NsdManager not available on this device.")
        } else if (registerNsd) {
            registerNsd()
        }

        // Outside the coroutine on purpose. Everything below runs on a scope that can already
        // be cancelled, in which case the block never executes and prints nothing at all; this
        // line is what tells a reader the difference between "never asked" and "asked, and the
        // answer never came". Two complete reporter captures could not be told apart without it.
        AppLog.i("WirelessServer: binding port 5288...")

        job = service.serviceScope.launch(Dispatchers.IO) {
            try {
                // Unbound first, then the option, then bind. ServerSocket(int) binds inside the
                // constructor, so setting reuseAddress after it is a no-op on a socket that is
                // already bound - which is the whole failure this line was written to prevent.
                // The previous peer's connection sits in TIME_WAIT for minutes after a session
                // ends, so a re-init within that window threw BindException, isListening stayed
                // false, and the next handshake woke the phone over Bluetooth and handed it
                // nothing (NativeAaHandshakeManager aborts with "nothing is listening on 5288").
                var bound: ServerSocket? = null
                var attempt = 0
                while (isActive && bound == null) {
                    attempt++
                    try {
                        bound = ServerSocket().apply {
                            reuseAddress = true
                            bind(InetSocketAddress(5288))
                        }
                    } catch (e: Exception) {
                        // The last attempt rethrows, so a permanent failure still reaches the
                        // catch below and is reported as an error rather than disappearing.
                        if (attempt >= BIND_ATTEMPTS) throw e
                        AppLog.w("WirelessServer: port 5288 did not bind on attempt $attempt of $BIND_ATTEMPTS (${e.javaClass.simpleName}: ${e.message}). Retrying in ${BIND_RETRY_DELAY_MS}ms.")
                        delay(BIND_RETRY_DELAY_MS)
                    }
                }
                if (bound == null) {
                    AppLog.i("WirelessServer: stopped before port 5288 could be bound.")
                    return@launch
                }
                serverSocket = bound
                isListening = true
                // A bind that worked ends the rebuild budget: the next failure, whenever it
                // comes, is a fresh one and gets its own attempts.
                history.reset()
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
                        } else if (SystemClock.elapsedRealtime() <  service.userExitCooldownUntil) {
                            // [FIX] User just exited AA — reject the instant reconnection.
                            AppLog.w("WirelessServer: Rejecting connection from ${clientSocket.inetAddress} — user exit cooldown active (${service.userExitCooldownUntil - SystemClock.elapsedRealtime()}ms remaining)")
                            withContext(Dispatchers.IO) {
                                try { clientSocket.close() } catch (_: Exception) {}
                            }
                        } else {
                            AppLog.i("WirelessServer: Accepted client connection from ${clientSocket.inetAddress}. Passing to CommManager...")
                            service.userExitedAA = false // Clear flag on genuine new connection
                            commManager.connect(clientSocket)
                        }
                    }
                }
            } catch (e: Exception) {
                // The cancelled branch used to be silent, which made a server that was torn
                // down indistinguishable from one that was never started.
                if (isActive) AppLog.e("Wireless server error", e)
                else AppLog.i("WirelessServer: port 5288 released (${e.javaClass.simpleName}).")
            } finally {
                isListening = false
                unregisterNsd()
                try { serverSocket?.close() } catch (_: Exception) {}
            }
        }
    }

    /** Logs all non-loopback IPv4 addresses; useful for debugging connectivity issues. */
    private fun logLocalNetworkInterfaces() {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
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
