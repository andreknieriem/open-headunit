package com.andrerinas.openheadunit.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingButtonOpacityPolicyTest {

    @Test
    fun `when connection status mode is off, connected opacity is used regardless of connection state`() {
        val alphaConnected = FloatingButtonOpacityPolicy.targetAlpha(
            isConnectionStatusMode = false,
            isConnected = true,
            connectedOpacityPercent = 80,
            disconnectedOpacityPercent = 10
        )
        val alphaDisconnected = FloatingButtonOpacityPolicy.targetAlpha(
            isConnectionStatusMode = false,
            isConnected = false,
            connectedOpacityPercent = 80,
            disconnectedOpacityPercent = 10
        )
        assertEquals(0.8f, alphaConnected, 0.001f)
        assertEquals(0.8f, alphaDisconnected, 0.001f)
    }

    @Test
    fun `when connection status mode is on and connected, connected opacity is used`() {
        val alpha = FloatingButtonOpacityPolicy.targetAlpha(
            isConnectionStatusMode = true,
            isConnected = true,
            connectedOpacityPercent = 80,
            disconnectedOpacityPercent = 10
        )
        assertEquals(0.8f, alpha, 0.001f)
    }

    @Test
    fun `when connection status mode is on and disconnected, disconnected opacity is used`() {
        val alpha = FloatingButtonOpacityPolicy.targetAlpha(
            isConnectionStatusMode = true,
            isConnected = false,
            connectedOpacityPercent = 80,
            disconnectedOpacityPercent = 0
        )
        assertEquals(0.0f, alpha, 0.001f)
    }

    @Test
    fun `connected opacity is clamped to 10 percent floor and disconnected to 0 percent`() {
        val alphaUnderConnected = FloatingButtonOpacityPolicy.targetAlpha(
            isConnectionStatusMode = false,
            isConnected = true,
            connectedOpacityPercent = 0,
            disconnectedOpacityPercent = 0
        )
        val alphaUnderDisconnected = FloatingButtonOpacityPolicy.targetAlpha(
            isConnectionStatusMode = true,
            isConnected = false,
            connectedOpacityPercent = 80,
            disconnectedOpacityPercent = -10
        )
        val alphaOver = FloatingButtonOpacityPolicy.targetAlpha(
            isConnectionStatusMode = false,
            isConnected = true,
            connectedOpacityPercent = 150,
            disconnectedOpacityPercent = 0
        )
        assertEquals(0.1f, alphaUnderConnected, 0.001f)
        assertEquals(0.0f, alphaUnderDisconnected, 0.001f)
        assertEquals(1.0f, alphaOver, 0.001f)
    }

    @Test
    fun `overlay window is touchable only when opacity is above 0 percent`() {
        assertFalse(FloatingButtonOpacityPolicy.isTouchable(0.0f))
        assertTrue(FloatingButtonOpacityPolicy.isTouchable(0.1f))
        assertTrue(FloatingButtonOpacityPolicy.isTouchable(0.8f))
    }
}
