package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.SessionEndGroupPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEndGroupPolicyTest {

    @Test
    fun `a user exit stops the launcher so the phone leaves the network`() {
        assertEquals(
            Action.STOP,
            SessionEndGroupPolicy.decide(activeModeIsNative = true, isUserExit = true, rearmedAfterWiredSession = false)
        )
    }

    /**
     * The point of the policy: a session that ends on its own leaves the network up. Removing
     * it and creating another gives the group a new address on units that re-address, and the
     * phone then spends seconds on the network it saved before it takes the new one.
     */
    @Test
    fun `an unexpected end keeps the network up for the phone's return`() {
        assertEquals(
            Action.KEEP_AND_REARM,
            SessionEndGroupPolicy.decide(activeModeIsNative = true, isUserExit = false, rearmedAfterWiredSession = false)
        )
    }

    @Test
    fun `a mode that is not Native is not this policy's session`() {
        for (mode in WifiLauncherMode.entries) {
            if (mode == WifiLauncherMode.NATIVE) continue
            for (userExit in listOf(true, false)) {
                assertEquals(
                    "mode=$mode userExit=$userExit",
                    Action.NONE,
                    SessionEndGroupPolicy.decide(activeModeIsNative = false, isUserExit = userExit, rearmedAfterWiredSession = false)
                )
            }
        }
    }

    @Test
    fun `a wired session's re-arm owns the wireless stack`() {
        assertEquals(
            Action.NONE,
            SessionEndGroupPolicy.decide(activeModeIsNative = true, isUserExit = true, rearmedAfterWiredSession = true)
        )
        assertEquals(
            Action.NONE,
            SessionEndGroupPolicy.decide(activeModeIsNative = true, isUserExit = false, rearmedAfterWiredSession = true)
        )
    }

    @Test
    fun `listeners closed by a completed handoff are reopened`() {
        assertTrue(SessionEndGroupPolicy.shouldReopenAaListeners(handshakeRunning = true, listenersClosedForSession = true))
    }

    /** A session that landed on the TCP port without a handshake never closed them. */
    @Test
    fun `listeners that were never closed are not opened twice`() {
        assertFalse(SessionEndGroupPolicy.shouldReopenAaListeners(handshakeRunning = true, listenersClosedForSession = false))
    }

    @Test
    fun `a manager that never started is not the re-arm's to open`() {
        assertFalse(SessionEndGroupPolicy.shouldReopenAaListeners(handshakeRunning = false, listenersClosedForSession = true))
        assertFalse(SessionEndGroupPolicy.shouldReopenAaListeners(handshakeRunning = false, listenersClosedForSession = false))
    }

    @Test
    fun `a phone that said goodbye is not woken again`() {
        assertFalse(SessionEndGroupPolicy.wakesPhoneAfterSessionEnd(phoneSaidGoodbye = true))
    }

    @Test
    fun `a link that died is woken, because nothing chose to end it`() {
        assertTrue(SessionEndGroupPolicy.wakesPhoneAfterSessionEnd(phoneSaidGoodbye = false))
    }

    @Test
    fun `a poke with no session behind it waits for nothing`() {
        assertEquals(0L, SessionEndGroupPolicy.wakeSettleRemainingMs(sessionEndedAt = 0L, now = 10_000L))
    }

    @Test
    fun `the settle is what is left of the window`() {
        assertEquals(
            SessionEndGroupPolicy.WAKE_SETTLE_MS - 1_000L,
            SessionEndGroupPolicy.wakeSettleRemainingMs(sessionEndedAt = 10_000L, now = 11_000L)
        )
        assertEquals(
            SessionEndGroupPolicy.WAKE_SETTLE_MS,
            SessionEndGroupPolicy.wakeSettleRemainingMs(sessionEndedAt = 10_000L, now = 10_000L)
        )
    }

    @Test
    fun `a session that ended long ago costs nothing`() {
        assertEquals(
            0L,
            SessionEndGroupPolicy.wakeSettleRemainingMs(
                sessionEndedAt = 10_000L,
                now = 10_000L + SessionEndGroupPolicy.WAKE_SETTLE_MS
            )
        )
        assertEquals(0L, SessionEndGroupPolicy.wakeSettleRemainingMs(sessionEndedAt = 10_000L, now = 90_000L))
    }

    /** elapsedRealtime does not go backwards, but a stamp read across a restart can look like it. */
    @Test
    fun `a clock that reads backwards never waits longer than the window`() {
        assertEquals(
            SessionEndGroupPolicy.WAKE_SETTLE_MS,
            SessionEndGroupPolicy.wakeSettleRemainingMs(sessionEndedAt = 10_000L, now = 0L)
        )
    }

    @Test
    fun `ending the session at the head unit keeps the network up without waking the phone`() {
        // The network is kept precisely so the phone can return when the user wants it to. Poking
        // it five seconds later would undo the button they just pressed.
        assertFalse(
            SessionEndGroupPolicy.wakesPhoneAfterSessionEnd(
                phoneSaidGoodbye = false, headUnitEndedByHand = true
            )
        )
    }

    @Test
    fun `a link that simply died is still worth a wake`() {
        assertTrue(
            SessionEndGroupPolicy.wakesPhoneAfterSessionEnd(
                phoneSaidGoodbye = false, headUnitEndedByHand = false
            )
        )
    }

    @Test
    fun `ending it by hand is not a user exit, so the network survives`() {
        // The whole point: STOP would take the group with it, which is what "Stop connection" is
        // for. This lands in the same branch the driver switch already uses.
        assertEquals(
            SessionEndGroupPolicy.Action.KEEP_AND_REARM,
            SessionEndGroupPolicy.decide(
                activeModeIsNative = true, isUserExit = false, rearmedAfterWiredSession = false
            )
        )
    }
}
