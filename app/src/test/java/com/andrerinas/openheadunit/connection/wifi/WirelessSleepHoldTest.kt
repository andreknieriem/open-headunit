package com.andrerinas.openheadunit.connection.wifi

import com.andrerinas.openheadunit.app.BtAutoStartRearmPolicy
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.ConnectionOperation
import com.andrerinas.openheadunit.connection.CommManager.DisconnectReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WirelessSleepHoldTest {

    private val operation = ConnectionOperation(1L, null)

    @Before
    fun setUp() = WirelessSleepHold.clear()

    @After
    fun tearDown() = WirelessSleepHold.clear()

    @Test
    fun `a dark screen is asleep`() = assertTrue(WirelessSleepHold.isAsleep(false))

    @Test
    fun `an unreadable screen is awake`() = assertFalse(WirelessSleepHold.isAsleep(null))

    @Test
    fun `a lit screen is awake`() = assertFalse(WirelessSleepHold.isAsleep(true))

    @Test
    fun `an automatic bring-up is refused while asleep`() =
        assertTrue(WirelessSleepHold.refusesBringUp(true, false, false))

    @Test
    fun `the user lifts the hold`() =
        assertFalse(WirelessSleepHold.refusesBringUp(true, true, false))

    @Test
    fun `manual mode is never held`() =
        assertFalse(WirelessSleepHold.refusesBringUp(true, false, true))

    @Test
    fun `nothing is refused while awake`() =
        assertFalse(WirelessSleepHold.refusesBringUp(false, false, false))

    @Test
    fun `a formed session drops a forced replay`() =
        assertNull(WirelessSleepHold.replayForce(true, connected = true, connecting = false))

    @Test
    fun `a formed session drops an unforced replay`() =
        assertNull(WirelessSleepHold.replayForce(false, connected = true, connecting = false))

    @Test
    fun `an attempt in flight replays a forced hold unforced`() =
        assertEquals(false, WirelessSleepHold.replayForce(true, connected = false, connecting = true))

    @Test
    fun `an attempt in flight replays an unforced hold unforced`() =
        assertEquals(false, WirelessSleepHold.replayForce(false, connected = false, connecting = true))

    @Test
    fun `with no session the held force replays`() =
        assertEquals(true, WirelessSleepHold.replayForce(true, connected = false, connecting = false))

    @Test
    fun `nothing held replays nothing`() =
        assertNull(WirelessSleepHold.replayForce(null, connected = false, connecting = true))

    @Test
    fun `a forced hold waits out an attempt in flight`() =
        assertTrue(WirelessSleepHold.waitsForAttempt(true, connected = false, connecting = true))

    @Test
    fun `an unforced hold does not wait for an attempt`() =
        assertFalse(WirelessSleepHold.waitsForAttempt(false, connected = false, connecting = true))

    @Test
    fun `a forced hold does not wait with no attempt`() =
        assertFalse(WirelessSleepHold.waitsForAttempt(true, connected = false, connecting = false))

    @Test
    fun `a formed session ends the wait`() =
        assertFalse(WirelessSleepHold.waitsForAttempt(true, connected = true, connecting = false))

    @Test
    fun `take with nothing held is null`() = assertNull(WirelessSleepHold.take())

    @Test
    fun `a hold is taken once`() {
        WirelessSleepHold.hold(false, operation)
        assertEquals(false, WirelessSleepHold.take()?.force)
        assertNull(WirelessSleepHold.take())
    }

    @Test
    fun `force is sticky across holds`() {
        WirelessSleepHold.hold(false, operation)
        WirelessSleepHold.hold(true, operation)
        assertEquals(true, WirelessSleepHold.take()?.force)
    }

    @Test
    fun `only the first hold since a take reports new`() {
        assertTrue(WirelessSleepHold.hold(false, operation))
        assertFalse(WirelessSleepHold.hold(true, operation))
        WirelessSleepHold.take()
        assertTrue(WirelessSleepHold.hold(false, operation))
    }

    @Test
    fun `clear drops a hold`() {
        WirelessSleepHold.hold(true, operation)
        WirelessSleepHold.clear()
        assertNull(WirelessSleepHold.take())
    }

    @Test
    fun `the radio is not switched on while asleep`() =
        assertTrue(WirelessSleepHold.refusesRadioEnable(true, startedByUser = false))

    @Test
    fun `the radio is switched on while awake`() =
        assertFalse(WirelessSleepHold.refusesRadioEnable(false, startedByUser = false))

    @Test
    fun `the user's bring-up switches the radio on while asleep`() =
        assertFalse(WirelessSleepHold.refusesRadioEnable(true, startedByUser = true))

    @Test
    fun `no group is recreated while asleep`() =
        assertTrue(WirelessSleepHold.refusesGroupRecreate(true, startedByUser = false))

    @Test
    fun `a group is recreated while awake`() =
        assertFalse(WirelessSleepHold.refusesGroupRecreate(false, startedByUser = false))

    @Test
    fun `the user's bring-up recreates its group while asleep`() =
        assertFalse(WirelessSleepHold.refusesGroupRecreate(true, startedByUser = true))

    @Test
    fun `a start records who asked and drops the hold`() {
        WirelessSleepHold.hold(true, operation)
        WirelessSleepHold.started(userRequested = true)
        assertTrue(WirelessSleepHold.startedByUser)
        assertNull(WirelessSleepHold.take())
        WirelessSleepHold.started(userRequested = false)
        assertFalse(WirelessSleepHold.startedByUser)
    }

    @Test
    fun `clear forgets the user's start`() {
        WirelessSleepHold.started(userRequested = true)
        WirelessSleepHold.clear()
        assertFalse(WirelessSleepHold.startedByUser)
    }

    @Test
    fun `a radio hold forces the replay`() {
        WirelessSleepHold.hold(false, operation)
        WirelessSleepHold.hold(true, operation)
        assertEquals(true, WirelessSleepHold.take()?.force)
    }

    @Test
    fun `an unforced replay is never vetoed`() =
        assertNull(WirelessSleepHold.replayVeto(force = false, networkComingUp = true, attemptInFlight = true))

    @Test
    fun `a forced replay with nothing under way goes ahead`() =
        assertNull(WirelessSleepHold.replayVeto(force = true, networkComingUp = false, attemptInFlight = false))

    @Test
    fun `a forced replay with nothing readable goes ahead`() =
        assertNull(WirelessSleepHold.replayVeto(force = true, networkComingUp = null, attemptInFlight = null))

    @Test
    fun `a forced replay is vetoed while the network is coming up`() = assertEquals(
        "the network has been asked for and has not answered yet",
        WirelessSleepHold.replayVeto(force = true, networkComingUp = true, attemptInFlight = false)
    )

    @Test
    fun `a forced replay is vetoed while an attempt is in flight`() = assertEquals(
        "a handshake attempt is already in flight",
        WirelessSleepHold.replayVeto(force = true, networkComingUp = false, attemptInFlight = true)
    )

    @Test
    fun `the replay veto matches the Bluetooth auto-start's`() {
        val values = listOf(true, false, null)
        for (n in values) for (a in values) {
            assertEquals(
                "networkComingUp=$n attemptInFlight=$a",
                BtAutoStartRearmPolicy.vetoReason(
                    sessionUp = false, handshakeActive = null, attemptInFlight = a,
                    groupUp = null, networkComingUp = n
                ),
                WirelessSleepHold.replayVeto(force = true, networkComingUp = n, attemptInFlight = a)
            )
        }
    }

    @Test
    fun `a forced replay keeps its force with no start since the take`() =
        assertTrue(WirelessSleepHold.replayKeepsForce(true, 3, 3))

    @Test
    fun `a start since the take drops the replay's force`() =
        assertFalse(WirelessSleepHold.replayKeepsForce(true, 3, 4))

    @Test
    fun `an unforced replay stays unforced after a start`() =
        assertFalse(WirelessSleepHold.replayKeepsForce(false, 3, 4))

    @Test
    fun `take records the starts so far`() {
        WirelessSleepHold.started(false)
        WirelessSleepHold.hold(true, operation)
        val held = checkNotNull(WirelessSleepHold.take())
        assertEquals(WirelessSleepHold.starts, held.startsAtTake)
    }

    @Test
    fun `a take with nothing held keeps the baseline`() {
        WirelessSleepHold.hold(true, operation)
        val held = checkNotNull(WirelessSleepHold.take())
        val baseline = held.startsAtTake
        WirelessSleepHold.started(false)
        assertNull(WirelessSleepHold.take())
        assertEquals(baseline, held.startsAtTake)
        assertFalse(
            WirelessSleepHold.replayKeepsForce(true, held.startsAtTake, WirelessSleepHold.starts)
        )
    }

    @Test
    fun `a start after the take is seen by the replay`() {
        WirelessSleepHold.hold(true, operation)
        val held = checkNotNull(WirelessSleepHold.take())
        WirelessSleepHold.started(false)
        assertFalse(
            WirelessSleepHold.replayKeepsForce(true, held.startsAtTake, WirelessSleepHold.starts)
        )
    }

    @Test
    fun `a stop ends the user's start`() {
        WirelessSleepHold.started(true)
        WirelessSleepHold.stopped()
        assertFalse(WirelessSleepHold.startedByUser)
    }

    @Test
    fun `a stop keeps a held bring-up`() {
        WirelessSleepHold.hold(true, operation)
        WirelessSleepHold.stopped()
        assertEquals(true, WirelessSleepHold.take()?.force)
    }

    @Test
    fun `a taken hold retains the exact input operation`() {
        val save = CommManager.ConnectionState.Disconnected(reason = DisconnectReason.SETTINGS_RESTART)
        val input = ConnectionOperation(1L, save)
        WirelessSleepHold.hold(true, input)
        val held = checkNotNull(WirelessSleepHold.take())
        assertTrue(held.force)
        assertSame(input, held.operation)
        assertSame(save, held.operation.save)
        assertEquals(WirelessSleepHold.starts, held.startsAtTake)
        assertNull(WirelessSleepHold.take())
    }

    @Test
    fun `a different Save replaces the held authority without borrowing force`() {
        val oldSave = CommManager.ConnectionState.Disconnected(reason = DisconnectReason.SETTINGS_RESTART)
        val newSave = CommManager.ConnectionState.Disconnected(reason = DisconnectReason.SETTINGS_RESTART)
        val oldInput = ConnectionOperation(1L, oldSave)
        val newInput = ConnectionOperation(1L, newSave)
        WirelessSleepHold.hold(true, oldInput)
        WirelessSleepHold.hold(false, newInput)
        val held = checkNotNull(WirelessSleepHold.take())
        assertFalse(held.force)
        assertSame(newInput, held.operation)
        assertSame(newSave, held.operation.save)
    }

    @Test
    fun `a later take cannot change a queued hold baseline`() {
        WirelessSleepHold.hold(true, operation)
        val first = checkNotNull(WirelessSleepHold.take())
        val firstStarts = first.startsAtTake
        WirelessSleepHold.started(false)
        WirelessSleepHold.hold(true, operation)
        val second = checkNotNull(WirelessSleepHold.take())
        assertEquals(firstStarts, first.startsAtTake)
        assertEquals(firstStarts + 1, second.startsAtTake)
        assertFalse(WirelessSleepHold.replayKeepsForce(first.force, first.startsAtTake, WirelessSleepHold.starts))
    }

    @Test
    fun `a held Direct continuation is delivered once with its owner`() {
        var calls = 0
        val resume: () -> Unit = { calls++ }
        WirelessSleepHold.hold(true, operation, resume)
        val held = checkNotNull(WirelessSleepHold.take())
        assertSame(operation, held.operation)
        assertSame(resume, held.resume)
        checkNotNull(held.resume).invoke()
        assertEquals(1, calls)
        assertNull(WirelessSleepHold.take())
    }
}
