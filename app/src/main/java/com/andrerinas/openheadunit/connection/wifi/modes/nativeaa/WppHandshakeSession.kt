package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/** Message types of the WiFi Projection Protocol. See `wireless.proto` for the payload shapes. */
object WppMessageType {
    /** Head unit -> phone: where to open the projection session. */
    const val START_REQUEST = 1
    /** Phone -> head unit: asking for the network credentials. */
    const val INFO_REQUEST = 2
    /** Head unit -> phone: the credentials. */
    const val INFO_RESPONSE = 3
    /** Head unit -> phone: our protocol version, opening the modern handshake. */
    const val VERSION_REQUEST = 4
    /** Phone -> head unit: its protocol version. */
    const val VERSION_RESPONSE = 5
    /** Phone -> head unit: whether it managed to join our network. */
    const val CONNECT_STATUS = 6
    /** Phone -> head unit: acknowledges [START_REQUEST]. */
    const val START_RESPONSE = 7
    /** Keepalive, either direction. A request is answered by echoing its payload back as a response. */
    const val PING_REQUEST = 8
    const val PING_RESPONSE = 9
    /** Head unit -> phone, TCP only: withdraws the endpoint the phone dialled. */
    const val CONNECTION_REJECTION = 10
    /** Not sent; defined so a capture can be read. */
    const val SETUP_INFO = 11
}

/** Where a [WppHandshakeSession] is in the exchange. */
enum class WppStage {
    /** Nothing sent yet. */
    NEW,
    /** [WppMessageType.VERSION_REQUEST] is out; waiting for the phone's version, briefly. */
    AWAIT_VERSION,
    /** Waiting for this head unit's own WiFi credentials to become available. */
    AWAIT_CREDENTIALS,
    /** [WppMessageType.START_REQUEST] is out; waiting for the phone to ask for credentials. */
    AWAIT_INFO_REQUEST,
    /** Credentials delivered; the phone is associating, doing WPS and getting a DHCP lease. */
    SETTLING,
    /** The phone's projection session landed. */
    DONE,
    /** The exchange ended without a session. */
    FAILED
}

/** Something that happened to a handshake. Fed to [WppHandshakeSession.on]. */
sealed class WppEvent {
    /** The Bluetooth channel is up and has had its settle delay. */
    object SocketReady : WppEvent()
    /** This head unit's SSID/PSK/IP are known. */
    object CredentialsReady : WppEvent()
    /** They never became available, and waiting longer will not help. */
    object CredentialsUnavailable : WppEvent()
    /**
     * A message arrived from the phone. [status] is the parsed status field for the types that
     * carry one (5, 6, 7), or null when the message has no status or could not be parsed — an
     * unparseable message is never read as a failure report.
     */
    data class MessageReceived(val type: Int, val status: Int? = null) : WppEvent()
    /** The current stage's deadline ([WppHandshakeSession.currentStageTimeoutMs]) elapsed. */
    object StageTimeout : WppEvent()
    /** The phone's projection session reached us over WiFi. */
    object TcpSessionUp : WppEvent()
    /** The settling window elapsed with no session. */
    object SettleTimeout : WppEvent()
    /** The network the phone was sent has been taken down, so its join can no longer succeed. */
    object NetworkWithdrawn : WppEvent()
}

/**
 * What the caller should do about it. The session performs no I/O of its own — that is the whole
 * point of it being pure — so every effect leaves as one of these.
 */
sealed class WppAction {
    object SendVersionRequest : WppAction()
    object SendStartRequest : WppAction()
    /** Send the credentials. In [WppStage.SETTLING] this is a re-send of the same ones. */
    object SendInfoResponse : WppAction()
    /**
     * Echo the payload of the ping that produced this action back as a
     * [WppMessageType.PING_RESPONSE]. Raw bytes, so the reply cannot be wrong.
     */
    object SendPingResponse : WppAction()
    /** The phone is still working on it — push the settling deadline out. */
    object ExtendSettle : WppAction()
    object CompleteSuccess : WppAction()
    /**
     * [phoneWasSilent] is true when the phone never sent a single message — the signature of a
     * head unit whose Bluetooth stack drops our writes, not of a phone-side problem. It is what
     * the handshake backoff counts.
     *
     * [joinRefused] is the opposite shape: the phone answered everything and then said it could
     * not get onto the network. That backoff cannot see it, because the phone was never silent.
     */
    data class Fail(
        val reason: String,
        val phoneWasSilent: Boolean,
        val joinRefused: Boolean = false
    ) : WppAction()
    /** Restart the wake poke. Only ever emitted once the phone has given up on this handoff. */
    object ResumePoke : WppAction()
}

/**
 * The Android Auto wireless handshake, as a state machine with no Android in it.
 *
 * The exchange this models is the modern one, cross-checked against aa-proxy-rs's reference dongle
 * and the OEM ZLink app:
 *
 * ```
 *   HU -> 4 WifiVersionRequest        (optional; real head units send it, aa-proxy's dongle does not)
 *   HU <- 5 WifiVersionResponse
 *   HU -> 1 WifiStartRequest
 *   HU <- 2 WifiInfoRequest
 *   HU -> 3 WifiInfoResponse          credentials
 *   HU <- 7 WifiStartResponse         acknowledgement
 *   HU <- 6 WifiConnectStatus         did the phone actually get on the network
 * ```
 *
 * A phone that is already on the network skips 2 and 3, since it needs no credentials: it answers
 * the start request with 7 and reports the join with 6. That is the ordinary shape over TCP, where
 * the phone dials us from inside the network, and measured on hardware.
 *
 * What this replaces sent 1, read one message, sent 3 and stopped listening. Types 6 and 7 arrive
 * *after* type 3, so it never saw them — hence no way to tell "still associating" from "gave up",
 * and a timer where the phone was willing to say.
 *
 * Pure so the ordering rules can be tested; they cannot be, on a real Bluetooth socket. Not
 * thread-safe: one session per handshake coroutine.
 *
 * Opens with [WppMessageType.VERSION_REQUEST], as a real head unit does, so the phone learns our
 * version and where to reach us over TCP. What goes in it is [WppEndpointPolicy]'s decision, not
 * this session's.
 */
class WppHandshakeSession {

    companion object {
        /** How long to wait for the phone's version response before carrying on without it.
         *  Short on purpose: it must not eat into the phone's own ~12 s first-message budget. */
        const val VERSION_RESPONSE_TIMEOUT_MS = 3_000L

        /** How long the phone has to ask for credentials after [WppMessageType.START_REQUEST].
         *  Unchanged from the value this replaces. */
        const val INFO_REQUEST_TIMEOUT_MS = 15_000L

        /** How much longer to wait each time the phone says it is still joining. */
        const val SETTLE_EXTENSION_MS = 15_000L

        /** Our protocol version, read out of Gearhead 17.5. The phone ignores the WPP-over-TCP
         *  endpoint below 4.1, and at exactly 4.1 it also requires our make to be on an allowlist
         *  we are not on. 4.2 clears both, and announcing a version above the phone's own is
         *  negotiated down rather than refused. */
        const val WPP_VERSION_MAJOR = 4
        const val WPP_VERSION_MINOR = 2
    }

    var stage: WppStage = WppStage.NEW
        private set

    /** How many messages the phone has sent. Zero at the end of a handshake means the phone never
     *  answered anything, which is what the backoff in `NativeAaHandshakeManager` counts. */
    var messagesReceived: Int = 0
        private set

    /** The phone asked for credentials before we were ready to send them. */
    private var pendingInfoRequest = false

    /** Credentials arrived while we were still waiting on the version response. */
    private var credentialsLatched = false

    /** Total time allowed in [WppStage.SETTLING], grown by [WppAction.ExtendSettle]. */
    private var settleBudgetMs = NativeHandoffPolicy.SETTLE_TIMEOUT_MS

    /**
     * How long the current stage may last before its timeout event is due, or null when the stage
     * has no deadline of its own. Measured from when the stage was entered; re-read it after every
     * [on] call, since [WppAction.ExtendSettle] grows it.
     */
    fun currentStageTimeoutMs(): Long? = when (stage) {
        WppStage.AWAIT_VERSION -> VERSION_RESPONSE_TIMEOUT_MS
        WppStage.AWAIT_INFO_REQUEST -> INFO_REQUEST_TIMEOUT_MS
        WppStage.SETTLING -> settleBudgetMs
        // AWAIT_CREDENTIALS is bounded by the caller's own credential wait, which ends in
        // CredentialsReady or CredentialsUnavailable; NEW, DONE and FAILED never expire.
        else -> null
    }

    /** True once the exchange is over, either way. */
    fun isTerminal(): Boolean = stage == WppStage.DONE || stage == WppStage.FAILED

    /**
     * Feed [event] in and get back what to do about it, in order. Safe to call after the session
     * has finished: a terminal session ignores everything and returns nothing.
     */
    fun on(event: WppEvent): List<WppAction> {
        if (isTerminal()) return emptyList()

        // The projection session landing ends the exchange from wherever it has got to: whatever
        // the phone was still going to ask for, it has stopped needing. Over TCP that can happen
        // before the exchange has run its course, because the phone dials us from inside the
        // network it is already on.
        if (event is WppEvent.TcpSessionUp) {
            stage = WppStage.DONE
            return listOf(WppAction.CompleteSuccess)
        }

        if (event is WppEvent.MessageReceived) {
            messagesReceived++
            // A ping can arrive at any point in the exchange and is answered the same way
            // everywhere, so it never reaches the per-stage logic below and never changes stage.
            if (event.type == WppMessageType.PING_REQUEST) return listOf(WppAction.SendPingResponse)
            if (event.type == WppMessageType.PING_RESPONSE) return emptyList()
        }

        return when (stage) {
            WppStage.NEW -> onNew(event)
            WppStage.AWAIT_VERSION -> onAwaitVersion(event)
            WppStage.AWAIT_CREDENTIALS -> onAwaitCredentials(event)
            WppStage.AWAIT_INFO_REQUEST -> onAwaitInfoRequest(event)
            WppStage.SETTLING -> onSettling(event)
            WppStage.DONE, WppStage.FAILED -> emptyList()
        }
    }

    private fun onNew(event: WppEvent): List<WppAction> = when (event) {
        is WppEvent.SocketReady -> {
            stage = WppStage.AWAIT_VERSION
            listOf(WppAction.SendVersionRequest)
        }
        is WppEvent.CredentialsReady -> { credentialsLatched = true; emptyList() }
        is WppEvent.CredentialsUnavailable -> fail("no WiFi credentials to hand the phone")
        else -> emptyList()
    }

    private fun onAwaitVersion(event: WppEvent): List<WppAction> = when {
        event is WppEvent.MessageReceived && event.type == WppMessageType.VERSION_RESPONSE ->
            enterAwaitCredentials()
        // No answer to type 4. Phones complete the rest of the exchange without it, so carrying
        // on beats failing on an optional step.
        event is WppEvent.StageTimeout -> enterAwaitCredentials()
        // Some phones skip the version response and ask for credentials straight away. Latch it:
        // still answered, just not before type 1 goes out.
        event is WppEvent.MessageReceived && event.type == WppMessageType.INFO_REQUEST -> {
            pendingInfoRequest = true
            enterAwaitCredentials()
        }
        // Hold credentials that resolve early rather than jumping the queue: ordering 4 before 1
        // is the only reason to send type 4 at all.
        event is WppEvent.CredentialsReady -> { credentialsLatched = true; emptyList() }
        event is WppEvent.CredentialsUnavailable -> fail("no WiFi credentials to hand the phone")
        else -> emptyList()
    }

    private fun onAwaitCredentials(event: WppEvent): List<WppAction> = when {
        event is WppEvent.CredentialsReady -> credentialsBecameAvailable()
        event is WppEvent.MessageReceived && event.type == WppMessageType.INFO_REQUEST -> {
            pendingInfoRequest = true
            emptyList()
        }
        event is WppEvent.CredentialsUnavailable -> fail("no WiFi credentials to hand the phone")
        else -> emptyList()
    }

    private fun onAwaitInfoRequest(event: WppEvent): List<WppAction> = when {
        event is WppEvent.MessageReceived && event.type == WppMessageType.INFO_REQUEST -> {
            stage = WppStage.SETTLING
            listOf(WppAction.SendInfoResponse)
        }
        event is WppEvent.MessageReceived && event.type == WppMessageType.START_RESPONSE ->
            if (isFailureStatus(event.status)) {
                fail("phone rejected the projection endpoint (WifiStartResponse status=${WppStatus.describe(event.status)})")
            } else {
                emptyList()
            }
        // A phone already on our network never asks for credentials. Over TCP it dialled us from
        // inside the network, so it acknowledges the start request and then reports the join, and
        // waiting for a type 2 that cannot come would fail a handoff that has already worked.
        event is WppEvent.MessageReceived && event.type == WppMessageType.CONNECT_STATUS ->
            if (isFailureStatus(event.status)) {
                fail(
                    "phone reported join failure (type ${event.type}, status=${WppStatus.describe(event.status)})",
                    joinRefused = true
                ) + WppAction.ResumePoke
            } else {
                stage = WppStage.SETTLING
                emptyList()
            }
        event is WppEvent.StageTimeout ->
            fail("phone did not ask for credentials within ${INFO_REQUEST_TIMEOUT_MS / 1000}s of WifiStartRequest")
        event is WppEvent.CredentialsUnavailable -> fail("WiFi credentials went away mid-handshake")
        else -> emptyList()
    }

    private fun onSettling(event: WppEvent): List<WppAction> = when {
        event is WppEvent.MessageReceived &&
            (event.type == WppMessageType.CONNECT_STATUS || event.type == WppMessageType.START_RESPONSE) ->
            if (isFailureStatus(event.status)) {
                // The phone has stopped trying, so nothing is left to disturb: this and the
                // settle timeout are the only places where restarting the poke is safe.
                // Only type 6 is the phone failing to reach the network; a rejected type 7 is the
                // endpoint being refused, which a prompt retry can still fix.
                fail(
                    "phone reported join failure (type ${event.type}, status=${WppStatus.describe(event.status)})",
                    joinRefused = event.type == WppMessageType.CONNECT_STATUS
                ) + WppAction.ResumePoke
            } else if (event.type == WppMessageType.CONNECT_STATUS) {
                extendSettle()
            } else {
                // A successful type 7 is an acknowledgement, not progress on the join.
                emptyList()
            }
        // aa-proxy-rs re-sends the credentials when the phone asks twice; a phone that asks again
        // has not given up, and refusing it would strand a handoff that could still complete.
        event is WppEvent.MessageReceived && event.type == WppMessageType.INFO_REQUEST ->
            listOf(WppAction.SendInfoResponse)
        // Some platforms drop an emptied group the moment the phone's first try leaves it, and
        // the phone then hunts the old name until it gives up. Ending now hands it the new one.
        event is WppEvent.NetworkWithdrawn ->
            fail("the network the phone was sent was taken down while it was joining") + WppAction.ResumePoke
        // Today's behaviour: leave the listeners open and let the wake poke retry.
        event is WppEvent.SettleTimeout -> {
            stage = WppStage.FAILED
            listOf(WppAction.ResumePoke)
        }
        else -> emptyList()
    }

    /** Enter [WppStage.AWAIT_CREDENTIALS], acting immediately if the credentials already landed. */
    private fun enterAwaitCredentials(): List<WppAction> {
        stage = WppStage.AWAIT_CREDENTIALS
        return if (credentialsLatched) credentialsBecameAvailable() else emptyList()
    }

    private fun credentialsBecameAvailable(): List<WppAction> {
        credentialsLatched = true
        return if (pendingInfoRequest) {
            // It asked while the network was still coming up: answer both back to back rather
            // than wait for it to ask again.
            stage = WppStage.SETTLING
            listOf(WppAction.SendStartRequest, WppAction.SendInfoResponse)
        } else {
            stage = WppStage.AWAIT_INFO_REQUEST
            listOf(WppAction.SendStartRequest)
        }
    }

    private fun extendSettle(): List<WppAction> {
        val extended = settleBudgetMs + SETTLE_EXTENSION_MS
        if (extended > NativeHandoffPolicy.MAX_SETTLE_MS) {
            // Capped: a phone that says "still joining" forever would otherwise hold the
            // Bluetooth channel open and the wake poke suppressed indefinitely.
            return emptyList()
        }
        settleBudgetMs = extended
        return listOf(WppAction.ExtendSettle)
    }

    private fun fail(reason: String, joinRefused: Boolean = false): List<WppAction> {
        stage = WppStage.FAILED
        return listOf(
            WppAction.Fail(reason, phoneWasSilent = messagesReceived == 0, joinRefused = joinRefused)
        )
    }

    /**
     * Whether a status field reports a failure. Only `!= 0` counts, never a particular value —
     * the meanings of the non-zero codes are not known (a real phone answers -8 on type 5 during
     * an exchange that goes on to succeed). Null is not a failure either: an unparseable message
     * must never abort a handshake that was going fine.
     */
    private fun isFailureStatus(status: Int?): Boolean = status != null && status != 0
}
