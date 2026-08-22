package com.andrerinas.openheadunit.connection

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

class NetworkDiscovery(private val context: Context, private val listener: Listener) {

    companion object {
        /**
         * The most recently started scan, whichever instance owns it. Probing port 5277 is not an
         * instance-local activity: the server on the other end is a process-wide singleton, so two
         * instances probing at once break it exactly as two scans in one instance would.
         *
         * Guarded by [PROBE_LOCK] rather than by the instance, because the whole point is that the
         * two racing callers are usually *different* instances, and a per-instance lock would let
         * them straight past each other.
         */
        private var inFlightScan: Job? = null

        /** Held across the read-modify-write of [inFlightScan]. Never held while blocking. */
        private val PROBE_LOCK = Any()
    }

    /**
     * Failed 5289 probes in the phase currently running, and the last exception kind seen.
     *
     * The subnet sweep fans out 254 coroutines across [Dispatchers.IO], so the counter is atomic;
     * the reason is a plain volatile because a representative sample is all the summary needs and
     * racing writers would be reporting the same failure anyway.
     *
     * Scoped to a *phase*, not to a scan. The gateway scan and the subnet sweep each reset it and
     * each report their own tally before the next phase begins. Sharing one counter across both
     * lost the gateway numbers twice over: the sweep zeroed them on entry, and on the path where
     * the gateway scan succeeded the sweep never ran to report anything at all.
     */
    private val probesFailed = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    private var lastProbeFailure: String? = null

    /** Start a phase's tally. */
    private fun beginProbeTally() {
        probesFailed.set(0)
        lastProbeFailure = null
    }

    /**
     * Close a phase's tally and say what it found, or stay quiet when every probe was answered.
     *
     * [probed] is how many addresses this phase tried and [responded] how many answered, so the
     * three numbers can be checked against each other on any path.
     */
    private fun reportProbeTally(phase: String, probed: Int, responded: Int) {
        val failed = probesFailed.getAndSet(0)
        val reason = lastProbeFailure?.let { " (last: $it)" } ?: ""
        lastProbeFailure = null
        AppLog.i(
            "NetworkDiscovery: $phase — $probed probed, $responded responded, " +
                    "$failed silent on 5289$reason"
        )
    }

    interface Listener {
        fun onServiceFound(ip: String, port: Int, socket: Socket? = null)
        /**
         * [oneShot] is the disposition of the scan that just finished, not of the last request.
         * A one-shot request made while a continuous scan was already running is ignored, so the
         * running scan has to report its own kind or it would silently lose its re-arm.
         */
        fun onScanFinished(oneShot: Boolean)
    }

    /**
     * One scan, with the disposition that belongs to it. Kept together rather than in an instance
     * field so a scan can never report the *next* scan's kind: a cancelled scan is replaced while
     * it is still unwinding, and a continuous sweep that reported itself as one-shot would decline
     * to re-arm and end discovery for good.
     */
    private class Generation(initialOneShot: Boolean) {
        /** Written by a promotion on another thread, read by this scan's own `finally`. */
        @Volatile
        var oneShot: Boolean = initialOneShot
    }

    /** @Volatile: written under [PROBE_LOCK], read by [stop] and by the promotion check. */
    @Volatile
    private var scanJob: Job? = null

    @Volatile
    private var currentGeneration: Generation? = null

    private val reportedIps = Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * [oneShot] belongs to the scan being started, not to the instance, because callers that want
     * a single sweep and callers that want the 10 s re-arm now share one instance.
     *
     * @return `true` if a scan was started, `false` if one was already in flight and this request
     *   was folded into it. A caller that kicked the scan because the *network changed* wants to
     *   know the difference — see `AapService`'s network monitor.
     */
    fun startScan(oneShot: Boolean = false): Boolean {
        var started = false
        synchronized(PROBE_LOCK) {
            val previous = scanJob
            // A healthy scan is already running: leave it alone. Callers that want a fresh
            // sweep call stop() first, and a cancelled job falls through to the join below.
            if (previous != null && previous.isActive && !previous.isCancelled) {
                // Continuous wins. Tying a one-shot scan to a caller that wanted the re-arm would
                // otherwise end discovery altogether: the running scan would finish, decline to
                // reschedule, and the request that asked for more was already dropped here.
                val running = currentGeneration
                if (!oneShot && running != null && running.oneShot) {
                    AppLog.i("NetworkDiscovery: in-flight scan promoted to continuous")
                    running.oneShot = false
                }
                return false
            }

            // [BUG_FIX] The per-instance guard above is not enough on its own. AapService used to
            // answer a scan request by throwing this object away and building a new one, whose
            // scanJob is null -- so the guard saw nothing to wait for and the new instance probed
            // port 5277 while the discarded one still had a probe in flight. One button press did
            // exactly that, twice, four milliseconds apart. The resource being protected is the
            // phone's head unit server, which is process-wide: it binds one connection and never
            // rebinds, so a connection nobody follows through leaves it deaf until the user
            // restarts it by hand. Serialise on that resource rather than on whoever happens to
            // own the instance.
            //
            // [BUG_FIX] The test is "not finished", not "still active". Every cross-instance case
            // arises *because* the previous instance was stopped -- AapService cancels its scan and
            // nulls the field together -- and a cancelled job is in Cancelling, where isActive is
            // already false while its probes are still unwinding. Asking for isActive here skipped
            // the one state this guard exists to catch, so it never engaged on the path it was
            // written for.
            val priorProbe = inFlightScan?.takeIf { it !== previous && !it.isCompleted }

            AppLog.i("NetworkDiscovery: Starting scan...")
            if (priorProbe != null) {
                AppLog.i("NetworkDiscovery: waiting for an in-flight probe before scanning")
            }

            val generation = Generation(oneShot)
            val job = newScanJob(previous, priorProbe, generation)
            currentGeneration = generation
            scanJob = job
            inFlightScan = job
            // Do not hold on to a finished scan for the life of the process; the next instance
            // only ever needs the one that might still be probing.
            job.invokeOnCompletion {
                synchronized(PROBE_LOCK) { if (inFlightScan === job) inFlightScan = null }
            }
            started = true
        }
        return started
    }

    private fun newScanJob(previous: Job?, priorProbe: Job?, generation: Generation): Job =
        CoroutineScope(Dispatchers.IO).launch {
            // Cancellation is cooperative, so a stopped scan still has probes in flight and
            // still holds a connected socket it has not handed over yet. Two scans probing
            // port 5277 at once is how the head unit server ends up bound to a connection
            // nobody owns. Wait for the old one to unwind before opening any of our own.
            previous?.join()
            priorProbe?.join()
            // Cleared here rather than at the call, so the set belongs to this generation and
            // the outgoing scan keeps its own dedupe while it winds down.
            reportedIps.clear()
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
                    // Read now rather than captured at the top: a continuous request that arrived
                    // while this scan was running promotes it, and it has to hear about that.
                    listener.onScanFinished(generation.oneShot)
                }
            }
        }

    private suspend fun scanGateways(): Boolean {
        var foundAny = false
        var probed = 0
        var responded = 0
        beginProbeTally()
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
                    probed++
                    if (checkAndReport(ip)) {
                        foundAny = true
                        responded++
                    }
                }
            }
        } catch (e: CancellationException) {
            // Not an error, and swallowing it hid the socket leak below: the scan reported
            // "Gateway scan error" and carried on into the subnet sweep as if nothing had
            // happened, while the probe socket it had already opened was left connected.
            throw e
        } catch (e: Exception) {
            AppLog.e("NetworkDiscovery: Gateway scan error", e)
        }
        // Before the caller can return early on success, so this phase's numbers survive the path
        // that skips the subnet sweep entirely.
        if (probed > 0) reportProbeTally("Gateway scan", probed, responded)
        return foundAny
    }

    private suspend fun scanSubnet(): Unit = coroutineScope {
        val subnet = getSubnet()
        if (subnet == null) {
            AppLog.e("NetworkDiscovery: Could not determine subnet for deep scan")
            return@coroutineScope
        }

        val myIp = getLocalIpAddress()
        AppLog.i("NetworkDiscovery: Scanning subnet: $subnet.*")

        beginProbeTally()

        val tasks = mutableListOf<Deferred<Boolean>>()

        // Scan range 1..254
        for (i in 1..254) {
            val ip = "$subnet.$i"
            if (ip == myIp) continue // Skip self

            // Children of the scan job, not of a detached scope: stop() has to be able to
            // reach these. Detached, they outlived the scan that started them and kept
            // probing a subnet the device had already left.
            tasks.add(async(Dispatchers.IO) {
                checkAndReport(ip)
            })
        }

        val found = tasks.awaitAll().count { it }
        reportProbeTally("Swept $subnet.*", tasks.size, found)
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue

                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (addr is java.net.Inet4Address) {
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

        // [BUG_FIX] Check before connecting, not only after. Socket.connect() is not interruptible,
        // so a probe that starts after stop() runs to completion, reaches the peer and is then
        // thrown away -- and the head unit server is wedged by any connection it accepts and nobody
        // follows through, whether or not we close it afterwards. Cancellation means stop working,
        // and connecting is work.
        coroutineContext.ensureActive()

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
            var released = false
            try {
                try { delay(holdMs) } catch (e: Exception) {}
                withContext(Dispatchers.Main) {
                    try { launcherSocket.close() } catch (e: Exception) {}
                    released = true
                    AppLog.i("NetworkDiscovery: Wifi Launcher probe released; awaiting inbound helper connection on 5288")
                    listener.onServiceFound(ip, 5289)
                }
            } finally {
                // The hold is deliberate (it is what wakes the helper), so this socket is open
                // for 500 ms by design. A cancellation anywhere in that window used to leak it.
                if (!released) try { launcherSocket.close() } catch (e: Exception) {}
            }
            return true
        }

        // Check Port 5277 (Standard Headunit). Re-checked because the 5289 probe above can have
        // spent 300 ms, or 500 ms more holding a launcher socket open, since the last check.
        coroutineContext.ensureActive()

        // Check Port 5277 (Standard Headunit)
        val serverSocket = checkPort(ip, 5277, timeout = 300)
        if (serverSocket != null) {
            AppLog.i("NetworkDiscovery: Found Headunit Server on $ip:5277")
            reportedIps.add(ip)
            var handedOver = false
            try {
                withContext(Dispatchers.Main) {
                    // Ownership passes to the listener, which is why this is not closed here.
                    listener.onServiceFound(ip, 5277, serverSocket)
                    handedOver = true
                }
            } finally {
                // [BUG_FIX] This socket is not a probe: it is already connected to Android Auto's
                // head unit server, and that server binds itself to one connection and never
                // rebinds for the life of its process. One it accepts that nobody follows through
                // leaves it deaf to every later attempt until the user restarts it by hand, and
                // closing the connection afterwards does not undo it. Cancelling the scan threw
                // out of withContext above before the listener ran, which left exactly that
                // behind.
                // A cancelled scan closes the socket rather than delivering it: cancellation
                // means stop working, and connecting is work.
                if (!handedOver) {
                    AppLog.w("NetworkDiscovery: Handover of $ip:5277 aborted; closing the probe socket")
                    try { serverSocket.close() } catch (e: Exception) {}
                }
            }
            return true
        }

        return false
    }

    private fun checkPort(ip: String, port: Int, timeout: Int = 500): Socket? {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(ip, port), timeout)
            socket
        } catch (e: Exception) {
            // A failed connect can still leave a half-open socket behind; close it rather than
            // waiting for the finalizer, which on the subnet sweep means 254 of them.
            try { socket.close() } catch (e2: Exception) {}
            // Counted, not logged. Telling "helper not listening" apart from "the scan never ran"
            // needs one fact per sweep, not one line per address: at a line per failed 5289 probe
            // a single /24 sweep wrote 254 of them and four sweeps buried an entire connection
            // fault under 60% of the capture. scanSubnet reports the tally instead.
            if (port == 5289) {
                probesFailed.incrementAndGet()
                lastProbeFailure = e.javaClass.simpleName
            }
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
        // Under the same lock as startScan(): cancel() is non-blocking, so holding it costs
        // nothing and keeps the read of scanJob from racing the write that replaces it.
        synchronized(PROBE_LOCK) {
            val job = scanJob ?: return
            if (!job.isActive) return
            job.cancel()
            // [BUG_FIX] Deliberately not nulled. Nulling it made the guard in startScan() blind to
            // a scan that was still unwinding, so a stop()/startScan() pair ran two sweeps side by
            // side; startScan() now joins this job instead of racing it.
            AppLog.i("NetworkDiscovery: Scan interrupted")
        }
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
