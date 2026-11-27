package com.andrerinas.openheadunit.connection.carplaynative.airplay

import com.andrerinas.openheadunit.connection.carplaybox.BoxAudioSink
import com.andrerinas.openheadunit.connection.carplaybox.BoxVideoSink
import com.andrerinas.openheadunit.decoder.video.ParameterSetInspector
import com.andrerinas.openheadunit.utils.AppLog
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * The sockets and threads of one AirPlay connection. [close] closes every socket, newest first, then ends and joins the threads.
 * A resource added after [close] is closed at once, so a thread that starts late leaves nothing open.
 */
internal class Resources : Closeable {
    private val closeables = ArrayList<Closeable>()
    private val threads = ArrayList<Thread>()
    private var closed = false

    fun <T : Closeable> track(resource: T): T {
        val late = synchronized(this) { if (closed) true else { closeables.add(resource); false } }
        if (late) runCatching { resource.close() }
        return resource
    }

    fun spawn(name: String, body: () -> Unit) {
        val thread = Thread(body, name).apply { isDaemon = true }
        val late = synchronized(this) { if (closed) true else { threads.add(thread); false } }
        if (!late) thread.start()
    }

    override fun close() {
        val (resources, running) = synchronized(this) {
            if (closed) return
            closed = true
            closeables.asReversed().toList() to threads.toList()
        }
        resources.forEach { runCatching { it.close() } }
        running.forEach { if (it !== Thread.currentThread()) it.interrupt() }
        running.forEach {
            if (it !== Thread.currentThread()) try { it.join(JOIN_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }

    private companion object {
        const val JOIN_MS = 1500L
    }
}

/** Accepts until the connection comes from [expected]; any other host is closed unread. */
internal fun ServerSocket.acceptFrom(expected: InetAddress, onRejected: (InetAddress?) -> Unit): Socket {
    while (true) {
        val socket = accept()
        if (PeerGate.accepts(expected, socket.inetAddress)) return socket
        onRejected(socket.inetAddress)
        runCatching { socket.close() }
    }
}

/**
 * The screen data stream: one TCP connection from the phone. Reads 16 KiB at a time; the reader holds at most one
 * message (8 MiB cap). A decode call blocks this thread, so the phone's TCP window is the bound on queued frames.
 * Follows xcertplay (https://github.com/shilapi/xcertplay) `airplay/ScreenStream.kt` and DiPlay (https://github.com/shihabal3amri/DiPlay) `airplay/ScreenStream.kt`.
 */
internal class ScreenReceiver(
    key: ByteArray,
    private val bindAddress: InetAddress,
    private val peer: InetAddress,
    private val resources: Resources,
    private val video: BoxVideoSink,
    private val events: Events,
) {
    interface Events {
        fun onVideoSize(width: Int, height: Int)
        fun onFirstFrame()
        fun onScreenEnded(reason: String)
    }

    private val reader = ScreenPacketReader(key)
    private var lengthSize = 0
    private var lastSize: Pair<Int, Int>? = null
    private var firstFrame = false
    private var droppedLogged = false

    fun listen(): Int {
        val server = resources.track(ServerSocket(0, 1, bindAddress))
        resources.spawn("carplay-screen") { serve(server) }
        return server.localPort
    }

    private fun serve(server: ServerSocket) {
        var reason = "screen stream closed"
        try {
            val socket = resources.track(server.acceptFrom(peer) { AppLog.w("CarPlayNative: screen stream refused a host outside the session") })
            runCatching { server.close() }
            val input = socket.getInputStream()
            val buffer = ByteArray(READ_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                for (event in reader.push(buffer, count)) handle(event)
            }
        } catch (e: ScreenStreamException) {
            reason = "screen stream broken (${e.message})"
        } catch (e: IOException) {
            reason = "screen stream closed (${e.javaClass.simpleName})"
        } catch (e: RuntimeException) {
            reason = "screen stream failed (${e.javaClass.simpleName})"
        }
        events.onScreenEnded(reason)
    }

    private fun handle(event: ScreenPacketReader.Event) {
        when (event) {
            is ScreenPacketReader.Event.Config -> config(event)
            is ScreenPacketReader.Event.Frame -> frame(event.payload)
            is ScreenPacketReader.Event.AuthFailed -> if (event.first) AppLog.w("CarPlayNative: a screen frame failed its tag; dropped")
        }
    }

    private fun config(event: ScreenPacketReader.Event.Config) {
        if (event.codec != ScreenCodec.H264) {
            AppLog.w("CarPlayNative: the screen stream is ${event.codec}; only H.264 is served")
            return
        }
        val avcc = AnnexB.parseAvcc(event.record)
        if (avcc == null) {
            AppLog.w("CarPlayNative: the screen config is not a readable avcC")
            return
        }
        lengthSize = avcc.nalLengthSize
        video.decode(avcc.parameterSets, 0, avcc.parameterSets.size)
        val size = AnnexB.firstSpsSize(avcc.parameterSets)
        if (size != null && size != lastSize) {
            lastSize = size
            AppLog.i("CarPlayNative: video ${size.first}x${size.second}")
            events.onVideoSize(size.first, size.second)
        }
    }

    private fun frame(payload: ByteArray) {
        val annexB = if (lengthSize == 0) null else AnnexB.convert(payload, lengthSize)
        if (annexB == null) {
            if (!droppedLogged) { droppedLogged = true; AppLog.w("CarPlayNative: a screen frame was dropped (no config or bad NAL lengths)") }
            return
        }
        video.decode(annexB, 0, annexB.size)
        if (!firstFrame) { firstFrame = true; events.onFirstFrame() }
    }

    private companion object {
        const val READ_BYTES = 16 * 1024
    }
}

/**
 * One audio stream: an RTP data socket and a control socket that is only drained. A datagram is at most 4096 bytes,
 * and the kernel queue holds 512 KiB, so the sink's own queue is the only other bound. Only linear PCM plays.
 * Follows xcertplay (https://github.com/shilapi/xcertplay) `airplay/AudioStream.kt`.
 */
internal class AudioReceiver(
    key: ByteArray,
    private val bindAddress: InetAddress,
    private val peer: InetAddress,
    private val streamType: Int,
    private val audioType: String,
    private val format: AirPlayAudioRouting.Format,
    private val channel: Int,
    private val formats: AudioChannelFormats,
    private val sink: BoxAudioSink,
    private val resources: Resources,
) {
    private val opener = AudioPacketOpener(key)
    private var foreignLogged = false

    /** The data port and the control port. */
    fun listen(): Pair<Int, Int> {
        val data = resources.track(bind())
        val control = resources.track(bind())
        resources.spawn("carplay-audio") { receive(data) }
        resources.spawn("carplay-audio-control") { drain(control) }
        return data.localPort to control.localPort
    }

    private fun bind(): DatagramSocket = DatagramSocket(null).also {
        it.bind(InetSocketAddress(bindAddress, 0))
        runCatching { it.receiveBufferSize = RECEIVE_BUFFER_BYTES }
    }

    private fun receive(socket: DatagramSocket) {
        val sinkFormat = format.sinkFormat
        val buffer = ByteArray(DATAGRAM_BYTES)
        val packet = DatagramPacket(buffer, buffer.size)
        while (true) {
            try { packet.length = buffer.size; socket.receive(packet) } catch (_: IOException) { return }
            if (!PeerGate.accepts(peer, packet.address)) {
                if (!foreignLogged) { foreignLogged = true; AppLog.w("CarPlayNative: audio datagram from a host outside the session dropped") }
                continue
            }
            if (sinkFormat == null) continue
            val opened = opener.open(buffer, packet.length)
            when (opened) {
                is AudioPacketOpener.Result.Opened -> {
                    if (formats.needsConfigure(channel, sinkFormat)) {
                        AppLog.i("CarPlayNative: audio $streamType $audioType ${sinkFormat.sampleRate}Hz/${sinkFormat.channels}")
                        sink.configure(channel, sinkFormat)
                    }
                    val pcm = AudioPacketOpener.swapS16InPlace(opened.payload)
                    sink.write(channel, pcm, 0, pcm.size)
                }
                is AudioPacketOpener.Result.AuthFailed -> if (opened.first) AppLog.w("CarPlayNative: an audio packet failed its tag; dropped")
                AudioPacketOpener.Result.TooShort -> Unit
            }
        }
    }

    private fun drain(socket: DatagramSocket) {
        val packet = DatagramPacket(ByteArray(DATAGRAM_BYTES), DATAGRAM_BYTES)
        while (true) try { socket.receive(packet) } catch (_: IOException) { return }
    }

    private companion object {
        const val DATAGRAM_BYTES = 4096
        const val RECEIVE_BUFFER_BYTES = 512 * 1024
    }
}

/**
 * The timing channel: answers the phone's PT 210 packets and asks for the clock once a second.
 * Only the phone's address is answered or believed. A reply from any other host changes nothing.
 * Follows xcertplay (https://github.com/shilapi/xcertplay) `airplay/NtpClock.kt` and DiPlay (https://github.com/shihabal3amri/DiPlay) `airplay/NtpClock.kt`.
 */
internal class TimingChannel(
    private val bindAddress: InetAddress,
    private val peer: InetAddress,
    private val peerPort: Int,
    private val resources: Resources,
    private val silence: PhoneSilence,
    private val elapsed: () -> Long,
    private val nanoClock: () -> Long,
    wallClock: () -> Long,
) {
    private val sync = NtpSync(nanoClock(), wallClock())
    fun listen(): Int {
        val socket = resources.track(DatagramSocket(null).also { it.bind(InetSocketAddress(bindAddress, 0)) })
        resources.spawn("carplay-timing-rx") { receive(socket) }
        if (peerPort > 0) resources.spawn("carplay-timing-tx") { send(socket) }
        return socket.localPort
    }

    private fun receive(socket: DatagramSocket) {
        val buffer = ByteArray(RECEIVE_BYTES)
        val packet = DatagramPacket(buffer, buffer.size)
        while (true) {
            try { packet.length = buffer.size; socket.receive(packet) } catch (_: IOException) { return }
            val fromPeer = PeerGate.accepts(peer, packet.address)
            if (!fromPeer) continue
            silence.heard(elapsed())
            val bytes = buffer.copyOf(packet.length)
            when (NtpPacket.type(bytes)) {
                NtpPacket.PT_REQUEST -> {
                    val reply = synchronized(sync) { sync.answer(bytes, nanoClock()) } ?: continue
                    try { socket.send(DatagramPacket(reply, reply.size, packet.socketAddress)) } catch (_: IOException) { return }
                }
                NtpPacket.PT_RESPONSE -> synchronized(sync) { sync.onResponse(bytes, fromPeer, nanoClock()) }
            }
        }
    }

    private fun send(socket: DatagramSocket) {
        val destination = InetSocketAddress(peer, peerPort)
        try {
            while (true) {
                val request = synchronized(sync) { sync.nextRequest(nanoClock()) }
                try { socket.send(DatagramPacket(request, request.size, destination)) } catch (_: IOException) { return }
                Thread.sleep(REQUEST_INTERVAL_MS)
            }
        } catch (_: InterruptedException) {
            // close() interrupts the sender.
        }
    }

    private companion object {
        const val RECEIVE_BYTES = 2048
        const val REQUEST_INTERVAL_MS = 1000L
    }
}

/** The keep-alive port only listens: a datagram from the phone tells us it is there. */
internal class KeepAliveChannel(
    private val bindAddress: InetAddress,
    private val peer: InetAddress,
    private val resources: Resources,
    private val silence: PhoneSilence,
    private val elapsed: () -> Long,
) {
    fun listen(): Int {
        val socket = resources.track(DatagramSocket(null).also { it.bind(InetSocketAddress(bindAddress, 0)) })
        resources.spawn("carplay-keepalive") {
            val packet = DatagramPacket(ByteArray(512), 512)
            while (true) {
                try { packet.length = 512; socket.receive(packet) } catch (_: IOException) { return@spawn }
                if (PeerGate.accepts(peer, packet.address)) silence.heard(elapsed())
            }
        }
        return socket.localPort
    }
}
