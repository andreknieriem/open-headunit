package com.andrerinas.openheadunit.connection.carplaynative.airplay

import com.andrerinas.openheadunit.connection.carplaybox.BoxAudioSink
import com.andrerinas.openheadunit.connection.carplaybox.BoxVideoSink
import com.andrerinas.openheadunit.connection.carplaynative.CarPlaySigner
import com.andrerinas.openheadunit.connection.carplaynative.SignerException
import com.andrerinas.openheadunit.connection.carplaynative.airplay.RtspRoutes.Route
import com.andrerinas.openheadunit.connection.carplaynative.codec.BinaryPlist
import com.andrerinas.openheadunit.connection.carplaynative.codec.PlistException
import com.andrerinas.openheadunit.connection.carplaynative.codec.PlistValue
import com.andrerinas.openheadunit.connection.carplaynative.codec.Rtsp
import com.andrerinas.openheadunit.utils.AppLog
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The AirPlay receiver of one session, bound to [bindAddress] (the tunnel address only); [close] ends every thread and socket.
 * Follows xcertplay (https://github.com/shilapi/xcertplay) `airplay/AirPlaySession.kt` and `CarPlayMediaEngine.kt`,
 * with the peer check and the bounded buffers of DiPlay (https://github.com/shihabal3amri/DiPlay); the silence rule follows LIVI (https://github.com/f-io/LIVI) crate livi-cp.
 */
class AirPlayServer(
    private val bindAddress: InetAddress,
    private val info: InfoPlist.Config,
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
    private val signer: CarPlaySigner,
    private val video: BoxVideoSink,
    private val audio: BoxAudioSink,
    private val listener: Listener,
    private val elapsed: () -> Long,
    private val acceptsPeer: (InetAddress) -> Boolean = { PeerGate.isNcmPeer(it, bindAddress) },
    private val nanoClock: () -> Long = System::nanoTime,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : Closeable {

    interface Listener {
        /** The phone opened a control connection. [peer] is the short address text. */
        fun onControlConnection(peer: String)

        /** The size of the picture in the screen config. */
        fun onVideoSize(width: Int, height: Int)

        /** The first frame went to the decoder. */
        fun onStreaming()

        /** The session ended on its own: a teardown, a silent phone or a lost channel after SETUP. Never called by [close]. */
        fun onEnded(reason: String)

        /** The current control connection ended before its SETUP. A replaced connection and [close] do not call it. */
        fun onEndedBeforeSetup(reason: String)
    }

    private val closed = AtomicBoolean(false)
    private val ended = AtomicBoolean(false)
    private val resources = Resources()
    private val connectionCount = AtomicInteger()
    private val formats = AudioChannelFormats()
    private val throttle = KeyFrameThrottle()
    @Volatile private var current: Connection? = null
    @Volatile private var server: ServerSocket? = null
    private val maxX = (info.main.widthPixels and 0xffff).toInt()
    private val maxY = (info.main.heightPixels and 0xffff).toInt()
    private val touchLogged = AtomicBoolean(false)
    private val keyframeLogged = AtomicBoolean(false)

    /** Binds the control listener and returns its port. Call once. */
    fun start(): Int {
        check(!closed.get() && server == null) { "AirPlayServer is single use" }
        val listening = resources.track(ServerSocket(0, 2, bindAddress))
        server = listening
        resources.spawn("carplay-airplay-accept") { acceptLoop(listening) }
        return listening.localPort
    }

    /**
     * Sends a touch report. [contacts] are in pixels of the picture [pictureWidth] by [pictureHeight]; they are mapped to
     * the display /info declared and clamped to it. False when the event channel is not ready.
     */
    fun sendTouch(contacts: List<AirPlayTouchReport.Contact>, pictureWidth: Int, pictureHeight: Int): Boolean {
        val scaled = AirPlayTouchReport.toDisplay(contacts, pictureWidth, pictureHeight, maxX, maxY)
        val sent = current?.send(AirPlayCommands.touch(AirPlayTouchReport.touch(scaled))) == true
        if (!sent && touchLogged.compareAndSet(false, true)) AppLog.w("CarPlayNative: touch dropped, the event channel is not ready")
        return sent
    }

    /** Asks the phone for a key frame of the main screen, at most once a second. [reason] goes to the log. */
    fun requestKeyframe(reason: String) {
        val connection = current
        if (connection == null || !connection.eventReady) {
            if (keyframeLogged.compareAndSet(false, true)) AppLog.i("CarPlayNative: keyframe not requested ($reason), the event channel is not ready")
            return
        }
        if (!throttle.tryAcquire(elapsed())) return
        if (connection.send(AirPlayCommands.forceKeyFrame())) AppLog.i("CarPlayNative: keyframe requested ($reason)") else throttle.release()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        current?.close("server closed", quiet = true)
        current = null
        resources.close()
    }

    private fun acceptLoop(listening: ServerSocket) {
        while (!closed.get()) {
            val socket = try { listening.accept() } catch (_: IOException) { return }
            val peer = socket.inetAddress
            if (peer == null || !acceptsPeer(peer)) {
                AppLog.w("CarPlayNative: AirPlay connection from a host outside the NCM link refused")
                runCatching { socket.close() }
                continue
            }
            val text = Ipv6Text.of(peer)
            AppLog.i("CarPlayNative: AirPlay connection from $text")
            listener.onControlConnection(text)
            current?.close("replaced by a new connection", quiet = true)
            val connection = Connection(socket, peer, connectionCount.incrementAndGet())
            current = connection
            if (closed.get()) { connection.close("server closed", quiet = true); return }
            connection.start()
        }
    }

    private fun connectionEnded(connection: Connection, reason: String) {
        if (current !== connection) return
        if (!connection.setupDone) {
            AppLog.i("CarPlayNative: AirPlay connection ended before SETUP ($reason)")
            listener.onEndedBeforeSetup(reason)
            return
        }
        if (ended.compareAndSet(false, true)) listener.onEnded(reason)
    }

    private inner class Connection(private val socket: Socket, val peer: InetAddress, private val number: Int) {
        private val own = Resources()
        private val isClosed = AtomicBoolean(false)
        private val pairSetup = PairSetup(identity, pairings)
        private val pairVerify = PairVerify(identity, pairings)
        private val tracker = AirPlayCommandTracker()
        private val eventQueue = LinkedBlockingQueue<ByteArray>(EVENT_QUEUE)
        private val eventWriteLock = Any()
        private val cseq = AtomicInteger()
        private val authLogged = AtomicBoolean(false)
        private val queueLogged = AtomicBoolean(false)
        @Volatile private var cipher: ControlCipher? = null
        @Volatile private var eventCipher: ControlCipher? = null
        @Volatile private var eventSocket: Socket? = null
        @Volatile var eventReady = false
            private set
        @Volatile var setupDone = false
            private set
        @Volatile private var silence: PhoneSilence? = null
        @Volatile private var screen: Resources? = null
        private val audioStreams = HashMap<Int, Resources>()
        private var sessionOpen = false
        // Rtsp.parse caps a header at 64 KiB and a body at 1 MiB; a record is at most 64 KiB, so both buffers stay bounded.
        private var encrypted = ByteArray(0)
        private var plain = ByteArray(0)

        fun start() {
            own.spawn("carplay-airplay-control-$number") { run() }
        }

        /** Queues a command for the event channel. False when the channel is not ready or the queue is full. */
        fun send(command: PlistValue.Dict): Boolean {
            if (!eventReady) return false
            val number = cseq.incrementAndGet()
            val type = command["type"]?.asText ?: "?"
            tracker.sent(number, type)
            val queued = eventQueue.offer(AirPlayCommands.eventRequest(command, number))
            if (!queued && queueLogged.compareAndSet(false, true)) AppLog.w("CarPlayNative: the event queue is full ($EVENT_QUEUE); a command was dropped")
            return queued
        }

        fun close(reason: String, quiet: Boolean) {
            if (!isClosed.compareAndSet(false, true)) return
            eventReady = false
            runCatching { socket.close() }
            // Every stream group is tracked by own; walking audioStreams here could race a SETUP on the control thread.
            own.close()
            if (!quiet) connectionEnded(this, reason)
        }

        private fun run() {
            var reason = "the phone closed the control connection"
            try {
                socket.soTimeout = POLL_MS
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                val buffer = ByteArray(READ_BYTES)
                while (!isClosed.get()) {
                    val count = try { input.read(buffer) } catch (_: SocketTimeoutException) {
                        if (silence?.isSilent(elapsed()) == true) { reason = "the phone has been silent for ${PhoneSilence.SILENT_MS / 1000} s"; break }
                        continue
                    }
                    if (count < 0) break
                    silence?.heard(elapsed())
                    val failure = onBytes(buffer, count, output)
                    if (failure != null) { reason = failure; break }
                }
            } catch (e: IOException) {
                if (!isClosed.get()) reason = "control connection failed (${e.javaClass.simpleName})"
            } catch (e: RuntimeException) {
                reason = "control connection failed (${e.javaClass.simpleName})"
            }
            close(reason, quiet = false)
        }

        /** Returns a reason to end the connection, or null. */
        private fun onBytes(chunk: ByteArray, count: Int, output: java.io.OutputStream): String? {
            val active = cipher
            val data = if (active == null) chunk.copyOf(count) else {
                encrypted += chunk.copyOf(count)
                val decrypted = active.decrypt(encrypted) ?: return "a control record failed its tag"
                encrypted = decrypted.rest
                decrypted.data
            }
            plain += data
            val parsed = Rtsp.parse(plain)
            if (parsed !is Rtsp.Parsed.Ok) return "an RTSP message was too large"
            plain = parsed.rest
            for (request in parsed.requests) {
                val response = try { handle(request) } catch (e: SignerException) {
                    AppLog.w("CarPlayNative: the MFi signer failed (${e.message})")
                    Rtsp.Response(500)
                } catch (e: RuntimeException) {
                    AppLog.w("CarPlayNative: AirPlay ${request.method} failed (${e.javaClass.simpleName})")
                    Rtsp.Response(500)
                }
                val wire = Rtsp.buildResponse(request, response)
                output.write(active?.encrypt(wire) ?: wire)
                output.flush()
                if (cipher == null) pairVerify.controlKeys?.let {
                    cipher = ControlCipher(it.readKey, it.writeKey)
                    AppLog.i("CarPlayNative: pair-verify done")
                }
            }
            return null
        }

        private fun handle(request: Rtsp.Request): Rtsp.Response = when (RtspRoutes.route(request.method, request.path, cipher != null)) {
            Route.NeedsPairVerify -> Rtsp.Response(RtspRoutes.STATUS_NEEDS_PAIR_VERIFY)
            Route.PairSetup -> Rtsp.Response(headers = listOf("Content-Type" to RtspRoutes.CONTENT_PAIRING), body = pairSetup.handle(request.body))
            Route.PairVerify -> Rtsp.Response(headers = listOf("Content-Type" to RtspRoutes.CONTENT_PAIRING), body = pairVerify.handle(request.body))
            Route.AuthSetup -> {
                val body = AuthSetup.handle(request.body, signer)
                if (body == null) Rtsp.Response(400) else {
                    if (authLogged.compareAndSet(false, true)) AppLog.i("CarPlayNative: auth-setup done")
                    Rtsp.Response(headers = listOf("Content-Type" to RtspRoutes.CONTENT_OCTET), body = body)
                }
            }
            Route.Info -> Rtsp.Response(headers = listOf("Content-Type" to RtspRoutes.CONTENT_PLIST), body = InfoPlist.encode(info))
            Route.Setup -> setup(request)
            Route.Record -> Rtsp.Response()
            Route.Teardown -> teardown(request)
            Route.Command -> {
                (decode(request.body) as? PlistValue.Dict)?.get("type")?.asText?.let { AppLog.d("CarPlayNative: phone command $it") }
                Rtsp.Response()
            }
            Route.Feedback, Route.Options, Route.Other -> Rtsp.Response()
        }

        private fun decode(body: ByteArray): PlistValue? = try { BinaryPlist.decode(body) } catch (_: PlistException) { null }

        private fun setup(request: Rtsp.Request): Rtsp.Response {
            val body = decode(request.body) ?: return Rtsp.Response(400)
            val shared = pairVerify.sharedSecret ?: return Rtsp.Response(500)
            val answer = when (val parsed = SetupNegotiation.parse(body)) {
                null -> return Rtsp.Response(400)
                is SetupNegotiation.Request.SessionSetup -> openSession(parsed.session)
                is SetupNegotiation.Request.Streams -> openStreams(parsed.streams, shared)
            } ?: return Rtsp.Response(400)
            return Rtsp.Response(headers = listOf("Content-Type" to RtspRoutes.CONTENT_PLIST), body = BinaryPlist.encode(answer))
        }

        private fun openSession(session: SetupNegotiation.Session): PlistValue.Dict? {
            if (sessionOpen) { AppLog.w("CarPlayNative: a second session SETUP was refused"); return null }
            sessionOpen = true
            val quiet = PhoneSilence(elapsed()).also { silence = it }
            val timingPort = TimingChannel(bindAddress, peer, session.peerTimingPort, own, quiet, elapsed, nanoClock, wallClock).listen()
            val keepAlivePort = if (session.wantsKeepAlive) KeepAliveChannel(bindAddress, peer, own, quiet, elapsed).listen() else null
            val eventPort = openEvent()
            val features = SetupNegotiation.enabledFeatures(session.offeredFeatures, info.main.viewArea != null)
            return SetupNegotiation.sessionResponse(timingPort, eventPort, keepAlivePort, features)
        }

        private fun openEvent(): Int {
            val listening = own.track(ServerSocket(0, 1, bindAddress))
            own.spawn("carplay-event-accept") {
                try {
                    val accepted = own.track(listening.acceptFrom(peer) { AppLog.w("CarPlayNative: event channel refused a host outside the session") })
                    runCatching { listening.close() }
                    serveEvents(accepted)
                } catch (_: IOException) {
                    // closed with the connection
                }
            }
            return listening.localPort
        }

        private fun serveEvents(event: Socket) {
            val shared = pairVerify.sharedSecret ?: return close("pair-verify secret missing", quiet = false)
            event.tcpNoDelay = true
            val keys = StreamKeys.events(shared)
            val sealing = ControlCipher(keys.readKey, keys.writeKey)
            eventCipher = sealing
            eventSocket = event
            own.spawn("carplay-event-write") { writeEvents() }
            eventReady = true
            AppLog.i("CarPlayNative: event channel open")
            var pending = ByteArray(0)
            var framed = ByteArray(0)
            val buffer = ByteArray(READ_BYTES)
            val input = event.getInputStream()
            var reason = "the event channel closed"
            try {
                while (!isClosed.get()) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    pending += buffer.copyOf(count)
                    val decrypted = sealing.decrypt(pending)
                    if (decrypted == null) { reason = "an event record failed its tag"; break }
                    pending = decrypted.rest
                    framed += decrypted.data
                    val parsed = Rtsp.parse(framed)
                    if (parsed !is Rtsp.Parsed.Ok) { reason = "an event message was too large"; break }
                    framed = parsed.rest
                    parsed.requests.forEach(::onEventMessage)
                }
            } catch (e: IOException) {
                if (!isClosed.get()) reason = "the event channel failed (${e.javaClass.simpleName})"
            }
            close(reason, quiet = false)
        }

        private fun onEventMessage(message: Rtsp.Request) {
            if (message.method.startsWith("RTSP/") || message.method.startsWith("HTTP/")) {
                val answered = message.headers["cseq"]?.toIntOrNull()?.let { tracker.answered(it) } ?: return
                if (answered.shouldLog) AppLog.i("CarPlayNative: command ${answered.type} answered (#${answered.count})")
                return
            }
            // The phone's own requests on the event channel get an empty 200.
            val reply = Rtsp.buildResponse(message, Rtsp.Response())
            writeSealed(reply)
        }

        private fun writeEvents() {
            try {
                while (!isClosed.get()) {
                    val next = eventQueue.poll(POLL_MS.toLong(), TimeUnit.MILLISECONDS) ?: continue
                    writeSealed(next)
                }
            } catch (_: InterruptedException) {
                // close() interrupts the writer.
            } catch (e: IOException) {
                if (!isClosed.get()) close("the event write failed (${e.javaClass.simpleName})", quiet = false)
            }
        }

        private fun writeSealed(plainBytes: ByteArray) {
            synchronized(eventWriteLock) {
                val sealing = eventCipher ?: return
                val out = (eventSocket ?: return).getOutputStream()
                out.write(sealing.encrypt(plainBytes))
                out.flush()
            }
        }

        private fun openStreams(streams: List<SetupNegotiation.Stream>, shared: ByteArray): PlistValue.Dict {
            val entries = ArrayList<PlistValue>()
            for (stream in streams) {
                when (stream) {
                    is SetupNegotiation.Stream.Screen -> screenEntry(stream, shared)?.let(entries::add)
                    is SetupNegotiation.Stream.Audio -> audioEntry(stream, shared)?.let(entries::add)
                    is SetupNegotiation.Stream.Unsupported -> AppLog.i("CarPlayNative: stream type ${stream.type} is not served")
                }
            }
            setupDone = true
            return SetupNegotiation.streamsResponse(entries)
        }

        private fun screenEntry(stream: SetupNegotiation.Stream.Screen, shared: ByteArray): PlistValue? {
            val id = stream.connectionId
            if (id == null) {
                AppLog.w("CarPlayNative: the screen stream has no streamConnectionID; refused")
                return null
            }
            val group = Resources().also { own.track(it) }
            replaceScreen(group)
            val events = object : ScreenReceiver.Events {
                override fun onVideoSize(width: Int, height: Int) = listener.onVideoSize(width, height)
                override fun onFirstFrame() = listener.onStreaming()
                override fun onScreenEnded(reason: String) {
                    if (screen === group && !isClosed.get()) close(reason, quiet = false)
                }
            }
            val port = ScreenReceiver(StreamKeys.dataStream(shared, id), bindAddress, peer, group, video, events).listen()
            return SetupNegotiation.screenEntry(InfoPlist.STREAM_TYPE_MAIN_SCREEN, port)
        }

        private fun audioEntry(stream: SetupNegotiation.Stream.Audio, shared: ByteArray): PlistValue? {
            val id = stream.connectionId
            val channel = AirPlayAudioRouting.channelFor(stream.type)
            if (id == null || channel == null) {
                AppLog.w("CarPlayNative: the audio stream ${stream.type} has no streamConnectionID; refused")
                return null
            }
            val format = AirPlayAudioRouting.formatFor(stream.formatBits)
            if (format.sinkFormat == null) {
                AppLog.w("CarPlayNative: audio ${stream.type} ${stream.audioType} is ${format.codec}; its packets are dropped")
            }
            audioStreams.remove(stream.type)?.close()
            val group = Resources().also { audioStreams[stream.type] = it; own.track(it) }
            val receiver = AudioReceiver(
                StreamKeys.dataStream(shared, id), bindAddress, peer, stream.type, stream.audioType, format, channel, formats, audio, group,
            )
            val (data, control) = receiver.listen()
            return SetupNegotiation.audioEntry(stream, data, control)
        }

        /** The new group is current before the old one closes, so the old reader's end is not taken for the stream's end. */
        private fun replaceScreen(next: Resources?) {
            val old = screen
            screen = next
            old?.close()
        }

        private fun teardown(request: Rtsp.Request): Rtsp.Response {
            val body = decode(request.body) as? PlistValue.Dict
            val types = (body?.get("streams")?.asItems ?: emptyList()).mapNotNull { (it as? PlistValue.Dict)?.get("type")?.asLong }
            if (types.isEmpty()) {
                // As the references do: every stream closes and the connection stays until the phone closes it.
                replaceScreen(null)
                audioStreams.values.forEach { it.close() }
                audioStreams.clear()
                return Rtsp.Response()
            }
            for (type in types) {
                if (type == InfoPlist.STREAM_TYPE_MAIN_SCREEN) replaceScreen(null) else audioStreams.remove(type.toInt())?.close()
            }
            return Rtsp.Response()
        }
    }

    private companion object {
        const val READ_BYTES = 16 * 1024
        const val POLL_MS = 250

        /** Commands waiting for the event writer. A touch is about 100 bytes, so a full queue is under 13 KiB. */
        const val EVENT_QUEUE = 128
    }
}
