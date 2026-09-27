package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/**
 * What the end of a session does to the Native AA network and the Bluetooth side.
 *
 * A session that ends unexpectedly used to stop the launcher and rebuild it, which removed the
 * P2P group and created a new one. On a unit that gives every group a new address the phone's
 * saved network then never matched, and even where it did the phone waited on the create. The
 * network is kept up instead: the phone finds the network it saved, and only the Bluetooth
 * listeners a completed handoff closed have to come back. A user exit still stops everything,
 * because removing the network is the only thing that makes the phone leave it.
 */
object SessionEndGroupPolicy {

    enum class Action {
        /** Stop the launcher and, with it, the network the phone is on. */
        STOP,
        /** Keep the launcher and its network; reopen the Bluetooth side and read the credentials again. */
        KEEP_AND_REARM,
        /** Not this policy's session. */
        NONE,
    }

    fun decide(
        activeModeIsNative: Boolean,
        isUserExit: Boolean,
        rearmedAfterWiredSession: Boolean,
    ): Action = when {
        rearmedAfterWiredSession -> Action.NONE
        !activeModeIsNative -> Action.NONE
        isUserExit -> Action.STOP
        else -> Action.KEEP_AND_REARM
    }

    /**
     * Whether the re-arm has to reopen the Android Auto RFCOMM listeners.
     *
     * Only a completed handoff closes them, so a session that landed straight on the TCP port
     * without a handshake leaves them open, and opening them again would publish a second record
     * on the same UUID. A manager that is not running is start()'s job, not the re-arm's.
     */
    fun shouldReopenAaListeners(handshakeRunning: Boolean, listenersClosedForSession: Boolean): Boolean =
        handshakeRunning && listenersClosedForSession

    /**
     * Whether the re-arm wakes the phone.
     *
     * A phone that sent a ByeByeRequest chose to end the session, whatever its reason, so a poke
     * pulls it straight back against that choice. A link that simply died chose nothing. Ending the
     * session at this unit is the same kind of choice, made at this end: the network stays up so
     * the phone can come back when it likes, and waking it would be arguing with the button.
     */
    fun wakesPhoneAfterSessionEnd(
        phoneSaidGoodbye: Boolean,
        headUnitEndedByHand: Boolean = false,
    ): Boolean = !phoneSaidGoodbye && !headUnitEndedByHand

    /** How long a poke waits after a session ends, so the phone can see the group again. */
    const val WAKE_SETTLE_MS = 5_000L

    /**
     * What is left of [WAKE_SETTLE_MS] since the session ended, 0 if none or if none ended.
     *
     * Measured: a poke 0.5s after a session end had the phone answer WIFI_NETWORK_UNAVAILABLE with
     * no_bss_found against a group that never moved. The 5s itself is a guess awaiting a round.
     */
    fun wakeSettleRemainingMs(sessionEndedAt: Long, now: Long): Long {
        if (sessionEndedAt <= 0L) return 0L
        return (sessionEndedAt + WAKE_SETTLE_MS - now).coerceIn(0L, WAKE_SETTLE_MS)
    }
}
