package lifecycle

import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
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
    private val PROBE_MUTEX = Mutex()
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
            } finally {
                HeldServerSocket.discardAll()
                old.close(); replacement.close()
                comm.close()
            }
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
    // Discovery's hand-off of its held socket is queued behind connection IO when a launch's dial
    // to the same endpoint arrives. The hand-off holds no request yet, so it can be neither joined
    // nor waited on: the dial must not join it and stand failed. It adopts the held socket itself,
    // and the late hand-off leaves that request's socket alone.
    networkComm().let { comm ->
        NetworkIngressService(comm).use { service ->
            val io = QueuedDispatcher()
            CommManager::class.java.getDeclaredField("connectionIoDispatcher")
                .apply { isAccessible = true }.set(comm, io)
            val endpoint = "192.0.2.73:5277"
            val held = Socket()
            HeldServerSocket.hold(endpoint, held)
            try {
                NetworkDiscoveryIngress(service).onServiceFound("192.0.2.73", 5277, held, comm.appConnectionTicket())
                service.main.drain()
                val handOff = checkNotNull(io.tasks.poll()) { "the hand-off did not reach connection IO" }
                val mark = comm.launchMark(comm.appConnectionTicket())
                val dial = launch(start = CoroutineStart.UNDISPATCHED) { comm.connect("192.0.2.73", 5277) }
                io.drain(); dial.join()
                val decision = comm.reportLaunchFailure("timeout", mark)
                handOff.run(); io.drain(); service.main.drain()
                // Recorded after every production frame has returned.
                val live = comm.network()
                println("held hand-off dial: decision=$decision state=${comm.state} heldClosed=${held.isClosed}")
                check(decision !== CommManager.LaunchOutcome.Failed) {
                    "a launch whose dial met discovery's queued hand-off stood failed ($decision)"
                }
                check(comm.state is ConnectionState.Connected && live?.closed == false && !held.isClosed)
            } finally {
                HeldServerSocket.discardAll(); held.close()
                comm.close()
            }
        }
    }
    // P2-B(a). Discovery's hand-off waits behind connection IO; Stop and Open revoke its ticket;
    // a fresh request to the endpoint adopts the held socket and connects over it. The late
    // hand-off does not own that socket any more: it ends without closing it, whatever else
    // would refuse it, and the fresh connection stays up on its socket.
    networkComm().let { comm ->
        NetworkIngressService(comm).use { service ->
            val io = QueuedDispatcher()
            CommManager::class.java.getDeclaredField("connectionIoDispatcher")
                .apply { isAccessible = true }.set(comm, io)
            val endpoint = "192.0.2.74:5277"
            val held = Socket()
            HeldServerSocket.hold(endpoint, held)
            try {
                NetworkDiscoveryIngress(service).onServiceFound("192.0.2.74", 5277, held, comm.appConnectionTicket())
                service.main.drain()
                val handOff = checkNotNull(io.tasks.poll()) { "the hand-off did not reach connection IO" }
                comm.requestExplicitAppExit(killProcess = false)
                comm.onAppRelaunched()
                val fresh = launch(start = CoroutineStart.UNDISPATCHED) {
                    comm.connect("192.0.2.74", 5277, ticket = comm.appConnectionTicket())
                }
                io.drain(); fresh.join()
                val live = comm.network()
                handOff.run(); io.drain(); service.main.drain()
                // Recorded after every production frame has returned.
                println("late hand-off after Stop/Open: state=${comm.state} same=${comm.network() === live} heldClosed=${held.isClosed}")
                check(live != null && comm.network() === live && live.closed == false && comm.state is ConnectionState.Connected) {
                    "the fresh request did not keep its connection (state=${comm.state})"
                }
                check(!held.isClosed) { "a late discovery hand-off closed the socket a fresh request adopted (heldClosed=true)" }
            } finally {
                HeldServerSocket.discardAll(); held.close()
                comm.close()
            }
        }
    }
    println("PASS network producer ingress: queued server accept, discovery hold/replacement/cancellation, overlapping scan replacement/promotion, held hand-off dial, late hand-off after Stop/Open and fresh inputs")
}
