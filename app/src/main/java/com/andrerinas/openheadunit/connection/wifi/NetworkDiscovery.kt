package com.andrerinas.openheadunit.connection.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

class NetworkDiscovery(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onServiceFound(ip: String, port: Int, socket: Socket? = null)
        fun onScanFinished()
    }

    private var scanJob: Job? = null
    private val reportedIps = Collections.synchronizedSet(mutableSetOf<String>())

    fun startScan() {
        if (scanJob?.isActive == true) return

        reportedIps.clear()
        AppLog.i("NetworkDiscovery: Starting scan...")

        scanJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Quick Scan: Check likely Gateways first
                AppLog.i("NetworkDiscovery: Step 1 - Quick Gateway Scan")
                val gatewayFound = scanGateways()

                if (gatewayFound) {
                    AppLog.i("NetworkDiscovery: Gateway found service, skipping subnet scan.")
                    return@launch
                }

                // 2. Deep Scan: Check entire Subnet
                AppLog.i("NetworkDiscovery: Step 2 - Full Subnet Scan")
                scanSubnet()
            } finally {
                withContext(Dispatchers.Main) {
                    listener.onScanFinished()
                }
            }
        }
    }

    private suspend fun scanGateways(): Boolean {
        var foundAny = false
        try {
            val suspects = mutableSetOf<String>()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val activeNet = cm.activeNetwork
                if (activeNet != null) {
                    val lp = cm.getLinkProperties(activeNet)
                    lp?.routes?.forEach { route ->
                        if (route.isDefaultRoute && route.gateway is Inet4Address) {
                            route.gateway?.hostAddress?.let { suspects.add(it) }
                        }
                    }
                }
            }
            // Always try heuristics (X.X.X.1) for all interfaces
            collectInterfaceSuspects(suspects)

            // Special case for emulators: 10.0.2.2 is the host machine
            if (isEmulator()) {
                suspects.add("10.0.2.2")
            }

            if (suspects.isNotEmpty()) {
                AppLog.i("NetworkDiscovery: Checking suspects: $suspects")
                for (ip in suspects) {
                    if (checkAndReport(ip)) {
                        foundAny = true
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.e("NetworkDiscovery: Gateway scan error", e)
        }
        return foundAny
    }

    private suspend fun scanSubnet() {
        val subnet = getSubnet()
        if (subnet == null) {
            AppLog.e("NetworkDiscovery: Could not determine subnet for deep scan")
            return
        }

        val myIp = getLocalIpAddress()
        AppLog.i("NetworkDiscovery: Scanning subnet: $subnet.*")

        val tasks = mutableListOf<Deferred<Boolean>>()

        // Scan range 1..254
        for (i in 1..254) {
            val ip = "$subnet.$i"
            if (ip == myIp) continue // Skip self

            tasks.add(CoroutineScope(Dispatchers.IO).async {
                checkAndReport(ip)
            })
        }

        tasks.awaitAll()
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue

                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.e("NetworkDiscovery: Error getting local IP", e)
        }
        return null
    }

    private suspend fun checkAndReport(ip: String): Boolean {
        if (reportedIps.contains(ip)) return true

        // Check Port 5289 (Wifi Launcher) - prioritizing this
        val launcherSocket = checkPort(ip, 5289, timeout = 300)
        if (launcherSocket != null) {
            // The phone-side Wireless Helper watches for this inbound probe to auto-launch
            // itself. Hold the socket open briefly before closing so it has time to react;
            // closing it instantly (as the consolidated scan did) stopped waking the helper,
            // so the user had to open it by hand.
            val holdMs = 500L
            AppLog.i("NetworkDiscovery: Found Wifi Launcher on $ip:5289, holding probe ${holdMs}ms to wake the helper")
            reportedIps.add(ip)
            try {
                delay(holdMs)
            } catch (e: Exception) {}
            withContext(Dispatchers.Main) {
                try {
                    launcherSocket.close()
                } catch (e: Exception) {
                }
                AppLog.i("NetworkDiscovery: Wifi Launcher probe released; awaiting inbound helper connection on 5288")
                listener.onServiceFound(ip, 5289)
            }
            return true
        }

        // Check Port 5277 (Standard Headunit)
        val serverSocket = checkPort(ip, 5277, timeout = 300)
        if (serverSocket != null) {
            AppLog.i("NetworkDiscovery: Found Headunit Server on $ip:5277")
            reportedIps.add(ip)
            withContext(Dispatchers.Main) {
                // DO NOT CLOSE serverSocket! Pass it to the listener.
                listener.onServiceFound(ip, 5277, serverSocket)
            }
            return true
        }

        return false
    }

    private fun checkPort(ip: String, port: Int, timeout: Int = 500): Socket? {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, port), timeout)
            socket
        } catch (e: Exception) {
            // Surface a failed Wifi Launcher probe so logs can tell "helper not listening"
            // apart from "the scan never ran".
            if (port == 5289) AppLog.d("NetworkDiscovery: $ip:5289 probe failed (${e.javaClass.simpleName}); Wifi Launcher not listening")
            null
        }
    }

    private fun collectInterfaceSuspects(suspects: MutableSet<String>) {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address) {
                        // Heuristic: Gateway is usually .1 in the same subnet
                        val ipBytes = addr.address
                        ipBytes[3] = 1
                        val suspectIp = InetAddress.getByAddress(ipBytes).hostAddress
                        // Only add if it's not our own IP (though checking own IP is fast anyway)
                        suspects.add(suspectIp)
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.e("NetworkDiscovery: Interface collection failed", e)
        }
    }

    private fun getSubnet(): String? {
        // Reuse similar logic to collectInterfaceSuspects but return subnet string
        try {
             val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
             for (networkInterface in interfaces) {
                 if (!networkInterface.isUp || networkInterface.isLoopback) continue

                 for (addr in Collections.list(networkInterface.inetAddresses)) {
                     if (addr is Inet4Address) {
                         val host = addr.hostAddress
                         val lastDot = host.lastIndexOf('.')
                         if (lastDot > 0) {
                             return host.substring(0, lastDot)
                         }
                     }
                 }
             }
        } catch (e: Exception) {
            AppLog.e("NetworkDiscovery: Failed to get subnet", e)
        }
        return null
    }

    fun stop() {
        if (scanJob == null || scanJob?.isActive == false) return
        scanJob?.cancel()
        scanJob = null
        AppLog.i("NetworkDiscovery: Scan interrupted")
    }

    private fun isEmulator(): Boolean {
        return (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.PRODUCT.contains("sdk_google")
                || Build.PRODUCT.contains("google_sdk")
                || Build.PRODUCT.contains("sdk")
                || Build.PRODUCT.contains("sdk_x86")
                || Build.PRODUCT.contains("vbox86p")
                || Build.PRODUCT.contains("emulator")
                || Build.PRODUCT.contains("simulator")
    }
}
