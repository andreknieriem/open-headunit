package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeHandoffPolicy

/**
 * When to ask the external Bluetooth module to bring the phone's link up.
 *
 * The wake poke on the ordinary route dials the phone's hands-free profile over `android.bluetooth`
 * to make Android Auto notice us. On a unit whose Bluetooth is an external module that does
 * nothing at all — it goes out over the radio the phone is not paired to. The module-side
 * equivalent is `RequestReconn` (`0x114`), which the daemon does register a handler for.
 *
 * It is a heavier thing to send than a poke, so it is paced more conservatively. It asks the module
 * to change state while the vendor's own client is also connected to the same daemon, and its
 * effect on that client has been observed exactly once.
 */
object ZbtWakePolicy {

    /**
     * Shortest gap between two `RequestReconn` messages.
     *
     * Matched to the poke cadence it replaces rather than derived from anything the module says.
     * It also has to stay clear of the module's own link-state heartbeat, about twelve seconds on
     * the unit this was measured on, so that a wake and a heartbeat are still distinguishable in a
     * log read after the fact.
     */
    const val MIN_INTERVAL_MS = 15_000L

    /**
     * Whether to send one now.
     *
     * The three state flags are the ordinary poke's, so this asks
     * [NativeHandoffPolicy.shouldPoke] rather than restating them. What is this policy's own is the
     * minimum interval: the module has no accept event to pace against.
     *
     * @param lastSentAtMs when the last one went out, or 0 if none has
     * @param settling a handoff is still settling
     * @param handshakeInFlight an exchange is running
     * @param sessionConnected a projection session is already up
     */
    fun shouldSend(
        lastSentAtMs: Long,
        nowMs: Long,
        settling: Boolean,
        handshakeInFlight: Boolean,
        sessionConnected: Boolean
    ): Boolean {
        if (!NativeHandoffPolicy.shouldPoke(settling, handshakeInFlight, sessionConnected)) return false
        if (lastSentAtMs == 0L) return true
        val elapsed = nowMs - lastSentAtMs
        // A negative elapsed means the clock moved backwards under us. Treat it as "too soon"
        // rather than "long enough ago": waiting one more interval is harmless, and the alternative
        // is a burst of state-changing messages at a moment we cannot reason about.
        return elapsed >= MIN_INTERVAL_MS
    }

    /** How long a wake asked for before it could go out is still worth sending. */
    const val PENDING_MAX_MS = 60_000L

    /**
     * Give the daemon the same opening-state budget as the diagnostic hello. InitInfo
     * (0x102) identifies the module; LinkInfo/LinkInfo2 (0x104/0x10c) arrive separately.
     * There is no end-of-burst marker. A usable peer can start its handshake immediately,
     * while a silent or unclassified module must still get a chance to be woken.
     * This bounds observation, not the time required for HFP to connect.
     */
    const val INITIAL_STATE_WAIT_MS = 8_000L

    enum class Pending { SEND, HOLD, SESSION_UP, PHONE_PRESENT, EXPIRED }

    /**
     * What to do with a wake that was asked for and not yet sent. A cold bring-up asks before the
     * module channel exists, so the request waits for the carrier's own thread to send it.
     */
    fun pending(
        requestedAtMs: Long,
        lastSentAtMs: Long,
        nowMs: Long,
        settling: Boolean,
        handshakeInFlight: Boolean,
        sessionConnected: Boolean,
        channelOpenForMs: Long = INITIAL_STATE_WAIT_MS,
        phonePresent: Boolean = false
    ): Pending = when {
        sessionConnected -> Pending.SESSION_UP
        // Link presence proves enough to attempt AA, not a bond or a working HFP
        // profile. Neither the vendor's is_pair scalar nor HFP=0 authorizes recovery.
        phonePresent -> Pending.PHONE_PRESENT
        nowMs < requestedAtMs || nowMs - requestedAtMs >= PENDING_MAX_MS -> Pending.EXPIRED
        channelOpenForMs < INITIAL_STATE_WAIT_MS -> Pending.HOLD
        shouldSend(lastSentAtMs, nowMs, settling, handshakeInFlight, sessionConnected) -> Pending.SEND
        else -> Pending.HOLD
    }
}
