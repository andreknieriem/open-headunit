package com.andrerinas.openheadunit.connection.carplaynative

import com.andrerinas.openheadunit.connection.carplaynative.CarPlayNativeSession.Action
import com.andrerinas.openheadunit.connection.carplaynative.CarPlayNativeSession.Event
import com.andrerinas.openheadunit.connection.carplaynative.CarPlayNativeSession.Phase
import com.andrerinas.openheadunit.connection.carplaynative.CarPlayNativeSession.State
import com.andrerinas.openheadunit.connection.carplaynative.usb.IphoneUsbPolicy.Attach
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One row per edge of the session. Each deadline test runs the fake clock to the tick before it and on it. */
class CarPlayNativeSessionTest {

    private class Run(var state: State = State(), var now: Long = 0) {
        val actions = ArrayList<Action>()

        fun at(ms: Long, event: Event): Run {
            now = ms
            val result = CarPlayNativeSession.reduce(state, event, now)
            state = result.state
            actions += result.actions
            return this
        }

        fun take(): List<Action> = actions.toList().also { actions.clear() }
        val phase: Phase get() = state.phase
    }

    private fun session(at: Long = 0): Run = Run().at(at, Event.Attached(Attach.Open)).also { it.take() }

    private fun startSent(run: Run, at: Long): Run = run.at(at, Event.Opened).at(at, Event.CarkitOpen).at(at, Event.IdentificationAccepted).at(at, Event.StartSent(1))

    // The prepare phase.

    @Test fun anOpenVerdictBeginsTheSessionAtOnce() {
        val run = Run().at(0, Event.Attached(Attach.Open))
        assertEquals(Phase.Opening, run.phase)
        assertEquals(listOf<Action>(Action.BeginSession), run.take())
    }

    @Test fun anAskVerdictAsksOnceAndAGrantReevaluates() {
        val run = Run().at(0, Event.Attached(Attach.AskPermission)).at(10, Event.Attached(Attach.AskPermission))
        assertEquals(listOf<Action>(Action.AskPermission), run.take())
        run.at(20, Event.Attached(Attach.RequestMode))
        assertEquals(listOf<Action>(Action.RequestMode), run.take())
    }

    @Test fun aRefusingVerdictNamesItAndStaysIdle() {
        for (verdict in listOf(Attach.BelowApiFloor, Attach.NoTunnel, Attach.NoSigner, Attach.AlreadyLive, Attach.UserExited, Attach.RefusedAaLive)) {
            val run = Run().at(0, Event.Attached(verdict))
            assertEquals(Phase.Idle, run.phase)
            assertEquals(listOf<Action>(Action.Refused(verdict)), run.take())
        }
        assertEquals(emptyList<Action>(), Run().at(0, Event.Attached(Attach.Disabled)).take())
        assertEquals(emptyList<Action>(), Run().at(0, Event.Attached(Attach.NotAnIphone)).take())
    }

    @Test fun theModeRequestGoesOutOnceAndAnAcceptedAnswerStartsTheTenSecondWait() {
        val run = Run().at(0, Event.Attached(Attach.RequestMode)).at(5, Event.Attached(Attach.RequestMode))
        assertEquals(listOf<Action>(Action.RequestMode), run.take())
        run.at(100, Event.ModeAccepted)
        assertEquals(Phase.AwaitReenumeration, run.phase)
        assertEquals(listOf<Action>(Action.Log("CarPlayNative: requested CarPlay mode")), run.take())
    }

    @Test fun aRefusedModeIsNotAskedAgainUntilTheIphoneLeavesAndAnAnswerWithNoRequestIsIgnored() {
        val run = Run().at(0, Event.ModeAccepted)
        assertEquals(Phase.Idle, run.phase)
        run.at(1, Event.Attached(Attach.RequestMode)).at(2, Event.ModeRefused).take()
        // A rescan attaches the same iPhone again: one attach, one request.
        run.at(3, Event.Attached(Attach.RequestMode)).at(4, Event.Attached(Attach.RequestMode))
        assertEquals(emptyList<Action>(), run.take())
        run.at(5, Event.Detached).at(6, Event.Attached(Attach.RequestMode))
        assertEquals(listOf<Action>(Action.RequestMode), run.take())
    }

    @Test fun theReenumerationWaitEndsAtTenSecondsAndNotBefore() {
        val run = Run().at(0, Event.Attached(Attach.RequestMode)).at(100, Event.ModeAccepted).also { it.take() }
        run.at(10_099, Event.Tick)
        assertEquals(Phase.AwaitReenumeration, run.phase)
        run.at(10_100, Event.Tick)
        assertEquals(
            listOf(
                Action.Log("CarPlayNative: the iPhone did not come back in CarPlay mode in 10 s; not started"),
                Action.End("the iPhone did not come back in CarPlay mode in 10 s; not started", begun = false),
            ),
            run.take(),
        )
    }

    @Test fun theOldInstanceLeavingDuringTheWaitIsIgnored() {
        val run = Run().at(0, Event.Attached(Attach.RequestMode)).at(1, Event.ModeAccepted).also { it.take() }
        run.at(50, Event.Detached)
        assertEquals(Phase.AwaitReenumeration, run.phase)
        assertEquals(emptyList<Action>(), run.take())
    }

    @Test fun theNewInstanceWithPermissionOpensAtOnce() {
        val run = Run().at(0, Event.Attached(Attach.RequestMode)).at(1, Event.ModeAccepted).also { it.take() }
        run.at(800, Event.Attached(Attach.Open))
        assertEquals(Phase.Opening, run.phase)
        assertEquals(listOf<Action>(Action.BeginSession), run.take())
        // The deadline of the old wait is gone.
        run.at(60_000, Event.Tick)
        assertEquals(Phase.Opening, run.phase)
    }

    @Test fun theNewInstanceWithoutPermissionAsksAgainAndTheSecondDialogIgnoredForThirtySecondsEndsTheAttemptWithoutABeginSession() {
        val run = Run().at(0, Event.Attached(Attach.RequestMode)).at(1, Event.ModeAccepted).also { it.take() }
        run.at(800, Event.Attached(Attach.AskPermission))
        assertEquals(Phase.AwaitSecondPermission, run.phase)
        assertEquals(listOf<Action>(Action.AskPermission), run.take())
        run.at(30_799, Event.Tick)
        assertEquals(Phase.AwaitSecondPermission, run.phase)
        run.at(30_800, Event.Tick)
        val line = "permission for the CarPlay mode iPhone not given in 30 s; not started"
        assertEquals(listOf(Action.Log("CarPlayNative: $line"), Action.End(line, begun = false)), run.take())
        assertFalse(run.actions.contains(Action.BeginSession))
        run.at(31_000, Event.Attached(Attach.Open))
        assertEquals(emptyList<Action>(), run.take())
    }

    @Test fun aGrantInTheSecondWaitBeginsTheSessionAndAnotherAskIsIgnored() {
        val run = Run().at(0, Event.Attached(Attach.RequestMode)).at(1, Event.ModeAccepted).at(800, Event.Attached(Attach.AskPermission)).also { it.take() }
        run.at(900, Event.Attached(Attach.AskPermission))
        assertEquals(emptyList<Action>(), run.take())
        run.at(5_000, Event.Attached(Attach.Open))
        assertEquals(listOf<Action>(Action.BeginSession), run.take())
    }

    @Test fun aDenialInTheSecondWaitEndsItAtOnce() {
        val run = Run().at(0, Event.Attached(Attach.RequestMode)).at(1, Event.ModeAccepted).at(800, Event.Attached(Attach.AskPermission)).also { it.take() }
        run.at(900, Event.PermissionDenied)
        assertTrue(run.phase is Phase.Ended)
        assertEquals(false, (run.take().last() as Action.End).begun)
    }

    @Test fun aDenialOfTheFirstAskIsNotAskedAgainUntilTheIphoneLeaves() {
        val run = Run().at(0, Event.Attached(Attach.AskPermission)).at(1, Event.PermissionDenied).also { it.take() }
        run.at(2, Event.Attached(Attach.AskPermission))
        assertEquals(emptyList<Action>(), run.take())
        run.at(3, Event.Detached).at(4, Event.Attached(Attach.AskPermission))
        assertEquals(listOf<Action>(Action.AskPermission), run.take())
    }

    @Test fun aDetachInIdleForgetsTheRequests() {
        val run = Run().at(0, Event.Attached(Attach.RequestMode)).at(1, Event.Detached).also { it.take() }
        run.at(2, Event.Attached(Attach.RequestMode))
        assertEquals(listOf<Action>(Action.RequestMode), run.take())
    }

    // The session.

    @Test fun thePhasesFollowTheSessionOrder() {
        val run = session()
        run.at(1, Event.Opened)
        assertEquals(Phase.Pairing, run.phase)
        run.at(2, Event.CarkitOpen)
        assertEquals(Phase.Iap2, run.phase)
        run.at(3, Event.IdentificationAccepted)
        assertEquals(Phase.Authenticating, run.phase)
        run.at(4, Event.StartSent(1))
        assertEquals(Phase.StartSent, run.phase)
        run.at(5, Event.FirstNcmFrame)
        assertEquals(Phase.NetworkUp, run.phase)
        run.at(6, Event.AirPlayConnected)
        assertEquals(Phase.AirPlay, run.phase)
        run.at(7, Event.Streaming)
        assertEquals(Phase.Streaming, run.phase)
        assertEquals(emptyList<Action>(), run.take())
    }

    @Test fun anEventOutOfOrderChangesNothing() {
        val run = session()
        run.at(1, Event.CarkitOpen).at(1, Event.IdentificationAccepted).at(1, Event.AirPlayConnected).at(1, Event.Streaming)
        assertEquals(Phase.Opening, run.phase)
    }

    @Test fun noNcmFrameInThirtySecondsEndsTheSessionAtTheThirtiethSecond() {
        val run = startSent(session(), 1_000)
        run.at(30_999, Event.Tick)
        assertEquals(Phase.StartSent, run.phase)
        run.at(31_000, Event.Tick)
        assertEquals(listOf<Action>(Action.End("no USB network from the iPhone", begun = true)), run.take())
    }

    @Test fun theFirstNcmFrameAtTwentySecondsGivesTheResendAtTwentyTwoAndTheSessionLives() {
        val run = startSent(session(), 0)
        run.at(20_000, Event.FirstNcmFrame)
        assertEquals(Phase.NetworkUp, run.phase)
        run.at(21_750, Event.Tick)
        assertEquals(emptyList<Action>(), run.take())
        run.at(22_000, Event.Tick)
        assertEquals(listOf<Action>(Action.ResendStart), run.take())
        // The 30 s wait for the first frame is spent, and the AirPlay wait runs on.
        run.at(31_750, Event.Tick)
        assertEquals(Phase.NetworkUp, run.phase)
        assertEquals(emptyList<Action>(), run.take())
        run.at(32_000, Event.AirPlayConnected)
        assertEquals(Phase.AirPlay, run.phase)
        run.at(46_999, Event.Tick)
        assertEquals(Phase.AirPlay, run.phase)
    }

    @Test fun noAirPlayConnectionTenSecondsAfterTheResendEndsOnce() {
        val run = startSent(session(), 0)
        run.at(1_000, Event.FirstNcmFrame).at(3_000, Event.Tick)
        assertEquals(listOf<Action>(Action.ResendStart), run.take())
        run.at(12_999, Event.Tick)
        assertEquals(emptyList<Action>(), run.take())
        run.at(13_000, Event.Tick)
        assertEquals(listOf<Action>(Action.End("no AirPlay connection", begun = true)), run.take())
        run.at(14_000, Event.Tick)
        assertEquals(emptyList<Action>(), run.take())
    }

    @Test fun theResendGoesOutOnlyOncePerSession() {
        val run = startSent(session(), 0)
        run.at(1_000, Event.FirstNcmFrame).at(3_000, Event.Tick).at(3_250, Event.Tick).at(8_000, Event.Tick)
        assertEquals(listOf<Action>(Action.ResendStart), run.take())
    }

    @Test fun anAirPlayConnectionInsideTheTwoSecondsCancelsTheResend() {
        val run = startSent(session(), 0)
        run.at(1_000, Event.FirstNcmFrame).at(2_500, Event.AirPlayConnected).at(5_000, Event.Tick)
        assertEquals(emptyList<Action>(), run.take())
        assertEquals(Phase.AirPlay, run.phase)
    }

    @Test fun aSecondStartSentChangesNoDeadline() {
        val run = startSent(session(), 0)
        run.at(10_000, Event.StartSent(2))
        run.at(30_000, Event.Tick)
        assertEquals(listOf<Action>(Action.End("no USB network from the iPhone", begun = true)), run.take())
    }

    @Test fun anNcmFrameBeforeTheStartSentStartsTheTwoSecondWaitAtTheStartSent() {
        val run = session()
        run.at(500, Event.Opened).at(600, Event.FirstNcmFrame).at(700, Event.CarkitOpen).at(800, Event.IdentificationAccepted)
        run.at(5_000, Event.StartSent(1))
        assertEquals(Phase.NetworkUp, run.phase)
        run.at(6_999, Event.Tick)
        assertEquals(emptyList<Action>(), run.take())
        run.at(7_000, Event.Tick)
        assertEquals(listOf<Action>(Action.ResendStart), run.take())
    }

    @Test fun aFailInEveryBegunPhaseEndsOnceWithTheReason() {
        for (steps in 0..6) {
            val run = session()
            val events = listOf(Event.Opened, Event.CarkitOpen, Event.IdentificationAccepted, Event.StartSent(1), Event.AirPlayConnected, Event.Streaming)
            events.take(steps).forEach { run.at(1, it) }
            run.take()
            run.at(2, Event.Fail("lockdown failed"))
            assertEquals("steps $steps", listOf<Action>(Action.End("lockdown failed", begun = true)), run.take())
            run.at(3, Event.Fail("again"))
            assertEquals(emptyList<Action>(), run.take())
        }
    }

    @Test fun anUnplugAfterBeginEndsWithUnpluggedAndAUserExitWithItsReason() {
        val run = session().at(1, Event.Opened)
        run.at(2, Event.Detached)
        assertEquals(listOf<Action>(Action.End("unplugged", begun = true)), run.take())
        val exit = session().at(1, Event.Opened).at(2, Event.UserExit)
        assertEquals(listOf<Action>(Action.End("user exit", begun = true)), exit.take())
    }

    @Test fun anEndedStateTakesNoMoreEvents() {
        val run = session().at(1, Event.Fail("x")).also { it.take() }
        for (event in listOf(Event.Tick, Event.Opened, Event.Attached(Attach.Open), Event.UserExit, Event.Detached, Event.FirstNcmFrame)) {
            run.at(100_000, event)
            assertEquals(emptyList<Action>(), run.take())
        }
        assertEquals(Phase.Ended("x"), run.phase)
    }

    @Test fun anAttachInsideALiveSessionChangesNothing() {
        val run = session().at(1, Event.Opened)
        run.at(2, Event.Attached(Attach.Open)).at(3, Event.Attached(Attach.AlreadyLive))
        assertEquals(Phase.Pairing, run.phase)
        assertEquals(emptyList<Action>(), run.take())
    }

    // The waits after auth and after the AirPlay connection.

    private fun airPlay(at: Long): Run = startSent(session(), 0).at(at - 1, Event.FirstNcmFrame).at(at, Event.AirPlayConnected).also { it.take() }

    @Test fun noVideoFifteenSecondsAfterTheAirPlayConnectionEndsAtTheFifteenthSecond() {
        val run = airPlay(1_000)
        run.at(15_999, Event.Tick)
        assertEquals(Phase.AirPlay, run.phase)
        assertEquals(emptyList<Action>(), run.take())
        run.at(16_000, Event.Tick)
        assertEquals(listOf<Action>(Action.End("no video from the iPhone in 15 s", begun = true)), run.take())
    }

    @Test fun aConnectionThatDropsBeforeSetupIsCountedAndAReconnectKeepsTheFirstDeadline() {
        val run = airPlay(1_000)
        run.at(2_000, Event.AirPlayEndedBeforeSetup).at(3_000, Event.AirPlayConnected).at(4_000, Event.AirPlayEndedBeforeSetup)
        run.at(15_999, Event.Tick)
        assertEquals(emptyList<Action>(), run.take())
        run.at(16_000, Event.Tick)
        assertEquals(listOf<Action>(Action.End("no video from the iPhone in 15 s (2 AirPlay connections ended before SETUP)", begun = true)), run.take())
    }

    @Test fun theFirstFrameClearsTheVideoDeadline() {
        val run = airPlay(1_000)
        run.at(15_000, Event.Streaming).at(60_000, Event.Tick)
        assertEquals(Phase.Streaming, run.phase)
        assertEquals(emptyList<Action>(), run.take())
    }

    @Test fun aConnectionThatReplacesTheStreamingOneStartsTheFifteenSecondWaitAgain() {
        val run = airPlay(1_000).at(2_000, Event.Streaming)
        run.at(50_000, Event.AirPlayConnected)
        assertEquals(Phase.AirPlay, run.phase)
        run.at(64_999, Event.Tick)
        assertEquals(emptyList<Action>(), run.take())
        run.at(65_000, Event.Tick)
        assertEquals(listOf<Action>(Action.End("no video from the iPhone in 15 s", begun = true)), run.take())
    }

    @Test fun theReplacingConnectionsFirstFrameClearsTheWaitAndADropInStreamingIsCounted() {
        val run = airPlay(1_000).at(2_000, Event.Streaming).at(3_000, Event.AirPlayEndedBeforeSetup)
        assertEquals(1, run.state.airplayDrops)
        run.at(50_000, Event.AirPlayConnected).at(51_000, Event.Streaming).at(90_000, Event.Tick)
        assertEquals(Phase.Streaming, run.phase)
        assertEquals(emptyList<Action>(), run.take())
    }

    @Test fun noStartRequestThirtySecondsAfterAuthEndsAtTheThirtiethSecond() {
        val run = session().at(1, Event.Opened).at(2, Event.CarkitOpen).at(3, Event.IdentificationAccepted)
        run.at(500, Event.Tick)
        assertEquals(Phase.Authenticating, run.phase)
        run.at(1_000, Event.Authenticated)
        run.at(30_999, Event.Tick)
        assertEquals(emptyList<Action>(), run.take())
        run.at(31_000, Event.Tick)
        assertEquals(listOf<Action>(Action.End("no CarPlay start request from the iPhone in 30 s", begun = true)), run.take())
    }

    @Test fun aStartSentBeforeTheAuthDeadlineReplacesItAndAnAuthOutsideItsPhaseIsIgnored() {
        val run = session().at(1, Event.Opened).at(1, Event.Authenticated)
        assertEquals(Phase.Pairing, run.phase)
        run.at(2, Event.CarkitOpen).at(3, Event.IdentificationAccepted).at(1_000, Event.Authenticated).at(20_000, Event.StartSent(1))
        assertEquals(Phase.StartSent, run.phase)
        run.at(31_000, Event.Tick)
        assertEquals(Phase.StartSent, run.phase)
        assertEquals(emptyList<Action>(), run.take())
    }

    // The user-exit latch.

    private val latched = CarPlayNativeSession.ExitLatch().exit("/dev/bus/usb/001/005")

    @Test fun theLatchHoldsThroughTheIphonesOwnReenumerationDuringOrJustAfterTheClose() {
        // The detach comes while the close runs (it sets the configuration back), the new instance attaches 2 s later.
        val during = latched.onDetach("/dev/bus/usb/001/005", 1_000).closed(1_500).onAttach("/dev/bus/usb/001/006", 3_000)
        assertTrue(during.held)
        assertEquals("/dev/bus/usb/001/006", during.deviceId)
        // The same after the close, at the edges of both windows.
        val after = latched.closed(0).onDetach("/dev/bus/usb/001/005", CarPlayNativeSession.LATCH_DETACH_AFTER_CLOSE_MS)
            .onAttach("/dev/bus/usb/001/006", CarPlayNativeSession.LATCH_DETACH_AFTER_CLOSE_MS + CarPlayNativeSession.LATCH_REATTACH_MS)
        assertTrue(after.held)
    }

    @Test fun aRealUnplugClearsTheLatch() {
        // Late after the close.
        assertFalse(latched.closed(0).onDetach("/dev/bus/usb/001/005", CarPlayNativeSession.LATCH_DETACH_AFTER_CLOSE_MS + 1).held)
        // Soon after the close, but the phone stays away longer than a re-enumeration.
        val soon = latched.closed(0).onDetach("/dev/bus/usb/001/005", 1_000)
        assertTrue(soon.held)
        assertFalse(soon.onAttach("/dev/bus/usb/001/007", 1_000 + CarPlayNativeSession.LATCH_REATTACH_MS + 1).held)
        // After the phone came back once, its next detach is an unplug, whenever it comes.
        val back = latched.onDetach("/dev/bus/usb/001/005", 1_000).closed(1_500).onAttach("/dev/bus/usb/001/006", 2_000)
        assertFalse(back.onDetach("/dev/bus/usb/001/006", 2_500).held)
    }

    @Test fun anotherDevicesDetachAndAnAttachWithNoDetachLeaveTheLatchAsItIs() {
        val closed = latched.closed(0)
        assertEquals(closed, closed.onDetach("/dev/bus/usb/001/009", 1_000))
        assertEquals(closed, closed.onAttach("/dev/bus/usb/001/005", 1_000))
        assertTrue(closed.held)
        assertEquals(closed, closed.exit("/dev/bus/usb/001/009"))
        assertFalse(CarPlayNativeSession.ExitLatch().onDetach("/dev/bus/usb/001/005", 0).held)
    }
}
