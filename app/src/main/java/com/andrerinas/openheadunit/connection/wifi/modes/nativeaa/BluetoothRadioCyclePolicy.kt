package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/**
 * Whether to raise the Bluetooth event Android Auto needs by cycling this unit's own radio, rather
 * than by poking over a hands-free link the unit already holds.
 *
 * A poke takes the phone's single hands-free slot and cannot give it back. A cycle drops the ACL
 * instead, so the unit's own stack reconnects and the phone sees `ACL_CONNECTED` and the hands-free
 * `CONNECTION_STATE_CHANGED` for an address that is connected with its profile: Gearhead's trigger
 * and its presence gate, with no slot taken from anyone.
 */
object BluetoothRadioCyclePolicy {

    /** Last release where `BluetoothAdapter.disable()` does anything; from 33 it is a no-op. */
    const val LAST_CYCLEABLE_SDK = 32

    /** One per arming. A cycle costs every Bluetooth device on the unit a few seconds. */
    const val MAX_CYCLES_PER_ARMING = 1

    /** Shared with the poke it replaces, so a phone that is merely slow is never disturbed twice. */
    const val CYCLE_AFTER_MS = HandsFreeWakeEscalationPolicy.ESCALATE_AFTER_MS
    const val CYCLE_COOLDOWN_MS = HandsFreeWakeEscalationPolicy.ESCALATION_COOLDOWN_MS

    /** How long the unit's own stack is given to reconnect before the cycle is judged. */
    const val RECOVERY_WINDOW_MS = NativeAaWakeDamagePolicy.PROBE_WINDOW_MS

    /**
     * How long the device is left alone afterwards. Longer than the recovery window because the
     * phone has to act on the event too: the one measured poke-to-dial-back took 66 s, and a poke
     * 15 s after the cycle would take back the link it just handed over.
     */
    const val POST_CYCLE_QUIET_MS = 90_000L

    /** Bounds on the two adapter transitions, so a radio that never answers cannot hang the loop. */
    const val ADAPTER_OFF_WAIT_MS = 10_000L
    const val ADAPTER_ON_WAIT_MS = 20_000L

    /** What this unit is known to do with its own Bluetooth after a cycle. */
    enum class Verdict {
        /** Never measured. The next cycle is the probe. */
        UNKNOWN,

        /** The radio came back and reconnected the hands-free link on its own. */
        RECOVERS,

        /** The radio or the link did not come back. Nothing cycles on this unit again. */
        STRANDS;

        companion object {
            /** Stored as an int so an unknown value from a newer build reads as unmeasured. */
            fun of(stored: Int): Verdict = entries.getOrNull(stored) ?: UNKNOWN
        }
    }

    /** What a stand-down pass does about a hands-free link that raises no event. */
    enum class StandDownAction { NOTHING, CYCLE_RADIO, ESCALATED_WAKE }

    /**
     * The one place the two levers are chosen between, so they can never both run for one pass.
     * The cycle goes first: it gives the link back where the wake cannot. [wakeAllowed] is a lambda
     * because asking it spends the wake's budget and arms its probe, which a chosen cycle must not.
     */
    fun chooseAction(cycleAllowed: Boolean, wakeAllowed: () -> Boolean): StandDownAction = when {
        cycleAllowed -> StandDownAction.CYCLE_RADIO
        wakeAllowed() -> StandDownAction.ESCALATED_WAKE
        else -> StandDownAction.NOTHING
    }

    /**
     * Whether to cycle the radio for a stand-down that has run long enough.
     *
     * @param reason why [BluetoothWakePolicy.wakeDecision] refused. Only TARGET_CONNECTED cycles:
     *   TARGET_UNREADABLE could not tell whose link it is, and a guess is not a reason to drop it.
     * @param pairingHasRunAaHere a phone paired only for calls is never disturbed.
     * @param callActive a call rides on the link this would drop.
     * @param sessionInProgress a session up, handshaking or settling; nothing touches the radio then.
     */
    fun cycleDecision(
        sdkInt: Int,
        verdict: Verdict,
        reason: BluetoothWakePolicy.WakeReason,
        pairingHasRunAaHere: Boolean,
        callActive: Boolean,
        sessionInProgress: Boolean,
        standDownSinceMs: Long,
        lastCycleMs: Long,
        cyclesUsed: Int,
        now: Long,
    ): Boolean {
        if (sdkInt > LAST_CYCLEABLE_SDK) return false
        if (verdict == Verdict.STRANDS) return false
        if (reason != BluetoothWakePolicy.WakeReason.TARGET_CONNECTED) return false
        if (!pairingHasRunAaHere) return false
        if (callActive) return false
        if (sessionInProgress) return false
        if (cyclesUsed >= MAX_CYCLES_PER_ARMING) return false
        if (standDownSinceMs <= 0L || now - standDownSinceMs < CYCLE_AFTER_MS) return false
        if (lastCycleMs > 0L && now - lastCycleMs < CYCLE_COOLDOWN_MS) return false
        return true
    }

    /** Whether this cycle is the one being measured. */
    fun isProbe(verdict: Verdict): Boolean = verdict == Verdict.UNKNOWN

    /**
     * What the cycle did. A radio that never came back is the worst outcome there is, so it is
     * recorded even when the link cannot be read; anything else unreadable stays unmeasured rather
     * than condemning the unit on a reading the adapter refused to give.
     */
    fun verdictFrom(adapterCameBackOn: Boolean?, linkReturned: Boolean?): Verdict = when {
        adapterCameBackOn == false -> Verdict.STRANDS
        adapterCameBackOn == null -> Verdict.UNKNOWN
        linkReturned == true -> Verdict.RECOVERS
        linkReturned == false -> Verdict.STRANDS
        else -> Verdict.UNKNOWN
    }

    /** Whether a probe result is worth storing. An unreadable one would only overwrite a measurement. */
    fun recordable(verdict: Verdict): Boolean = verdict != Verdict.UNKNOWN
}
