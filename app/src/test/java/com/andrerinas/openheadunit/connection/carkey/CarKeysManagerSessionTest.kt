package com.andrerinas.openheadunit.connection.carkey

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarKeysManagerSessionTest {

    @Test
    fun `a session wants every session-scoped receiver`() {
        assertTrue(CarKeysManager.wantsSessionReceiver(servesLearning = true, sessionActive = true, learning = false))
        assertTrue(CarKeysManager.wantsSessionReceiver(servesLearning = false, sessionActive = true, learning = true))
    }

    @Test
    fun `the keymap screen with no session wants only a receiver that serves learning`() {
        assertTrue(CarKeysManager.wantsSessionReceiver(servesLearning = true, sessionActive = false, learning = true))
        assertFalse(CarKeysManager.wantsSessionReceiver(servesLearning = false, sessionActive = false, learning = true))
    }

    @Test
    fun `nothing wants a session-scoped receiver with no session and no learning`() {
        assertFalse(CarKeysManager.wantsSessionReceiver(servesLearning = true, sessionActive = false, learning = false))
        assertFalse(CarKeysManager.wantsSessionReceiver(servesLearning = false, sessionActive = false, learning = false))
    }
}
