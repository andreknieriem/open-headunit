package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.HandshakeLink
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import com.andrerinas.openheadunit.utils.BluetoothHelper

/**
 * Runs the Android Auto handshake over the head unit's external Bluetooth module.
 *
 * Replaces the accept loop, on units where there is nothing to accept. Our own RFCOMM listener is
 * what normally says a phone has arrived; here the vendor daemon owns the Android Auto server on
 * the module and accepts the phone itself, so this instead opens a channel to the daemon, watches
 * the link state it reports, and starts a handshake when [ZbtAttemptPolicy] says a phone is there.
 *
 * The handshake above it is the same one, unchanged, reached through [HandshakeLink].
 *
 * **One pump, and it changes hands.** [ZbtByteChannel] is driven by whoever consumes it. While this
 * class is watching, that is the loop in [run]; once a handshake starts, it is the handshake's own
 * reader coroutine reading [ZbtByteChannel.input]. They never run at once: [run] stops pumping for
 * exactly as long as [serve] is running. The control-frame callback still fires throughout — on
 * whichever thread happens to be pumping — so it only logs and sets volatile fields, and never
 * writes to the channel.
 *
 * @param serve runs the handshake and closes the link when it ends. `NativeAaHandshakeManager`'s
 *   `handleHandshake`, whose `finally` does the closing.
 */
class ZbtAaCarrier(
    private val serve: suspend (HandshakeLink) -> Unit,
    private val isRunning: () -> Boolean,
    private val isFinishedForSession: () -> Boolean,
    private val isSessionConnected: () -> Boolean,
    private val isSettling: () -> Boolean,
    private val isHandshakeInFlight: () -> Boolean,
    private val mayServeHandshake: () -> Boolean,
    private val onPhoneEvidence: () -> Unit,
    /** The module's own Bluetooth address, the first time the daemon names it. */
    private val onModuleAddress: (String) -> Unit = {},
    private val retryDelayMs: () -> Long = { ZbtAttemptPolicy.MIN_ATTEMPT_INTERVAL_MS },
    private val openChannel: (
        onControl: (Int, ByteArray) -> Unit,
        onRfcomm: (ByteArray) -> Unit
    ) -> ZbtByteChannel = { onControl, onRfcomm ->
        ZbtByteChannel.open(
            enableType = ZbtMessages.ENABLE_TYPE_ANDROID_AUTO,
            onControlFrame = onControl,
            onRfcommData = onRfcomm,
            // The handshake reads these bytes through the link, so they must be kept.
            bufferRfcommData = true
        )
    },
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    companion object {
        /** How long to wait before dialling the daemon again after it refused a connection. */
        const val REOPEN_DELAY_MS = 30_000L

        /** How often to repeat "there is still nothing listening" at a level a default log keeps. */
        const val QUIET_REMINDER_MS = 5 * 60_000L

        /** How often to summarise while waiting for a phone. Matches the probe, and exists for the
         *  same reason: a log exported early must still contain the answer. */
        const val SUMMARY_EVERY_MS = 15_000L

        /** How often to ask the module to re-state the link, so a missed edge is picked up and the
         *  log carries proof the channel is still alive. */
        const val LINK_POLL_EVERY_MS = 30_000L
    }

    /** The live channel, for [close] to reach from another thread. */
    @Volatile
    private var channel: ZbtByteChannel? = null

    /** What the module last said about the phone. Written from the pump thread, read anywhere. */
    @Volatile
    private var presence: ZbtAttemptPolicy.Presence? = null

    /** Whether this channel has ever named a usable phone, which decides if we may speak blind. */
    @Volatile
    private var everSawPresence = false

    @Volatile
    private var moduleMac: String? = null

    @Volatile
    private var lastWakeAt = 0L

    @Volatile
    private var stopped = false

    /** The coroutine [run] is on, so the non-suspending [keepRunning] can see cancellation — it is
     *  called from inside [ZbtByteChannel.pumpOnce]'s plain callback, where suspending is not an
     *  option. */
    @Volatile
    private var carrierJob: Job? = null

    private var controlFrames = 0
    private var rfcommFrames = 0
    private var lastAttemptEndedAt = 0L
    private var attempts = 0

    /**
     * Open, watch, and hand over to a handshake whenever a phone appears. Returns when the manager
     * stops or a handoff completes.
     */
    suspend fun run() {
        carrierJob = currentCoroutineContext()[Job]
        var loudAboutNoDaemon = true
        var lastQuietReminder = 0L
        // Claimed across the reopen loop, not just an open channel: a probe that took the daemon's
        // one client slot first must give way to a real connection rather than outlast it.
        ZbtDaemonReachability.setCarrierWantsClient(true)
        try {
            while (keepRunning()) {
                val opened = try {
                    openChannel(::onControl, ::onRfcomm).also { channel = it }
                } catch (e: IOException) {
                    // The one live test of whether this unit is really on this route. Detection says the
                    // Bluetooth is an external module; it cannot say the module is reachable this way,
                    // and one whole vendor family in that class is not.
                    if (loudAboutNoDaemon) {
                        loudAboutNoDaemon = false
                        lastQuietReminder = now()
                        AppLog.w(
                            "NativeAA: [ZBT] nothing is listening on ${ZbtByteChannel.HOST}:${ZbtByteChannel.PORT}. " +
                                "This unit carries the external-Bluetooth markers but has no vendor daemon to " +
                                "carry Android Auto, so the module transport can do nothing here. " +
                                "Wireless will not connect over Bluetooth on this unit; use USB, or a WiFi mode " +
                                "that needs no Bluetooth handshake. (${e.message})"
                        )
                    } else if (now() - lastQuietReminder >= QUIET_REMINDER_MS) {
                        lastQuietReminder = now()
                        AppLog.i("NativeAA: [ZBT] still nothing listening on port ${ZbtByteChannel.PORT}.")
                    } else {
                        AppLog.d("NativeAA: [ZBT] daemon still refusing: ${e.message}")
                    }
                    delay(REOPEN_DELAY_MS)
                    continue
                }

                loudAboutNoDaemon = true
                // The daemon serves one client, and from here that client is us. Held from the socket,
                // because holding the socket is what holds the slot.
                ZbtDaemonReachability.setCarrierLive(true)
                AppLog.i(
                    "NativeAA: [ZBT] channel open to the Bluetooth module daemon on " +
                        "${ZbtByteChannel.HOST}:${ZbtByteChannel.PORT} — asking it to carry Android Auto"
                )
                try {
                    watchAndServe(opened)
                } finally {
                    val reason = opened.closeReason
                    ZbtDaemonReachability.setCarrierLive(false)
                    opened.close()
                    channel = null
                    AppLog.i("NativeAA: [ZBT] channel ended: ${reason ?: "closed"}")
                }
            }
        } finally {
            ZbtDaemonReachability.setCarrierWantsClient(false)
        }
        AppLog.i(
            "NativeAA: [ZBT] carrier stopped after $attempts handshake attempt(s), " +
                "$controlFrames control message(s), $rfcommFrames message(s) from the phone."
        )
    }

    /** Pump this channel until a phone appears, then hand it to the handshake. */
    private suspend fun watchAndServe(open: ZbtByteChannel) {
        var nextSummary = now() + SUMMARY_EVERY_MS
        var nextPoll = now() + LINK_POLL_EVERY_MS
        val openedAt = now()
        everSawPresence = false

        while (keepRunning() && !open.isFinished) {
            val wake = minOf(nextSummary, nextPoll)
            val pumped = open.pumpOnce(wake) { keepRunning() }
            if (pumped == ZbtByteChannel.Pump.ENDED) return

            // A connect proves something listens; only a frame proves it will carry Android Auto,
            // and a daemon busy with another client accepts the socket and answers nothing.
            if (pumped == ZbtByteChannel.Pump.FRAME) ZbtDaemonReachability.record(true)

            val namedNow = open.moduleMac
            if (namedNow != null && namedNow != moduleMac) {
                moduleMac = namedNow
                onModuleAddress(namedNow)
            }

            if (shouldAttemptNow(openedAt)) {
                serveOnce(open, openForMs = now() - openedAt)
                // The handshake owns the channel's lifetime: its finally closes the link, which
                // closes the channel. Reopening is the outer loop's job.
                return
            }

            if (now() >= nextPoll) {
                nextPoll = now() + LINK_POLL_EVERY_MS
                runCatching { open.requestLinkInfo() }
                    .onFailure { AppLog.d("NativeAA: [ZBT] link poll failed: ${it.message}") }
            }
            if (now() >= nextSummary) {
                nextSummary = now() + SUMMARY_EVERY_MS
                logSummary(openedAt)
            }
        }
    }

    private suspend fun serveOnce(open: ZbtByteChannel, openForMs: Long) {
        val seen = presence
        attempts++
        // Which of the two reasons fired is only ever visible in a reporter's exported log, and it
        // is the whole point of the blind path.
        if (seen?.usable == true) {
            AppLog.i(
                "NativeAA: [ZBT] the module reports an Android Auto phone connected" +
                    (seen.phoneName?.let { " ($it)" } ?: "") +
                    " — starting handshake attempt #$attempts over the module."
            )
        } else {
            AppLog.i(
                "NativeAA: [ZBT] the module has said nothing about a phone in ${openForMs / 1000}s " +
                    "— speaking anyway (blind attempt #$attempts). The head unit speaks first, so " +
                    "this costs nothing if no phone is there."
            )
        }
        val link = ZbtLink(
            channel = open,
            peerName = seen?.phoneName,
            // The module reports it without separators, and everything downstream compares it
            // with android.bluetooth's colon form, so a raw one could never match.
            peerAddress = BluetoothHelper.normalizeMacAddress(seen?.phoneMac),
            moduleMac = moduleMac,
            peerReportedPresent = seen?.usable == true
        )
        try {
            serve(link)
        } catch (e: CancellationException) {
            // Cancellation is the manager stopping us, not a failed attempt. Swallowing it here
            // would leave this coroutine running inside a scope that has already been cancelled.
            throw e
        } catch (e: Exception) {
            AppLog.w("NativeAA: [ZBT] handshake attempt ended: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            lastAttemptEndedAt = now()
        }
    }

    private fun shouldAttemptNow(openedAt: Long): Boolean = ZbtAttemptPolicy.shouldAttempt(
        phonePresent = presence?.usable == true,
        attemptInFlight = isHandshakeInFlight(),
        sessionConnected = isSessionConnected(),
        sinceLastAttemptMs = lastAttemptEndedAt.takeIf { it != 0L }?.let { now() - it },
        minIntervalMs = retryDelayMs(),
        channelOpenForMs = now() - openedAt,
        everSawPresence = everSawPresence
    ) && mayServeHandshake()

    /**
     * Ask the module to bring the phone's link up — the module-side wake, replacing the HFP poke
     * that cannot reach this phone. Safe to call from any thread.
     */
    fun requestWake() {
        val open = channel
        if (open == null || open.isFinished) {
            AppLog.d("NativeAA: [ZBT] no channel to wake through.")
            return
        }
        if (!ZbtWakePolicy.shouldSend(
                lastSentAtMs = lastWakeAt,
                nowMs = now(),
                settling = isSettling(),
                handshakeInFlight = isHandshakeInFlight(),
                sessionConnected = isSessionConnected()
            )
        ) return
        lastWakeAt = now()
        try {
            open.requestReconnect(ZbtMessages.ENABLE_TYPE_ANDROID_AUTO)
            AppLog.i("NativeAA: [ZBT] asked the module to connect Android Auto (RequestReconn).")
        } catch (e: Exception) {
            AppLog.w("NativeAA: [ZBT] could not ask the module to connect: ${e.message}")
        }
    }

    /** Ends the carrier and unblocks whatever is reading. Safe from any thread. */
    fun close() {
        stopped = true
        runCatching { channel?.close() }
    }

    private fun keepRunning(): Boolean =
        !stopped && isRunning() && !isFinishedForSession() && carrierJob?.isActive != false

    // -----------------------------------------------------------------------------------------
    // Frames in
    // -----------------------------------------------------------------------------------------

    /** Wired into the channel. Runs on whichever thread is pumping — logs and volatiles only. */
    private fun onControl(msgId: Int, body: ByteArray) {
        controlFrames++
        AppLog.i(
            "NativeAA: [ZBT] [RX] id=0x${Integer.toHexString(msgId)} ${ZbtMessages.describe(msgId, body)}"
        )
        val read = ZbtAttemptPolicy.readPresence(msgId, ZbtMessages.parse(body)) ?: return
        val previous = presence
        presence = read
        if (read.usable) everSawPresence = true
        if (ZbtAttemptPolicy.isArrival(previous, read)) {
            AppLog.i(
                "NativeAA: [ZBT] the phone" + (read.phoneName?.let { " ($it)" } ?: "") +
                    " arrived on the module."
            )
            // A phone that has just connected is a fresh chance, not a continuation of whatever
            // failed before it. Clearing the backoff here is what stops a run of empty attempts
            // from making the one attempt that could have worked never happen.
            onPhoneEvidence()
        }
    }

    /** Wired into the channel. Only counts: the bytes themselves belong to the handshake. */
    private fun onRfcomm(bytes: ByteArray) {
        rfcommFrames++
        if (rfcommFrames == 1) {
            AppLog.i(
                "NativeAA: [ZBT] first bytes from the phone over the module (${bytes.size} bytes) " +
                    "— the byte channel is live in this direction."
            )
            onPhoneEvidence()
        }
    }

    private fun logSummary(openedAt: Long) {
        val seconds = (now() - openedAt) / 1000
        val seen = presence
        AppLog.i(
            "NativeAA: [ZBT] ${seconds}s on the module channel — " +
                "control $controlFrames, from phone $rfcommFrames, attempts $attempts. " +
                "Phone: " + when {
                    seen == null -> "the module has not said yet"
                    seen.usable -> "connected${seen.phoneName?.let { " ($it)" } ?: ""}, Android Auto"
                    seen.connected -> "connected${seen.phoneName?.let { " ($it)" } ?: ""}, " +
                        "but not as an Android Auto device"
                    else -> "not connected"
                } + ". Module MAC ${moduleMac ?: "unknown"}."
        )
    }
}

/** A [ZbtByteChannel] as something the handshake can run over. */
class ZbtLink(
    private val channel: ZbtByteChannel,
    override val peerName: String?,
    override val peerAddress: String?,
    moduleMac: String?,
    override val peerReportedPresent: Boolean = true
) : HandshakeLink {

    override val input: InputStream get() = channel.input
    override val output: OutputStream get() = channel.output
    override val radioLabel: String = "external Bluetooth module${moduleMac?.let { " $it" } ?: ""}"

    /**
     * Never. This address belongs to a phone bonded to the module, not to the radio
     * `android.bluetooth` exposes — nothing in the app can dial it and no ACL broadcast will ever
     * name it. Storing it would fill the auto-start list with a device that cannot start anything.
     */
    override val persistPeerForAutoStart: Boolean = false

    /** No accept happens on this route, so a first message can be dropped unseen. */
    override val retransmitsWhileSilent: Boolean = true

    override fun close() {
        channel.close()
    }
}
