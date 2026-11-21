package com.andrerinas.openheadunit.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DynavinPanelKeyTest {

    @Test
    fun `only a knob turn carries its own press identity`() {
        assertTrue(DynavinPanelKey.isKnobTurn(380))
        assertTrue(DynavinPanelKey.isKnobTurn(381))
        assertFalse(DynavinPanelKey.isKnobTurn(340))
        assertFalse(DynavinPanelKey.isKnobTurn(376))
        assertFalse(DynavinPanelKey.isKnobTurn(305))
    }

    @Test
    fun `an unbound knob key sends its default`() {
        assertEquals(KeyEvent.KEYCODE_DPAD_CENTER, DynavinPanelKey.sendCode(340, emptyMap()))
        assertEquals(KeyEvent.KEYCODE_SOFT_RIGHT, DynavinPanelKey.sendCode(380, emptyMap()))
        assertEquals(KeyEvent.KEYCODE_SOFT_LEFT, DynavinPanelKey.sendCode(381, emptyMap()))
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, DynavinPanelKey.sendCode(376, emptyMap()))
    }

    @Test
    fun `a bound key goes on as its virtual code, Do nothing included`() {
        val virtual = DynavinPanelKey.toKeyCode(340)
        assertEquals(virtual, DynavinPanelKey.sendCode(340, mapOf(virtual to InputAction.AaKey(KeyEvent.KEYCODE_HOME))))
        assertEquals(virtual, DynavinPanelKey.sendCode(340, mapOf(virtual to InputAction.Ignore)))
        assertEquals(KeyEvent.KEYCODE_DPAD_CENTER, DynavinPanelKey.sendCode(340, mapOf(virtual to InputAction.None)))
    }

    @Test
    fun `a key with no default stays virtual until bound`() {
        assertEquals(5305, DynavinPanelKey.sendCode(305, emptyMap()))
    }

    @Test
    fun `an MCU frame names the joystick key in byte four`() {
        assertEquals(376, DynavinPanelKey.mcuKey(byteArrayOf(0, 0, 0, 0, 240.toByte())))
        assertEquals(379, DynavinPanelKey.mcuKey(byteArrayOf(0, 0, 0, 0, 243.toByte(), 1)))
        assertNull(DynavinPanelKey.mcuKey(byteArrayOf(0, 0, 0, 0, 5)))
        assertNull(DynavinPanelKey.mcuKey(byteArrayOf(0, 0, 0, 0)))
        assertNull(DynavinPanelKey.mcuKey(null))
    }

    @Test
    fun `only the joystick codes come from MCU frames`() {
        assertTrue(DynavinPanelKey.isMcuKey(377))
        assertFalse(DynavinPanelKey.isMcuKey(340))
    }

    @Test
    fun `labels name the virtual range only`() {
        assertEquals("Dynavin Knob press (340)", DynavinPanelKey.label(5340))
        assertEquals("Dynavin key (999)", DynavinPanelKey.label(5999))
        assertNull(DynavinPanelKey.label(4999))
        assertNull(DynavinPanelKey.label(6000))
    }
}
