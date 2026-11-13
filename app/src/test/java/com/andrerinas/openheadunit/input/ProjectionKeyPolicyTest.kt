package com.andrerinas.openheadunit.input

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionKeyPolicyTest {

    @Test
    fun `unmapped back key keeps system back behavior`() {
        assertFalse(
            ProjectionKeyPolicy.shouldRouteBackKeyToProjection(
                bindings = emptyMap(),
                keyCode = KeyEvent.KEYCODE_BACK
            )
        )
    }

    @Test
    fun `mapped physical back key routes to projection`() {
        assertTrue(
            ProjectionKeyPolicy.shouldRouteBackKeyToProjection(
                bindings = mapOf(KeyEvent.KEYCODE_BACK to InputAction.AaKey(KeyEvent.KEYCODE_BACK)),
                keyCode = KeyEvent.KEYCODE_BACK
            )
        )
    }

    @Test
    fun `physical back can be mapped to another Android Auto action`() {
        assertTrue(
            ProjectionKeyPolicy.shouldRouteBackKeyToProjection(
                bindings = mapOf(KeyEvent.KEYCODE_BACK to InputAction.AaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)),
                keyCode = KeyEvent.KEYCODE_BACK
            )
        )
    }

    @Test
    fun `physical back bound to a local action or an app is routed too`() {
        assertTrue(
            ProjectionKeyPolicy.shouldRouteBackKeyToProjection(
                bindings = mapOf(KeyEvent.KEYCODE_BACK to InputAction.Local(LocalAction.DIM_SCREEN)),
                keyCode = KeyEvent.KEYCODE_BACK
            )
        )
    }

    @Test
    fun `back bound to nothing keeps system back behavior`() {
        assertFalse(
            ProjectionKeyPolicy.shouldRouteBackKeyToProjection(
                bindings = mapOf(KeyEvent.KEYCODE_BACK to InputAction.None),
                keyCode = KeyEvent.KEYCODE_BACK
            )
        )
    }

    @Test
    fun `non-back keys do not use the back override`() {
        assertFalse(
            ProjectionKeyPolicy.shouldRouteBackKeyToProjection(
                bindings = mapOf(KeyEvent.KEYCODE_BACK to InputAction.AaKey(KeyEvent.KEYCODE_BACK)),
                keyCode = KeyEvent.KEYCODE_DPAD_CENTER
            )
        )
    }

    @Test
    fun `isHomeKey returns true for KEYCODE_HOME and false for other keys`() {
        assertTrue(ProjectionKeyPolicy.isHomeKey(KeyEvent.KEYCODE_HOME))
        assertFalse(ProjectionKeyPolicy.isHomeKey(KeyEvent.KEYCODE_BACK))
        assertFalse(ProjectionKeyPolicy.isHomeKey(KeyEvent.KEYCODE_VOLUME_UP))
        assertFalse(ProjectionKeyPolicy.isHomeKey(KeyEvent.KEYCODE_DPAD_CENTER))
    }
}
