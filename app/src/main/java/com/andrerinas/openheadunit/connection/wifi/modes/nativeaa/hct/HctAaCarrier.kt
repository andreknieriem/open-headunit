package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.hct

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import com.andrerinas.openheadunit.aap.protocol.proto.Wireless
import com.andrerinas.openheadunit.connection.ConnectionAdmission
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.ExternalModuleCarrier
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.HandshakeLink
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.bluetooth.BluetoothPeerInventory
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.SequenceInputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/**
 * The HCT service accepts the phone's AA RFCOMM connection; goc_rfcom carries its bytes.
 * In the inspected AVIN A10 and related HCT QCM6125 A13 fscsdkbw123 binaries, FIFO13 carries
 * phone input to this socket, and FIFO14 carries socket output to aap_send_data_packet().
 * This route uses neither the module UART nor Zlink start/disable commands.
 *
 * This is a shared, single-client socket, not an ownership API. A10 queues behind an existing
 * client; A13 replaces it. Open only for one actual handoff after a fresh AA event, never as
 * discovery, and do not repeatedly reclaim it if another client takes it back. These firmware
 * contracts do not establish socket/SELinux access on every HCT build.
 */
@RequiresApi(21)
internal class HctAaCarrier(
    private val context: Context,
    private val openingRequest: () -> Wireless.WifiVersionRequest,
    private val serve: suspend (HandshakeLink) -> Unit,
    private val mayServe: () -> Boolean,
    private val acceptPeer: (String) -> Boolean,
    private val onPhoneEvidence: () -> Unit,
    private val onUnavailable: (HctWakePolicy.Result?, () -> Boolean) -> Unit,
    private val admission: ConnectionAdmission,
) : ExternalModuleCarrier {
    @Volatile private var stopped = false
    private val active = AtomicReference<LocalSocket?>()
    private val wakes = Channel<Pair<HctWakeAttempts.Ticket, ConnectionAdmission>>(Channel.CONFLATED)
    private val attempts = HctWakeAttempts(HctPendingReconnect.process)
    @Volatile private var observer: HctServiceObserver? = null

    // Automatic credential refresh must not repeatedly reconnect a manufacturer's HFP profile.
    override val sendsWake = false
    override fun requestWake(userAsked: Boolean, admission: ConnectionAdmission) {
            if (!this.admission.run {} || !admission.run {}) return
        AppLog.i("NativeAA: [HCT] waiting for a fresh manufacturer AA connection")
    }

    /** Explicit phone selection authorizes one OEM disconnect/reconnect, including this phone. */
    fun requestWakeFor(mac: String, allowReplacePending: Boolean = false, admission: ConnectionAdmission, selected: () -> Unit = {}): Boolean {
        if (stopped || !this.admission.run {} || !admission.run {}) return false
        val target = BluetoothPeerInventory.normalizeAddress(mac) ?: return false
        val now = SystemClock.elapsedRealtime()
        var committed = false
        val held = attempts.selectAndEnqueue(target, now, { selected(); committed = true }, allowReplacePending) { wakes.trySend(it to admission).isSuccess }
        if (held != null) {
            AppLog.i("NativeAA: [HCT] selected-phone wake held: $held")
        }
        return committed
    }

    private fun reportUnavailable(result: HctWakePolicy.Result?, ticket: HctWakeAttempts.Ticket? = null) {
        val current = attempts.failureIsCurrent(ticket)
        onUnavailable(result) { !stopped && current() }
    }

    suspend fun run() = coroutineScope {
        if (stopped || !admission.run {}) return@coroutineScope
        val status = HctServiceObserver(context,
            onAaReport = { attempts.reported(SystemClock.elapsedRealtime()) },
            onInvalidated = { closeSocket() }, effectAdmission = admission)
        observer = status
        try {
            withContext(Dispatchers.Main.immediate) { if (!stopped) status.start() }
            if (stopped) return@coroutineScope
            // A timeout here is a CancellationException and would end the stack with no reason logged.
            withTimeoutOrNull(3_000L) { status.awaitService() }
                ?: throw IOException("Bluetooth status service did not connect within 3 s")
            val wakeJob = launch(Dispatchers.IO) {
                for ((ticket, commandAdmission) in wakes) {
                    try {
                        if (stopped || !admission.run {} || !commandAdmission.run {}) continue
                        when (attempts.prepare(ticket, mayServe)) {
                            HctWakeAttempts.Preparation.SUPERSEDED -> continue
                            HctWakeAttempts.Preparation.NOT_READY -> {
                                AppLog.i("NativeAA: [HCT] wake #${ticket.id} not sent: local connection state changed")
                                reportUnavailable(HctWakePolicy.Result.LOCAL_NOT_READY, ticket)
                                continue
                            }
                            HctWakeAttempts.Preparation.READY -> Unit
                        }
                        val result = status.requestWake(ticket.target, allowReconnect = true, commandAdmission = commandAdmission) {
                            attempts.claim(ticket, SystemClock.elapsedRealtime(), mayServe)
                        }
                        currentCoroutineContext().ensureActive()
                        if (stopped) break
                        if (!commandAdmission.run {}) continue
                        AppLog.i("NativeAA: [HCT] selected-phone wake #${ticket.id}: $result; " +
                            "submitted=${attempts.outstanding(ticket)}; ${status.observationSummary()}")
                        // A fresh AA arrival may have superseded the unsent wake; that handoff
                        // owns its outcome. A local timeout after IPC is not a retry permission.
                        if (result == HctWakePolicy.Result.STATE_CHANGED) {
                            // Binder status can change without a new AA event. Only a replaced
                            // ticket has another handoff responsible for its outcome.
                            if (attempts.prepare(ticket, mayServe) != HctWakeAttempts.Preparation.SUPERSEDED)
                                reportUnavailable(HctWakePolicy.Result.LOCAL_NOT_READY, ticket)
                        } else if (result != HctWakePolicy.Result.REQUESTED &&
                            result != HctWakePolicy.Result.SUBMISSION_UNCONFIRMED) reportUnavailable(result, ticket)
                        if (attempts.outstanding(ticket)) launch {
                            delay(WAKE_OBSERVATION_MS)
                            if (commandAdmission.run {} && attempts.outstanding(ticket)) {
                                AppLog.w("NativeAA: [HCT] wake #${ticket.id} has no matching WPP after 30 s; " +
                                    "no reconnect repeated; ${status.observationSummary()}")
                                if (attempts.waitingForResult(ticket) && mayServe())
                                    reportUnavailable(HctWakePolicy.Result.WAKE_UNCONFIRMED, ticket)
                            }
                        }
                    } finally {
                        attempts.finishUnsent(ticket)
                    }
                }
            }
            try {
                AppLog.i("NativeAA: [HCT] observing AA reports; no local socket opened yet")
                for (ticket in status.arrivals) {
                    if (stopped || !isActive || !admission.run {}) break
                    // A selection dialog can briefly hold the handoff. Retain the live event
                    // within its original deadline instead of losing it on the first poll.
                    while (!stopped && isActive && admission.run {} && !mayServe() &&
                        status.admission.fresh(ticket, SystemClock.elapsedRealtime())) delay(50)
                    if (stopped || !admission.run {} || !mayServe() || !status.admission.fresh(ticket, SystemClock.elapsedRealtime())) {
                        status.admission.retireUnused(ticket)
                        continue
                    }
                    // Reserve opening before the first Binder read: activePeer is not known yet.
                    if (!attempts.beginOpening()) continue
                    val failureCurrent = attempts.openingFailure { status.admission.current(ticket) }
                    try {
                        if (failureCurrent == null) continue
                        status.admission.handoff(ticket) { socketAttempted ->
                            serveOnce(status, ticket, socketAttempted)
                        }
                    } catch (error: CancellationException) {
                        if (stopped || !isActive) throw error
                        if (failureCurrent != null) onUnavailable(null) { !stopped && failureCurrent() }
                    } catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        if (stopped) break
                        AppLog.w("NativeAA: [HCT] handoff stopped: ${error.message}; no automatic socket retry")
                        if (failureCurrent != null) onUnavailable(null) { !stopped && failureCurrent() }
                    } finally {
                        closeSocket()
                        attempts.endOpening()
                    }
                }
            } finally {
                wakeJob.cancel()
                wakes.close()
            }
        } finally {
            closeSocket()
            withContext(NonCancellable + Dispatchers.Main.immediate) { status.close() }
            observer = null
        }
    }

    private suspend fun serveOnce(
        status: HctServiceObserver,
        ticket: HctAdmission.Ticket,
        onSocketAttempt: () -> Unit
    ) = coroutineScope {
        val mac = status.phone(ticket)
        if (!acceptPeer(mac)) throw IOException("HCT: phone was not selected for this handoff")
        status.watchAddress(mac)
        val socket = LocalSocket()
        try {
            // Create the descriptor before publishing it so stop/invalidation can close it.
            socket.inputStream
            active.set(socket)
            checkCurrent(status, ticket, mac)
            if (!status.admission.fresh(ticket, SystemClock.elapsedRealtime())) {
                throw IOException("HCT: AA event expired before socket handoff")
            }
            // connect(endpoint, timeout) is unimplemented in AOSP. Use nonblocking connect
            // where fcntlInt is public, and a bounded single blocking attach on older releases.
            val address = LocalSocketAddress("goc_rfcom", LocalSocketAddress.Namespace.RESERVED)
            if (Build.VERSION.SDK_INT >= 30) {
                connectNonblocking(socket, address, onSocketAttempt)
            } else {
                // Android 5.0 LocalSocketImpl JNI and later Java implementations set both
                // SO_RCVTIMEO and SO_SNDTIMEO via this public setter. Linux AF_UNIX
                // unix_stream_connect uses sock_sndtimeo while the listen backlog is full.
                // A coroutine timeout or close alone cannot bound a blocked native connect.
                socket.soTimeout = CONNECT_TIMEOUT_MS
                if (!admission.run {}) throw IOException("HCT: connection authority withdrawn before socket attach")
                onSocketAttempt()
                socket.connect(address)
            }
            checkCurrent(status, ticket, mac)
            if (socket.peerCredentials.uid !in 0 until android.os.Process.FIRST_APPLICATION_UID) {
                throw IOException("HCT: local AA socket is not owned by a system daemon")
            }
            // Retain a finite timeout through the preamble write on the older API path.
            // Coroutine cancellation alone does not stop blocking stream IO.
            val watchdog = launch(Dispatchers.Default) {
                delay(OPENING_TIMEOUT_MS)
                HctLocalSocket.close(socket)
            }
            val opening = try {
                checkCurrent(status, ticket, mac)
                if (status.phone(ticket) != mac) throw IOException("HCT: peer changed before handoff")
                val ack = status.expectAddressAck()
                // The daemon treats its first read as a twelve-byte MAC, with no length prefix.
                // Keep this write separate. SPC2 is only a consumption hint (also used by iAP),
                // so WPP must still be validated before delivering Wi-Fi credentials.
                socket.outputStream.write(mac.replace(":", "").toByteArray(Charsets.US_ASCII))
                socket.outputStream.flush()
                HctAddressAck.await(ack, { stopped })
                checkCurrent(status, ticket, mac)
                HctOpening.exchange(
                    socket.inputStream, socket.outputStream, openingRequest(),
                    SystemClock.elapsedRealtime() + 3_000L,
                    SystemClock::elapsedRealtime, socket::setSoTimeout
                ).also {
                    checkCurrent(status, ticket, mac)
                    if (status.phone(ticket) != mac) throw IOException("HCT: peer changed during opening")
                }
            } finally {
                watchdog.cancel()
            }
            checkCurrent(status, ticket, mac)
            socket.soTimeout = 0
            attempts.accepted(mac)
            onPhoneEvidence()
            AppLog.i("NativeAA: [HCT] WPP 1.7 accepted; continuing Wi-Fi handoff")
            val link = object : HandshakeLink {
                // SequenceInputStream closes its sources on EOF. Route that implicit close
                // through shutdown too, so it cannot strand a concurrent native write.
                override val input = SequenceInputStream(ByteArrayInputStream(opening),
                    HctLocalSocket.input(socket))
                override val output = socket.outputStream
                override val peerName: String? = null
                override val peerAddress = mac
                override val radioLabel = "HCT local AA"
                override val persistPeerForAutoStart = false
                override val peerReportedPresent = true
                override val retransmitsWhileSilent = false
                override val versionRequestAlreadySent = true
                override fun close() { HctLocalSocket.close(socket) }
            }
            serve(link)
        } finally {
            active.compareAndSet(socket, null)
            HctLocalSocket.close(socket)
        }
    }

    @RequiresApi(30)
    private fun connectNonblocking(
        socket: LocalSocket,
        address: LocalSocketAddress,
        onSocketAttempt: () -> Unit,
    ) {
        if (!admission.run {}) throw IOException("HCT: connection authority withdrawn before socket attach")
        val fd = socket.fileDescriptor
        val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
        Os.fcntlInt(fd, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
        // Mark before connect: even a failed attach may have displaced another client.
        onSocketAttempt()
        socket.connect(address)
        Os.fcntlInt(fd, OsConstants.F_SETFL, flags)
    }

    private fun checkCurrent(status: HctServiceObserver, ticket: HctAdmission.Ticket, mac: String) {
        if (stopped || !admission.run {} || !mayServe() || !status.admission.current(ticket) ||
            !acceptPeer(mac)) {
            throw IOException("HCT: AA handoff is no longer current")
        }
    }

    private fun closeSocket() { active.getAndSet(null)?.let(HctLocalSocket::close) }

    override fun close() {
        stopped = true
        attempts.close()
        closeSocket()
        wakes.close()
        observer?.close()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 1_000
        private const val OPENING_TIMEOUT_MS = 5_000L
        // Observation deadline only; it does not establish OEM connector completion.
        private const val WAKE_OBSERVATION_MS = HctPendingReconnect.OBSERVATION_MS
    }
}
