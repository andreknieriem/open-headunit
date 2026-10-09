package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.unix

import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.HandshakeLink
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException

/**
 * Carrier that continuously monitors and connects to `/dev/socket/zj_bt_socket`.
 *
 * When the Unix domain socket is available and accepts a connection, this carrier
 * creates a [RawUnixHandshakeLink] and delegates to [serve] (i.e. `handleHandshake`).
 *
 * @param socketPath Path to the unix domain socket (default `/dev/socket/zj_bt_socket`).
 * @param serve Callback to handle the handshake link.
 * @param isRunning Predicate to determine if the carrier should continue running.
 * @param isFinishedForSession Predicate to check if listeners were closed for an active session.
 * @param isSessionConnected Predicate to check if AA is currently connected.
 * @param isHandshakeInFlight Predicate to check if a handshake is already active.
 * @param mayServeHandshake Predicate to check if handshakes are permitted (backoff policy).
 * @param onPhoneEvidence Callback when phone activity is detected to reset backoff.
 */
class RawUnixSocketCarrier(
    private val socketPath: String = DEFAULT_SOCKET_PATH,
    private val serve: suspend (HandshakeLink) -> Unit,
    private val isRunning: () -> Boolean,
    private val isFinishedForSession: () -> Boolean,
    private val isSessionConnected: () -> Boolean,
    private val isHandshakeInFlight: () -> Boolean,
    private val mayServeHandshake: () -> Boolean,
    private val onPhoneEvidence: () -> Unit,
    private val retryDelayMs: () -> Long = { DEFAULT_RETRY_INTERVAL_MS }
) {

    companion object {
        const val DEFAULT_SOCKET_PATH = "/dev/socket/zj_bt_socket"
        const val POLL_INTERVAL_MS = 2_000L
        const val DEFAULT_RETRY_INTERVAL_MS = 3_000L

        fun isSocketAvailable(path: String = DEFAULT_SOCKET_PATH): Boolean =
            try { File(path).exists() } catch (e: Exception) { false }
    }

    @Volatile
    private var stopped = false

    @Volatile
    private var activeSocket: LocalSocket? = null

    @Volatile
    private var carrierJob: Job? = null

    private var attempts = 0

    suspend fun run() {
        carrierJob = currentCoroutineContext()[Job]
        AppLog.i("NativeAA: [RawUnix] Carrier started, watching $socketPath")

        var socketMissingLogged = false

        while (keepRunning()) {
            val socketFile = File(socketPath)
            if (!socketFile.exists()) {
                if (!socketMissingLogged) {
                    AppLog.w("NativeAA: [RawUnix] Socket $socketPath does not exist yet. Waiting...")
                    socketMissingLogged = true
                }
                delay(POLL_INTERVAL_MS)
                continue
            }
            socketMissingLogged = false

            if (isSessionConnected() || isHandshakeInFlight() || !mayServeHandshake()) {
                delay(POLL_INTERVAL_MS)
                continue
            }

            var socket: LocalSocket? = null
            try {
                socket = LocalSocket()
                val address = LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM)
                socket.connect(address)
                activeSocket = socket
                attempts++
                AppLog.i("NativeAA: [RawUnix] Connected to $socketPath (attempt #$attempts)")
                onPhoneEvidence()

                val link = RawUnixHandshakeLink(socket)
                try {
                    serve(link)
                } finally {
                    link.close()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // Connection refused or broken pipe
                AppLog.d("NativeAA: [RawUnix] Socket connect/serve IO error: ${e.message}")
            } catch (e: Exception) {
                AppLog.w("NativeAA: [RawUnix] Unexpected error in handshake: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                runCatching { socket?.close() }
                activeSocket = null
            }

            delay(retryDelayMs())
        }

        AppLog.i("NativeAA: [RawUnix] Carrier stopped after $attempts attempts.")
    }

    fun close() {
        stopped = true
        runCatching { activeSocket?.close() }
    }

    private fun keepRunning(): Boolean =
        !stopped && isRunning() && !isFinishedForSession() && carrierJob?.isActive != false
}
