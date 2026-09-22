package com.andrerinas.openheadunit.secondscreen.network

import com.andrerinas.openheadunit.secondscreen.EncodedOutput
import com.andrerinas.openheadunit.secondscreen.H264Units
import com.andrerinas.openheadunit.secondscreen.SecondScreenOutputPolicy
import com.andrerinas.openheadunit.secondscreen.StreamBacklog
import com.andrerinas.openheadunit.utils.AppLog
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Serves the second stream as raw Annex-B H.264 over TCP, to any number of receivers.
 *
 * Adapted from moriceh/open-headunit's ClusterVideoStreamer (AGPLv3). Each receiver has its own
 * writer thread and a bounded [StreamBacklog], so a slow one never holds up the video lane's acks.
 */
class NetworkStreamOutput(private val port: Int) : EncodedOutput {

    override val output = SecondScreenOutputPolicy.Output.NETWORK
    override var onKeyframeNeeded: (() -> Unit)? = null

    @Volatile private var server: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Client>()

    /** The latest SPS and PPS, replayed to each receiver before its first frame. */
    @Volatile private var parameterSets: ByteArray? = null

    override fun start() {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port))
        server = socket
        Thread({ acceptLoop(socket) }, "SecondScreen:NetworkAccept").apply { isDaemon = true }.start()
        AppLog.i("SecondScreen: network stream listening on port $port")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val accepted = try {
                socket.accept()
            } catch (e: Exception) {
                if (!socket.isClosed) AppLog.w("SecondScreen: network accept failed: ${e.message}")
                continue
            }
            val client = Client(accepted)
            clients.add(client)
            client.start()
        }
    }

    override fun onAccessUnit(buf: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        val config = H264Units.parameterSets(buf, offset, length)
        if (config != null) parameterSets = config
        if (clients.isEmpty()) return
        val chunk = StreamBacklog.Chunk(
            buf.copyOfRange(offset, offset + length), H264Units.isKeyframe(buf, offset, length), config != null,
        )
        for (client in clients) client.offer(chunk)
    }

    override fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        for (client in clients) client.close()
        clients.clear()
        parameterSets = null
        AppLog.i("SecondScreen: network stream stopped")
    }

    private inner class Client(private val socket: Socket) {
        private val lock = Object()
        private val backlog = StreamBacklog(NetworkStreamPolicy.MAX_QUEUED_UNITS, NetworkStreamPolicy.MAX_QUEUED_BYTES)
        @Volatile private var closed = false
        private val peer = socket.remoteSocketAddress

        fun start() {
            Thread({ run() }, "SecondScreen:NetworkClient").apply { isDaemon = true }.start()
        }

        fun offer(chunk: StreamBacklog.Chunk) {
            val result = synchronized(lock) {
                backlog.offer(chunk).also { lock.notifyAll() }
            }
            if (result == StreamBacklog.Offer.OVERFLOWED) {
                AppLog.w("SecondScreen: network receiver $peer fell behind; resuming at the next keyframe")
                onKeyframeNeeded?.invoke()
            }
        }

        private fun run() {
            try {
                socket.tcpNoDelay = true
                val out = socket.getOutputStream()
                if (peekIsHttp()) out.write(NetworkStreamPolicy.HTTP_RESPONSE_HEADER)
                parameterSets?.let { out.write(it) }
                out.flush()
                AppLog.i("SecondScreen: network client connected from $peer (${clients.size} connected)")
                onKeyframeNeeded?.invoke()
                while (!closed) {
                    val chunk = synchronized(lock) {
                        while (!closed && backlog.size == 0) lock.wait()
                        backlog.poll()
                    } ?: continue
                    out.write(chunk.bytes)
                    out.flush()
                }
            } catch (e: Exception) {
                if (!closed) AppLog.i("SecondScreen: network client $peer left: ${e.message}")
            } finally {
                close()
                clients.remove(this)
            }
        }

        private fun peekIsHttp(): Boolean {
            socket.soTimeout = NetworkStreamPolicy.REQUEST_PEEK_MS
            val buf = ByteArray(512)
            val read = try { socket.getInputStream().read(buf) } catch (_: SocketTimeoutException) { 0 }
            socket.soTimeout = 0
            return NetworkStreamPolicy.isHttpRequest(buf, read)
        }

        fun close() {
            closed = true
            synchronized(lock) { lock.notifyAll() }
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
