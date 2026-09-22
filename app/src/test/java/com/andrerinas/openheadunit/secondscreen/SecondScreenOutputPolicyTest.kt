package com.andrerinas.openheadunit.secondscreen

import com.andrerinas.openheadunit.secondscreen.SecondScreenOutputPolicy.Availability
import com.andrerinas.openheadunit.secondscreen.SecondScreenOutputPolicy.Output
import com.andrerinas.openheadunit.secondscreen.SecondScreenOutputPolicy.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecondScreenOutputPolicyTest {

    private val panel = Target(1024, 600, 160)

    @Test
    fun `an unknown or missing stored output reads as an Android display`() {
        assertEquals(Output.ANDROID_DISPLAY, Output.of(null))
        assertEquals(Output.ANDROID_DISPLAY, Output.of("HOLOGRAM"))
        assertEquals(Output.MS912X, Output.of("MS912X"))
    }

    @Test
    fun `each output announces only its own availability`() {
        val onlyNetwork = Availability(network = panel)
        assertEquals(panel, SecondScreenOutputPolicy.target(Output.NETWORK, onlyNetwork))
        for (other in listOf(Output.ANDROID_DISPLAY, Output.MS912X, Output.USB_DISPLAY)) {
            assertNull(SecondScreenOutputPolicy.target(other, onlyNetwork))
        }
    }

    @Test
    fun `only the Android display and the MacroSilicon adapter are decoded here`() {
        assertTrue(SecondScreenOutputPolicy.decodesOnHeadUnit(Output.ANDROID_DISPLAY))
        assertTrue(SecondScreenOutputPolicy.decodesOnHeadUnit(Output.MS912X))
        assertFalse(SecondScreenOutputPolicy.decodesOnHeadUnit(Output.NETWORK))
        assertFalse(SecondScreenOutputPolicy.decodesOnHeadUnit(Output.USB_DISPLAY))
    }

    @Test
    fun `a stored network size out of range falls back to the smallest`() {
        assertEquals(Target(800, 480, 160), SecondScreenOutputPolicy.networkTarget(-1))
        assertEquals(Target(800, 480, 160), SecondScreenOutputPolicy.networkTarget(9))
        assertEquals(Target(1280, 720, 213), SecondScreenOutputPolicy.networkTarget(1))
    }
}
