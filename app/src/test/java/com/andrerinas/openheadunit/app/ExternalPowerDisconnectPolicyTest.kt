package com.andrerinas.openheadunit.app

import com.andrerinas.openheadunit.aap.AapService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalPowerDisconnectPolicyTest {

    private val P = ExternalPowerDisconnectPolicy

    @Test
    fun `a switch that is off arms nothing`() {
        assertEquals(ExternalPowerArm.IGNORE, P.onPowerEvent(false, connected = false, sessionUp = true, screenRow = true))
        assertEquals(ExternalPowerArm.IGNORE, P.onPowerEvent(false, connected = false, sessionUp = false, screenRow = false))
    }

    @Test
    fun `power back cancels even with the switch turned off`() {
        assertEquals(ExternalPowerArm.CANCEL, P.onPowerEvent(false, connected = true, sessionUp = true, screenRow = true))
        assertEquals(ExternalPowerArm.CANCEL, P.onPowerEvent(false, connected = true, sessionUp = false, screenRow = false))
    }

    @Test
    fun `power lost with a session arms`() {
        assertEquals(ExternalPowerArm.ARM, P.onPowerEvent(true, connected = false, sessionUp = true, screenRow = true))
    }

    @Test
    fun `power lost with nothing projecting and the screen row off arms nothing`() {
        assertEquals(ExternalPowerArm.NOTHING_PROJECTING, P.onPowerEvent(true, connected = false, sessionUp = false, screenRow = false))
    }

    @Test
    fun `power lost with nothing projecting and the screen row on arms the screen only`() {
        assertEquals(ExternalPowerArm.ARM_SCREEN_ONLY, P.onPowerEvent(true, connected = false, sessionUp = false, screenRow = true))
    }

    @Test
    fun `a session up arms the full timer whatever the screen row`() {
        assertEquals(ExternalPowerArm.ARM, P.onPowerEvent(true, connected = false, sessionUp = true, screenRow = true))
        assertEquals(ExternalPowerArm.ARM, P.onPowerEvent(true, connected = false, sessionUp = true, screenRow = false))
    }

    @Test
    fun `a switch that is off ignores power loss whatever the screen row`() {
        assertEquals(ExternalPowerArm.IGNORE, P.onPowerEvent(false, connected = false, sessionUp = false, screenRow = true))
        assertEquals(ExternalPowerArm.IGNORE, P.onPowerEvent(false, connected = false, sessionUp = true, screenRow = true))
    }

    @Test
    fun `screen only outcome is the plug first`() {
        assertEquals(ScreenOnlyOutcome.SKIP_POWER_BACK, P.screenOnlyOutcome(true, true, true, false, chargerBack = true))
        assertEquals(ScreenOnlyOutcome.SKIP_POWER_BACK, P.screenOnlyOutcome(false, false, false, true, chargerBack = true))
    }

    @Test
    fun `screen only outcome skips a switch or row turned off`() {
        assertEquals(ScreenOnlyOutcome.SKIP_DISABLED, P.screenOnlyOutcome(false, true, true, false, chargerBack = false))
        assertEquals(ScreenOnlyOutcome.SKIP_DISABLED, P.screenOnlyOutcome(true, false, true, false, chargerBack = false))
    }

    @Test
    fun `screen only outcome leaves a session up or forming alone whatever the grant`() {
        assertEquals(ScreenOnlyOutcome.SKIP_SESSION, P.screenOnlyOutcome(true, true, true, true, chargerBack = false))
        assertEquals(ScreenOnlyOutcome.SKIP_SESSION, P.screenOnlyOutcome(true, true, false, true, chargerBack = false))
    }

    @Test
    fun `screen only outcome without the grant warns`() {
        assertEquals(ScreenOnlyOutcome.NO_GRANT, P.screenOnlyOutcome(true, true, false, false, chargerBack = false))
    }

    @Test
    fun `screen only outcome turns the screen off when everything holds`() {
        assertEquals(ScreenOnlyOutcome.SCREEN_OFF, P.screenOnlyOutcome(true, true, true, false, chargerBack = false))
    }

    @Test
    fun `power back cancels whatever the session state`() {
        assertEquals(ExternalPowerArm.CANCEL, P.onPowerEvent(true, connected = true, sessionUp = true, screenRow = true))
        assertEquals(ExternalPowerArm.CANCEL, P.onPowerEvent(true, connected = true, sessionUp = false, screenRow = false))
    }

    @Test
    fun `lost, back, lost arms once per live loss`() {
        val seq = listOf(false, true, false).map { P.onPowerEvent(true, it, sessionUp = true, screenRow = true) }
        assertEquals(listOf(ExternalPowerArm.ARM, ExternalPowerArm.CANCEL, ExternalPowerArm.ARM), seq)
    }

    @Test
    fun `delay is seconds in milliseconds`() {
        assertEquals(30_000L, P.delayMs(30))
    }

    @Test
    fun `delay is clamped to the range`() {
        assertEquals(5_000L, P.delayMs(0))
        assertEquals(5_000L, P.delayMs(4))
        assertEquals(5_000L, P.delayMs(-1))
        assertEquals(3_600_000L, P.delayMs(3600))
        assertEquals(3_600_000L, P.delayMs(99_999))
    }

    @Test
    fun `default delay is inside the range`() {
        assertTrue(P.DEFAULT_DELAY_SECONDS in P.MIN_DELAY_SECONDS..BtAutoDisconnectPolicy.MAX_DELAY_SECONDS)
    }

    @Test
    fun `minimum delay outlasts the ACC inference window`() {
        assertTrue(P.MIN_DELAY_SECONDS * 1000L >= AapService.ACC_POWER_LOSS_CORROBORATION_WINDOW_MS)
    }

    @Test
    fun `a live session ends when power stayed away`() {
        assertTrue(P.shouldEndSession(enabled = true, sessionUp = true, chargerBack = false))
        assertFalse(P.shouldEndSession(enabled = true, sessionUp = false, chargerBack = false))
        assertFalse(P.shouldEndSession(enabled = true, sessionUp = true, chargerBack = true))
    }

    @Test
    fun `a switch turned off during the delay ends nothing`() {
        assertFalse(P.shouldEndSession(enabled = false, sessionUp = true, chargerBack = false))
    }

    @Test
    fun `screen goes off only with the row and the grant`() {
        assertEquals(listOf(ExternalPowerStep.DISCONNECT, ExternalPowerStep.SCREEN_OFF), P.planAfterEnd(true, true))
        assertEquals(listOf(ExternalPowerStep.DISCONNECT), P.planAfterEnd(true, false))
        assertEquals(listOf(ExternalPowerStep.DISCONNECT), P.planAfterEnd(false, true))
        assertEquals(listOf(ExternalPowerStep.DISCONNECT), P.planAfterEnd(false, false))
    }

    @Test
    fun `every end plan starts with the disconnect`() {
        for (row in listOf(true, false)) for (grant in listOf(true, false)) {
            assertEquals(ExternalPowerStep.DISCONNECT, P.planAfterEnd(row, grant).first())
        }
    }

    @Test
    fun `power back lights the screen and re-arms only after our own end`() {
        assertEquals(listOf(ExternalPowerStep.SCREEN_ON, ExternalPowerStep.REARM), P.planOnReturn(true, true))
        assertEquals(listOf(ExternalPowerStep.REARM), P.planOnReturn(true, false))
        assertEquals(listOf(ExternalPowerStep.SCREEN_ON), P.planOnReturn(false, true))
        assertEquals(emptyList<ExternalPowerStep>(), P.planOnReturn(false, false))
    }

    @Test
    fun `power back during our own session end waits for the end`() {
        assertTrue(P.returnWaitsForEnd(endedByUs = true, stillEnding = true))
        assertFalse(P.returnWaitsForEnd(endedByUs = true, stillEnding = false))
        assertFalse(P.returnWaitsForEnd(endedByUs = false, stillEnding = true))
        assertFalse(P.returnWaitsForEnd(endedByUs = false, stillEnding = false))
    }

    @Test
    fun `an end that never reached its stand-down is dropped, and only that one`() {
        assertTrue(P.dropsUnreachedEnd(stillEnding = true, reachedStandDown = false, sameEnd = true))
        assertFalse(P.dropsUnreachedEnd(stillEnding = true, reachedStandDown = true, sameEnd = true))
        assertFalse(P.dropsUnreachedEnd(stillEnding = false, reachedStandDown = false, sameEnd = true))
        assertFalse(P.dropsUnreachedEnd(stillEnding = true, reachedStandDown = false, sameEnd = false))
    }

    @Test
    fun `the stand-down holds only while power is still out`() {
        assertTrue(P.standDownHolds(powerBackSeen = false, pluggedNow = false))
        assertFalse(P.standDownHolds(powerBackSeen = true, pluggedNow = true))
    }

    @Test
    fun `a return unplugged again before the stand-down still stands down`() {
        assertTrue(P.standDownHolds(powerBackSeen = true, pluggedNow = false))
    }

    @Test
    fun `a wired re-arm runs now when the stand-down does not hold`() {
        assertEquals(WiredRearm.NOW, P.wiredRearm(standDownHolds = false, endedByPower = true))
        assertEquals(WiredRearm.NOW, P.wiredRearm(standDownHolds = false, endedByPower = false))
    }

    @Test
    fun `a wired re-arm waits for power back after a power stand-down`() {
        assertEquals(WiredRearm.ON_POWER_BACK, P.wiredRearm(standDownHolds = true, endedByPower = true))
    }

    @Test
    fun `a wired re-arm is dropped after a Bluetooth stand-down`() {
        assertEquals(WiredRearm.DROP, P.wiredRearm(standDownHolds = true, endedByPower = false))
    }
}
