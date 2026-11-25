package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

import android.os.SystemClock
import com.andrerinas.openheadunit.connection.ConnectionAdmission
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.ExternalModuleCarrier
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.HandshakeLink
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
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
    private val admission: ConnectionAdmission,
    /** The module's own Bluetooth address, the first time the daemon names it. */
    private val onModuleAddress: (String) -> Unit = {},
    private val retryDelayMs: () -> Long = { ZbtAttemptPolicy.MIN_ATTEMPT_INTERVAL_MS },
    private val openChannel: (
        opening: ZbtChannelOpening,
        onControl: (Int, ByteArray) -> Unit,
        onRfcomm: (ByteArray) -> Unit
    ) -> ZbtByteChannel = { opening, onControl, onRfcomm ->
        ZbtByteChannel.open(
            enableType = ZbtMessages.ENABLE_TYPE_ANDROID_AUTO,
            onControlFrame = onControl,
            onRfcommData = onRfcomm,
            // The handshake reads these bytes through the link, so they must be kept.
            bufferRfcommData = true,
            opening = opening,
        )
    },
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
    private val elapsedNow: () -> Long = { SystemClock.elapsedRealtime() },
) : ExternalModuleCarrier {

    companion object {
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

    /** Published before connect, so close reaches sockets not yet returned by openChannel. */
    private var openingChannel: ZbtChannelOpening? = null
    private var carrierClaim: ZbtDaemonReachability.CarrierClaim? = null
    private val stoppedSignal = CompletableDeferred<Unit>()

    /** Each open starts unknown; a callback from an old socket cannot populate the new one. */
    private class ChannelObservation {
        @Volatile var presence: ZbtAttemptPolicy.Presence? = null
        @Volatile var everSawPresence = false
        @Volatile var moduleMac: String? = null
        // Progress belongs to this channel/link, not just the wake queued at receipt.
        // Only an explicit link disconnect or channel replacement releases this guard.
        @Volatile var sawRfcomm = false
        var wakeSuppressionLogged = false
    }

    @Volatile
    private var observation: ChannelObservation? = null
    private val observationLock = Any()

    /**
     * The last link state reported on any of this carrier's channels, kept across reopen.
     *
     * Used only to decide whether a report is *new* recovery evidence for [onPhoneEvidence], which
     * clears the manager's consecutive-failure count. Attempt admission and wakes still read the
     * channel's own [ChannelObservation], which starts unknown on every open. If this history were
     * per channel too, the first "still connected" report after each reopen would count as an
     * arrival, and a phone that never answers would never reach the failure limit.
     *
     * An explicit is_connect=0 followed by a connected report, a different phone address, and the
     * first phone bytes on a channel remain evidence. A reconnect that happens entirely while no
     * channel is open is not seen; a manual retry clears the count in that case.
     * Guarded by [observationLock]; only the current observation's callbacks write it.
     */
    private var reportedPresence: ZbtAttemptPolicy.Presence? = null

    @Volatile
    private var lastWakeAt = 0L

    private class PendingWake(val requestedAt: Long, val admission: ConnectionAdmission)
    /** The queued command keeps its own Save/ticket, separately from the carrier's original arm. */
    private var pendingWake: PendingWake? = null

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
        if (!effectCurrent()) return
        var refusals = 0
        var firstRefusalAt = 0L
        var warnedNoDaemon = false
        var lastQuietReminder = 0L
        // Claimed across the reopen loop, not just an open channel: a probe that took the daemon's
        // one client slot first must give way to a real connection rather than outlast it.
        val claim = ZbtDaemonReachability.claimCarrier()
        val ownsRun = synchronized(observationLock) {
            if (stopped) false else { carrierClaim = claim; true }
        }
        if (!ownsRun) { claim.close(); return }
        AppLog.i("NativeAA: [ZBT] carrier owner=${claim.id} registered an OHU client claim.")
        try {
            while (effectCurrent()) {
                val observed = ChannelObservation()
                val opening = ZbtChannelOpening { effectCurrent() }
                val admitted = synchronized(observationLock) {
                    if (!keepRunning()) false else {
                        observation = observed
                        openingChannel = opening
                        true
                    }
                }
                if (!admitted) break
                AppLog.i("NativeAA: [ZBT] opening owner=${claim.id}, open=${opening.id}.")
                val opened = try {
                    val candidate = openChannel(
                        opening,
                        { id, body -> onControl(observed, id, body) },
                        { bytes -> onRfcomm(observed, bytes) },
                    )
                    if (!effectCurrent()) {
                        candidate.close()
                        throw ZbtChannelOpening.Cancelled()
                    }
                    val published = synchronized(observationLock) {
                        if (!keepRunning() || observation !== observed || openingChannel !== opening) false else {
                            channel = candidate
                            openingChannel = null
                            true
                        }
                    }
                    if (!published) {
                        candidate.close()
                        throw ZbtChannelOpening.Cancelled()
                    }
                    candidate
                } catch (_: ZbtChannelOpening.Cancelled) {
                    retireObservation(observed)
                    AppLog.i("NativeAA: [ZBT] opening retired owner=${claim.id}, open=${opening.id}, initAdmitted=${opening.initWasAdmitted()}.")
                    break
                } catch (e: IOException) {
                    opening.close()
                    retireObservation(observed, discardWake = false)
                    if (!keepRunning()) break
                    // The one live test of whether this unit is really on this route. Detection says the
                    // Bluetooth is an external module; it cannot say the module is reachable this way,
                    // and one whole vendor family in that class is not.
                    if (refusals == 0) firstRefusalAt = now()
                    refusals++
                    val wait = ZbtReopenPolicy.delayAfterRefusalMs(refusals)
                    if (!warnedNoDaemon && ZbtReopenPolicy.warnsNoDaemon(now() - firstRefusalAt)) {
                        warnedNoDaemon = true
                        lastQuietReminder = now()
                        AppLog.w(
                            "NativeAA: [ZBT] nothing is listening on ${ZbtByteChannel.HOST}:${ZbtByteChannel.PORT}. " +
                                "This unit carries the external-Bluetooth markers but has no vendor daemon to " +
                                "carry Android Auto, so the module transport can do nothing here. " +
                                "Wireless will not connect over Bluetooth on this unit; use USB, or a WiFi mode " +
                                "that needs no Bluetooth handshake. (${e.message})"
                        )
                    } else if (warnedNoDaemon && now() - lastQuietReminder >= QUIET_REMINDER_MS) {
                        lastQuietReminder = now()
                        AppLog.i("NativeAA: [ZBT] still nothing listening on port ${ZbtByteChannel.PORT}.")
                    }
                    AppLog.i("NativeAA: [ZBT] daemon refused, retrying in ${wait / 1000}s (${e.message})")
                    // close() wakes this wait immediately; an old refusal backoff must not
                    // keep the daemon claim alive while a new connection is being armed.
                    withTimeoutOrNull(wait) { stoppedSignal.await() }
                    continue
                } finally {
                    synchronized(observationLock) {
                        if (openingChannel === opening) openingChannel = null
                    }
                    // On success channel owns this socket. On a failed/cancelled open close reaches
                    // any resource a custom opener installed before failing.
                    if (channel == null) opening.close()
                }

                refusals = 0
                warnedNoDaemon = false
                // An OHU socket is open. Keep our diagnostics from dialing beside it; this does not
                // prove that the server is processing this client or that the phone has working HFP.
                claim.setLive(true)
                AppLog.i(
                    "NativeAA: [ZBT] channel open to the Bluetooth module daemon on " +
                        "${ZbtByteChannel.HOST}:${ZbtByteChannel.PORT}, owner=${claim.id}, open=${opening.id} — asking it to carry Android Auto"
                )
                try {
                    watchAndServe(opened, observed)
                } finally {
                    val reason = opened.closeReason
                    claim.setLive(false)
                    retireObservation(observed)
                    opened.close()
                    synchronized(observationLock) { if (channel === opened) channel = null }
                    AppLog.i("NativeAA: [ZBT] channel ended owner=${claim.id}, open=${opening.id}: ${reason ?: "closed"}")
                }
            }
        } finally {
            claim.close()
            synchronized(observationLock) {
                if (carrierClaim === claim) carrierClaim = null
            }
            AppLog.i("NativeAA: [ZBT] carrier owner=${claim.id} released its OHU client claim.")
        }
        AppLog.i(
            "NativeAA: [ZBT] carrier stopped after $attempts handshake attempt(s), " +
                "$controlFrames control message(s), $rfcommFrames message(s) from the phone."
        )
    }

    /** Pump this channel until a phone appears, then hand it to the handshake. */
    private suspend fun watchAndServe(open: ZbtByteChannel, observed: ChannelObservation) {
        var nextSummary = now() + SUMMARY_EVERY_MS
        var nextPoll = now() + LINK_POLL_EVERY_MS
        val openedAt = now()
        val openedElapsedAt = elapsedNow()

        while (effectCurrent() && !open.isFinished) {
            val wake = minOf(nextSummary, nextPoll)
            val pumped = open.pumpOnce(wake) { keepRunning() }
            if (pumped == ZbtByteChannel.Pump.ENDED || !effectCurrent() || open.isFinished) return

            // A connect proves something listens; only a frame proves it will carry Android Auto,
            // and a daemon busy with another client accepts the socket and answers nothing.
            if (pumped == ZbtByteChannel.Pump.FRAME) ZbtDaemonReachability.record(true)

            val namedNow = open.moduleMac
            if (namedNow != null && namedNow != observed.moduleMac) {
                observed.moduleMac = namedNow
                onModuleAddress(namedNow)
            }

            // InitInfo is not the end of the opening burst. Read the link reports before
            // spending a queued cold-start wake; a connected phone needs a handshake, not
            // another Bluetooth reconnect. Quiet daemons retain a bounded fallback.
            sendPendingWake(open, observed, openedElapsedAt)
            if (keepRunning() && !open.isFinished && shouldAttemptNow(openedAt, observed) && !isSessionConnected()) {
                serveOnce(open, observed, openForMs = now() - openedAt)
                return
            }

            if (now() >= nextPoll) {
                nextPoll = now() + LINK_POLL_EVERY_MS
                if (!effectCurrent()) return
                runCatching { open.requestLinkInfo() }
                    .onFailure { AppLog.d("NativeAA: [ZBT] link poll failed: ${it.message}") }
            }
            if (now() >= nextSummary) {
                nextSummary = now() + SUMMARY_EVERY_MS
                logSummary(openedAt, observed)
            }
        }
    }

    private suspend fun serveOnce(open: ZbtByteChannel, observed: ChannelObservation, openForMs: Long) {
        if (!effectCurrent()) return
        // The handshake consumes the request that led here. It must not replay after a
        // failed exchange on another socket, even if the module never reported a peer.
        synchronized(observationLock) { pendingWake = null }
        val seen = observed.presence
        attempts++
        // Which of the two reasons fired is only ever visible in a reporter's exported log, and it
        // is the whole point of the blind path.
        if (seen?.usable == true) {
            AppLog.i(
                "NativeAA: [ZBT] the module reports an Android Auto phone connected" +
                    (seen.phoneName?.let { " ($it)" } ?: "") +
                    " — starting handshake attempt #$attempts over the module."
            )
        } else if (observed.sawRfcomm) {
            AppLog.i("NativeAA: [ZBT] phone data is buffered — starting handshake attempt #$attempts without waiting for a link-state report.")
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
            moduleMac = observed.moduleMac,
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

    private fun shouldAttemptNow(openedAt: Long, observed: ChannelObservation): Boolean =
        ZbtAttemptPolicy.shouldAttempt(
        phonePresent = observed.presence?.usable == true || observed.sawRfcomm,
        attemptInFlight = isHandshakeInFlight(),
        sessionConnected = isSessionConnected(),
        sinceLastAttemptMs = lastAttemptEndedAt.takeIf { it != 0L }?.let { now() - it },
        minIntervalMs = retryDelayMs(),
        channelOpenForMs = now() - openedAt,
        everSawPresence = observed.everSawPresence
    ) && mayServeHandshake()

    /**
     * Ask the module to bring the phone's link up — the module-side wake, replacing the HFP poke
     * that cannot reach this phone. Only records the request: the carrier's own thread sends it,
     * because a caller may be the main thread and the channel is a socket. Safe from any thread.
     *
     * @param userAsked the WiFi button, whose wake is held through a busy link rather than dropped
     */
    override fun requestWake(userAsked: Boolean, admission: ConnectionAdmission) {
        if (!this.admission.run {} || !admission.run {}) return
        val previous = synchronized(observationLock) { pendingWake }
        val keepOriginalWait = previous?.admission?.run {} == true
        synchronized(observationLock) {
            // A callback may consume or replace the pending command while its authority is
            // checked outside this lock. Do not resurrect or overwrite that newer decision.
            if (pendingWake !== previous) return@synchronized
            if (stopped || isSessionConnected() || observation?.presence?.usable == true) return@synchronized
            val observed = observation
            if (observed?.sawRfcomm == true) {
                if (!observed.wakeSuppressionLogged) {
                    observed.wakeSuppressionLogged = true
                    AppLog.i("NativeAA: [ZBT] reconnect request ignored: phone data already received on this link; waiting for handshake.")
                }
                return@synchronized
            }
            val sendable = ZbtWakePolicy.shouldSend(
                lastSentAtMs = lastWakeAt,
                nowMs = elapsedNow(),
                settling = isSettling(),
                handshakeInFlight = isHandshakeInFlight(),
                sessionConnected = false
            )
            if (!sendable && !userAsked) return@synchronized
            // Coalesce without extending the original wait. The most recent valid command
            // supplies the authority; an old sender can consume only its captured identity.
            pendingWake = PendingWake(if (keepOriginalWait) checkNotNull(previous).requestedAt else elapsedNow(), admission)
            if (previous == null) {
                AppLog.i("NativeAA: [ZBT] reconnect request queued (${if (userAsked) "user" else "automatic"}); waiting for link observation and handoff readiness.")
            }
        }
    }

    private fun sendPendingWake(open: ZbtByteChannel, observed: ChannelObservation, openedAt: Long) {
        if (!effectCurrent()) return
        val pending = synchronized(observationLock) {
            if (stopped || observation !== observed || open.isFinished) null else pendingWake
        } ?: return
        // Command admission enters the app lifecycle; never evaluate it under observationLock.
        val commandCurrent = pending.admission.run {}
        var sendAt = 0L
        val send = synchronized(observationLock) {
            if (stopped || observation !== observed || open.isFinished || pendingWake !== pending) return@synchronized false
            if (!commandCurrent) {
                pendingWake = null
                return@synchronized false
            }
            val requestedAt = pending.requestedAt
            val current = elapsedNow()
            val verdict = ZbtWakePolicy.pending(
                requestedAtMs = requestedAt,
                lastSentAtMs = lastWakeAt,
                nowMs = current,
                settling = isSettling(),
                handshakeInFlight = isHandshakeInFlight(),
                sessionConnected = isSessionConnected(),
                channelOpenForMs = current - openedAt,
                phonePresent = observed.presence?.usable == true
            )
            if (verdict == ZbtWakePolicy.Pending.HOLD) return@synchronized false
            pendingWake = null
            if (verdict != ZbtWakePolicy.Pending.SEND) {
                AppLog.i("NativeAA: [ZBT] queued reconnect cancelled: $verdict, age=${current - requestedAt}ms.")
                return@synchronized false
            }
            sendAt = current
            true
        }
        if (!send || !effectCurrent() || !pending.admission.run {}) return
        // A write already admitted can finish during close. Reserve its interval, including
        // a failed/partial write, only after both the carrier and command still authorize it.
        lastWakeAt = sendAt
        try {
            open.requestReconnect(ZbtMessages.ENABLE_TYPE_ANDROID_AUTO)
            AppLog.i("NativeAA: [ZBT] asked the module to connect Android Auto after observing its initial state.")
        } catch (e: Exception) {
            AppLog.w("NativeAA: [ZBT] could not ask the module to connect: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Ends the carrier and unblocks whatever is reading. Safe from any thread. */
    override fun close() {
        val closing = synchronized(observationLock) {
            stopped = true
            pendingWake = null
            observation = null
            Triple(openingChannel, channel, carrierClaim).also { carrierClaim = null }
        }
        closing.first?.close()
        runCatching { closing.second?.close() }
        closing.third?.close()
        stoppedSignal.complete(Unit)
    }

    private fun retireObservation(observed: ChannelObservation, discardWake: Boolean = true) {
        synchronized(observationLock) {
            if (observation === observed) {
                observation = null
                if (discardWake) pendingWake = null
            }
        }
    }

    private fun keepRunning(): Boolean =
        !stopped && isRunning() && !isFinishedForSession() && carrierJob?.isActive != false

    // Never call while holding observationLock: the admission enters the app lifecycle first.
    private fun effectCurrent(): Boolean = keepRunning() && admission.run {}

    // -----------------------------------------------------------------------------------------
    // Frames in
    // -----------------------------------------------------------------------------------------

    /** Wired into the channel. Runs on whichever thread is pumping — logs and volatiles only. */
    private fun onControl(observed: ChannelObservation, msgId: Int, body: ByteArray) = synchronized(observationLock) {
        if (observation !== observed || stopped) return@synchronized
        controlFrames++
        AppLog.i(
            "NativeAA: [ZBT] [RX] id=0x${Integer.toHexString(msgId)} ${ZbtMessages.describe(msgId, body)}"
        )
        val fields = ZbtMessages.parse(body)
        val read = ZbtAttemptPolicy.readPresence(msgId, fields) ?: run {
            if (msgId == ZbtMessages.LINK_INFO || msgId == ZbtMessages.LINK_INFO2) {
                AppLog.d("NativeAA: [ZBT] incomplete link state ignored; preserving the current observation.")
            }
            return@synchronized
        }
        // Channel-local state starts unknown on every open; evidence compares with the carrier's
        // history so a reopen's repeat of the same connected report is not a fresh arrival.
        val previous = reportedPresence
        observed.presence = read
        reportedPresence = read
        // is_connect is field 3 in both LinkInfo variants. A missing field or the
        // separate HFP scalar is not evidence that this RFCOMM link disconnected.
        // Permit a fresh wake only after the daemon explicitly reports is_connect=0.
        if (fields?.int(3) == 0 && observed.sawRfcomm) {
            observed.sawRfcomm = false
            observed.wakeSuppressionLogged = false
            AppLog.i("NativeAA: [ZBT] module reported link disconnected after phone data; a new reconnect request is allowed.")
        }
        if (read.usable) {
            observed.everSawPresence = true
            cancelPendingWake("module reports an Android Auto phone connected")
        }
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

    /** Records progress; the bytes themselves stay buffered for the handshake. */
    private fun onRfcomm(observed: ChannelObservation, bytes: ByteArray) = synchronized(observationLock) {
        if (observation !== observed || stopped) return@synchronized
        if (bytes.isEmpty()) return@synchronized
        // Real phone bytes are progress even on modules without LinkInfo. Keep them
        // buffered for the handshake and consume any wake queued before that progress.
        cancelPendingWake("received phone data (${bytes.size} bytes)")
        rfcommFrames++
        if (!observed.sawRfcomm) {
            observed.sawRfcomm = true
            AppLog.i(
                "NativeAA: [ZBT] first bytes from the phone over the module (${bytes.size} bytes) " +
                    "— the byte channel is live in this direction."
            )
            onPhoneEvidence()
        }
    }

    /** Called under observationLock; log only when there was work to cancel. */
    private fun cancelPendingWake(reason: String) {
        val pending = pendingWake ?: return
        pendingWake = null
        AppLog.i("NativeAA: [ZBT] queued reconnect cancelled: $reason, age=${elapsedNow() - pending.requestedAt}ms.")
    }

    private fun logSummary(openedAt: Long, observed: ChannelObservation) {
        val seconds = (now() - openedAt) / 1000
        val seen = observed.presence
        AppLog.i(
            "NativeAA: [ZBT] ${seconds}s on the module channel — " +
                "control $controlFrames, from phone $rfcommFrames, attempts $attempts. " +
                "Phone: " + when {
                    seen == null -> "the module has not said yet"
                    seen.usable -> "connected${seen.phoneName?.let { " ($it)" } ?: ""}, Android Auto"
                    seen.connected -> "connected${seen.phoneName?.let { " ($it)" } ?: ""}, " +
                        "but not as an Android Auto device"
                    else -> "not connected"
                } + ". Module MAC ${observed.moduleMac ?: "unknown"}."
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
