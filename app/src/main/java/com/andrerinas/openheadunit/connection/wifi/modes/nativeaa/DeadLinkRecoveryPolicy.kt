package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/**
 * When repeated failed wake pokes are evidence of a dead hands-free link rather than a slow phone.
 *
 * The wake loop pokes a paired phone over RFCOMM. Where the phone's own connection to this unit is
 * absent, that poke is also the thing that raises the link, so a single failure says nothing: a
 * phone that is merely slow answers on the next pass. What it may not do is fail forever.
 *
 * Two failed passes is the line. One is the ordinary retry; by the second the unit has opened no
 * socket to this phone twice over several seconds, and on the reported unit that was the point of
 * no return — `HeadsetStateMachine` and `A2dpStateMachine` both `Disconnected`, the RFCOMM log
 * naming a slot it could not find, and the home card parked at "Waking your phone" until the radio
 * was cycled by hand or the app restarted.
 *
 * Kept separate from [BluetoothRadioCyclePolicy] on purpose: that policy decides whether a cycle is
 * *safe* (a call, a session, a measured radio), this one decides whether it is *earned*.
 */
object DeadLinkRecoveryPolicy {

    /** Failed passes at one phone before a cycle is considered. */
    const val MIN_FAILED_POKES = 2

    /**
     * How old the streak has to be before it counts, and why it is not
     * [BluetoothRadioCyclePolicy.CYCLE_AFTER_MS].
     *
     * That 90 s wait exists for the stand-down case, where a link genuinely exists and has to be
     * given its run before it is taken away. Here there is no link to give a run to — the streak of
     * failures is the whole evidence — so the wait only has to stop a unit from cycling on one
     * burst of fast errors.
     *
     * It also has to be reachable. A wake of the chosen driver is budgeted at
     * `NativeDriverSelectionPolicy.CHOSEN_WAKE_ROUNDS` rounds inside
     * `CHOSEN_EXCLUSIVE_MAX_MS`, and on the reported unit each round costs about 50 s: two RFCOMM
     * records at an ~18 s connect timeout plus the retry gap. The third round's check therefore
     * lands around 84 s after the first failure, measured on the rig. A 90 s floor put the cycle
     * six seconds out of reach of the very loop it exists to break, which is how the first version
     * of this policy shipped doing nothing. One full round is the honest floor: 30 s is inside the
     * budget with room to spare, and the second round is the earliest that has failures to act on.
     */
    const val MIN_STREAK_MS = 30_000L

    /**
     * Only the reasons that mean "there is no link to take" qualify. `TARGET_CONNECTED` is the
     * stand-down path's own case and reaches the cycle through [BluetoothRadioCyclePolicy] directly.
     *
     * The remaining reasons describe a link that belongs to somebody else (`GATEWAY_ONLY`,
     * `SWITCH_TARGET`) or could not be read (`TARGET_UNREADABLE`); dropping this unit's radio for
     * those would be a guess, and the existing policy already refuses them.
     */
    fun shouldCycle(
        failedPokes: Int,
        reason: BluetoothWakePolicy.WakeReason,
        streakAgeMs: Long,
    ): Boolean =
        failedPokes >= MIN_FAILED_POKES &&
            streakAgeMs >= MIN_STREAK_MS &&
            (reason == BluetoothWakePolicy.WakeReason.NO_LINK ||
                reason == BluetoothWakePolicy.WakeReason.TARGET_ABSENT)
}
