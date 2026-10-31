package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.blink

import com.andrerinas.openheadunit.connection.ConnectionAdmission
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.ExternalModuleCarrier
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.HandshakeLink
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs the Android Auto handshake over FYT's BLINK module through `blink`'s `/dev/auto_serial`.
 *
 * On FYT UIS7862 units with DUDUOS the phone pairs with the external module ("DUDUAUTO") for calls,
 * and the module, not this unit's own radio, owns the Android Auto RFCOMM service. `blink` relays
 * that channel line by line (see [BlinkAutoLine]). This takes the place the stock
 * `com.syu.carlink` holds, so the phone keeps calls on the module and the car's speakers.
 *
 * Sequence as captured from the stock client: the phone's hands-free link comes up (`JH`), `blink`
 * arms the Android Auto service by itself, the phone opens it (`AA`), and the head unit speaks
 * first. Nothing has to be sent to `blink` to arm it.
 *
 * After a handshake the phone keeps pinging over this channel for as long as the session lives, and
 * the stock client answers; [BlinkAutoLine.idleReply] does the same while no handshake owns it.
 */
class BlinkAaCarrier(
    private val serve: suspend (HandshakeLink) -> Unit,
    private val isRunning: () -> Boolean,
    private val isFinishedForSession: () -> Boolean,
    private val mayServeHandshake: () -> Boolean,
    private val onPhoneEvidence: () -> Unit,
    private val admission: ConnectionAdmission,
    /** Called with the reason whenever it changes, and with null once the port is open again. */
    private val onRefusalChanged: (BlinkRefusal?) -> Unit = {},
    /** Whether the stock client is out of the way, read without root before every pass. */
    private val stockClient: () -> StockCarLink.State = { StockCarLink.State.UNKNOWN },
    private val openDirect: (onLine: (String) -> Unit, onEnded: (String) -> Unit) -> BlinkAutoSerialChannel =
        { onLine, onEnded -> BlinkAutoSerialChannel.openDirect(onLine, onEnded, admission) },
    private val openRoot: (onLine: (String) -> Unit, onEnded: (String) -> Unit) -> BlinkAutoSerialChannel =
        { onLine, onEnded -> BlinkAutoSerialChannel.open(onLine, onEnded, admission) },
    private val now: () -> Long = { System.currentTimeMillis() },
) : ExternalModuleCarrier {

    companion object {
        /** How long to wait before starting the bridge again after an open bridge ended. */
        const val REOPEN_DELAY_MS = 5_000L

        /** How often the run loop looks at the flags the reader thread sets. */
        private const val POLL_MS = 200L

        /** How long the copy loop run as this app gets to open the port before root is tried. */
        private const val DIRECT_OPEN_TIMEOUT_MS = 3_000L

        /** How long that copy loop gets to exit by itself once its stdin is closed. */
        private const val DIRECT_CLOSE_TIMEOUT_MS = 3_000L
    }

    @Volatile private var channel: BlinkAutoSerialChannel? = null
    @Volatile private var current: BlinkAutoSerialChannel.FrameStream? = null
    private val channelOpenedAt = AtomicLong(0L)
    @Volatile private var phoneAddress: String? = null
    @Volatile private var stopped = false
    @Volatile private var carrierJob: Job? = null

    private var attempts = 0
    private var framesIn = 0

    @Volatile private var refusal: BlinkRefusal? = null
    private var consecutiveRefusals = 0
    @Volatile private var wakeHintLogged = false
    private var directFailure: String? = null

    /** The module wakes the phone itself; nothing is sent. */
    override val sendsWake: Boolean get() = false

    suspend fun run() {
        carrierJob = currentCoroutineContext()[Job]
        while (effectCurrent()) {
            val ended = AtomicReference<String?>(null)
            val opened = openChannel { why -> ended.set(why) } ?: continue
            if (!effectCurrent()) { opened.close(); break }
            channel = opened
            var announced = false
            try {
                while (effectCurrent() && !opened.isFinished) {
                    if (!announced && opened.portOpened) {
                        announced = true
                        markOpen()
                    }
                    // After a handoff the bridge stays up to answer the phone's pings, which the
                    // stock client does for the whole session, but no new handshake starts on it.
                    if (channelOpenedAt.get() != 0L && isFinishedForSession()) {
                        channelOpenedAt.set(0L)
                        AppLog.i("NativeAA: [BLINK] the phone reopened Android Auto while a session is up; not starting another handshake.")
                    } else if (channelOpenedAt.get() != 0L && mayServeHandshake()) {
                        // getAndSet, so an AA that lands between a read and a reset is not lost.
                        if (channelOpenedAt.getAndSet(0L) != 0L) serveOnce(opened)
                    } else {
                        delay(POLL_MS)
                    }
                }
            } finally {
                current?.close()
                current = null
                opened.close()
                if (channel === opened) channel = null
            }
            if (!keepRunning()) break
            if (opened.portOpened) {
                AppLog.i("NativeAA: [BLINK] bridge ended: ${ended.get() ?: "closed"}")
                delay(REOPEN_DELAY_MS)
            } else {
                // Ended before it opened the port: the guard in the bridge refused it.
                refuse(
                    BlinkAutoSerialChannel.refusalFor(opened.exitCode, opened.scriptSpoke),
                    "${ended.get() ?: "the bridge ended"}, exit ${opened.exitCode}"
                )
            }
        }
        AppLog.i("NativeAA: [BLINK] carrier stopped after $attempts handshake(s), $framesIn frame(s) from the phone.")
    }

    /**
     * Opens the port as this app when the stock client is known to be out of the way, the way the
     * stock client itself does, and falls back to the root bridge otherwise. At most one `su` per
     * pass: the bridge checks the stock client itself, and its exit code says which check refused it.
     *
     * @return the channel, or null after waiting out a refusal
     */
    private suspend fun openChannel(onEnded: (String) -> Unit): BlinkAutoSerialChannel? {
        if (!effectCurrent()) return null
        if (directStuck) {
            refuse(BlinkRefusal.BRIDGE_FAILED, "a no-root channel did not exit earlier")
            return null
        }
        val stock = stockClient()
        if (stock == StockCarLink.State.ENABLED) {
            // Refused without su: nothing root could do would make the port safe to share.
            refuse(BlinkRefusal.STOCK_CLIENT_ENABLED, "${StockCarLink.PACKAGE} is enabled")
            return null
        }
        // Anything the package manager cannot vouch for goes to the bridge, whose guard asks with root.
        if (stock.allowsDirectOpen) {
            try {
                openDirectly(onEnded)?.let { return it }
            } catch (_: DirectChannelStuck) {
                return null
            }
        } else if (stock != lastStockState) {
            AppLog.i("NativeAA: [BLINK] stock client state $stock; using the root bridge, which checks it.")
        }
        lastStockState = stock
        // Package-manager observation and no-root open/retirement can both wait. Carry the
        // constructor's original arm all the way to the next shell effect.
        if (!effectCurrent()) return null
        return try {
            openRoot(::onLine, onEnded)
        } catch (e: Exception) {
            refuse(BlinkRefusal.ROOT_DENIED, "could not run su: ${e.message}")
            null
        }
    }

    private var lastStockState: StockCarLink.State? = null

    /** Set once a no-root shell would not exit; nothing more is opened until the carrier restarts. */
    private var directStuck = false

    /**
     * Tries the copy loop as this app. It either opens the port or ends early, when the node's
     * mode or SELinux keeps this app out; a loop that does neither in time is given up on too.
     *
     * @return the open channel, or null to fall back to root
     */
    private suspend fun openDirectly(onEnded: (String) -> Unit): BlinkAutoSerialChannel? {
        if (!effectCurrent()) return null
        val ended = AtomicReference<String?>(null)
        val direct = try {
            openDirect(::onLine) { why -> ended.set(why); onEnded(why) }
        } catch (e: Exception) {
            noteDirectFailure("could not run sh: ${e.message}")
            return null
        }
        var transferred = false
        var retired = false
        try {
            var waited = 0L
            while (effectCurrent() && !direct.portOpened && !direct.isFinished && waited < DIRECT_OPEN_TIMEOUT_MS) {
                delay(POLL_MS)
                waited += POLL_MS
            }
            if (effectCurrent() && direct.portOpened && !direct.isFinished) {
                AppLog.i("NativeAA: [BLINK] opened ${BlinkAutoSerialChannel.PORT} without root.")
                directFailure = null
                transferred = true
                return direct
            }
            val why = if (direct.isFinished) "${ended.get() ?: "the channel ended"}, exit ${direct.exitCode}"
                else "no answer within ${DIRECT_OPEN_TIMEOUT_MS} ms"
            // Before root opens the port, this shell must be gone and its trap run: otherwise its
            // reader could take the phone's lines, or its mode restore undo the root bridge's raw mode.
            val exitedCleanly = withContext(NonCancellable + Dispatchers.IO) {
                direct.closeAndAwait(DIRECT_CLOSE_TIMEOUT_MS)
            }
            retired = true
            if (!exitedCleanly) {
                // Its reader may still be on the port, where neither another try nor root can help.
                directStuck = true
                refuse(BlinkRefusal.BRIDGE_FAILED, "$why; the no-root channel did not exit when asked, restart the unit")
                throw DirectChannelStuck()
            }
            noteDirectFailure(why)
            return null
        } finally {
            // Cancellation while waiting for the first port acknowledgement still owns this shell.
            if (!transferred && !retired) withContext(NonCancellable + Dispatchers.IO) {
                direct.closeAndAwait(DIRECT_CLOSE_TIMEOUT_MS)
            }
        }
    }

    /** The no-root shell would not exit, so root is not tried this pass. */
    private class DirectChannelStuck : Exception()

    private fun noteDirectFailure(why: String) {
        if (why == directFailure) return
        directFailure = why
        AppLog.i("NativeAA: [BLINK] cannot open ${BlinkAutoSerialChannel.PORT} without root ($why); trying the root bridge.")
    }

    /** Logs a refusal once per distinct reason and waits longer each time it repeats. */
    private suspend fun refuse(reason: BlinkRefusal, detail: String) {
        if (refusal != reason) {
            refusal = reason
            AppLog.e("NativeAA: [BLINK] not opening ${BlinkAutoSerialChannel.PORT}: $reason ($detail).")
            onRefusalChanged(reason)
        }
        val wait = BlinkRefusalBackoff.delayMs(consecutiveRefusals++)
        delay(wait)
    }

    private fun markOpen() {
        AppLog.i("NativeAA: [BLINK] listening on ${BlinkAutoSerialChannel.PORT} for the phone's Android Auto channel.")
        consecutiveRefusals = 0
        if (refusal != null) {
            refusal = null
            onRefusalChanged(null)
        }
    }

    private suspend fun serveOnce(open: BlinkAutoSerialChannel) {
        if (!effectCurrent()) return
        attempts++
        val stream = BlinkAutoSerialChannel.FrameStream(open::sendFrame)
        current = stream
        AppLog.i(
            "NativeAA: [BLINK] the phone${phoneAddress?.let { " ($it)" } ?: ""} opened Android Auto " +
                "on the module — handshake #$attempts."
        )
        try {
            serve(BlinkLink(stream, phoneAddress))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("NativeAA: [BLINK] handshake ended: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            stream.close()
            if (current === stream) current = null
            wakeHintLogged = false
        }
    }

    /** Reader thread. Routes a line; never blocks on the handshake. */
    private fun onLine(line: String) {
        if (!admission.run {}) return
        when (val event = BlinkAutoLine.parse(line)) {
            is BlinkAutoLine.Event.PhoneLinked -> {
                phoneAddress = event.address
                AppLog.i("NativeAA: [BLINK] hands-free link up to ${event.address}.")
            }
            BlinkAutoLine.Event.ChannelOpened -> {
                AppLog.i("NativeAA: [BLINK] the phone opened the Android Auto channel.")
                // A new channel supersedes whatever handshake was waiting on the old one.
                current?.close()
                channelOpenedAt.set(now())
                onPhoneEvidence()
            }
            BlinkAutoLine.Event.ChannelClosed -> {
                AppLog.i("NativeAA: [BLINK] the phone closed the Android Auto channel.")
                channelOpenedAt.set(0L)
                current?.close()
            }
            is BlinkAutoLine.Event.Frame -> {
                framesIn++
                val live = current
                if (live != null && !live.closed) {
                    live.deliver(event.bytes)
                    return
                }
                val reply = BlinkAutoLine.idleReply(event.bytes)
                if (reply != null) {
                    runCatching { channel?.sendFrame(reply) }
                        .onFailure { AppLog.w("NativeAA: [BLINK] idle reply failed: ${it.message}") }
                } else {
                    AppLog.d("NativeAA: [BLINK] [RX] unowned frame ${BlinkAutoLine.toHex(event.bytes)}")
                }
            }
            is BlinkAutoLine.Event.Other -> AppLog.d("NativeAA: [BLINK] [RX] ${event.line}")
        }
    }

    /**
     * `blink` arms the Android Auto service itself once the phone's hands-free link is up, so
     * nothing is sent. Called on every credential delivery and resume, so the hint is logged once
     * per arming.
     */
    override fun requestWake(userAsked: Boolean, admission: ConnectionAdmission) {
        if (!this.admission.run {} || !admission.run {}) return
        if (wakeHintLogged) return
        wakeHintLogged = true
        AppLog.i(
            "NativeAA: [BLINK] nothing to send for a wake — the module opens Android Auto on its own " +
                "once the phone connects to it for calls."
        )
    }

    override fun close() {
        stopped = true
        current?.close()
        channel?.close()
    }

    /** Unlike the ZBT carrier, a finished session does not end this: the pings still need answers. */
    private fun keepRunning(): Boolean =
        !stopped && isRunning() && carrierJob?.isActive != false

    private fun effectCurrent(): Boolean = keepRunning() && admission.run {}
}

/** One handshake over the BLINK bridge. */
class BlinkLink(
    private val stream: BlinkAutoSerialChannel.FrameStream,
    override val peerAddress: String?
) : HandshakeLink {
    override val input: InputStream get() = stream.input
    override val output: OutputStream get() = stream.output
    override val peerName: String? = null
    override val radioLabel: String = "FYT external Bluetooth module (${BlinkAutoSerialChannel.PORT})"

    /** The phone is bonded to the module, not to anything `android.bluetooth` can dial. */
    override val persistPeerForAutoStart: Boolean = false

    /** `AA` is the phone opening the channel, so it is known to be there. */
    override val peerReportedPresent: Boolean = true

    /** The channel is open before we speak, as on the unit's own radio. */
    override val retransmitsWhileSilent: Boolean = false

    /** Ends this handshake's view only; the bridge stays up for the next one and for pings. */
    override fun close() {
        stream.close()
    }
}
