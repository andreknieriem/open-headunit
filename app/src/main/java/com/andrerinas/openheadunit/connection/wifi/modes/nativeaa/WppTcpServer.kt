package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import android.content.Context
import android.os.SystemClock
import com.andrerinas.openheadunit.connection.wifi.direct.GroupIdentityStability
import com.andrerinas.openheadunit.aap.protocol.proto.Wireless
import com.andrerinas.openheadunit.connection.wifi.direct.WifiBandCapability
import com.andrerinas.openheadunit.ssl.SslContextFactory
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ConnectionIssue
import com.andrerinas.openheadunit.utils.ConnectionIssues
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * The WiFi Projection Protocol over TCP, with this head unit answering the phone's dial.
 *
 * From Android Auto 17.4 the phone can run the handshake over TCP instead of Bluetooth RFCOMM. It
 * learns where to dial from the endpoint we put in our WifiVersionRequest, stores it against our
 * Bluetooth address, and on later connections dials it directly. That is what makes a reconnect
 * work without the phone ever opening our RFCOMM channel again, and it is the only route open to
 * a unit whose Bluetooth adapter the phone cannot reach.
 *
 * We accept the connection but not the TLS server role: the phone dials us and then answers in
 * server mode with client authentication required, exactly as it does on 5288. So this is the same
 * side of TLS we play in AAP, with the same certificate, and [WppTcpTls] is where that is decided.
 *
 * The exchange itself is [WppHandshakeSession]'s, unchanged: a second transport under the same
 * state machine, not a second protocol.
 */
class WppTcpServer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val callbacks: Callbacks
) {

    /** What the server needs from its owner, so it does not have to reach into the manager. */
    interface Callbacks {
        /** This head unit's credentials, or null while they are still resolving. */
        fun credentials(): NativeNetworkCredentials?

        /** Which access point we are running, deciding the credentials' access-point type. */
        fun strategy(): NativeStrategy

        /** Whether the network's name and address are known to repeat, from the credentials. */
        fun identity(): GroupIdentityStability

        /** Our identity, built once by the owner so both transports announce the same thing. */
        fun carInfo(): Wireless.WppCarInfo

        /**
         * Whether the Bluetooth handshake could take this phone instead, right now. A dial is only
         * turned away with a rejection where there is a route left for the phone to fall back to.
         */
        fun canRunRfcomm(): Boolean

        /** True once the projection session has landed, which ends the handshake. */
        fun projectionSessionUp(): Boolean

        /** The address and port to advertise for the projection session itself. */
        fun projectionEndpoint(): Pair<String, Int>?

        /**
         * A dial was turned away. The owner tracks it, because what disproves the record is a
         * Bluetooth handshake landing without one, which only the owner sees.
         */
        fun noteDialRefused()

        /** The network being retired when the group on the air is one, so a rejection there is expected. */
        fun retiringNetworkName(): String?

        /** A rejection went out on the retiring network, which is what retires it. */
        fun noteEndpointRetired()

        /** An endpoint went out on a served dial, so the phone now stores this network. */
        fun noteEndpointAdvertised()
    }

    companion object {
        /**
         * The port we listen on. Distinct from 5288, which carries the projection session itself,
         * and from 5277, which is Android Auto's own head unit server that mode 1 dials outward.
         * Nothing on the phone constrains this value; it dials whatever we advertise.
         */
        const val PORT = 5299

        /** Bounded, because the port frees itself in seconds or the previous server is still up. */
        private const val BIND_ATTEMPTS = 3
        private const val BIND_RETRY_DELAY_MS = 700L

        /** How long the TLS handshake gets before we drop the socket. */
        private const val TLS_HANDSHAKE_TIMEOUT_MS = 15_000

        /** How often the exchange services its timers. Matches the Bluetooth path. */
        private const val TICK_MS = 250L

        /**
         * How long to wait for this head unit's own credentials before giving up.
         *
         * The session leaves AWAIT_CREDENTIALS with no deadline of its own, on the understanding
         * that the caller bounds it. Without this a phone that dialled in before the network came
         * up would hold the loop open for the life of the server.
         */
        private const val CREDENTIALS_WAIT_MS = 30_000L
    }

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptJob: Job? = null
    @Volatile private var running = false

    /**
     * The port we are actually listening on, or null when we are not.
     *
     * The Bluetooth path reads this before advertising an endpoint. Advertising a port nothing
     * answers is worse than advertising none: the phone stores it and dials it on the next
     * connection instead of running the Bluetooth handshake again.
     */
    val listeningPort: Int?
        get() = if (running) serverSocket?.localPort?.takeIf { it > 0 } else null

    fun start() {
        if (running) return
        running = true
        acceptJob = scope.launch(Dispatchers.IO + CoroutineName("WppTcp-Accept")) {
            try {
                val factory = SslContextFactory.create(context).socketFactory
                // Unbound first, then the option, then bind: ServerSocket(int) binds inside the
                // constructor. holdOpen() keeps the control channel up all session, so this port is
                // in TIME_WAIT at the next bring-up and a plain constructor threw EADDRINUSE - after
                // which listeningPort stayed null and the endpoint was withheld for good.
                var bound: ServerSocket? = null
                var attempt = 0
                while (running && isActive && bound == null) {
                    attempt++
                    try {
                        bound = ServerSocket().apply {
                            reuseAddress = true
                            bind(java.net.InetSocketAddress(PORT))
                        }
                    } catch (e: Exception) {
                        if (attempt >= BIND_ATTEMPTS) throw e
                        AppLog.w("WppTcpServer: port $PORT did not bind on attempt $attempt of $BIND_ATTEMPTS (${e.javaClass.simpleName}: ${e.message}). Retrying in ${BIND_RETRY_DELAY_MS}ms.")
                        delay(BIND_RETRY_DELAY_MS)
                    }
                }
                val server = bound ?: run {
                    AppLog.i("WppTcpServer: stopped before port $PORT could be bound.")
                    return@launch
                }
                serverSocket = server
                AppLog.i("WppTcpServer: listening for Android Auto on TCP $PORT")
                while (running && isActive) {
                    val socket = try {
                        server.accept()
                    } catch (e: Exception) {
                        if (running) AppLog.e("WppTcpServer: accept failed", e)
                        break
                    }
                    AppLog.i("WppTcpServer: connection from ${socket.inetAddress?.hostAddress}")
                    // Whether this dial is served is decided inside the session rather than here:
                    // turning one away means telling the phone so, and that needs TLS up first.
                    scope.launch(Dispatchers.IO + CoroutineName("WppTcp-Session")) {
                        handleConnection(socket, factory)
                    }
                }
            } catch (e: Exception) {
                AppLog.e("WppTcpServer: could not listen on $PORT: ${e.message}", e)
            } finally {
                running = false
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
    }

    /**
     * Runs one phone's handshake.
     *
     * The TLS handshake is forced before anything is written, so a phone that cannot agree on a
     * cipher fails here with a clear message rather than inside the first framed write.
     */
    private suspend fun handleConnection(
        raw: Socket,
        factory: SSLSocketFactory
    ) = withContext(Dispatchers.IO) {
        val inbound = Channel<NativeAaHandshakeManager.ProtobufMessage>(Channel.UNLIMITED)
        var readerJob: Job? = null
        var socket: SSLSocket? = null
        try {
            val tls = WppTcpTls.clientSocket(factory, raw)
            socket = tls
            tls.soTimeout = TLS_HANDSHAKE_TIMEOUT_MS
            tls.startHandshake()
            tls.soTimeout = 0
            AppLog.i(
                "WppTcpServer: TLS handshake complete with ${tls.inetAddress?.hostAddress} " +
                    "(${tls.session?.protocol}, ${tls.session?.cipherSuite})"
            )

            // A phone dialling an endpoint from an earlier session, on a unit we have since judged
            // unsafe to be remembered by. Serving it hands out a name the next create replaces,
            // which it then retries instead of falling back to Bluetooth - fifteen minutes of it,
            // measured. See [WppTcpServePolicy].
            val decision = WppEndpointPolicy.decide(callbacks.strategy(), listeningPort, callbacks.identity())
            if (!WppTcpServePolicy.servesDial(decision)) {
                refuse(tls, decision, callbacks.projectionSessionUp())
                return@withContext
            }
            // A dial we serve is a phone whose stored endpoint is this one, so whatever it held
            // before is no longer the reason anything is failing.
            ConnectionIssues.clear(context, ConnectionIssue.PHONE_HOLDS_STALE_ENDPOINT)

            val input = DataInputStream(tls.inputStream)
            val output = tls.outputStream

            readerJob = scope.launch(Dispatchers.IO + CoroutineName("WppTcp-Reader")) {
                try {
                    while (isActive) inbound.send(readFrame(input))
                } catch (e: Exception) {
                    AppLog.d("WppTcpServer: reader ended: ${e.message}")
                } finally {
                    inbound.close()
                }
            }

            runExchange(output, inbound)
        } catch (e: Exception) {
            // The class and the cause, not just the message: a TLS refusal arrives here as an
            // outer "connection closed" with the real reason - the alert, the missing certificate,
            // the cipher - only in the cause chain.
            AppLog.e("WppTcpServer: session error: ${describe(e)}", e)
        } finally {
            readerJob?.cancel()
            try { (socket ?: raw).close() } catch (_: Exception) {}
            AppLog.i("WppTcpServer: session closed")
        }
    }

    /**
     * Turns a dial away, and withdraws the endpoint that brought it here where that is safe.
     *
     * A bare close reads to the phone as a connect failure, which it retries against the same dead
     * endpoint for as long as Android Auto runs. The rejection is the only thing that ends that,
     * and it needs a Bluetooth route to send the phone back to.
     */
    private fun refuse(socket: SSLSocket, decision: WppEndpointDecision, projectionUp: Boolean) {
        val reason = WppTcpServePolicy.refusalReason(decision)
        if (!WppTcpServePolicy.blamesStaleEndpoint(decision)) {
            AppLog.i("WppTcpServer: not serving this dial, and it says nothing about the phone: $reason")
            return
        }
        if (projectionUp) {
            // Held silent for the same reason a served dial is while projection is up: anything we
            // put on this socket costs the live session, and a phone that is projecting is not one
            // stranded on a stale endpoint.
            AppLog.i("WppTcpServer: a dial arrived while projection is up; holding it silent: $reason")
            return
        }
        val retiring = callbacks.retiringNetworkName()
        if (retiring != null && WppTcpServePolicy.rejectsDial(decision, callbacks.canRunRfcomm(), projectionUp)) {
            // Expected, not a fault to show: this network is up only for this rejection.
            AppLog.w("WppTcpServer: rejecting this dial so the phone drops the endpoint it stored for $retiring and goes back to Bluetooth")
            send(socket.outputStream, WppMessages.connectionRejection().toByteArray(), WppMessageType.CONNECTION_REJECTION)
            callbacks.noteEndpointRetired()
            return
        }
        // Once, not per dial: the phone re-dials on its own backoff and a moving stamp would
        // overtake the user's dismissal every time.
        ConnectionIssues.raiseOnce(context, ConnectionIssue.PHONE_HOLDS_STALE_ENDPOINT)
        // What retires it is a Bluetooth handshake landing with no dial beside it, which only the
        // owner sees. A served dial cannot do it: a rejection that works stops the dialling.
        callbacks.noteDialRefused()
        if (!WppTcpServePolicy.rejectsDial(decision, callbacks.canRunRfcomm(), projectionUp)) {
            AppLog.w(
                "WppTcpServer: not serving this dial, and not withdrawing the endpoint because the " +
                    "Bluetooth listeners are not open for the phone to fall back to: $reason"
            )
            return
        }
        AppLog.w("WppTcpServer: rejecting this dial so the phone drops our endpoint and goes back to Bluetooth: $reason")
        send(socket.outputStream, WppMessages.connectionRejection().toByteArray(), WppMessageType.CONNECTION_REJECTION)
    }

    /**
     * Drives [WppHandshakeSession] over the open socket.
     *
     * Same shape as the Bluetooth path's loop, and deliberately not shared with it: that one also
     * owns the wake poke, the RFCOMM listeners and the auto-start device list, none of which exist
     * here. What both must agree on is the message bytes, and that is [WppMessages]' job.
     */
    private suspend fun runExchange(
        output: OutputStream,
        inbound: Channel<NativeAaHandshakeManager.ProtobufMessage>
    ) {
        val session = WppHandshakeSession()
        var stageEnteredAt = SystemClock.elapsedRealtime()
        var readerClosed = false

        suspend fun runAction(action: WppAction, source: NativeAaHandshakeManager.ProtobufMessage?) {
            when (action) {
                WppAction.SendVersionRequest -> {
                    // The same rule as the Bluetooth path: an endpoint outlives the connection that
                    // carried it, so it only goes out for a network that will still be there.
                    val endpoint = when (val decision =
                        WppEndpointPolicy.decide(callbacks.strategy(), listeningPort, callbacks.identity())) {
                        is WppEndpointDecision.Withhold -> {
                            AppLog.i("WppTcpServer: not advertising WPP over TCP: ${decision.reason}")
                            null
                        }
                        is WppEndpointDecision.Advertise ->
                            WppMessages.endpoint(callbacks.credentials()?.ip.orEmpty(), decision.port)
                                .also { callbacks.noteEndpointAdvertised() }
                    }
                    AppLog.i("WppTcpServer: [TX] WifiVersionRequest (Type 4) v${WppHandshakeSession.WPP_VERSION_MAJOR}.${WppHandshakeSession.WPP_VERSION_MINOR}")
                    val channelType = WppChannelTypePolicy.forHeadUnit(WifiBandCapability.supports5Ghz(context))
                    send(output, WppMessages.versionRequest(callbacks.carInfo(), endpoint, channelType).toByteArray(), WppMessageType.VERSION_REQUEST)
                }
                WppAction.SendStartRequest -> {
                    val endpoint = callbacks.projectionEndpoint()
                    if (endpoint == null) {
                        AppLog.w("WppTcpServer: no projection endpoint yet; cannot send WifiStartRequest")
                        return
                    }
                    AppLog.i("WppTcpServer: [TX] WifiStartRequest (Type 1) -> ${endpoint.first}:${endpoint.second}")
                    send(output, WppMessages.startRequest(endpoint.first, endpoint.second).toByteArray(), WppMessageType.START_REQUEST)
                }
                WppAction.SendInfoResponse -> {
                    val creds = callbacks.credentials()
                    if (creds == null) {
                        AppLog.w("WppTcpServer: no credentials to send")
                        return
                    }
                    AppLog.i("WppTcpServer: [TX] WifiInfoResponse (Type 3) with credentials")
                    send(
                        output,
                        WppMessages.infoResponse(
                            creds.ssid, creds.psk, creds.bssid, callbacks.identity()
                        ).toByteArray(),
                        WppMessageType.INFO_RESPONSE
                    )
                }
                WppAction.SendPingResponse ->
                    send(output, source?.payload ?: ByteArray(0), WppMessageType.PING_RESPONSE)
                WppAction.CompleteSuccess ->
                    AppLog.i("WppTcpServer: handshake complete; projection session is up")
                is WppAction.Fail ->
                    AppLog.w("WppTcpServer: handshake failed: ${action.reason} (phone silent=${action.phoneWasSilent})")
                // The poke and the settling window belong to the Bluetooth path; over TCP the phone
                // is already on our network, so there is nothing to wake and nothing to extend.
                WppAction.ExtendSettle, WppAction.ResumePoke -> {}
            }
        }

        suspend fun feed(event: WppEvent, source: NativeAaHandshakeManager.ProtobufMessage? = null) {
            val before = session.stage
            for (action in session.on(event)) runAction(action, source)
            if (session.stage != before) {
                stageEnteredAt = SystemClock.elapsedRealtime()
                AppLog.d("WppTcpServer: stage $before -> ${session.stage}")
            }
        }

        // A phone that re-dials while its projection is up must hear nothing: a version request
        // restarts wireless setup on its side and drops the live AAP session. Asked before the first
        // send and ahead of every message, because a phone that answers each tick never leaves the
        // loop idle long enough for a check that waits for silence.
        if (callbacks.projectionSessionUp()) {
            AppLog.i("WppTcpServer: projection already up; holding the re-dialled control channel open without a handshake")
            feed(WppEvent.TcpSessionUp)
        } else {
            feed(WppEvent.SocketReady)
            if (callbacks.credentials() != null) feed(WppEvent.CredentialsReady)
        }

        while (running && !session.isTerminal()) {
            if (callbacks.projectionSessionUp()) {
                feed(WppEvent.TcpSessionUp)
                continue
            }
            var msg: NativeAaHandshakeManager.ProtobufMessage? = null
            if (readerClosed) {
                delay(TICK_MS)
            } else {
                val deadline = SystemClock.elapsedRealtime() + TICK_MS
                while (true) {
                    val result = inbound.tryReceive()
                    val received = result.getOrNull()
                    if (received != null) { msg = received; break }
                    if (result.isClosed) { readerClosed = true; break }
                    if (SystemClock.elapsedRealtime() >= deadline) break
                    delay(25)
                }
            }

            if (msg != null) {
                val status = parseStatus(msg)
                AppLog.i(
                    "WppTcpServer: [RX] Type ${msg.type} (${msg.payload.size} bytes)" +
                        if (status != null) " status=${WppStatus.describe(status)}" else ""
                )
                feed(WppEvent.MessageReceived(msg.type, status), msg)
                continue
            }

            val waitedInStage = SystemClock.elapsedRealtime() - stageEnteredAt

            if (session.stage == WppStage.AWAIT_CREDENTIALS) {
                if (callbacks.credentials() != null) {
                    feed(WppEvent.CredentialsReady)
                } else if (waitedInStage >= CREDENTIALS_WAIT_MS) {
                    AppLog.w("WppTcpServer: no credentials after ${CREDENTIALS_WAIT_MS / 1000}s; ending the exchange")
                    feed(WppEvent.CredentialsUnavailable)
                }
                continue
            }
            val timeout = session.currentStageTimeoutMs()
            if (timeout != null && waitedInStage >= timeout) {
                feed(WppEvent.StageTimeout)
                continue
            }
            // Nothing left to wait for: the phone has gone and this stage has no deadline to end
            // it. Without this the loop would spin until the server itself was stopped.
            if (readerClosed && timeout == null) {
                AppLog.w("WppTcpServer: phone closed the socket in stage ${session.stage}; ending the exchange")
                return
            }
        }

        if (session.stage == WppStage.DONE && !readerClosed) holdOpen(output, inbound)
    }

    /**
     * Holds the control channel open once the handshake is done, answering the phone's pings.
     *
     * WPP pings are the phone's to send: Android Auto's own dispatcher lists WifiPingRequest among
     * the messages it does not expect to receive, so we answer pings and never open with one. 17.5
     * sends none over TCP and drops the channel about ten seconds after the exchange, which costs
     * nothing because projection has its own connection; we hold it anyway, because a build that
     * does ping counts the answers as a health check and reads our closing as a failure.
     */
    private suspend fun holdOpen(
        output: OutputStream,
        inbound: Channel<NativeAaHandshakeManager.ProtobufMessage>
    ) {
        AppLog.i("WppTcpServer: holding the control channel open for the session")
        var pings = 0
        while (running && callbacks.projectionSessionUp()) {
            val result = inbound.tryReceive()
            val msg = result.getOrNull()
            if (msg == null) {
                if (result.isClosed) {
                    AppLog.i("WppTcpServer: the phone closed the control channel after $pings pings")
                    return
                }
                delay(TICK_MS)
                continue
            }
            if (msg.type == WppMessageType.PING_REQUEST) {
                pings++
                // Quietly: one line per ping for the length of a drive is not worth reading.
                output.write(WppFraming.encodeFrame(msg.payload, WppMessageType.PING_RESPONSE))
                output.flush()
            } else {
                AppLog.i("WppTcpServer: [RX] Type ${msg.type} after the handshake (${msg.payload.size} bytes)")
            }
        }
        AppLog.i("WppTcpServer: the session ended; closing the control channel after $pings pings")
    }

    /** The exception and everything under it, so one log line carries the whole chain. */
    private fun describe(e: Throwable): String {
        val parts = mutableListOf<String>()
        var current: Throwable? = e
        while (current != null && parts.size < 5) {
            parts += "${current.javaClass.simpleName}: ${current.message ?: "no message"}"
            current = current.cause.takeIf { it !== current }
        }
        return parts.joinToString(" <- ")
    }

    private fun send(output: OutputStream, payload: ByteArray, type: Int) {
        output.write(WppFraming.encodeFrame(payload, type))
        output.flush()
        AppLog.i("WppTcpServer: [TX] wrote type $type (${payload.size} bytes)")
    }

    private fun readFrame(input: DataInputStream): NativeAaHandshakeManager.ProtobufMessage {
        val header = ByteArray(WppFraming.HEADER_SIZE)
        input.readFully(header)
        val size = WppFraming.decodePayloadSize(header)
        val type = WppFraming.decodeType(header)
        val payload = if (size > 0) ByteArray(size).also { input.readFully(it) } else ByteArray(0)
        return NativeAaHandshakeManager.ProtobufMessage(type, payload)
    }

    /** The status field of the types that carry one; null when absent or unparseable. */
    private fun parseStatus(msg: NativeAaHandshakeManager.ProtobufMessage): Int? = try {
        when (msg.type) {
            WppMessageType.VERSION_RESPONSE ->
                Wireless.WifiVersionResponse.parseFrom(msg.payload).status
            WppMessageType.CONNECT_STATUS ->
                Wireless.WifiConnectStatus.parseFrom(msg.payload).status
            WppMessageType.START_RESPONSE ->
                Wireless.WifiStartResponse.parseFrom(msg.payload).status
            else -> null
        }
    } catch (e: Exception) {
        AppLog.d("WppTcpServer: type ${msg.type} did not parse for status: ${e.message}")
        null
    }
}
