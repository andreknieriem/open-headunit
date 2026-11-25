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

/**
 * The launcher's shape for one logical USB request: admitted once at its input for the scan it
 * runs for, with that input's [ticket], its job's IO entry continuing that request, its producer
 * ending it.
 */
private suspend fun CommManager.connectUsbRequest(tier: ConnectionPriorityPolicy.Tier, ticket: Long = appConnectionTicket()) {
    val request = admitUsbRequest(tier, "USB fixture", null, usbRecheck(ticket)) ?: return
    try { connect(UsbDevice(), request) } finally { endRequest(request) }
}

/** AapService's preempt callback: it ends only a published attempt or an unformed open. */
private class ServicePreempt(private val manager: CommManager) : ConnectionArbiter.Actions {
    var preempts = 0
    override fun preempt(loser: ConnectionArbiter.Claim) {
        preempts++
        // No session forms in this fixture, so an open connection is still unformed.
        val state = manager.state
        if (state is ConnectionState.Connecting || state is ConnectionState.Connected) {
            manager.disconnect(ConnectionOperation(manager.appConnectionTicket(), null), sendByeBye = false, isUserExit = false, honorKillOnDisconnect = false)
        }
    }
    override fun standDownWireless(by: ConnectionArbiter.Claim): Boolean = false
    override fun giveBack(wireless: Boolean, usb: UsbRecheck?) {}
    override fun schedule(delayMs: Long, block: () -> Unit) {}
}

private fun CommManager.ingressEndpoint(): Pair<String, Int>? =
    CommManager::class.java.getDeclaredField("outgoingEndpoint").apply { isAccessible = true }.get(this) as Pair<String, Int>?

private class RecordingClaims(private val manager: CommManager) : ConnectionArbiter.Actions {
    var preempts = 0
    var standDowns = 0
    override fun preempt(loser: ConnectionArbiter.Claim) {
        preempts++
        manager.disconnect(ConnectionOperation(manager.appConnectionTicket(), null), sendByeBye = false, isUserExit = false, honorKillOnDisconnect = false)
    }
    override fun standDownWireless(by: ConnectionArbiter.Claim): Boolean { standDowns++; return false }
    override fun giveBack(wireless: Boolean, usb: UsbRecheck?) {}
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
            c.captureHandshake()?.let { c.startHandshake(it) }
            val transport = c.owner()
            val a: AppExitPolicy.Request
            val oldAutomatic: Runnable?
            if (explicitFirst) {
                a = checkNotNull(c.requestExplicitAppExit(ticket = c.appConnectionTicket(), killProcess = true))
                transport.quit(false)
                check(Handler.delayed.isEmpty())
                oldAutomatic = null
            } else {
                transport.quit(false)
                oldAutomatic = checkNotNull(Handler.delayed.peek())
                a = checkNotNull(c.requestExplicitAppExit(ticket = c.appConnectionTicket(), killProcess = true))
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

    // The first public suspension is already U's continuation, not a second ingress. A USB
    // request is admitted at its input, as the launcher's beginAttempt always claimed, so its
    // stand-down precedes the Stop; what the Stop prevents is any publication.
    for (route in listOf("USB", "IP", "SOCKET")) for (cancelledByStop in listOf(false, true)) {
        val c = CommManager()
        val io = QueuedDispatcher()
        val socket = Socket()
        c.disconnect(ConnectionOperation(c.appConnectionTicket(), null), isUserExit = false, honorKillOnDisconnect = false)
        c.cleanups()
        c.ingressIo(io)
        val effects = RecordingClaims(c)
        ConnectionArbiter.actions = effects
        try {
            val u = launch(start = CoroutineStart.UNDISPATCHED) {
                when (route) {
                    "USB" -> c.connectUsbRequest(ConnectionPriorityPolicy.Tier.USER)
                    "IP" -> c.connect("192.0.2.10", 5277, ConnectionPriorityPolicy.Tier.USER, ticket = c.appConnectionTicket())
                    else -> c.connect(socket, ConnectionPriorityPolicy.Tier.USER, ticket = c.appConnectionTicket())
                }
            }
            check(!u.isCompleted && io.tasks.isNotEmpty())
            if (cancelledByStop) checkNotNull(c.requestExplicitAppExit(ticket = c.appConnectionTicket(), killProcess = false))
            val opened = c.onAppRelaunched()
            io.drain()
            u.join()
            if (cancelledByStop) {
                check(c.state is ConnectionState.Disconnected && c.network() == null)
                check(c.isAppRunOwner(opened) && effects.preempts == 0 && effects.standDowns == if (route == "USB") 1 else 0)
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
        c.disconnect(ConnectionOperation(c.appConnectionTicket(), null), honorKillOnDisconnect = false)
        c.cleanups()
        HeldServerSocket.hold(endpoint, socket)
        try {
            AppLog.hook = { text ->
                if (text.contains("adopting the socket discovery already opened")) {
                    AppLog.hook = null
                    if (cancelledByStop) checkNotNull(c.requestExplicitAppExit(ticket = c.appConnectionTicket(), killProcess = false))
                    opened = c.onAppRelaunched()
                }
            }
            c.connect("192.0.2.20", 5277, ConnectionPriorityPolicy.Tier.USER, ticket = c.appConnectionTicket())
            check(opened >= 0 && !HeldServerSocket.isHeld(endpoint))
            if (cancelledByStop) {
                check(socket.isClosed && c.network() == null && c.isAppRunOwner(opened))
            } else {
                check(!socket.isClosed && c.state is ConnectionState.Connected)
                check(c.ingressEndpoint() == ("192.0.2.20" to 5277))
            }
        } finally {
            AppLog.hook = null
            HeldServerSocket.discardAll()
            socket.close()
            c.close()
        }
    }

    // Old high-tier U cannot preempt/stand down newer low-tier V before its eventual
    // rejection. The no-Stop control must really preempt, proving the hook is effective. A USB
    // request claims at its input, so its U is the USB input still carrying U's ticket after
    // Stop and Open, as a permission reply or a delayed attach scan delivers it, arriving while V
    // is live: its admission is where it must neither claim, preempt nor stand V down.
    for (route in listOf("USB", "IP", "SOCKET")) for (cancelledByStop in listOf(false, true)) {
        val c = CommManager()
        val oldIo = QueuedDispatcher()
        val oldSocket = Socket()
        val liveSocket = Socket()
        val openedV = CountDownLatch(1)
        val releaseV = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<Throwable>()
        var candidateV: ProjectionConnection? = null
        c.disconnect(ConnectionOperation(c.appConnectionTicket(), null), isUserExit = false, honorKillOnDisconnect = false)
        c.cleanups()
        c.ingressIo(oldIo)
        val effects = RecordingClaims(c)
        ConnectionArbiter.actions = effects
        val ticketU = c.appConnectionTicket()
        var u: Job? = if (route == "USB") null else launch(start = CoroutineStart.UNDISPATCHED) {
            when (route) {
                "IP" -> c.connect("192.0.2.30", 5277, ConnectionPriorityPolicy.Tier.USER, ticket = c.appConnectionTicket())
                else -> c.connect(oldSocket, ConnectionPriorityPolicy.Tier.USER, ticket = c.appConnectionTicket())
            }
        }
        if (cancelledByStop) checkNotNull(c.requestExplicitAppExit(ticket = c.appConnectionTicket(), killProcess = false))
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
            try { runBlocking { c.connect(liveSocket, ConnectionPriorityPolicy.Tier.WIRELESS_HANDSHAKE, ticket = c.appConnectionTicket()) } }
            catch (failure: Throwable) { errors.add(failure) }
        }
        try {
            v.start()
            check(openedV.await(5, TimeUnit.SECONDS))
            val live = checkNotNull(candidateV)
            check(c.state is ConnectionState.Connecting && c.network() === live)
            if (route == "USB") u = launch(start = CoroutineStart.UNDISPATCHED) {
                c.connectUsbRequest(ConnectionPriorityPolicy.Tier.USER, ticketU)
            }
            oldIo.drain()
            if (cancelledByStop) {
                checkNotNull(u).join()
                check(effects.preempts == 0 && effects.standDowns == 0) {
                    "an old $route input preempted or stood down the newer attempt (preempts=${effects.preempts} standDowns=${effects.standDowns})"
                }
                check(c.network() === live && !live.closed && c.state is ConnectionState.Connecting)
            } else {
                check(effects.preempts == 1 && effects.standDowns == 1)
                c.cleanups() // U waits for the actual retirement caused by its valid preemption.
                oldIo.drain()
                checkNotNull(u).join()
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
        c.disconnect(ConnectionOperation(c.appConnectionTicket(), null), honorKillOnDisconnect = false)
        c.cleanups()
        c.ingressMain(main)
        val effects = ServicePreempt(c)
        ConnectionArbiter.actions = effects
        try {
            val a = launch(start = CoroutineStart.UNDISPATCHED) {
                if (route == "IP") c.connect("192.0.2.40", 5277, ticket = c.appConnectionTicket()) else c.connect(socketA, ticket = c.appConnectionTicket())
            }
            checkNotNull(main.tasks.poll()).run() // A's claim acceptance.
            val publicationA = checkNotNull(main.tasks.poll())
            check(main.tasks.isEmpty() && !a.isCompleted && c.state is ConnectionState.Disconnected)
            var b: ProjectionConnection? = null
            if (preempted) {
                val userB = launch(start = CoroutineStart.UNDISPATCHED) {
                    c.connect("192.0.2.50", 5277, ConnectionPriorityPolicy.Tier.USER, ticket = c.appConnectionTicket())
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

    // Automatic socket A is admitted, with its claim and JOIN record, and waits for its Main
    // acceptance turn when USER B preempts it and connects, before SSL. Late A must neither take
    // B's JOIN target nor, as it stands down, clear it, and must publish nothing: a same-endpoint
    // USER request then JOINs B instead of dialing and closing it. The unpreempted control's
    // request JOINs A.
    for (preempted in listOf(true, false)) {
        val c = CommManager()
        val main = QueuedDispatcher()
        val socketA = object : Socket() {
            override fun getInetAddress(): java.net.InetAddress = java.net.InetAddress.getByName("192.0.2.61")
            override fun getPort(): Int = 5277
        }
        c.disconnect(ConnectionOperation(c.appConnectionTicket(), null), honorKillOnDisconnect = false)
        c.cleanups()
        c.ingressMain(main)
        val effects = ServicePreempt(c)
        ConnectionArbiter.actions = effects
        try {
            val createdBefore = SocketProjectionConnection.created.size
            val automaticA = launch(start = CoroutineStart.UNDISPATCHED) { c.connect(socketA, ticket = c.appConnectionTicket()) }
            val acceptanceA = checkNotNull(main.tasks.poll()) { "A did not reach its Main acceptance" }
            check(main.tasks.isEmpty() && !automaticA.isCompleted && c.state is ConnectionState.Disconnected)
            var b: ProjectionConnection? = null
            if (preempted) {
                val userB = launch(start = CoroutineStart.UNDISPATCHED) {
                    c.connect("192.0.2.60", 5277, ConnectionPriorityPolicy.Tier.USER, ticket = c.appConnectionTicket())
                }
                main.drain()
                userB.join()
                b = c.network()
            }
            val live = c.network()
            acceptanceA.run()
            main.drain()
            automaticA.join()
            val afterA = c.network()
            val target = if (preempted) "192.0.2.60" else "192.0.2.61"
            val joining = launch(start = CoroutineStart.UNDISPATCHED) { c.connect(target, 5277, ConnectionPriorityPolicy.Tier.USER, ticket = c.appConnectionTicket()) }
            main.drain()
            joining.join()
            // Recorded after every production frame has returned.
            val after = c.network()
            val created = SocketProjectionConnection.created.size - createdBefore
            val state = c.state
            println("preempted record preempted=$preempted: preempts=${effects.preempts}, bClosed=${b?.closed}, " +
                "bReplaced=${b != null && after !== b}, created=$created, socketAClosed=${socketA.isClosed}")
            if (preempted) {
                check(effects.preempts == 1 && b != null && afterA === b && live === b) {
                    "B did not preempt admitted A, or late A replaced B (preempts=${effects.preempts})"
                }
                check(socketA.isClosed) { "late A did not dispose of its own socket" }
            } else {
                check(effects.preempts == 0 && !socketA.isClosed && afterA != null)
            }
            check(after === afterA && after?.closed == false && state is ConnectionState.Connected) {
                "a same-endpoint request replaced the live connection (preempted=$preempted bClosed=${b?.closed} created=$created)"
            }
            check(created == 1) { "a same-endpoint request dialed instead of joining (preempted=$preempted created=$created)" }
        } finally {
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
            c.disconnect(ConnectionOperation(c.appConnectionTicket(), null), honorKillOnDisconnect = false) // The previous session's terminal; its cleanup is queued.
            if (outcome == "cleanup-done") c.cleanups()
            if (outcome == "fails") SocketProjectionConnection.connectionHook = { false }
            val createdBefore = SocketProjectionConnection.created.size
            val userA = launch(start = CoroutineStart.UNDISPATCHED) {
                c.connect("127.0.0.1", 5277, ConnectionPriorityPolicy.Tier.USER, ticket = c.appConnectionTicket())
            }
            val mark = c.launchMark(c.appConnectionTicket())
            val acceptedIn = c.state
            c.connect("127.0.0.1", 5277, ticket = c.appConnectionTicket()) // The launch's own dial.
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

    // P2-1. A user's socket request to the Headunit Server is paused computing its endpoint:
    // after its claim but before its JOIN record on 725c52f63, before its admission now. A launch's
    // dial to that endpoint then neither joined nor waited, so the launch stood failed while that
    // request went on to connect. Admission is now one step: nothing holds a claim unseen.
    run {
        val c = CommManager()
        val reached = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val userSocket = object : Socket() {
            @Volatile private var paused = false
            override fun getInetAddress(): java.net.InetAddress = java.net.InetAddress.getByName("127.0.0.1")
            override fun getPort(): Int {
                if (!paused) { paused = true; reached.countDown(); resume.await(5, TimeUnit.SECONDS) }
                return 5277
            }
        }
        c.disconnect(ConnectionOperation(c.appConnectionTicket(), null), honorKillOnDisconnect = false)
        c.cleanups()
        val user = Thread {
            try { runBlocking { c.connect(userSocket, ConnectionPriorityPolicy.Tier.USER, ticket = c.appConnectionTicket()) } } catch (failure: Throwable) { errors.add(failure) }
        }
        try {
            user.start()
            check(reached.await(5, TimeUnit.SECONDS)) { "the user request never reached its endpoint" }
            val mark = c.launchMark(c.appConnectionTicket())
            c.connect("127.0.0.1", 5277, ticket = c.appConnectionTicket()) // The launch's own dial.
            val decision = c.reportLaunchFailure("timeout", mark)
            val launchConnection = checkNotNull(c.network())
            resume.countDown()
            // The later USER publication wins over the automatic dial, then waits for that
            // physical predecessor's cleanup before installing its own accepted socket.
            withTimeout(5_000) {
                while (c.queue.tasks.isEmpty() && user.isAlive) delay(1)
            }
            check(user.isAlive && c.state is ConnectionState.Disconnected && !launchConnection.closed)
            c.cleanups()
            user.join(5000)
            // Recorded after every production frame has returned.
            println("claimed-unseen request: decision=$decision state=${c.state}")
            check(!user.isAlive && errors.isEmpty()) { errors.toString() }
            check(decision !== CommManager.LaunchOutcome.Failed) {
                "a launch stood failed while a user request to its endpoint held the claim ($decision)"
            }
            check(c.state is ConnectionState.Connected)
        } finally {
            resume.countDown()
            user.join(5000)
            userSocket.close(); c.close()
        }
    }

    // P2-2, W4, W6, W7. User request A waits in the previous session's cleanup join when its
    // authority goes: Stop (then Open) revokes its ticket, a manual launch revokes its Save, or a
    // USB attach takes its claim. A fresh request after that must not be absorbed by A as a JOIN
    // target, nor refused by A's dead claim; it connects once the cleanup ends. The launch-side
    // variant is a launch whose dial meets A after Stop and Open: it must not stand failed.
    for (lost in listOf("stop", "stop-launch", "save", "stop-other-endpoint", "usb-claim")) {
        val c = CommManager()
        try {
            val endpoint = "192.0.2.80"
            if (lost == "save") {
                c.disconnect(ConnectionOperation(c.appConnectionTicket(), null), isUserExit = false, honorKillOnDisconnect = false, reason = DisconnectReason.SETTINGS_RESTART)
            } else c.disconnect(ConnectionOperation(c.appConnectionTicket(), null), honorKillOnDisconnect = false)
            val saved = c.state as ConnectionState.Disconnected
            val tierA = if (lost == "stop" || lost == "stop-launch" || lost == "stop-other-endpoint") ConnectionPriorityPolicy.Tier.USER
                else ConnectionPriorityPolicy.Tier.WIRELESS_HANDSHAKE
            val requestA = launch(start = CoroutineStart.UNDISPATCHED) {
                c.connect(endpoint, 5277, tierA, expectedState = if (lost == "save") saved else null, ticket = c.appConnectionTicket())
            }
            check(!requestA.isCompleted) { "A did not wait in the cleanup join ($lost)" }
            when (lost) {
                "save" -> c.cancelPendingSettingsRestart() // What a manual launch does first.
                "usb-claim" -> checkNotNull(ConnectionArbiter.claim(ConnectionPriorityPolicy.Tier.USB,
                    ConnectionPriorityPolicy.Owner.USB, "a USB attach"))
                else -> { checkNotNull(c.requestExplicitAppExit(ticket = c.appConnectionTicket(), killProcess = false)); c.onAppRelaunched() }
            }
            val createdBefore = SocketProjectionConnection.created.size
            val mark = if (lost == "stop-launch") c.launchMark(c.appConnectionTicket()) else null
            val freshEndpoint = if (lost == "stop-other-endpoint") "192.0.2.83" else endpoint
            val freshTier = when (lost) {
                "stop-launch", "stop-other-endpoint" -> ConnectionPriorityPolicy.Tier.WIRELESS_HANDSHAKE
                else -> ConnectionPriorityPolicy.Tier.USER
            }
            val fresh = launch(start = CoroutineStart.UNDISPATCHED) {
                c.connect(freshEndpoint, 5277, freshTier, ticket = c.appConnectionTicket())
            }
            val decision = mark?.let { c.reportLaunchFailure("timeout", it) }
            c.cleanups()
            requestA.join(); fresh.join()
            // Recorded after every production frame has returned.
            val created = SocketProjectionConnection.created.size - createdBefore
            val state = c.state
            val endpointNow = c.ingressEndpoint()
            println("lost authority $lost: created=$created state=$state endpoint=$endpointNow decision=$decision")
            check(created == 1 && state is ConnectionState.Connected && endpointNow == (freshEndpoint to 5277)) {
                "a fresh request was lost to a request whose authority was gone ($lost created=$created state=$state)"
            }
            if (mark != null) check(decision !== CommManager.LaunchOutcome.Failed) {
                "a launch after Stop and Open stood failed on the stopped request ($decision)"
            }
        } finally { c.close() }
    }

    // Wake. A launch waits on the request its dial joined. Stop, or another route taking that
    // request's claim, ends the wait at once, not when the request's producer finally runs. USB
    // outranks only an automatic request, so that cause waits on one; Stop revokes a user's too.
    for (cause in listOf("stop", "usb-claim")) {
        val c = CommManager()
        try {
            c.disconnect(ConnectionOperation(c.appConnectionTicket(), null), honorKillOnDisconnect = false)
            val tierA = if (cause == "usb-claim") ConnectionPriorityPolicy.Tier.WIRELESS_HANDSHAKE
                else ConnectionPriorityPolicy.Tier.USER
            val requestA = launch(start = CoroutineStart.UNDISPATCHED) {
                c.connect("192.0.2.85", 5277, tierA, ticket = c.appConnectionTicket())
            }
            val mark = c.launchMark(c.appConnectionTicket())
            c.connect("192.0.2.85", 5277, ticket = c.appConnectionTicket()) // The launch's own dial joins A.
            val decision = c.reportLaunchFailure("timeout", mark)
            val waiter = (decision as? CommManager.LaunchOutcome.Forming)?.let {
                launch(start = CoroutineStart.UNDISPATCHED) { c.awaitSettled(it) }
            }
            val pendingBefore = waiter?.isCompleted == false
            if (cause == "stop") checkNotNull(c.requestExplicitAppExit(ticket = c.appConnectionTicket(), killProcess = false))
            else checkNotNull(ConnectionArbiter.claim(ConnectionPriorityPolicy.Tier.USB, ConnectionPriorityPolicy.Owner.USB, "a USB attach"))
            yield()
            val wokeBeforeCleanup = waiter?.isCompleted == true
            c.cleanups()
            requestA.join(); waiter?.join()
            // Recorded after every production frame has returned.
            println("wake on $cause: decision=$decision pendingBefore=$pendingBefore woke=$wokeBeforeCleanup")
            check(decision is CommManager.LaunchOutcome.Forming && pendingBefore) { "the launch did not wait on the joined request ($cause $decision)" }
            check(wokeBeforeCleanup) { "the launch kept waiting on a request whose authority was gone ($cause)" }
        } finally { c.close() }
    }

    // P2-A. USER A is Connected, before SSL. USER B to another endpoint is admitted, the same
    // owner replacing A's claim, and its scope is cancelled while it waits for its Main
    // acceptance. B published nothing, so A's state is no reason for B to keep its claim or its
    // record: a later USER request C to B's endpoint dials and connects. In the control B is
    // accepted and connects, and C to its endpoint joins it.
    for (cancelled in listOf(true, false)) {
        val c = CommManager()
        val main = QueuedDispatcher()
        try {
            c.disconnect(ConnectionOperation(c.appConnectionTicket(), null), honorKillOnDisconnect = false)
            c.cleanups()
            c.connect("192.0.2.90", 5277, ConnectionPriorityPolicy.Tier.USER, ticket = c.appConnectionTicket())
            check(c.state is ConnectionState.Connected) { "A did not connect" }
            c.ingressMain(main)
            val requestB = launch(start = CoroutineStart.UNDISPATCHED) {
                c.connect("192.0.2.91", 5277, ConnectionPriorityPolicy.Tier.USER, ticket = c.appConnectionTicket())
            }
            check(!requestB.isCompleted && main.tasks.size == 1) { "B did not wait for its Main acceptance" }
            if (cancelled) requestB.cancel()
            main.drain()
            if (!cancelled) {
                check(!requestB.isCompleted && c.state is ConnectionState.Disconnected)
                c.cleanups()
                main.drain()
            }
            requestB.join()
            val createdBefore = SocketProjectionConnection.created.size
            val requestC = launch(start = CoroutineStart.UNDISPATCHED) {
                c.connect("192.0.2.91", 5277, ConnectionPriorityPolicy.Tier.USER, ticket = c.appConnectionTicket())
            }
            main.drain()
            if (cancelled) {
                check(!requestC.isCompleted && c.state is ConnectionState.Disconnected)
                c.cleanups()
                main.drain()
            }
            requestC.join()
            // Recorded after every production frame has returned.
            val created = SocketProjectionConnection.created.size - createdBefore
            val state = c.state
            val endpoint = c.ingressEndpoint()
            println("cancelled unpublished request cancelled=$cancelled: created=$created state=$state endpoint=$endpoint")
            check(state is ConnectionState.Connected && endpoint == ("192.0.2.91" to 5277)) {
                "a request to the cancelled request's endpoint did not connect (cancelled=$cancelled state=$state endpoint=$endpoint)"
            }
            check(created == if (cancelled) 1 else 0) {
                "a request joined a cancelled request that published nothing (cancelled=$cancelled created=$created)"
            }
        } finally { c.close() }
    }

    // USB P2-1. USB request A is accepted and waits in the previous session's cleanup join; its
    // resumption is held on connection IO. A later USB request B from the same owner displaces it,
    // without cancelling A, and connects. A, resuming, must publish nothing: B's connection stays
    // the live one, open. The control lets A connect alone.
    for (late in listOf(true, false)) {
        val c = CommManager()
        val io = QueuedDispatcher()
        try {
            c.disconnect(ConnectionOperation(c.appConnectionTicket(), null), isUserExit = false, honorKillOnDisconnect = false) // The previous session's cleanup is queued.
            c.ingressIo(io)
            val requestA = launch(start = CoroutineStart.UNDISPATCHED) { c.connectUsbRequest(ConnectionPriorityPolicy.Tier.USB) }
            io.drain()
            check(!requestA.isCompleted) { "A did not wait in the cleanup join" }
            c.cleanups()
            val resumeA = checkNotNull(io.tasks.poll()) { "A did not resume on connection IO" }
            var newer: ProjectionConnection? = null
            if (late) {
                val requestB = launch(start = CoroutineStart.UNDISPATCHED) { c.connectUsbRequest(ConnectionPriorityPolicy.Tier.USB) }
                io.drain()
                requestB.join()
                newer = checkNotNull(c.network()) { "B did not connect" }
            }
            resumeA.run()
            io.drain()
            requestA.join()
            // Recorded after every production frame has returned.
            val live = c.network()
            val state = c.state
            println("late USB request late=$late: same=${live === newer} newerClosed=${newer?.closed} state=$state")
            if (late) {
                check(live === newer && newer?.closed == false && state is ConnectionState.Connected) {
                    "a late USB request replaced the newer USB connection (same=${live === newer} newerClosed=${newer?.closed})"
                }
            } else {
                check(live != null && !live.closed && state is ConnectionState.Connected) { "A did not connect alone ($state)" }
            }
        } finally { c.close() }
    }

    // USB P2-2. USER A is in its handshake when a USB check is deferred behind it. A same-owner
    // USER request B takes the claim and is cancelled before its Main acceptance; the USB scan
    // given back meanwhile may arrive while A is still in SSL, or after it. When A fails, exactly
    // one USB check must follow in every order: the deferred scan belongs to A's attempt.
    for (order in listOf("none", "after-end", "during-ssl")) {
        val c = CommManager()
        val main = QueuedDispatcher()
        val inSsl = CountDownLatch(1)
        val failSsl = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val rechecks = ConcurrentLinkedQueue<() -> Unit>()
        var usbChecks = 0
        // What AapService's give-back runs: checkAlreadyConnected's validity and live-session
        // guards, then a scan.
        fun usbCheck(recheck: UsbRecheck) {
            if (!c.isCurrent(recheck)) return
            if (c.isConnected) c.deferUsbCheckForConnectionAttempt(recheck)
            else if (!c.deferUsbCheckForConnectionAttempt(recheck)) usbChecks++
        }
        ConnectionArbiter.actions = object : ConnectionArbiter.Actions {
            override fun preempt(loser: ConnectionArbiter.Claim) {}
            override fun standDownWireless(by: ConnectionArbiter.Claim): Boolean = false
            override fun giveBack(wireless: Boolean, usb: UsbRecheck?) { if (usb != null) rechecks.add { usbCheck(usb) } }
            override fun schedule(delayMs: Long, block: () -> Unit) { block() }
        }
        val handshake = Thread {
            try { runBlocking { c.captureHandshake()?.let { c.startHandshake(it) } } } catch (failure: Throwable) { errors.add(failure) }
        }
        try {
            c.disconnect(ConnectionOperation(c.appConnectionTicket(), null), honorKillOnDisconnect = false)
            c.cleanups()
            c.connect("192.0.2.95", 5277, ConnectionPriorityPolicy.Tier.USER, ticket = c.appConnectionTicket())
            check(c.state is ConnectionState.Connected) { "A did not connect" }
            AapTransport.handshakeHook = { inSsl.countDown(); failSsl.await(5, TimeUnit.SECONDS) }
            AapTransport.forcedFailure = AapTransport.HandshakeFailure.OTHER
            handshake.start()
            check(inSsl.await(5, TimeUnit.SECONDS) && c.state is ConnectionState.StartingTransport) { "A did not reach SSL" }
            check(c.deferUsbCheckForConnectionAttempt(c.usbRecheck(c.appConnectionTicket()))) {
                "the USB check was not deferred behind A's handshake"
            }
            if (order != "none") {
                c.ingressMain(main)
                val requestB = launch(start = CoroutineStart.UNDISPATCHED) {
                    c.connect("192.0.2.96", 5277, ConnectionPriorityPolicy.Tier.USER, ticket = c.appConnectionTicket())
                }
                requestB.cancel()
                main.drain()
                requestB.join()
            }
            if (order == "during-ssl") while (rechecks.isNotEmpty()) rechecks.poll()()
            failSsl.countDown()
            handshake.join(5000)
            c.cleanups()
            ConnectionArbiter.sessionEnded(wirelessAlreadyRearmed = false, userExit = false)
            while (rechecks.isNotEmpty()) rechecks.poll()()
            // Recorded after every production frame has returned.
            println("deferred USB check order=$order: checks=$usbChecks state=${c.state}")
            check(!handshake.isAlive && errors.isEmpty()) { errors.toString() }
            check(usbChecks == 1) {
                "a USB check deferred behind A's handshake was lost when A failed (order=$order checks=$usbChecks)"
            }
        } finally {
            failSsl.countDown()
            handshake.join(5000)
            AapTransport.handshakeHook = null
            AapTransport.forcedFailure = null
            c.close()
        }
    }
    println("PASS public connection ingress: explicit Stop survives EOF, USB/IP/socket pre-IO tickets, held adoption, stale-preempt rejection, preempted-publication, preempted-record, JOINed-request, claimed-unseen, lost-authority, wake, cancelled-unpublished, late USB request, deferred USB check and compatible-Open controls")
}
