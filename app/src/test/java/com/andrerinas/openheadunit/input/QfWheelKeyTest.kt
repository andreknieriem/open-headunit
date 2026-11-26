package com.andrerinas.openheadunit.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QfWheelKeyTest {

    @Test
    fun `only the vendor range is taken, never an Android key`() {
        assertTrue(QfWheelKey.isVendorKey(290))
        assertTrue(QfWheelKey.isVendorKey(368))
        assertFalse(QfWheelKey.isVendorKey(289))
        assertFalse(QfWheelKey.isVendorKey(369))
        assertFalse(QfWheelKey.isVendorKey(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertFalse(QfWheelKey.isVendorKey(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test
    fun `an Android key on the listener is never a press`() {
        assertEquals(QfWheelKey.Press.NONE,
            QfWheelKey.pressOf(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.ACTION_DOWN, 0, 0, 0L, 1_000L))
        assertEquals(QfWheelKey.Press.NONE, QfWheelKey.pressOf(KeyEvent.KEYCODE_HOME, null, 0, 0, 0L, 1_000L))
    }

    @Test
    fun `virtual codes round-trip, sit above 1000 and clear the other vendor ranges`() {
        assertEquals(7298, QfWheelKey.toKeyCode(298))
        assertEquals(298, QfWheelKey.qfCodeOf(7298))
        assertNull(QfWheelKey.qfCodeOf(7289))
        assertNull(QfWheelKey.qfCodeOf(6005))
        assertNull(TopwayKey.topwayCodeOf(QfWheelKey.toKeyCode(290)))
        assertNull(DynavinPanelKey.label(QfWheelKey.toKeyCode(368)))
        assertTrue(QfWheelKey.toKeyCode(290) >= 1000)
    }

    @Test
    fun `known keys and custom keys are named`() {
        assertEquals("Navi", QfWheelKey.nameOf(298))
        assertEquals("Mode", QfWheelKey.nameOf(292))
        assertEquals("Phone", QfWheelKey.nameOf(301))
        assertEquals("Voice", QfWheelKey.nameOf(304))
        assertEquals("KEY1", QfWheelKey.nameOf(354))
        assertEquals("KEY15", QfWheelKey.nameOf(368))
        assertNull(QfWheelKey.nameOf(295))
    }

    @Test
    fun `labels name the key for the keymap screen`() {
        assertEquals("QF Navi (298)", QfWheelKey.label(7298))
        assertEquals("QF KEY3 (356)", QfWheelKey.label(7356))
        assertEquals("QF key (295)", QfWheelKey.label(7295))
        assertNull(QfWheelKey.label(KeyEvent.KEYCODE_MEDIA_NEXT))
    }

    @Test
    fun `an event gives a down and an up, and a held key does not repeat`() {
        assertEquals(QfWheelKey.Press.DOWN, QfWheelKey.pressOf(300, KeyEvent.ACTION_DOWN, 0, 0, 0L, 1_000L))
        assertEquals(QfWheelKey.Press.NONE, QfWheelKey.pressOf(300, KeyEvent.ACTION_DOWN, 1, 0, 0L, 1_000L))
        assertEquals(QfWheelKey.Press.UP, QfWheelKey.pressOf(300, KeyEvent.ACTION_UP, 0, 0, 0L, 1_000L))
        assertEquals(QfWheelKey.Press.NONE, QfWheelKey.pressOf(300, 2, 0, 0, 0L, 1_000L))
    }

    @Test
    fun `a call with no event is one press, and a quick second call is the same press`() {
        assertEquals(QfWheelKey.Press.CLICK, QfWheelKey.pressOf(298, null, 0, 0, 0L, 10_000L))
        assertEquals(QfWheelKey.Press.NONE, QfWheelKey.pressOf(298, null, 0, 298, 10_000L, 10_100L))
        assertEquals(QfWheelKey.Press.CLICK, QfWheelKey.pressOf(298, null, 0, 298, 10_000L, 10_000L + QfWheelKey.EVENTLESS_REPEAT_MS))
        assertEquals(QfWheelKey.Press.CLICK, QfWheelKey.pressOf(300, null, 0, 298, 10_000L, 10_100L))
    }

    @Test
    fun `a run of eventless calls under 250 ms apart still counts a press each window`() {
        val gate = QfWheelKey.EventlessGate()
        val presses = (0L..600L step 100L).map { gate.next(325, 10_000L + it) }
        val clicks = presses.withIndex().filter { it.value == QfWheelKey.Press.CLICK }.map { it.index * 100 }
        assertEquals(listOf(0, 300, 600), clicks)
    }

    @Test
    fun `the gate keeps a dropped call from moving the window`() {
        val gate = QfWheelKey.EventlessGate()
        assertEquals(QfWheelKey.Press.CLICK, gate.next(298, 10_000L))
        assertEquals(QfWheelKey.Press.NONE, gate.next(298, 10_200L))
        assertEquals(QfWheelKey.Press.CLICK, gate.next(298, 10_250L))
        assertEquals(QfWheelKey.Press.CLICK, gate.next(300, 10_260L))
        assertEquals(QfWheelKey.Press.NONE, gate.next(KeyEvent.KEYCODE_HOME, 20_000L))
    }
}
