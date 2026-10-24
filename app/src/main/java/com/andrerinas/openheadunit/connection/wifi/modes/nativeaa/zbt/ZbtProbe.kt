package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

import android.os.SystemClock
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.WppFraming
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.WppHandshakeSession
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.WppMessageType
import com.andrerinas.openheadunit.aap.protocol.proto.Wireless
import com.andrerinas.openheadunit.utils.AppLog
import java.io.IOException
import java.net.Socket

/**
 * Asks a head unit's external Bluetooth module whether it will carry Android Auto for us, and
 * watches to see whether it does.
 *
 * Two earlier rounds got us here. The first (2026-08-11) proved the frame layout, the recovered
 * schema and `enable_type = 2` against the real daemon. The second (2026-08-12) asked whether the
 * `HicarServiceRegister` family would take the Android Auto UUID; it would not, and reading the
 * vendor library afterwards showed why — that family is keyed to HiCar's own `FE35`/`FE36` service
 * UUIDs, and the Android Auto bytes were never going to be there. **Message `0x105` is the byte
 * channel**, in both directions, and it needs no registration: the daemon owns the Android Auto
 * RFCOMM server on the module and `RequestInit` with `enable_type = 2` is the whole setup.
 *
 * The diagnostic reports byte traffic and the daemon's link-state messages separately. A module
 * can report a link without forwarding its bytes to this socket, and data after our version request
 * is not necessarily its reply. No verdict below treats either observation as a verified WPP
 * exchange or evidence that another application owns the daemon.
 *
 * Opening sends RequestInit with enable_type=2, which can activate the module's Android Auto
 * handling. Watch-only skips the additional RequestReconn command; it is not a passive identity
 * check. The diagnostic also sends WPP/version and link-state requests when the link permits.
 *
 * Blocking, and for up to about two minutes. Call it off the main thread and pass [keepGoing].
 */
object ZbtProbe {

    /** How long to give the opening state burst before calling the daemon quiet. */
    private const val HELLO_BUDGET_MS = 8_000L

    /** How long to sit and watch. Long, because what we are waiting for is a phone. */
    private const val WATCH_BUDGET_MS = 90_000L

    /**
     * How often to write a running total to the log.
     *
     * Not cosmetic. Round two's log was exported eighteen seconds before the run finished, so the
     * verdict line was simply absent from it and the state had to be reconstructed by counting
     * frames. A summary every fifteen seconds means an early export still carries the answer.
     */
    private const val SUMMARY_EVERY_MS = 15_000L

    /**
     * How often to ask the daemon to re-send link state.
     *
     * Read-only — its handler ignores the body — and it does two useful things: it proves the socket
     * is still alive during a long silence, and it re-reports whether the phone is connected at the
     * moment we are claiming nothing happened.
     */
    private const val LINK_INFO_EVERY_MS = 30_000L

    /**
     * Run the probe and return a one-line verdict.
     *
     * Everything of interest goes to [AppLog] as it happens, so a reporter's exported log is the real
     * output and the return value is only what to put on screen.
     *
     * [onProgress] is called as the run moves along, so a screen showing the verdict can show that
     * something is still happening — a ninety-second run that says only "Running…" is
     * indistinguishable from one that has hung, and two rounds were already lost to a probe that
     * looked like it did nothing. It may be called from any thread.
     *
     * [askModuleToReconnect] sends `RequestReconn`, asking the module to bring Bluetooth up for
     * Android Auto. This additional reconnect is off unless the caller asks, and the log records
     * when it goes out. Both modes still send the initial RequestInit, which may activate AA.
     *
     * [keepGoing] is polled between frames; return false to end the run early.
     *
     * [socketFactory] is the socket boundary, replaced only by tests.
     */
    fun run(
        askModuleToReconnect: Boolean = false,
        onProgress: (String) -> Unit = {},
        keepGoing: () -> Boolean = { true },
        socketFactory: () -> Socket = { Socket() }
    ): String {
        AppLog.i("ZbtProbe: starting — external Bluetooth module probe")

        // The daemon serves one program at a time. Dialling a second socket beside our own live
        // carrier gets it accepted and never answered, which reads exactly like a dead daemon.
        if (ZbtDaemonReachability.carrierLive() || ZbtDaemonReachability.carrierWantsClient()) {
            AppLog.i("ZbtProbe: our own carrier holds the module's client slot; not dialling beside it.")
            return busyCarrierVerdict()
        }

        // The carrier can arm underneath a run that started before it. Polled between frames rather
        // than read once at the top, so the slot goes back within a frame instead of at the end.
        var yielded = false
        val keepProbing: () -> Boolean = {
            if (ZbtDaemonReachability.carrierWantsClient()) yielded = true
            !yielded && keepGoing()
        }

        var helloFrames = 0
        var controlFrames = 0
        var rfcommFrames = 0
        var rfcommBytes = 0
        var linkedType: Int? = null
        var phoneName: String? = null
        var phoneConnected = false
        var reportedPhoneStatus: Int? = null
        var phase = "hello"

        // Registration is atomic with a new carrier claim. That claim also closes an observer
        // already connecting/reading, rather than waiting for the next three-second read timeout.
        // Every write after init (reconnect, version, link poll) is admitted against it again.
        val opening = try {
            ZbtDaemonReachability.observerOpening(keepProbing, diagnostic = true)
                ?: return busyCarrierVerdict()
        } catch (_: ZbtChannelOpening.ObserverBusy) {
            return "Another Bluetooth module check is running. Try again when it finishes."
        }
        // A carrier can claim and release the slot while this reader is waking from close.
        // The opening remembers that cancellation after the global claim has disappeared.
        // Keep this outside keepProbing: wasCancelled itself consults that callback.
        fun stoppedVerdict(): String? {
            val running = keepProbing()
            return if (opening.wasCancelled() || !running) {
                if (yielded) carrierTookOverVerdict() else "Probe stopped."
            } else null
        }
        val channel = try {
            ZbtByteChannel.open(
                enableType = ZbtMessages.ENABLE_TYPE_ANDROID_AUTO,
                onControlFrame = { msgId, body ->
                    controlFrames++
                    if (phase == "hello") helloFrames++
                    AppLog.i(
                        "ZbtProbe: [RX] $phase id=0x${Integer.toHexString(msgId)} " +
                            ZbtMessages.describe(msgId, body)
                    )
                    val fields = ZbtMessages.parse(body)
                    if (fields != null) when (msgId) {
                        ZbtMessages.PHONE_LINK_STATE ->
                            if (fields.int(2) == 1) linkedType = fields.int(3)
                        ZbtMessages.LINK_INFO2 -> {
                            reportedPhoneStatus = fields.int(3)
                            phoneConnected = fields.int(3) == 1
                            fields.string(8)?.takeIf { it.isNotEmpty() }?.let { phoneName = it }
                        }
                        ZbtMessages.LINK_INFO -> {
                            reportedPhoneStatus = fields.int(3)
                            phoneConnected = fields.int(3) == 1
                        }
                    }
                },
                onRfcommData = { bytes ->
                    rfcommFrames++
                    rfcommBytes += bytes.size
                    // In full, never truncated. These are the bytes the whole investigation is for,
                    // and their first four are what identify them as Android Auto's own framing.
                    AppLog.i(
                        "ZbtProbe: [RX] $phase RFCOMM ${bytes.size} bytes ${describeWpp(bytes)} " +
                            ZbtMessages.hex(bytes, limit = Int.MAX_VALUE)
                    )
                },
                // Everything this probe learns comes through the callbacks above; it never reads the
                // stream. Buffering for a reader that never arrives would end a healthy channel as
                // soon as the phone sent a few hundred kilobytes.
                bufferRfcommData = false,
                opening = opening,
                socketFactory = socketFactory
            )
        } catch (e: Exception) {
            val stopped = stoppedVerdict() // Before our own cleanup marks the opening closed.
            opening.close()
            ZbtDaemonReachability.releaseObserver(opening)
            if (stopped != null || e is ZbtChannelOpening.Cancelled) {
                return stopped ?: "Probe stopped."
            }
            AppLog.i("ZbtProbe: ${e.message}")
            // Preserve the opening category for the result without inferring daemon ownership.
            return notConnectedVerdict(
                (e as? ZbtByteChannel.NotConnected)?.verdict
                    ?: ZbtReachabilityPolicy.classify(e.javaClass.simpleName, answered = false)
            )
        }

        try {
            // Phase 1 — hello. Known-good since 2026-08-11: RequestInit draws InitInfo and a burst of
            // link state. The channel has already sent it.
            onProgress("Asking the Bluetooth module…")
            val helloDeadline = SystemClock.elapsedRealtime() + HELLO_BUDGET_MS
            while (SystemClock.elapsedRealtime() < helloDeadline && keepProbing()) {
                val pump = channel.pumpOnce(helloDeadline, keepProbing)
                stoppedVerdict()?.let { return it }
                if (pump == ZbtByteChannel.Pump.ENDED) break
                // After a control burst and one quiet read, switch to the longer watch window.
                if (pump == ZbtByteChannel.Pump.QUIET && helloFrames > 0) break
                if (helloFrames > 0) onProgress("Received $helloFrames module control message(s)…")
            }

            stoppedVerdict()?.let { return it }

            if (helloFrames == 0) {
                // Control reports and RFCOMM payloads are counted separately. A daemon that
                // delivers bytes before any status report has answered this socket; preserve
                // that evidence without advancing this incomplete opening into more commands.
                if (rfcommFrames > 0) return verdict(
                    rfcommFrames = rfcommFrames,
                    rfcommBytes = rfcommBytes,
                    observedDataAfterVersionRequest = false,
                    linkedType = linkedType,
                    controlFrames = controlFrames,
                    phoneConnected = phoneConnected,
                    phoneName = phoneName,
                    askedToReconnect = false,
                )
                if (ZbtDaemonReachability.carrierLive()) return busyCarrierVerdict()
                return if (channel.isFinished) {
                    "The diagnostic connection on port ${ZbtByteChannel.PORT} ended before a " +
                        "complete message was received: ${channel.closeReason ?: "reason unavailable"}."
                } else {
                    "The socket on port ${ZbtByteChannel.PORT} opened, but no complete message " +
                        "was received during the initial observation window."
                }
            }
            channel.moduleMac?.let { AppLog.i("ZbtProbe: the module reports its Bluetooth address as $it") }

            // Phase 2 — watch. Nothing we send makes the phone start Android Auto; the most we can do
            // is ask the module to bring its Bluetooth up and then wait.
            phase = "watch"
            if (askModuleToReconnect) {
                AppLog.i("ZbtProbe: asking the module to reconnect for Android Auto (RequestReconn, enable_type=2)")
                onProgress("Asking the module to reconnect…")
                // keepProbing() above can be stale: a carrier may have claimed the daemon since,
                // with this socket's close still in flight. Re-check at the write, outside any lock.
                opening.admitCommand()
                channel.requestReconnect(ZbtMessages.ENABLE_TYPE_ANDROID_AUTO)
            }
            onProgress("Watching for Android Auto — this takes about a minute and a half…")

            val start = SystemClock.elapsedRealtime()
            val deadline = start + WATCH_BUDGET_MS
            var nextSummary = start + SUMMARY_EVERY_MS
            var nextLinkInfo = start + LINK_INFO_EVERY_MS
            var versionRequestAt = 0L
            var rfcommBytesBeforeVersionRequest = 0

            while (SystemClock.elapsedRealtime() < deadline && keepProbing()) {
                // Wake at whichever comes first: the end of the watch, the next summary line, or the
                // next link-state poll. Between those the pump is simply waiting for the phone.
                val wakeAt = minOf(deadline, nextSummary, nextLinkInfo)
                val pump = channel.pumpOnce(wakeAt, keepProbing)
                stoppedVerdict()?.let { return it }
                if (pump == ZbtByteChannel.Pump.ENDED) {
                    AppLog.w("ZbtProbe: diagnostic channel ended during the watch: ${channel.closeReason ?: "reason unavailable"}")
                    break
                }
                val now = SystemClock.elapsedRealtime()

                // Retain the activity gate and send at most once. Link reports or raw bytes
                // permit this diagnostic request, but do not prove a completed WPP exchange.
                if (versionRequestAt == 0L && (rfcommFrames > 0 || linkedType != null)) {
                    rfcommBytesBeforeVersionRequest = rfcommBytes
                    versionRequestAt = now
                    opening.admitCommand()
                    sendVersionRequest(channel)
                    onProgress("Module link activity observed — sending a version request…")
                }

                if (now >= nextSummary) {
                    nextSummary = now + SUMMARY_EVERY_MS
                    val seconds = (now - start) / 1000
                    val phoneStatus = when (val status = reportedPhoneStatus) {
                        null -> "no phone connection status reported"
                        0 -> "latest phone status: disconnected"
                        1 -> "latest phone status: connected"
                        else -> "latest phone status: $status"
                    }
                    AppLog.i(
                        "ZbtProbe: ${seconds}s watching — RFCOMM $rfcommFrames message(s)/$rfcommBytes bytes, " +
                            "control $controlFrames, $phoneStatus" +
                            (phoneName?.let { " ($it)" } ?: "") +
                            (linkedType?.let { ", observed projection link type $it" } ?: ", no projection link reported")
                    )
                    onProgress("Observed RFCOMM: $rfcommFrames message(s), $rfcommBytes bytes in ${seconds}s…")
                }

                if (now >= nextLinkInfo) {
                    nextLinkInfo = now + LINK_INFO_EVERY_MS
                    opening.admitCommand()
                    channel.requestLinkInfo()
                }
            }

            stoppedVerdict()?.let { return it }

            return verdict(
                rfcommFrames = rfcommFrames,
                rfcommBytes = rfcommBytes,
                observedDataAfterVersionRequest = versionRequestAt != 0L && rfcommBytes > rfcommBytesBeforeVersionRequest,
                linkedType = linkedType,
                controlFrames = controlFrames,
                phoneConnected = phoneConnected,
                phoneName = phoneName,
                askedToReconnect = askModuleToReconnect
            )
        } catch (e: Exception) {
            // A refused admission is a yield, never an exchange failure, even if the claim that
            // caused it has already been released by the time it is looked at here.
            stoppedVerdict()?.let { return it }
            if (e is ZbtChannelOpening.Cancelled) return "Probe stopped."
            AppLog.e("ZbtProbe: the exchange failed: ${e.javaClass.simpleName}: ${e.message}")
            return "Connected to the module but the exchange failed: ${e.javaClass.simpleName}."
        } finally {
            // Read before closing: close() claims the reason itself if nothing has, and the reason
            // the channel ended on its own is the more informative of the two.
            val ended = channel.closeReason
            channel.close()
            opening.close()
            ZbtDaemonReachability.releaseObserver(opening)
            AppLog.i(
                "ZbtProbe: finished — RFCOMM $rfcommFrames message(s)/$rfcommBytes bytes, " +
                    "control $controlFrames message(s)" + (ended?.let { ", channel ended: $it" } ?: "")
            )
        }
    }

    /**
     * Send one `WifiVersionRequest` over the RFCOMM channel.
     *
     * Deliberately the bare version rather than `WppMessages.versionRequest`, which carries car info
     * the probe has no settings to read. The outbound frame uses the transport's encoder;
     * incoming payloads are logged for inspection, not parsed here as verified version replies.
     */
    private fun sendVersionRequest(channel: ZbtByteChannel) {
        val request = Wireless.WifiVersionRequest.newBuilder()
            .setMajor(WppHandshakeSession.WPP_VERSION_MAJOR)
            .setMinor(WppHandshakeSession.WPP_VERSION_MINOR)
            .build()
        val frame = WppFraming.encodeFrame(request.toByteArray(), WppMessageType.VERSION_REQUEST)
        AppLog.i(
            "ZbtProbe: [TX] WifiVersionRequest (Type ${WppMessageType.VERSION_REQUEST}) " +
                "v${WppHandshakeSession.WPP_VERSION_MAJOR}.${WppHandshakeSession.WPP_VERSION_MINOR} " +
                "over the module — ${ZbtMessages.hex(frame, limit = Int.MAX_VALUE)}"
        )
        channel.output.write(frame)
        channel.output.flush()
    }

    /**
     * Name an inbound payload if it looks like the wireless handshake, so the log says what arrived
     * rather than only how much.
     *
     * Deliberately a guess, and labelled as one: the first bytes of an RFCOMM payload are only WPP
     * if that is what the phone is speaking, which is the very thing being established.
     */
    internal fun describeWpp(bytes: ByteArray): String {
        if (bytes.size < WppFraming.HEADER_SIZE) return "(too short to be a WPP frame)"
        val header = bytes.copyOfRange(0, WppFraming.HEADER_SIZE)
        val declared = WppFraming.decodePayloadSize(header)
        val type = WppFraming.decodeType(header)
        val known = type in WppMessageType.START_REQUEST..WppMessageType.SETUP_INFO
        val fits = declared == bytes.size - WppFraming.HEADER_SIZE
        return if (known && fits) "(reads as WPP type $type, $declared byte payload)"
        else "(not WPP framing: type $type, declares $declared of ${bytes.size - WppFraming.HEADER_SIZE})"
    }

    /** Report only this opening attempt: a timeout does not establish daemon ownership. */
    internal fun notConnectedVerdict(verdict: ZbtReachabilityPolicy.Verdict?): String =
        when (verdict) {
            ZbtReachabilityPolicy.Verdict.NOTHING_LISTENING ->
                "The connection to port ${ZbtByteChannel.PORT} was refused on this attempt. " +
                    "This does not show whether a compatible daemon is installed or can start later."
            else ->
                "Could not open the Bluetooth module connection on port ${ZbtByteChannel.PORT}. " +
                    "The diagnostic log has the failure details; this result does not identify " +
                    "whether another app owns the module."
        }

    /**
     * A carrier claim covers opening and retry backoff as well as an open socket. Yielding to it
     * avoids a second client, but does not prove that the daemon or phone has answered.
     */
    internal fun busyCarrierVerdict(): String =
        "Open Headunit is already using or opening the Bluetooth module connection. " +
            "The diagnostic did not open a second connection. " +
            "This does not confirm an Android Auto connection to the phone."

    /** A local carrier took priority; its connection outcome is separate from this diagnostic. */
    internal fun carrierTookOverVerdict(): String =
        "Open Headunit requested the Bluetooth module connection, so the diagnostic stopped " +
            "and gave the connection back. This does not confirm an Android Auto connection " +
            "to the phone."

    internal fun verdict(
        rfcommFrames: Int,
        rfcommBytes: Int,
        observedDataAfterVersionRequest: Boolean,
        linkedType: Int?,
        controlFrames: Int,
        phoneConnected: Boolean,
        phoneName: String?,
        askedToReconnect: Boolean
    ): String {
        val phone = phoneName?.let { " ($it)" } ?: ""
        return when {
            rfcommFrames > 0 && observedDataAfterVersionRequest ->
                "Received $rfcommFrames RFCOMM message(s), $rfcommBytes bytes$phone. More data arrived " +
                    "after the version request, but a matching WPP response was not verified."

            rfcommFrames > 0 ->
                "Received $rfcommFrames RFCOMM message(s), $rfcommBytes bytes$phone. " +
                    "A matching WPP version response was not verified."

            linkedType != null ->
                "The module reported a projection link$phone (link type $linkedType), but no RFCOMM " +
                    "data reached this diagnostic. This alone does not identify the cause."

            phoneConnected ->
                "The module's latest status reports a connected phone$phone, but this diagnostic " +
                    "observed no projection link or RFCOMM data" +
                    (if (askedToReconnect) ", even after requesting a reconnect." else ".")

            else ->
                "The diagnostic has no current report of a connected phone. " +
                    "Received $controlFrames status message(s), with no projection link or RFCOMM data observed."
        }
    }
}
