package com.andrerinas.openheadunit.connection.usb

import com.andrerinas.openheadunit.aap.AapTransport.HandshakeFailure
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy

/**
 * [FIX] A phone left in accessory mode after a session can fail every later handshake. A re-switch
 * lets it answer again with no re-enumeration, and a USB reset clears the TLS form by re-enumerating
 * it. This decides that bounded ladder: a re-switch, then a USB reset.
 */
object StaleAccessoryRecoveryPolicy {
    enum class Step { NONE, RESWITCH, USB_RESET, GIVE_UP }

    enum class Observation { WAITING, REENUMERATED, NO_CHANGE, LEFT_THE_BUS }

    enum class FollowUp { RECONNECT_ON_ATTACH, RETRY_HANDSHAKE, STOP }

    const val DETACH_WAIT_MS = 5_000L
    const val REATTACH_WAIT_MS = 10_000L
    const val MAX_STEPS = 2

    /**
     * Steps that still count. A half-done ladder older than the USB budget starts over, but a
     * finished one holds until the device it gave up on leaves the bus, so a stale phone is reset once.
     */
    fun stepsInEpisode(
        stepsTaken: Int,
        msSinceLastStep: Long,
        gaveUpOn: String?,
        accessoryNow: String?,
    ): Int = when {
        gaveUpOn != null -> if (gaveUpOn == accessoryNow) stepsTaken else 0
        msSinceLastStep >= ConnectionPriorityPolicy.USB_EPISODE_BUDGET_MS -> 0
        else -> stepsTaken
    }

    fun decide(
        failure: HandshakeFailure,
        inAccessoryMode: Boolean,
        cancelledByUser: Boolean,
        stepsInEpisode: Int,
    ): Step {
        val stale = failure == HandshakeFailure.TRANSPORT_ERROR || failure == HandshakeFailure.SSL
        if (!stale || !inAccessoryMode || cancelledByUser) return Step.NONE
        return when (stepsInEpisode) {
            0 -> Step.RESWITCH
            1 -> Step.USB_RESET
            else -> Step.GIVE_UP
        }
    }

    fun observe(detached: Boolean, reattached: Boolean, elapsedMs: Long): Observation = when {
        detached && reattached -> Observation.REENUMERATED
        detached ->
            if (elapsedMs >= DETACH_WAIT_MS + REATTACH_WAIT_MS) Observation.LEFT_THE_BUS else Observation.WAITING
        elapsedMs >= DETACH_WAIT_MS -> Observation.NO_CHANGE
        else -> Observation.WAITING
    }

    fun afterObservation(observation: Observation): FollowUp = when (observation) {
        Observation.REENUMERATED -> FollowUp.RECONNECT_ON_ATTACH
        Observation.NO_CHANGE -> FollowUp.RETRY_HANDSHAKE
        else -> FollowUp.STOP
    }
}
