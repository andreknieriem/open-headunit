package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

/**
 * When to try the Android Auto handshake over the external Bluetooth module.
 *
 * There is no "the phone connected" event on this route. Our own RFCOMM listener is what normally
 * says a phone has arrived, and here there is none: the daemon owns the Android Auto RFCOMM server
 * on the module, accepts the phone itself, and tells us nothing about it directly. The head unit is
 * also the side that speaks first in this handshake, so waiting for a byte from the phone would
 * wait forever.
 *
 * The vendor's own client solves this the same way, and that is where these rules come from. Its
 * log shows it gating on one thing — a link-state message naming a connected phone the module has
 * classified as Android Auto — and then sending `WifiVersionRequest` optimistically and retrying,
 * with no connection event of any kind in between. So: treat that message as the arrival signal,
 * attempt, and let the attempt's own timeouts recycle it.
 *
 * `PhoneLinkState` (`0x11f`) is deliberately **not** used, even though it looks like the ideal
 * signal. In the vendor's code the call that emits it is gated on a link-mode mask that
 * Android-Auto-only units do not carry, so on exactly the hardware this serves it is never sent.
 */
object ZbtAttemptPolicy {

    /**
     * The module's view of the phone, as one link-state message describes it.
     *
     * @param connected the module reports a Bluetooth connection to the phone
     * @param androidAuto the module has classified that phone as an Android Auto device
     */
    data class Presence(
        val connected: Boolean,
        val androidAuto: Boolean,
        val phoneName: String? = null,
        val phoneMac: String? = null
    ) {
        /** Worth attempting a handshake against. */
        val usable: Boolean get() = connected && androidAuto
    }

    /** The value of `phone_type` the module uses for a phone it will do Android Auto with. */
    const val PHONE_TYPE_ANDROID_AUTO = 1

    /**
     * What [msgId] says about the phone, or null if it says nothing.
     *
     * Field numbers differ between the two link-state messages and are the kind of detail that is
     * wrong silently, so they are read here and pinned by tests against real captured bodies rather
     * than open-coded at the call site.
     */
    fun readPresence(msgId: Int, fields: ZbtMessages.Record?): Presence? {
        if (fields == null || msgId !in setOf(ZbtMessages.LINK_INFO, ZbtMessages.LINK_INFO2)) return null
        // The vendor LinkInfo descriptors require is_connect (field 3) and phone_type (2).
        // Missing fields are unknown, not a down edge. An explicit is_connect=0 can still
        // release the current link even if its phone type was omitted.
        val connected = fields.int(3)
        if (connected != 0 && connected != 1) return null
        if (connected == 1 && fields.int(2) == null) return null
        return when (msgId) {
            // { 1 id, 2 phone_type, 3 is_connect, 4 is_pair, 5 local_mac_address }
            ZbtMessages.LINK_INFO -> Presence(
                connected = fields.int(3) == 1,
                androidAuto = fields.int(2) == PHONE_TYPE_ANDROID_AUTO
            )
            // { …, 2 phone_type, 3 is_connect, …, 7 phone_bt_mac_addr, 8 phone_bt_name }
            ZbtMessages.LINK_INFO2 -> Presence(
                connected = fields.int(3) == 1,
                androidAuto = fields.int(2) == PHONE_TYPE_ANDROID_AUTO,
                phoneName = fields.string(8)?.takeIf { it.isNotEmpty() },
                phoneMac = fields.string(7)?.takeIf { it.isNotEmpty() }
            )
            else -> null
        }
    }

    /**
     * Whether [current] is the phone *arriving*, rather than a repeat of what we already knew.
     *
     * The module re-sends link state on a timer — roughly every twelve seconds on the unit this was
     * measured on — so acting on every message would start an attempt every twelve seconds forever.
     * Only the edge is interesting.
     *
     * A usable report naming a different phone than a usable [previous] is also an arrival: that is
     * a new peer, not a heartbeat. Only both addresses being present can show that; a report
     * without one (legacy LinkInfo) never counts as a different phone.
     */
    fun isArrival(previous: Presence?, current: Presence): Boolean =
        current.usable && (previous?.usable != true || isDifferentPhone(previous.phoneMac, current.phoneMac))

    private fun isDifferentPhone(before: String?, now: String?): Boolean {
        if (before == null || now == null) return false
        fun canonical(mac: String) = mac.filter { it.isLetterOrDigit() }.uppercase()
        return canonical(before) != canonical(now)
    }

    /**
     * Whether an attempt may start now.
     *
     * @param phonePresent the module currently reports a usable phone
     * @param attemptInFlight one is already running
     * @param sessionConnected a projection session is already up, so there is nothing to start
     * @param sinceLastAttemptMs how long ago the last attempt ended, or null if none has
     * @param minIntervalMs the gap to leave between attempts, which a caller widens when the phone
     *   keeps refusing the network rather than failing to answer
     * @param channelOpenForMs how long this channel has been open, or null when the caller does not
     *   track it
     * @param everSawPresence any usable presence has been reported on this channel
     */
    fun shouldAttempt(
        phonePresent: Boolean,
        attemptInFlight: Boolean,
        sessionConnected: Boolean,
        sinceLastAttemptMs: Long?,
        minIntervalMs: Long = MIN_ATTEMPT_INTERVAL_MS,
        channelOpenForMs: Long? = null,
        everSawPresence: Boolean = false
    ): Boolean {
        if (attemptInFlight || sessionConnected) return false
        if (!phonePresent && !mayAttemptBlind(channelOpenForMs, everSawPresence)) return false
        // A failed attempt costs the phone nothing, but back-to-back attempts would spend the whole
        // window in setup rather than waiting for a phone that is simply not ready yet.
        return sinceLastAttemptMs == null || sinceLastAttemptMs >= minIntervalMs
    }

    /**
     * Whether to speak without the module ever having said a phone is there.
     *
     * A module that never reports presence, or reports it with a `phone_type` this does not
     * recognise, would otherwise be pumped forever in silence. The head unit speaks first here, so
     * an attempt against an empty car costs a few seconds of quiet and nothing else.
     *
     * [everSawPresence] means a *usable* presence: a phone the module reported but did not classify
     * as Android Auto leaves it false, and is spoken to.
     */
    fun mayAttemptBlind(channelOpenForMs: Long?, everSawPresence: Boolean): Boolean =
        !everSawPresence && channelOpenForMs != null && channelOpenForMs >= BLIND_ATTEMPT_AFTER_MS

    /**
     * How long a channel must be open in silence before we speak anyway.
     *
     * [ZbtProbe.HELLO_BUDGET_MS] budgets eight seconds for the daemon's opening state burst, so by
     * ten the burst has been and gone and nothing in it named a phone.
     */
    const val BLIND_ATTEMPT_AFTER_MS = 10_000L

    /**
     * How long to leave between attempts.
     *
     * The vendor retries its opening message three times about 2.1 s apart and then gives up on the
     * session entirely. Ours is a whole handshake rather than one message, and it fails only after
     * its own stage timeouts have run, so the gap between attempts is longer and the cost of an
     * attempt against an absent phone is a few seconds of quiet rather than a wasted session.
     */
    const val MIN_ATTEMPT_INTERVAL_MS = 5_000L
}
