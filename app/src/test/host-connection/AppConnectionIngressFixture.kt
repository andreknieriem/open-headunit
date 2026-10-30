package lifecycle

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.os.Handler
import com.andrerinas.openheadunit.connection.AppExitPolicy
import kotlinx.coroutines.*
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private fun CommManager.ingressIo(dispatcher: CoroutineDispatcher) {
    CommManager::class.java.getDeclaredField("connectionIoDispatcher")
        .apply { isAccessible = true }.set(this, dispatcher)
}

private fun CommManager.ingressEndpoint(): Pair<String, Int>? =
    CommManager::class.java.getDeclaredField("outgoingEndpoint").apply { isAccessible = true }.get(this) as Pair<String, Int>?

private class RecordingClaims(private val manager: CommManager) : ConnectionArbiter.Actions {
    var preempts = 0
    var standDowns = 0
    override fun preempt(loser: ConnectionArbiter.Claim) {
        preempts++
        manager.disconnect(sendByeBye = false, isUserExit = false, honorKillOnDisconnect = false)
    }
    override fun standDownWireless(by: ConnectionArbiter.Claim): Boolean { standDowns++; return false }
    override fun giveBack(wireless: Boolean, usb: Boolean) {}
    override fun schedule(delayMs: Long, block: () -> Unit) {} // No real clock advances in this fixture.
}

/** Production public overloads, private owned-ticket handoff, arbiter and service ingress prefix. */
fun appConnectionIngressOwnership() = runBlocking {
    // The real startHandshake-installed EOF callback must not weaken an explicit Stop,
    // whether the explicit command or the natural terminal was observed first.
    for (explicitFirst in listOf(false, true)) {
        val c = CommManager()
        val service = ServiceBootstrap(c).apply { initializedForStart = true }
        try {
            c.settings.killOnDisconnect = true
            c.startHandshake()
            val transport = c.owner()
            val a: AppExitPolicy.Request
            val oldAutomatic: Runnable?
            if (explicitFirst) {
                a = c.requestExplicitAppExit(killProcess = true)
                transport.quit(false)
                check(Handler.delayed.isEmpty())
                oldAutomatic = null
            } else {
                transport.quit(false)
                oldAutomatic = checkNotNull(Handler.delayed.peek())
                a = c.requestExplicitAppExit(killProcess = true)
                check(Handler.delayed.isEmpty())
            }
            check(c.acceptedAppExit(a.id) === a && a.userStop && !a.requiresKillOnDisconnect)
            val epochOwner = c.appOwnershipRevision()
            val relay = Intent(ServiceBootstrap.ACTION_STOP_SERVICE)
                .putExtra(ServiceBootstrap.EXTRA_STOP_OWNER, a.id)
            service.onStartCommand(relay, 0, 1)
            check(service.isDestroying && service.stops == 1)
            check(c.appExitIsUserStop(a))
            c.settings.killOnDisconnect = false
            repeat(3) { oldAutomatic?.run() }
            while (Handler.delayed.isNotEmpty()) Handler.delayed.poll().run()
            check(c.appOwnershipRevision() == epochOwner && c.acceptedAppExit(a.id) === a)
            check(c.ownsAppExit(a.id) && c.appExitIsUserStop(a) && service.stops == 1)
            c.cleanups()
            c.destroy()
            val completion = c.completeAppExit(a)
            check(completion.effect == AppExitPolicy.Effect.STOPPED && completion.request === a)
        } finally { c.close(); Handler.delayed.clear() }
    }

    // The first public suspension is already U's continuation, not a second ingress.
    for (route in listOf("USB", "IP", "SOCKET")) for (cancelledByStop in listOf(false, true)) {
        val c = CommManager()
        val io = QueuedDispatcher()
        val socket = Socket()
        c.disconnect(honorKillOnDisconnect = false)
        c.cleanups()
        c.ingressIo(io)
        val effects = RecordingClaims(c)
        ConnectionArbiter.actions = effects
        try {
            val u = launch(start = CoroutineStart.UNDISPATCHED) {
                when (route) {
                    "USB" -> c.connect(UsbDevice(), ConnectionPriorityPolicy.Tier.USER)
                    "IP" -> c.connect("192.0.2.10", 5277, ConnectionPriorityPolicy.Tier.USER)
                    else -> c.connect(socket, ConnectionPriorityPolicy.Tier.USER)
                }
            }
            check(!u.isCompleted && io.tasks.isNotEmpty())
            if (cancelledByStop) c.requestExplicitAppExit(killProcess = false)
            val opened = c.onAppRelaunched()
            io.drain()
            u.join()
            if (cancelledByStop) {
                check(c.state is ConnectionState.Disconnected && c.network() == null)
                check(c.isAppRunOwner(opened) && effects.preempts == 0 && effects.standDowns == 0)
                if (route == "SOCKET") check(socket.isClosed)
            } else {
                check(c.state is ConnectionState.Connected && c.network()?.closed == false)
                check(effects.standDowns == 1)
            }
        } finally { socket.close(); c.close() }
    }

    // Pause the real IP->held-socket call at its adoption log: B/C must not cause
    // the socket delegate to issue a new ticket. Tier and restart endpoint survive too.
    for (cancelledByStop in listOf(false, true)) {
        val c = CommManager()
        val socket = Socket()
        val endpoint = "192.0.2.20:5277"
        var opened = -1L
        c.disconnect(honorKillOnDisconnect = false)
        c.cleanups()
        HeldServerSocket.hold(endpoint, socket)
        try {
            AppLog.hook = { text ->
                if (text.contains("adopting the socket discovery already opened")) {
                    AppLog.hook = null
                    if (cancelledByStop) c.requestExplicitAppExit(killProcess = false)
                    opened = c.onAppRelaunched()
                }
            }
            c.connect("192.0.2.20", 5277, ConnectionPriorityPolicy.Tier.USER)
            check(opened >= 0 && !HeldServerSocket.isTaken(endpoint))
            if (cancelledByStop) {
                check(socket.isClosed && c.network() == null && c.isAppRunOwner(opened))
            } else {
                check(!socket.isClosed && c.state is ConnectionState.Connected)
                check(c.ingressEndpoint() == ("192.0.2.20" to 5277))
            }
        } finally {
            AppLog.hook = null
            HeldServerSocket.abandon(socket)
            c.close()
        }
    }

    // Old high-tier U cannot preempt/stand down newer low-tier V before its eventual
    // rejection. The no-Stop control must really preempt, proving the hook is effective.
    for (route in listOf("USB", "IP", "SOCKET")) for (cancelledByStop in listOf(false, true)) {
        val c = CommManager()
        val oldIo = QueuedDispatcher()
        val oldSocket = Socket()
        val liveSocket = Socket()
        val openedV = CountDownLatch(1)
        val releaseV = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<Throwable>()
        var candidateV: ProjectionConnection? = null
        c.disconnect(honorKillOnDisconnect = false)
        c.cleanups()
        c.ingressIo(oldIo)
        val effects = RecordingClaims(c)
        ConnectionArbiter.actions = effects
        val u = launch(start = CoroutineStart.UNDISPATCHED) {
            when (route) {
                "USB" -> c.connect(UsbDevice(), ConnectionPriorityPolicy.Tier.USER)
                "IP" -> c.connect("192.0.2.30", 5277, ConnectionPriorityPolicy.Tier.USER)
                else -> c.connect(oldSocket, ConnectionPriorityPolicy.Tier.USER)
            }
        }
        if (cancelledByStop) c.requestExplicitAppExit(killProcess = false)
        c.onAppRelaunched()
        c.ingressIo(Dispatchers.IO)
        SocketProjectionConnection.connectionHook = { candidate ->
            if (candidateV == null) {
                candidateV = candidate
                openedV.countDown()
                check(releaseV.await(5, TimeUnit.SECONDS))
            }
            true
        }
        val v = Thread {
            try { runBlocking { c.connect(liveSocket, ConnectionPriorityPolicy.Tier.WIRELESS_HANDSHAKE) } }
            catch (failure: Throwable) { errors.add(failure) }
        }
        try {
            v.start()
            check(openedV.await(5, TimeUnit.SECONDS))
            val live = checkNotNull(candidateV)
            check(c.state is ConnectionState.Connecting && c.network() === live)
            oldIo.drain()
            if (cancelledByStop) {
                u.join()
                check(effects.preempts == 0 && effects.standDowns == 0)
                check(c.network() === live && !live.closed && c.state is ConnectionState.Connecting)
            } else {
                check(effects.preempts == 1 && effects.standDowns == 1)
                c.cleanups() // U waits for the actual retirement caused by its valid preemption.
                oldIo.drain()
                u.join()
                check(live.closed && c.network() !== live && c.state is ConnectionState.Connected)
            }
            releaseV.countDown()
            v.join(5000)
            check(!v.isAlive && errors.isEmpty()) { errors.toString() }
        } finally {
            releaseV.countDown()
            v.join(5000)
            SocketProjectionConnection.connectionHook = null
            oldSocket.close(); liveSocket.close(); c.close()
        }
    }
    println("PASS public connection ingress: explicit Stop survives EOF, USB/IP/socket pre-IO tickets, held adoption, stale-preempt rejection and compatible-Open controls")
}
