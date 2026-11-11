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
                actionToPhysicalKeyCode = emptyMap(),
                keyCode = KeyEvent.KEYCODE_BACK
            )
        )
    }

    @Test
    fun `mapped physical back key routes to projection`() {
        assertTrue(
            ProjectionKeyPolicy.shouldRouteBackKeyToProjection(
                actionToPhysicalKeyCode = mapOf(KeyEvent.KEYCODE_BACK to KeyEvent.KEYCODE_BACK),
                keyCode = KeyEvent.KEYCODE_BACK
            )
        )
    }

    @Test
    fun `physical back can be mapped to another Android Auto action`() {
        assertTrue(
            ProjectionKeyPolicy.shouldRouteBackKeyToProjection(
                actionToPhysicalKeyCode = mapOf(KeyEvent.KEYCODE_MEDIA_PREVIOUS to KeyEvent.KEYCODE_BACK),
                keyCode = KeyEvent.KEYCODE_BACK
            )
        )
    }

    @Test
    fun `non-back keys do not use the back override`() {
        assertFalse(
            ProjectionKeyPolicy.shouldRouteBackKeyToProjection(
                actionToPhysicalKeyCode = mapOf(KeyEvent.KEYCODE_BACK to KeyEvent.KEYCODE_BACK),
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

    @Test
    fun `isVolumeKey covers up, down and mute only`() {
        assertTrue(ProjectionKeyPolicy.isVolumeKey(KeyEvent.KEYCODE_VOLUME_UP))
        assertTrue(ProjectionKeyPolicy.isVolumeKey(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertTrue(ProjectionKeyPolicy.isVolumeKey(KeyEvent.KEYCODE_VOLUME_MUTE))
        assertFalse(ProjectionKeyPolicy.isVolumeKey(KeyEvent.KEYCODE_MUTE))
        assertFalse(ProjectionKeyPolicy.isVolumeKey(KeyEvent.KEYCODE_BACK))
        assertFalse(ProjectionKeyPolicy.isVolumeKey(KeyEvent.KEYCODE_MEDIA_NEXT))
    }
}
