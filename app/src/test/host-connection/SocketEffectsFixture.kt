package lifecycle

import android.net.Network
import com.andrerinas.openheadunit.connection.projection.SocketProjectionConnection as ActualSocketConnection
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Full production socket driver, with only Android and TCP boundary calls recorded. */
object SocketEffectsMain {
    private val results = mutableListOf<String>()
    private var failures = 0

    private class RecordingSocket : Socket() {
        @Volatile var connected = false
        @Volatile var retired = false
        var dials = 0
        var closes = 0
        var inputReads = 0
        var outputReads = 0
        var onDial: (() -> Unit)? = null
        var onStreams: (() -> Unit)? = null
        var onBoundary: (() -> Unit)? = null
        override fun isConnected() = connected
        override fun isClosed() = retired
        override fun getInetAddress(): InetAddress = InetAddress.getByName("192.0.2.33")
        override fun getPort() = 5277
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            onBoundary?.invoke()
            check(!retired) { "connect invoked after socket retirement" }
            dials++
            onDial?.invoke()
            if (retired) throw SocketException("retired during dial")
            connected = true
        }
        override fun close() { onBoundary?.invoke(); closes++; retired = true }
        override fun getInputStream(): ByteArrayInputStream {
            onBoundary?.invoke();inputReads++;onStreams?.invoke()
            return ByteArrayInputStream(byteArrayOf(1, 2, 3))
        }
        override fun getOutputStream(): ByteArrayOutputStream {
            onBoundary?.invoke();outputReads++;return ByteArrayOutputStream()
        }
        override fun setTcpNoDelay(value: Boolean) {}
        override fun getTcpNoDelay() = true
        override fun setKeepAlive(value: Boolean) {}
        override fun setReuseAddress(value: Boolean) {}
        override fun setTrafficClass(value: Int) {}
        override fun setReceiveBufferSize(value: Int) {}
        override fun getReceiveBufferSize() = 262144
        override fun setSendBufferSize(value: Int) {}
        override fun getSendBufferSize() = 262144
        override fun setSoTimeout(value: Int) {}
    }

    private class Worker(body: () -> Unit) {
        @Volatile var failure: Throwable? = null
        val thread = Thread {
            try { body() } catch (error: Throwable) { failure = error }
        }.apply { isDaemon = true; start() }
        fun finish() {
            thread.join(6000)
            check(!thread.isAlive) { "worker did not return" }
            failure?.let { throw it }
        }
    }

    private fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(instance)

    private fun install(driver: ActualSocketConnection, socket: RecordingSocket) {
        val transport = driver.javaClass.getDeclaredField("transport").apply { isAccessible = true }
        (transport.get(driver) as Socket).close()
        transport.set(driver, socket)
    }

    private fun noMonitors(manager: CommManager, driver: () -> ActualSocketConnection?): () -> Unit = {
        check(!Thread.holdsLock(field(manager, "transportLifecycleLock"))) { "platform effect held lifecycle monitor" }
        check(!Thread.holdsLock(ConnectionArbiter)) { "platform effect held arbiter monitor" }
        driver()?.let { actual ->
            for (candidate in actual.javaClass.declaredFields.filter { it.name.endsWith("Lock") }) {
                candidate.isAccessible = true
                candidate.get(actual)?.let { lock ->
                    check(!Thread.holdsLock(lock)) { "platform effect held driver monitor ${candidate.name}" }
                }
            }
        }
    }

    private fun idle(): CommManager = CommManager().also { manager ->
        manager.disconnect(ConnectionOperation(manager.appConnectionTicket(), null),
            sendByeBye = false, isUserExit = false, honorKillOnDisconnect = false)
        manager.cleanups()
    }

    private fun stop(manager: CommManager, reopen: Boolean) {
        checkNotNull(manager.requestExplicitAppExit(manager.appConnectionTicket(), killProcess = false))
        if (reopen) manager.onAppRelaunched()
    }

    private fun claim(what: String): ConnectionArbiter.Claim = checkNotNull(ConnectionArbiter.claim(
        ConnectionPriorityPolicy.Tier.USER, ConnectionPriorityPolicy.Owner.MANUAL, what))

    private fun request(manager: CommManager): CommManager.ConnectionRequest =
        CommManager.ConnectionRequest(manager.appConnectionTicket(), claim("socket owner"), "192.0.2.33:5277", null, null)

    private fun case(name: String, body: () -> Unit) {
        try {
            body();println("PASS $name");results += "{\"case\":\"$name\",\"passed\":true}"
        } catch (failure: Throwable) {
            failures++;println("FAIL $name: ${failure.message}");failure.printStackTrace()
            results += "{\"case\":\"$name\",\"passed\":false}"
        } finally {
            SocketProjectionConnection.prepare = null
            SocketProjectionConnection.connectionHook = null
            SocketProjectionConnection.constructionHook = null
            ConnectionArbiter.actions = null
            ConnectionArbiter.reset()
        }
    }

    /** The original request is revoked while the real driver awaits the Android WiFi callback. */
    private fun waitBoundary(mode: String, throughManager: Boolean) {
        val manager = idle()
        val socket = RecordingSocket()
        val successorSocket = RecordingSocket()
        val network = Network()
        var actual: ActualSocketConnection? = null
        var successor: ActualSocketConnection? = null
        var result = false
        val boundary = noMonitors(manager) { actual }
        socket.onBoundary = boundary
        network.onBind = { boundary() }
        manager.context.connectivity.onBoundary = boundary
        val owned = if (throughManager) null else request(manager)
        val admission = ConnectionAdmission { effect -> manager.runIfValid(checkNotNull(owned), effect) }
        if (throughManager) {
            SocketProjectionConnection.prepare = { wrapped ->
                if (field(wrapped.actual, "ip") == "192.0.2.44") {
                    successor = wrapped.actual
                    successorSocket.onBoundary = noMonitors(manager) { wrapped.actual }
                    install(wrapped.actual, successorSocket)
                } else {
                    actual = wrapped.actual
                    install(wrapped.actual, socket)
                }
            }
        } else {
            actual = ActualSocketConnection("192.0.2.33", 5277, manager.context /* DRIVER_AUTHORITY */)
            install(checkNotNull(actual), socket)
        }
        val worker = Worker {
            runBlocking {
                if (throughManager) manager.connect("192.0.2.33", 5277,
                    ConnectionPriorityPolicy.Tier.USER, ticket = manager.appConnectionTicket())
                else result = checkNotNull(actual).connect()
            }
        }
        try {
            check(manager.context.connectivity.registered.await(5, TimeUnit.SECONDS)) { "WiFi wait was not reached" }
            check(socket.dials == 0) { "TCP dial entered before test boundary" }
            when (mode) {
                "stop" -> stop(manager, false)
                "stop-open" -> stop(manager, true)
                "same-owner" -> {
                    if (throughManager) {
                        // A real second manual IP request acquires the same owner's next claim.
                        // Its WiFi is available now, while A's earlier callback remains queued.
                        manager.context.connectivity.allNetworks = arrayOf(network)
                        val replacement = Worker { runBlocking {
                            manager.connect("192.0.2.44", 5277, ConnectionPriorityPolicy.Tier.USER,
                                ticket = manager.appConnectionTicket())
                        } }
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                        while (replacement.thread.isAlive && System.nanoTime() < deadline) {
                            manager.cleanups()
                            Thread.sleep(1)
                        }
                        replacement.finish()
                    } else claim("replacement manual input")
                }
                "disconnect" -> checkNotNull(actual).disconnect()
            }
            manager.context.connectivity.offer(network)
            worker.finish()
            val expected = if (mode == "accepted") 1 else 0
            check(socket.dials == expected) { "raw TCP dials=${socket.dials}, expected=$expected" }
            val expectedSuccessor = if (throughManager && mode == "same-owner") 1 else 0
            check(network.binds == expected + expectedSuccessor) {
                "network binds=${network.binds}, expected=${expected + expectedSuccessor}"
            }
            if (throughManager) {
                check((manager.state is ConnectionState.Connected) == (expected + expectedSuccessor == 1)) {
                    "CM publication=${manager.state}, expected Connected=${expected + expectedSuccessor == 1}"
                }
                if (expectedSuccessor == 1) {
                    check(successorSocket.dials == 1 && !successorSocket.retired) { "successor did not retain its live connection" }
                    check((manager.network() as? SocketProjectionConnection)?.actual === successor) {
                        "old request replaced or closed the successor"
                    }
                }
            } else {
                check(result == (expected == 1)) { "driver published=$result, expected=${expected == 1}" }
            }
            if (mode == "disconnect") check(socket.retired) { "unconnected socket was not closed" }
            check(manager.context.connectivity.callbacks.isEmpty()) { "WiFi callback leaked" }
        } finally {
            manager.context.connectivity.offer(network)
            worker.finish()
            actual?.disconnect()
            successor?.disconnect()
            owned?.let(manager::endRequest)
            manager.close()
        }
    }

    private fun disconnectedBeforeEntry() {
        val manager = idle()
        val owned = request(manager)
        val admission = ConnectionAdmission { effect -> manager.runIfValid(owned, effect) }
        val driver = ActualSocketConnection("127.0.0.1", 5277, manager.context /* DRIVER_AUTHORITY */)
        val socket = RecordingSocket()
        install(driver, socket)
        try {
            driver.disconnect()
            check(socket.retired && socket.closes == 1) { "disconnect left unconnected socket open" }
            check(!runBlocking { driver.connect() }) { "retired driver reopened" }
            check(socket.dials == 0) { "retired driver dialed" }
        } finally { driver.disconnect();manager.endRequest(owned);manager.close() }
    }

    private fun lateDial(mode: String) {
        val manager = idle()
        val owned = request(manager)
        val admission = ConnectionAdmission { effect -> manager.runIfValid(owned, effect) }
        val driver = ActualSocketConnection("127.0.0.1", 5277, manager.context /* DRIVER_AUTHORITY */)
        val socket = RecordingSocket()
        install(driver, socket)
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        socket.onBoundary = noMonitors(manager) { driver }
        socket.onDial = { entered.countDown();check(resume.await(5, TimeUnit.SECONDS)) }
        var connected = false
        val worker = Worker { connected = runBlocking { driver.connect() } }
        try {
            check(entered.await(5, TimeUnit.SECONDS))
            check(socket.dials == 1) { "test did not enter blocking TCP dial" }
            if (mode == "stop") stop(manager, false) else if (mode == "same-owner") claim("new input")
            else driver.disconnect()
            resume.countDown()
            worker.finish()
            check(socket.dials == 1) { "unexpected second TCP dial" }
            check(!connected) { "retired dial returned a published connection" }
            check(socket.retired) { "rejected connected candidate was not closed" }
            check(field(driver, "input") == null && field(driver, "output") == null) { "retired socket published streams" }
        } finally { resume.countDown();worker.finish();driver.disconnect();manager.endRequest(owned);manager.close() }
    }

    /** Revocation inside an external stream getter must still lose final stream publication. */
    private fun streamBoundary() {
        val manager = idle()
        val owned = request(manager)
        val admission = ConnectionAdmission { effect -> manager.runIfValid(owned, effect) }
        val driver = ActualSocketConnection("127.0.0.1", 5277, manager.context /* DRIVER_AUTHORITY */)
        val socket = RecordingSocket()
        install(driver, socket)
        socket.onBoundary = noMonitors(manager) { driver }
        socket.onStreams = { stop(manager, true) }
        try {
            check(!runBlocking { driver.connect() }) { "revoked stream getter published connection" }
            check(socket.retired) { "candidate socket was not retired" }
            check(field(driver, "input") == null && field(driver, "output") == null) { "revoked getter installed streams" }
        } finally { driver.disconnect();manager.endRequest(owned);manager.close() }
    }

    /** A distinct manual request must finish the old transport before inheriting shared TLS. */
    private fun replacesHandshakingOwner() {
        val manager = idle()
        val firstSocket = RecordingSocket()
        val secondSocket = RecordingSocket()
        var secondDriver: ActualSocketConnection? = null
        val handshakeEntered = CountDownLatch(1)
        val finishHandshake = CountDownLatch(1)
        var firstWorker: Worker? = null
        var secondWorker: Worker? = null
        var cleanupWorker: Worker? = null
        SocketProjectionConnection.prepare = { wrapped ->
            if (field(wrapped.actual, "ip") == "127.0.0.2") {
                secondDriver = wrapped.actual
                secondSocket.onBoundary = noMonitors(manager) { wrapped.actual }
                install(wrapped.actual, secondSocket)
            } else {
                firstSocket.onBoundary = noMonitors(manager) { wrapped.actual }
                install(wrapped.actual, firstSocket)
            }
        }
        try {
            runBlocking { manager.connect("127.0.0.1", 5277, ConnectionPriorityPolicy.Tier.USER,
                ticket = manager.appConnectionTicket()) }
            val first = checkNotNull(manager.captureHandshake())
            AapTransport.handshakeHook = {
                handshakeEntered.countDown()
                check(finishHandshake.await(5, TimeUnit.SECONDS))
            }
            firstWorker = Worker { runBlocking { manager.startHandshake(first) } }
            check(handshakeEntered.await(5, TimeUnit.SECONDS)) { "first SSL did not enter" }
            val firstTransport = manager.owner()
            secondWorker = Worker { runBlocking { manager.connect("127.0.0.2", 5277,
                ConnectionPriorityPolicy.Tier.USER, ticket = manager.appConnectionTicket()) } }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (manager.queue.tasks.isEmpty() && secondWorker.thread.isAlive && System.nanoTime() < deadline) {
                Thread.sleep(1)
            }
            // In the old implementation B already opened over A's still-live SSL transport.
            // Capture before releasing A, then complete both paths so failure is not a hang.
            val openedBeforeRetirement = secondDriver != null || secondSocket.dials != 0
            cleanupWorker = Worker { manager.cleanups() }
            AapTransport.handshakeHook = null
            finishHandshake.countDown()
            firstWorker.finish()
            cleanupWorker.finish()
            secondWorker.finish()
            check(!openedBeforeRetirement) { "replacement opened before original SSL retirement" }
            check(manager.state is ConnectionState.Connected && secondSocket.dials == 1) {
                "replacement did not open after retirement: ${manager.state}, dials=${secondSocket.dials}"
            }
            check(manager.nullableOwner() !== firstTransport) { "replacement inherited the old transport" }
            runBlocking {
                manager.startHandshake(checkNotNull(manager.captureHandshake()))
                manager.startReading(checkNotNull(manager.captureReading()))
            }
            check(manager.state is ConnectionState.TransportStarted) { "replacement could not start its own SSL/read loop" }
            val reads = AapRead.creations
            firstTransport.startReading()
            firstTransport.onQuit?.invoke(false)
            check(AapRead.creations == reads) { "retired transport started another reader" }
            check(manager.state is ConnectionState.TransportStarted && !secondSocket.retired) {
                "old transport completion/quit retired the replacement"
            }
            check((manager.network() as? SocketProjectionConnection)?.actual === secondDriver)
        } finally {
            AapTransport.handshakeHook = null
            finishHandshake.countDown()
            firstWorker?.finish()
            cleanupWorker?.finish()
            secondWorker?.finish()
            manager.close()
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        for (mode in listOf("accepted", "stop", "stop-open", "same-owner", "disconnect")) {
            case("socket_driver_wait_$mode") { waitBoundary(mode, throughManager = false) }
        }
        for (mode in listOf("accepted", "stop", "stop-open", "same-owner")) {
            case("socket_manager_wait_$mode") { waitBoundary(mode, throughManager = true) }
        }
        case("socket_disconnect_before_entry") { disconnectedBeforeEntry() }
        for (mode in listOf("stop", "same-owner", "disconnect")) {
            case("socket_entered_dial_$mode") { lateDial(mode) }
        }
        case("socket_stream_publication_revoked") { streamBoundary() }
        case("socket_same_owner_ssl_cutover") { replacesHandshakingOwner() }
        println("RESULTS=[${results.joinToString(",")}]")
        check(failures == 0) { "$failures socket authority regressions failed" }
    }
}
