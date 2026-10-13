package com.andrerinas.openheadunit.app

import com.andrerinas.openheadunit.aap.AapService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalPowerDisconnectPolicyTest {

    private val P = ExternalPowerDisconnectPolicy

    @Test
    fun `a switch that is off ignores every event`() {
        assertEquals(ExternalPowerArm.IGNORE, P.onPowerEvent(false, connected = false, sessionUp = true))
        assertEquals(ExternalPowerArm.IGNORE, P.onPowerEvent(false, connected = true, sessionUp = true))
        assertEquals(ExternalPowerArm.IGNORE, P.onPowerEvent(false, connected = false, sessionUp = false))
    }

    @Test
    fun `power lost with a session arms`() {
        assertEquals(ExternalPowerArm.ARM, P.onPowerEvent(true, connected = false, sessionUp = true))
    }

    @Test
    fun `power lost with nothing projecting arms nothing`() {
        assertEquals(ExternalPowerArm.NOTHING_PROJECTING, P.onPowerEvent(true, connected = false, sessionUp = false))
    }

    @Test
    fun `power back cancels whatever the session state`() {
        assertEquals(ExternalPowerArm.CANCEL, P.onPowerEvent(true, connected = true, sessionUp = true))
        assertEquals(ExternalPowerArm.CANCEL, P.onPowerEvent(true, connected = true, sessionUp = false))
    }

    @Test
    fun `lost, back, lost arms once per live loss`() {
        val seq = listOf(false, true, false).map { P.onPowerEvent(true, it, sessionUp = true) }
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
        assertTrue(P.shouldEndSession(sessionUp = true, chargerBack = false))
        assertFalse(P.shouldEndSession(sessionUp = false, chargerBack = false))
        assertFalse(P.shouldEndSession(sessionUp = true, chargerBack = true))
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
        assertEquals(emptyList<ExternalPowerStep>(), P.planOnReturn(false, true))
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
        assertTrue(P.standDownHolds(powerBack = false))
        assertFalse(P.standDownHolds(powerBack = true))
    }
}
