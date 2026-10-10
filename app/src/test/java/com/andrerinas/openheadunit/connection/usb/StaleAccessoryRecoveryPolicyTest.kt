package com.andrerinas.openheadunit.connection.usb

import com.andrerinas.openheadunit.aap.AapTransport.HandshakeFailure
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy
import com.andrerinas.openheadunit.connection.usb.StaleAccessoryRecoveryPolicy.FollowUp
import com.andrerinas.openheadunit.connection.usb.StaleAccessoryRecoveryPolicy.Observation
import com.andrerinas.openheadunit.connection.usb.StaleAccessoryRecoveryPolicy.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class StaleAccessoryRecoveryPolicyTest {

    private companion object {
        const val DEV = "/dev/bus/usb/001/005"
    }

    private fun decide(
        failure: HandshakeFailure,
        inAccessoryMode: Boolean = true,
        cancelledByUser: Boolean = false,
        steps: Int = 0,
    ) = StaleAccessoryRecoveryPolicy.decide(failure, inAccessoryMode, cancelledByUser, steps)

    private fun episode(steps: Int, msSince: Long, gaveUpOn: String? = null, now: String? = DEV) =
        StaleAccessoryRecoveryPolicy.stepsInEpisode(steps, msSince, gaveUpOn, now)

    @Test
    fun `a send error on an accessory starts with the re-switch`() {
        assertEquals(Step.RESWITCH, decide(HandshakeFailure.TRANSPORT_ERROR))
    }

    @Test
    fun `an SSL failure on an accessory starts with the re-switch`() {
        assertEquals(Step.RESWITCH, decide(HandshakeFailure.SSL))
    }

    @Test
    fun `a silent peer is never a stale accessory`() {
        for (steps in 0..2) assertEquals(Step.NONE, decide(HandshakeFailure.PEER_SILENT, steps = steps))
    }

    @Test
    fun `an unnamed failure is never a stale accessory`() {
        assertEquals(Step.NONE, decide(HandshakeFailure.OTHER))
    }

    @Test
    fun `a success is not a failure`() {
        assertEquals(Step.NONE, decide(HandshakeFailure.NONE))
    }

    @Test
    fun `no accessory left on the bus means nothing to recover`() {
        assertEquals(Step.NONE, decide(HandshakeFailure.SSL, inAccessoryMode = false))
    }

    @Test
    fun `the status pill's X holds recovery too`() {
        assertEquals(Step.NONE, decide(HandshakeFailure.SSL, cancelledByUser = true))
    }

    @Test
    fun `the second step is the USB reset`() {
        assertEquals(Step.USB_RESET, decide(HandshakeFailure.SSL, steps = 1))
    }

    @Test
    fun `after both steps the user is asked to replug`() {
        assertEquals(Step.GIVE_UP, decide(HandshakeFailure.SSL, steps = 2))
    }

    @Test
    fun `a half-done ladder starts over after the arbiter's budget`() {
        val budget = ConnectionPriorityPolicy.USB_EPISODE_BUDGET_MS
        assertEquals(1, episode(steps = 1, msSince = budget - 1))
        assertEquals(0, episode(steps = 1, msSince = budget))
    }

    @Test
    fun `a finished ladder holds while the same device stays on the bus`() {
        val tenMinutes = 10 * 60_000L
        assertEquals(2, episode(steps = 2, msSince = tenMinutes, gaveUpOn = DEV))
        assertEquals(Step.GIVE_UP, decide(HandshakeFailure.SSL, steps = episode(2, tenMinutes, gaveUpOn = DEV)))
    }

    @Test
    fun `a finished ladder ends when its device leaves the bus`() {
        assertEquals(0, episode(steps = 2, msSince = 1_000L, gaveUpOn = DEV, now = "/dev/bus/usb/001/009"))
        assertEquals(0, episode(steps = 2, msSince = 1_000L, gaveUpOn = DEV, now = null))
    }

    @Test
    fun `a stale phone left plugged in for ten minutes gets two hardware steps`() {
        var steps = 0
        var lastStepAt = 0L
        var gaveUpOn: String? = null
        var hardware = 0
        for (t in 3_000L..600_000L step 3_000L) {
            val n = episode(steps, t - lastStepAt, gaveUpOn)
            when (decide(HandshakeFailure.SSL, steps = n)) {
                Step.RESWITCH, Step.USB_RESET -> { steps = n + 1; lastStepAt = t; hardware++ }
                Step.GIVE_UP -> gaveUpOn = DEV
                Step.NONE -> Unit
            }
        }
        assertEquals(2, hardware)
    }

    @Test
    fun `ten failures in a row spend at most two hardware steps`() {
        var steps = 0
        val seen = (1..10).map {
            val step = decide(HandshakeFailure.TRANSPORT_ERROR, steps = steps)
            if (step == Step.RESWITCH || step == Step.USB_RESET) steps++
            step
        }
        val expected = listOf(Step.RESWITCH, Step.USB_RESET) + List(8) { Step.GIVE_UP }
        assertEquals(expected, seen)
    }

    @Test
    fun `a step with no detach is not a re-enumeration`() {
        val wait = StaleAccessoryRecoveryPolicy.DETACH_WAIT_MS
        assertEquals(Observation.WAITING, StaleAccessoryRecoveryPolicy.observe(false, false, wait - 1))
        assertEquals(Observation.NO_CHANGE, StaleAccessoryRecoveryPolicy.observe(false, false, wait))
    }

    @Test
    fun `a detach and a new device is a re-enumeration`() {
        assertEquals(Observation.REENUMERATED, StaleAccessoryRecoveryPolicy.observe(true, true, 300L))
    }

    @Test
    fun `a detach with nothing back is the phone leaving`() {
        val limit = StaleAccessoryRecoveryPolicy.DETACH_WAIT_MS + StaleAccessoryRecoveryPolicy.REATTACH_WAIT_MS
        assertEquals(Observation.WAITING, StaleAccessoryRecoveryPolicy.observe(true, false, limit - 1))
        assertEquals(Observation.LEFT_THE_BUS, StaleAccessoryRecoveryPolicy.observe(true, false, limit))
    }

    @Test
    fun `only a re-enumeration hands over to the attach path`() {
        assertEquals(FollowUp.RECONNECT_ON_ATTACH, StaleAccessoryRecoveryPolicy.afterObservation(Observation.REENUMERATED))
        assertEquals(FollowUp.RETRY_HANDSHAKE, StaleAccessoryRecoveryPolicy.afterObservation(Observation.NO_CHANGE))
        assertEquals(FollowUp.STOP, StaleAccessoryRecoveryPolicy.afterObservation(Observation.LEFT_THE_BUS))
        assertEquals(FollowUp.STOP, StaleAccessoryRecoveryPolicy.afterObservation(Observation.WAITING))
    }
}
