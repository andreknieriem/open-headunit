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

private fun CommManager.ingressMain(dispatcher: CoroutineDispatcher) {
    CommManager::class.java.getDeclaredField("appExitDispatcher")
        .apply { isAccessible = true }.set(this, dispatcher)
}

/** AapService's preempt callback: it ends only a published attempt or an unformed open. */
private class ServicePreempt(private val manager: CommManager) : ConnectionArbiter.Actions {
    var preempts = 0
    override fun preempt(loser: ConnectionArbiter.Claim) {
        preempts++
        // No session forms in this fixture, so an open connection is still unformed.
        val state = manager.state
        if (state is ConnectionState.Connecting || state is ConnectionState.Connected) {
            manager.disconnect(sendByeBye = false, isUserExit = false, honorKillOnDisconnect = false)
        }
    }
    override fun standDownWireless(by: ConnectionArbiter.Claim): Boolean = false
    override fun giveBack(wireless: Boolean, usb: Boolean) {}
    override fun schedule(delayMs: Long, block: () -> Unit) {}
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

    // Automatic A passed its real Main acceptance and waits for its Main publication turn, still
    // Disconnected, so the service's preempt callback has nothing to end. USER B preempts it under
    // the same Stop ticket and connects. A's late publication must not replace or close B; A may
    // dispose only its own work. The unpreempted control proves the held turn is A's publication.
    for (route in listOf("IP", "SOCKET")) for (preempted in listOf(true, false)) {
        val c = CommManager()
        val main = QueuedDispatcher()
        val socketA = Socket()
        c.disconnect(honorKillOnDisconnect = false)
        c.cleanups()
        c.ingressMain(main)
        val effects = ServicePreempt(c)
        ConnectionArbiter.actions = effects
        try {
            val a = launch(start = CoroutineStart.UNDISPATCHED) {
                if (route == "IP") c.connect("192.0.2.40", 5277) else c.connect(socketA)
            }
            checkNotNull(main.tasks.poll()).run() // A's claim acceptance.
            val publicationA = checkNotNull(main.tasks.poll())
            check(main.tasks.isEmpty() && !a.isCompleted && c.state is ConnectionState.Disconnected)
            var b: ProjectionConnection? = null
            if (preempted) {
                val userB = launch(start = CoroutineStart.UNDISPATCHED) {
                    c.connect("192.0.2.50", 5277, ConnectionPriorityPolicy.Tier.USER)
                }
                main.drain()
                userB.join()
                b = checkNotNull(c.network())
                check(effects.preempts == 1 && c.state is ConnectionState.Connected && !b.closed)
            }
            val createdBeforeA = SocketProjectionConnection.created.size
            publicationA.run()
            main.drain()
            a.join()
            // Recorded after every production frame has returned.
            val live = c.network()
            val endpoint = c.ingressEndpoint()
            val createdByA = SocketProjectionConnection.created.size - createdBeforeA
            val state = c.state
            println("preempted publication $route preempted=$preempted: bClosed=${b?.closed}, " +
                "bReplaced=${b != null && live !== b}, endpoint=$endpoint, createdByA=$createdByA, socketAClosed=${socketA.isClosed}")
            check(state is ConnectionState.Connected && live?.closed == false)
            if (preempted) {
                check(live === b && endpoint == ("192.0.2.50" to 5277))
                check(createdByA == 0 && effects.preempts == 1)
                if (route == "SOCKET") check(socketA.isClosed)
            } else {
                check(createdByA == 1 && effects.preempts == 0)
                if (route == "IP") check(endpoint == ("192.0.2.40" to 5277)) else check(!socketA.isClosed)
            }
        } finally { socketA.close(); c.close() }
    }

    // Automatic socket A holds its claim but has not yet recorded its endpoint (paused computing
    // it) when USER B preempts it and connects, before SSL. A's late record must neither take B's
    // JOIN target nor, as A stands down, clear it: a same-endpoint USER request then JOINs B
    // instead of dialing and closing it. The unpreempted control's same-endpoint request JOINs A.
    for (preempted in listOf(true, false)) {
        val c = CommManager()
        val reachedRecord = CountDownLatch(1)
        val resumeRecord = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val socketA = object : Socket() {
            @Volatile private var paused = false
            override fun getInetAddress(): java.net.InetAddress = java.net.InetAddress.getByName("192.0.2.61")
            // Only the endpoint read after the claim asks for the port first.
            override fun getPort(): Int {
                if (!paused) { paused = true; reachedRecord.countDown(); resumeRecord.await(5, TimeUnit.SECONDS) }
                return 5277
            }
        }
        c.disconnect(honorKillOnDisconnect = false)
        c.cleanups()
        val effects = ServicePreempt(c)
        ConnectionArbiter.actions = effects
        val automaticA = Thread {
            try { runBlocking { c.connect(socketA) } } catch (failure: Throwable) { errors.add(failure) }
        }
        try {
            val createdBefore = SocketProjectionConnection.created.size
            automaticA.start()
            check(reachedRecord.await(5, TimeUnit.SECONDS)) { "A never reached its endpoint record" }
            var b: ProjectionConnection? = null
            if (preempted) {
                c.connect("192.0.2.60", 5277, ConnectionPriorityPolicy.Tier.USER)
                b = c.network()
            }
            resumeRecord.countDown()
            automaticA.join(5000)
            val target = if (preempted) "192.0.2.60" else "192.0.2.61"
            val live = c.network()
            c.connect(target, 5277, ConnectionPriorityPolicy.Tier.USER)
            // Recorded after every production frame has returned.
            val after = c.network()
            val created = SocketProjectionConnection.created.size - createdBefore
            val state = c.state
            println("preempted record preempted=$preempted: bClosed=${b?.closed}, bReplaced=${b != null && after !== b}, " +
                "created=$created, socketAClosed=${socketA.isClosed}")
            check(!automaticA.isAlive && errors.isEmpty()) { errors.toString() }
            check(after === live && after?.closed == false && state is ConnectionState.Connected) {
                "a same-endpoint request replaced the live connection (preempted=$preempted bClosed=${b?.closed} created=$created)"
            }
            check(created == 1) { "a same-endpoint request dialed instead of joining (preempted=$preempted created=$created)" }
            if (preempted) check(after === b && socketA.isClosed && effects.preempts == 1)
            else check(!socketA.isClosed && effects.preempts == 0)
        } finally {
            resumeRecord.countDown()
            automaticA.join(5000)
            socketA.close(); c.close()
        }
    }

    // User request A to the Headunit Server holds its claim and record but waits in the previous
    // session's cleanup join, with no attempt yet. A launch accepted then whose dial JOINs A must
    // wait on A instead of standing failed, and the wait ends however A resolves. The control
    // runs that cleanup first, so A has arrived before the launch.
    for (outcome in listOf("arrives", "fails", "cleanup-done")) {
        val c = CommManager()
        try {
            c.disconnect(honorKillOnDisconnect = false) // The previous session's terminal; its cleanup is queued.
            if (outcome == "cleanup-done") c.cleanups()
            if (outcome == "fails") SocketProjectionConnection.connectionHook = { false }
            val createdBefore = SocketProjectionConnection.created.size
            val userA = launch(start = CoroutineStart.UNDISPATCHED) {
                c.connect("127.0.0.1", 5277, ConnectionPriorityPolicy.Tier.USER)
            }
            val mark = c.launchMark(c.appConnectionTicket())
            val acceptedIn = c.state
            c.connect("127.0.0.1", 5277) // The launch's own dial.
            val decision = c.reportLaunchFailure("timeout", mark)
            val stateAtDecision = c.state
            val waiter = (decision as? CommManager.LaunchOutcome.Forming)?.let {
                launch(start = CoroutineStart.UNDISPATCHED) { c.awaitSettled(it) }
            }
            val waitingBeforeCleanup = waiter?.isCompleted == false
            c.cleanups()
            userA.join()
            waiter?.join()
            // Recorded after every production frame has returned.
            val after = c.reportLaunchFailure("timeout", mark)
            val created = SocketProjectionConnection.created.size - createdBefore
            val state = c.state
            println("joined request outcome=$outcome: decision=$decision waiting=$waitingBeforeCleanup after=$after created=$created")
            if (outcome == "cleanup-done") {
                check(decision === CommManager.LaunchOutcome.Answered) { "an arrived request did not answer the launch: $decision" }
            } else {
                check(decision is CommManager.LaunchOutcome.Forming && waitingBeforeCleanup && stateAtDecision === acceptedIn) {
                    "a launch JOINing a request still behind cleanup stood failed ($outcome decision=$decision state=$stateAtDecision)"
                }
                check(waiter?.isCompleted == true) { "the JOINed request resolved but its waiter did not ($outcome)" }
                if (outcome == "arrives") {
                    check(after === CommManager.LaunchOutcome.Answered && state is ConnectionState.Connected) { "A's arrival did not answer: $after" }
                } else {
                    check(after === CommManager.LaunchOutcome.Failed) { "A's failure did not stand after the wait: $after" }
                }
            }
            check(created == 1) { "the launch's dial did not JOIN A ($outcome created=$created)" }
        } finally { SocketProjectionConnection.connectionHook = null; c.close() }
    }
    println("PASS public connection ingress: explicit Stop survives EOF, USB/IP/socket pre-IO tickets, held adoption, stale-preempt rejection, preempted-publication, preempted-record, JOINed-request and compatible-Open controls")
}
