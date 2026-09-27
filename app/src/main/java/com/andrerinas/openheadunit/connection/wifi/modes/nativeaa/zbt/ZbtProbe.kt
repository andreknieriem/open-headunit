package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.WppFraming
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.WppHandshakeSession
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.WppMessageType
import com.andrerinas.openheadunit.aap.protocol.proto.Wireless
import com.andrerinas.openheadunit.utils.AppLog
import java.io.IOException

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
 * So this round drives [ZbtByteChannel] — the same class the transport will use — and answers the
 * one question left, which is *operational* rather than protocol: **will the daemon give those bytes
 * to us?** Three outcomes, and they need different next steps, so the run is built to tell them
 * apart:
 *
 * - RFCOMM data arrives → the route works, and the transport is worth building.
 * - The phone links for projection but no data arrives → the daemon serves one owner, and the
 *   question becomes whether being the only client changes that.
 * - Neither → the phone never started Android Auto, which is a trigger problem and not a protocol
 *   one.
 *
 * Round two could not distinguish the last two, because it had nothing to watch that reported a
 * projection link. `PhoneLinkState` is that signal, and this round watches for it.
 *
 * Still almost inert. It opens a loopback socket and reads. The one thing it can do to the module is
 * [askModuleToReconnect], which the caller has to ask for separately.
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
     * Android Auto. It is the only thing here that changes state on the module rather than observing
     * it, so it is off unless the caller asks, and the log says plainly when it goes out.
     *
     * [keepGoing] is polled between frames; return false to end the run early.
     */
    fun run(
        askModuleToReconnect: Boolean = false,
        onProgress: (String) -> Unit = {},
        keepGoing: () -> Boolean = { true }
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
        var phase = "hello"

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
                            phoneConnected = fields.int(3) == 1
                            fields.string(8)?.takeIf { it.isNotEmpty() }?.let { phoneName = it }
                        }
                        ZbtMessages.LINK_INFO -> phoneConnected = fields.int(3) == 1
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
                bufferRfcommData = false
            )
        } catch (e: IOException) {
            AppLog.i("ZbtProbe: ${e.message}")
            // A failure that is not a connect failure got past the connect, so something is there.
            return notConnectedVerdict(
                (e as? ZbtByteChannel.NotConnected)?.verdict
                    ?: ZbtReachabilityPolicy.classify(e.javaClass.simpleName, answered = false)
            )
        }

        try {
            // Phase 1 — hello. Known-good since 2026-08-11: RequestInit draws InitInfo and a burst of
            // link state. The channel has already sent it.
            onProgress("Asking the Bluetooth module…")
            val helloDeadline = System.currentTimeMillis() + HELLO_BUDGET_MS
            while (System.currentTimeMillis() < helloDeadline && keepProbing()) {
                val pump = channel.pumpOnce(helloDeadline, keepProbing)
                if (pump == ZbtByteChannel.Pump.ENDED) break
                // A gap after the burst means the burst is over; there is nothing else coming until
                // something happens on the module.
                if (pump == ZbtByteChannel.Pump.QUIET && helloFrames > 0) break
                if (helloFrames > 0) onProgress("Module answered — $helloFrames message(s)…")
            }

            if (yielded) return carrierTookOverVerdict()

            if (helloFrames == 0) {
                if (ZbtDaemonReachability.carrierLive()) return busyCarrierVerdict()
                return if (channel.isFinished) {
                    "Something is listening on port ${ZbtByteChannel.PORT}, but it closed the " +
                        "connection without answering."
                } else {
                    "Something is listening on port ${ZbtByteChannel.PORT} but it never answered. " +
                        "The port is open and our message got no reply."
                }
            }
            channel.moduleMac?.let { AppLog.i("ZbtProbe: the module reports its Bluetooth address as $it") }

            // Phase 2 — watch. Nothing we send makes the phone start Android Auto; the most we can do
            // is ask the module to bring its Bluetooth up and then wait.
            phase = "watch"
            if (askModuleToReconnect) {
                AppLog.i("ZbtProbe: asking the module to reconnect for Android Auto (RequestReconn, enable_type=2)")
                onProgress("Asking the module to reconnect…")
                channel.requestReconnect(ZbtMessages.ENABLE_TYPE_ANDROID_AUTO)
            }
            onProgress("Watching for Android Auto — this takes about a minute and a half…")

            val start = System.currentTimeMillis()
            val deadline = start + WATCH_BUDGET_MS
            var nextSummary = start + SUMMARY_EVERY_MS
            var nextLinkInfo = start + LINK_INFO_EVERY_MS
            var versionRequestAt = 0L
            var rfcommBeforeVersionRequest = 0

            while (System.currentTimeMillis() < deadline && keepProbing()) {
                // Wake at whichever comes first: the end of the watch, the next summary line, or the
                // next link-state poll. Between those the pump is simply waiting for the phone.
                val wakeAt = minOf(deadline, nextSummary, nextLinkInfo)
                if (channel.pumpOnce(wakeAt, keepProbing) == ZbtByteChannel.Pump.ENDED) {
                    AppLog.w("ZbtProbe: the daemon ended the connection during the watch")
                    break
                }
                val now = System.currentTimeMillis()

                // Only once there is evidence the channel is live. Writing into a channel with no
                // Android Auto session behind it would be sending bytes at nothing, and a reply is
                // only meaningful if we know what provoked it.
                if (versionRequestAt == 0L && (rfcommFrames > 0 || linkedType != null)) {
                    rfcommBeforeVersionRequest = rfcommFrames
                    versionRequestAt = now
                    sendVersionRequest(channel)
                    onProgress("Android Auto is on the module — asking it to talk to us…")
                }

                if (now >= nextSummary) {
                    nextSummary = now + SUMMARY_EVERY_MS
                    val seconds = (now - start) / 1000
                    AppLog.i(
                        "ZbtProbe: ${seconds}s watching — RFCOMM $rfcommFrames message(s)/$rfcommBytes bytes, " +
                            "control $controlFrames, phone ${if (phoneConnected) "connected" else "not connected"}" +
                            (phoneName?.let { " ($it)" } ?: "") +
                            (linkedType?.let { ", projection link type $it" } ?: ", no projection link")
                    )
                    onProgress(
                        if (rfcommFrames > 0) "Android Auto data arriving — $rfcommFrames message(s)…"
                        else "Watching — ${seconds}s, nothing from Android Auto yet…"
                    )
                }

                if (now >= nextLinkInfo) {
                    nextLinkInfo = now + LINK_INFO_EVERY_MS
                    channel.requestLinkInfo()
                }
            }

            if (yielded) return carrierTookOverVerdict()

            return verdict(
                rfcommFrames = rfcommFrames,
                rfcommBytes = rfcommBytes,
                answeredVersionRequest = versionRequestAt != 0L && rfcommFrames > rfcommBeforeVersionRequest,
                linkedType = linkedType,
                controlFrames = controlFrames,
                phoneConnected = phoneConnected,
                phoneName = phoneName,
                askedToReconnect = askModuleToReconnect
            )
        } catch (e: Exception) {
            AppLog.e("ZbtProbe: the exchange failed: ${e.javaClass.simpleName}: ${e.message}")
            return "Connected to the module but the exchange failed: ${e.javaClass.simpleName}."
        } finally {
            // Read before closing: close() claims the reason itself if nothing has, and the reason
            // the channel ended on its own is the more informative of the two.
            val ended = channel.closeReason
            channel.close()
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
     * the probe has no settings to read. A reply here only has to prove the module carries WPP frames
     * at all, and the framing is the real one, so a pass cannot come from a shape the transport
     * rejects.
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

    /**
     * What to say when the port would not open.
     *
     * A refusal and a port that is held but not accepting are different findings, and reporting
     * the second as the first told a reporter their working module was absent hardware.
     */
    internal fun notConnectedVerdict(verdict: ZbtReachabilityPolicy.Verdict?): String =
        if (verdict == ZbtReachabilityPolicy.Verdict.LISTENING_SILENT) {
            "Something is listening on port ${ZbtByteChannel.PORT} but it would not take the " +
                "connection. On some units the module only answers while no phone is linked to " +
                "it, so turn the phone's Bluetooth off and test again."
        } else {
            "Nothing is listening on port ${ZbtByteChannel.PORT}. " +
                "This unit has no vendor Bluetooth daemon to talk to."
        }

    /**
     * What to say when this app's own connection already holds the module's one client slot.
     *
     * A busy daemon is proof the route works, so this must never be reported as silence.
     */
    internal fun busyCarrierVerdict(): String =
        "This unit is already carrying Android Auto over the module. The module gives it to one " +
            "program at a time, and that program is this app right now, so the route works. " +
            "To test it on its own, turn the module transport off first."

    /**
     * What to say when a real connection claimed the module while this test was running.
     *
     * Giving the slot back is the right outcome: holding it kept a connection waiting 90 seconds
     * on the unit this was measured on.
     */
    internal fun carrierTookOverVerdict(): String =
        "This unit started carrying Android Auto over the module while the test was running, so " +
            "the test stopped and gave the module back. The module gives it to one program at a " +
            "time. To test it on its own, turn the module transport off first."

    internal fun verdict(
        rfcommFrames: Int,
        rfcommBytes: Int,
        answeredVersionRequest: Boolean,
        linkedType: Int?,
        controlFrames: Int,
        phoneConnected: Boolean,
        phoneName: String?,
        askedToReconnect: Boolean
    ): String {
        val phone = phoneName?.let { " ($it)" } ?: ""
        return when {
            rfcommFrames > 0 && answeredVersionRequest ->
                "The module carried Android Auto both ways: $rfcommFrames message(s), $rfcommBytes " +
                    "bytes, and the phone$phone answered us."

            rfcommFrames > 0 ->
                "The module carried $rfcommFrames Android Auto message(s), $rfcommBytes bytes, from " +
                    "the phone$phone. Nothing came back to our reply."

            linkedType != null ->
                "The phone$phone started Android Auto on the module (link type $linkedType) but none " +
                    "of the data reached us — the module gives it to one program at a time."

            phoneConnected ->
                "The phone$phone is connected to the module, but it never started Android Auto " +
                    "during the test" + (if (askedToReconnect) ", even after being asked to." else ".")

            else ->
                "No phone was connected to the module during the test. " +
                    "$controlFrames status message(s) and nothing else."
        }
    }
}
