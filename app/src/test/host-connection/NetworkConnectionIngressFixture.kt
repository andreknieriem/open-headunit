package lifecycle

import android.os.SystemClock
import kotlinx.coroutines.*
import java.net.Socket

private fun networkComm() = CommManager().apply {
    // Only producer dispatch is queued; public connect IO completes during the controlled drain.
    CommManager::class.java.getDeclaredField("connectionIoDispatcher")
        .apply { isAccessible = true }.set(this, Dispatchers.Unconfined)
    disconnect(honorKillOnDisconnect = false)
    cleanups()
}

/** Production producer bodies are injected by run.py; only network IO and Android are doubled. */
class NetworkIngressService(val commManager: CommManager) : AutoCloseable {
    val main = QueuedDispatcher()
    val serviceScope = CoroutineScope(SupervisorJob() + main)
    var userExitedAA = true
    var userExitCooldownUntil = 0L
    override fun close() { serviceScope.cancel(); main.drain() }
}
class NetworkServerIngress(val service: NetworkIngressService) {
    private val io = Dispatchers.Unconfined
    // NETWORK_SERVER_HANDOFF
}
class NetworkDiscoveryIngress(val service: NetworkIngressService) {
    private val io = Dispatchers.Unconfined
    private val commManager get() = service.commManager
    // NETWORK_DISCOVERY_HANDOFF
}
interface NetworkDiscoveryListener {
    fun onServiceFound(ip: String, port: Int, socket: Socket?, ticket: Long)
    fun onScanFinished(wasOneShot: Boolean, ticket: Long)
}
class NetworkScanIngress(val commManager: CommManager, val listener: NetworkDiscoveryListener) {
    val io = QueuedDispatcher()
    val main = QueuedDispatcher()
    private val PROBE_LOCK = Any()
    private var inFlightScan: Job? = null
    private var scanJob: Job? = null
    // NETWORK_SCAN_GENERATION
    private var currentGeneration: Generation? = null
    private val reportedIps = mutableSetOf<String>()
    private var probeHelperPort = false
    private fun readProbeHelperPort() = false
    private suspend fun scanGateways(ticket: Long): Boolean {
        listener.onServiceFound("192.0.2.72", 5277, null, ticket)
        return true
    }
    private suspend fun scanSubnet(ticket: Long) { error("gateway should have ended this scan") }
    // NETWORK_SCAN_START
    // NETWORK_SCAN_JOB
    fun scanOwner() = scanJob to currentGeneration?.ticket
    fun scanIsOneShot() = currentGeneration?.oneShot
    fun drain() { io.drain(); main.drain(); io.drain(); main.drain(); io.drain() }
}

fun networkConnectionIngressOwnership() = runBlocking {
    // Accepting a TCP socket is input. Its queued service child must not refresh after Stop/Open.
    for (stop in listOf(false, true)) {
        val comm = networkComm()
        NetworkIngressService(comm).use { service ->
            val server = NetworkServerIngress(service)
            val old = Socket()
            try {
                val delivery = server.handOffClient(old, comm)
                if (stop) comm.requestExplicitAppExit(killProcess = false)
                comm.onAppRelaunched()
                service.main.drain(); delivery.join()
                check(old.isClosed == stop)
                check(service.userExitedAA == stop)
                check((comm.state is ConnectionState.Connected) == !stop)
                if (stop) {
                    val fresh = Socket()
                    server.handOffClient(fresh, comm)
                    service.main.drain()
                    check(comm.state is ConnectionState.Connected && !fresh.isClosed)
                }
            } finally { old.close(); comm.close() }
        }
    }
    // The held socket belongs to the callback, not the endpoint string. A queued stale callback
    // cannot take a fresh replacement, and cancellation before child entry releases its old hold.
    for (stop in listOf(false, true)) for (replace in listOf(false, true)) {
        val comm = networkComm()
        NetworkIngressService(comm).use { service ->
            val ingress = NetworkDiscoveryIngress(service)
            val endpoint = "192.0.2.70:5277"
            val old = Socket()
            val replacement = Socket()
            val ticket = comm.appConnectionTicket()
            HeldServerSocket.hold(endpoint, old)
            try {
                ingress.onServiceFound("192.0.2.70", 5277, old, ticket)
                if (stop) comm.requestExplicitAppExit(killProcess = false)
                comm.onAppRelaunched()
                if (replace) HeldServerSocket.hold(endpoint, replacement)
                service.main.drain()
                if (stop || replace) {
                    check(old.isClosed && comm.network() == null)
                    if (replace) {
                        check(HeldServerSocket.isHeld(endpoint) && !replacement.isClosed)
                        ingress.onServiceFound("192.0.2.70", 5277, replacement, comm.appConnectionTicket())
                        service.main.drain()
                        check(comm.state is ConnectionState.Connected && !replacement.isClosed)
                    }
                } else check(comm.state is ConnectionState.Connected && !old.isClosed)
            } finally { HeldServerSocket.abandon(old); HeldServerSocket.abandon(replacement); comm.close() }
        }
    }
    networkComm().let { comm ->
        NetworkIngressService(comm).use { service ->
            val old = Socket()
            HeldServerSocket.hold("192.0.2.71:5277", old)
            NetworkDiscoveryIngress(service).onServiceFound("192.0.2.71", 5277, old, comm.appConnectionTicket())
            service.serviceScope.cancel(); service.main.drain()
            check(old.isClosed && !HeldServerSocket.isHeld("192.0.2.71:5277"))
            comm.close()
        }
    }
    // A reusable scanner retains per-scan input through IO entry and Main callback dispatch;
    // it must not stamp a later genuinely fresh scan with the old cancelled scan's ticket.
    for (stop in listOf(false, true)) for (pauseAtCallback in listOf(false, true)) {
        val comm = networkComm()
        NetworkIngressService(comm).use { service ->
            val ingress = NetworkDiscoveryIngress(service)
            val listener = object : NetworkDiscoveryListener {
                override fun onServiceFound(ip: String, port: Int, socket: Socket?, ticket: Long) {
                    ingress.onServiceFound(ip, port, socket, ticket)
                }
                override fun onScanFinished(wasOneShot: Boolean, ticket: Long) {}
            }
            val scan = NetworkScanIngress(comm, listener)
            scan.startScan(oneShot = true)
            if (pauseAtCallback) scan.io.drain()
            if (stop) comm.requestExplicitAppExit(killProcess = false)
            val owner = comm.onAppRelaunched()
            scan.drain(); service.main.drain()
            check((comm.state is ConnectionState.Connected) == !stop)
            if (stop) {
                check(comm.isAppRunOwner(owner) && comm.network() == null)
                check(scan.startScan(oneShot = true))
                scan.drain(); service.main.drain()
                check(comm.state is ConnectionState.Connected)
            }
            comm.close()
        }
    }
    // Fresh input arrives before the old scan's queued IO or Main cleanup can run. Stop/Open
    // replaces the old job immediately; a compatible Open still folds into and promotes it.
    for (stop in listOf(false, true)) for (pauseAtCallback in listOf(false, true)) {
        val comm = networkComm()
        NetworkIngressService(comm).use { service ->
            val ingress = NetworkDiscoveryIngress(service)
            val finished = mutableListOf<Pair<Boolean, Long>>()
            val listener = object : NetworkDiscoveryListener {
                override fun onServiceFound(ip: String, port: Int, socket: Socket?, ticket: Long) {
                    ingress.onServiceFound(ip, port, socket, ticket)
                }
                override fun onScanFinished(wasOneShot: Boolean, ticket: Long) {
                    finished += wasOneShot to ticket
                }
            }
            val scan = NetworkScanIngress(comm, listener)
            val oldTicket = comm.appConnectionTicket()
            check(scan.startScan(oneShot = true, ticket = oldTicket))
            val oldJob = requireNotNull(scan.scanOwner().first)
            if (pauseAtCallback) scan.io.drain()
            if (stop) comm.requestExplicitAppExit(killProcess = false)
            comm.onAppRelaunched()
            val freshTicket = comm.appConnectionTicket()
            check(scan.startScan(oneShot = stop, ticket = freshTicket) == stop)
            val (ownerJob, currentTicket) = scan.scanOwner()
            val currentJob = requireNotNull(ownerJob)
            check(currentTicket == freshTicket && currentJob.isActive)
            if (stop) {
                check(currentJob !== oldJob && oldJob.isCancelled)
                check(scan.scanIsOneShot() == true)
                // A stale continuous request must neither cancel nor promote the fresh one-shot.
                check(!scan.startScan(oneShot = false, ticket = oldTicket))
                check(scan.scanOwner().first === currentJob && currentJob.isActive)
                check(scan.scanIsOneShot() == true)
            } else {
                check(currentJob === oldJob && !oldJob.isCancelled)
                check(scan.scanIsOneShot() == false)
            }
            scan.drain(); service.main.drain()
            check(currentJob.isCompleted)
            check(finished == listOf(stop to freshTicket))
            check(comm.state is ConnectionState.Connected)
            comm.close()
        }
    }
    println("PASS network producer ingress: queued server accept, discovery hold/replacement/cancellation, overlapping scan replacement/promotion and fresh inputs")
}
