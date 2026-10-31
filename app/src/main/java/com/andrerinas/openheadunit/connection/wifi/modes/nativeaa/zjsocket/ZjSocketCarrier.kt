package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zjsocket

import android.net.LocalSocket
import android.os.SystemClock
import com.andrerinas.openheadunit.connection.ConnectionAdmission
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.ExternalModuleCarrier
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.HandshakeLink
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.hct.HctLocalSocket
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ExternalBtPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File

/**
 * Dials the ZJ daemon's socket and serves each link until the session ends; it sends no wake.
 * It stops only when the manager does. [onPhoneEvidence] runs at each connect so silent
 * attempts in an empty car never reach the handshake hard stop.
 */
class ZjSocketCarrier(
    private val socketPath: String = ExternalBtPolicy.ZJ_SOCKET_PATHS.first(),
    private val serve: suspend (HandshakeLink) -> Unit,
    private val isRunning: () -> Boolean,
    private val isSessionConnected: () -> Boolean,
    private val isHandshakeInFlight: () -> Boolean,
    private val isSettling: () -> Boolean,
    private val mayServeHandshake: () -> Boolean,
    private val onPhoneEvidence: () -> Unit,
    private val servedRefusals: () -> Int,
    private val admission: ConnectionAdmission,
) : ExternalModuleCarrier {

    companion object {
        const val POLL_INTERVAL_MS = 2_000L
        private const val WAKE_CHECK_MS = 250L
    }

    @Volatile private var stopped = false
    @Volatile private var activeSocket: LocalSocket? = null
    @Volatile private var carrierJob: Job? = null
    @Volatile private var dialNow = false
    @Volatile private var wakeNoteLogged = false

    private var attempts = 0
    private var refusedConnects = 0

    /** The daemon wakes the phone on its own; nothing is sent. */
    override val sendsWake: Boolean get() = false

    override fun requestWake(userAsked: Boolean, admission: ConnectionAdmission) {
        if (!this.admission.run {} || !admission.run {}) return
        if (!wakeNoteLogged) {
            wakeNoteLogged = true
            AppLog.i("NativeAA: [ZJ] this route sends no wake; the module brings the phone's link up by itself.")
        }
        if (userAsked) dialNow = true
    }

    suspend fun run() {
        carrierJob = currentCoroutineContext()[Job]
        AppLog.i("NativeAA: [ZJ] Carrier started, watching $socketPath")
        try {
            serveUntilStopped()
        } finally {
            AppLog.i("NativeAA: [ZJ] Carrier stopped after $attempts attempts.")
        }
    }

    private suspend fun serveUntilStopped() {
        var socketMissingLogged = false
        while (true) {
            when (ZjSocketPolicy.decide(running(), isSessionConnected(), isHandshakeInFlight(), isSettling(), mayServeHandshake())) {
                ZjSocketPolicy.Action.STOP -> break
                ZjSocketPolicy.Action.WAIT -> { delay(POLL_INTERVAL_MS); continue }
                ZjSocketPolicy.Action.DIAL -> Unit
            }
            dialNow = false
            if (!File(socketPath).exists()) {
                if (!socketMissingLogged) {
                    AppLog.w("NativeAA: [ZJ] Socket $socketPath does not exist yet. Waiting...")
                    socketMissingLogged = true
                }
                delay(POLL_INTERVAL_MS)
                continue
            }
            socketMissingLogged = false

            val attempt = ZjSocketConnector.connect(socketPath, admission)
            val socket = attempt.socket
            if (!running()) {
                socket?.let { HctLocalSocket.close(it) }
                break
            }
            if (socket == null) {
                refusedConnects++
                val gap = ZjSocketPolicy.retryDelayMs(afterServed = false, count = refusedConnects)
                val line = "NativeAA: [ZJ] connect to $socketPath failed (${attempt.outcome}: ${attempt.detail}); next try in ${gap}ms"
                if (refusedConnects == 1) AppLog.i(line) else AppLog.d(line)
                pause(gap)
                continue
            }
            refusedConnects = 0
            attempts++
            activeSocket = socket
            AppLog.i("NativeAA: [ZJ] Connected to $socketPath (attempt #$attempts)")
            onPhoneEvidence()
            try {
                serve(ZjSocketHandshakeLink(socket))
            } catch (e: CancellationException) {
                // A session-end rearm cancels the handshake's own job, not this one.
                if (ZjSocketPolicy.cancellationEndsCarrier(stopped, currentCoroutineContext().isActive)) throw e
                AppLog.i("NativeAA: [ZJ] handshake was cancelled; the carrier keeps watching for the next drive.")
            } catch (e: Exception) {
                AppLog.w("NativeAA: [ZJ] handshake ended with ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                HctLocalSocket.close(socket)
                if (activeSocket === socket) activeSocket = null
            }
            pause(ZjSocketPolicy.retryDelayMs(afterServed = true, count = servedRefusals()))
        }
    }

    override fun close() {
        stopped = true
        activeSocket?.let { HctLocalSocket.close(it) }
    }

    private fun running(): Boolean =
        !stopped && isRunning() && carrierJob?.isActive != false && admission.run {}

    /** Waits [ms], or less when the WiFi button asked for a dial now or the carrier stopped. */
    private suspend fun pause(ms: Long) {
        val until = SystemClock.elapsedRealtime() + ms
        while (running() && !dialNow && SystemClock.elapsedRealtime() < until) {
            delay(minOf(WAKE_CHECK_MS, maxOf(1L, until - SystemClock.elapsedRealtime())))
        }
    }
}
