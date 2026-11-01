package com.andrerinas.openheadunit.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenOnRaisePolicyTest {

    private val P = ScreenOnRaisePolicy

    @Test
    fun `below API 26 the wake lock alone acts, keyguard or not`() {
        for (lit in listOf(true, false, null)) for (kg in listOf(true, false, null)) for (session in listOf(true, false)) {
            assertEquals(ScreenOnRaise.NONE, P.decide(25, lit, kg, session))
        }
    }

    @Test
    fun `below API 33 with no keyguard the wake lock alone lights the panel`() {
        for (lit in listOf(true, false, null)) for (kg in listOf(false, null)) for (session in listOf(true, false)) {
            assertEquals(ScreenOnRaise.NONE, P.decide(32, lit, kg, session))
        }
    }

    @Test
    fun `a keyguard raises a screen to dismiss it, even on a lit panel`() {
        for (sdk in listOf(26, 32, 33, 35)) for (lit in listOf(true, false, null)) {
            assertEquals(ScreenOnRaise.PROJECTION, P.decide(sdk, lit, keyguardLocked = true, sessionConnected = true))
            assertEquals(ScreenOnRaise.HOME, P.decide(sdk, lit, keyguardLocked = true, sessionConnected = false))
        }
    }

    @Test
    fun `a lit panel with no keyguard raises nothing, so a projecting session keeps its screen`() {
        assertEquals(ScreenOnRaise.NONE, P.decide(33, interactive = true, keyguardLocked = false, sessionConnected = true))
        assertEquals(ScreenOnRaise.NONE, P.decide(34, interactive = true, keyguardLocked = null, sessionConnected = false))
    }

    @Test
    fun `a dark panel with a session raises the projection, never the home screen`() {
        assertEquals(ScreenOnRaise.PROJECTION, P.decide(33, interactive = false, keyguardLocked = false, sessionConnected = true))
        assertEquals(ScreenOnRaise.PROJECTION, P.decide(35, interactive = null, keyguardLocked = null, sessionConnected = true))
    }

    @Test
    fun `a dark panel with no session raises the home screen`() {
        assertEquals(ScreenOnRaise.HOME, P.decide(33, interactive = false, keyguardLocked = false, sessionConnected = false))
        assertEquals(ScreenOnRaise.HOME, P.decide(36, interactive = null, keyguardLocked = null, sessionConnected = false))
    }
}
