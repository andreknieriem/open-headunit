package com.andrerinas.openheadunit.input

import android.view.KeyEvent
import com.andrerinas.openheadunit.connection.carkey.CarKeyReceiver
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionKeyPolicyTest {

    @Test
    fun `a car key broadcast does not send its key a second time`() {
        // BYD, FYT and the OEM broadcast receiver use the default route; vendor routes add a suffix.
        assertFalse(ProjectionKeyPolicy.broadcastSendsKey(CarKeyReceiver.SOURCE))
        assertFalse(ProjectionKeyPolicy.broadcastSendsKey("carkey-topway"))
        assertFalse(ProjectionKeyPolicy.broadcastSendsKey("carkey-ola"))
    }

    @Test
    fun `a key broadcast from any other sender still sends its key`() {
        // Third-party KeyIntents and the media button receiver carry no route.
        assertTrue(ProjectionKeyPolicy.broadcastSendsKey(null))
        assertTrue(ProjectionKeyPolicy.broadcastSendsKey("session"))
    }

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

    @Test
    fun `isVolumeKey covers up, down and mute only`() {
        assertTrue(ProjectionKeyPolicy.isVolumeKey(KeyEvent.KEYCODE_VOLUME_UP))
        assertTrue(ProjectionKeyPolicy.isVolumeKey(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertTrue(ProjectionKeyPolicy.isVolumeKey(KeyEvent.KEYCODE_VOLUME_MUTE))
        assertFalse(ProjectionKeyPolicy.isVolumeKey(KeyEvent.KEYCODE_MUTE))
        assertFalse(ProjectionKeyPolicy.isVolumeKey(KeyEvent.KEYCODE_BACK))
        assertFalse(ProjectionKeyPolicy.isVolumeKey(KeyEvent.KEYCODE_MEDIA_NEXT))
    }

    private val up = KeyEvent.KEYCODE_VOLUME_UP
    private val volumeKeys = listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)

    @Test
    fun `unbound volume keys are local`() {
        for (code in volumeKeys) {
            assertTrue(ProjectionKeyPolicy.isLocalKey(code, emptyMap(), false, "carkey"))
        }
    }

    @Test
    fun `a bound volume key is not local`() {
        val bindings = mapOf(up to InputAction.AaKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertFalse(ProjectionKeyPolicy.isLocalKey(up, bindings, false, "carkey"))
        assertTrue(ProjectionKeyPolicy.isLocalKey(KeyEvent.KEYCODE_VOLUME_DOWN, bindings, false, "carkey"))
        assertTrue(ProjectionKeyPolicy.isLocalKey(up, mapOf(up to InputAction.None), false, "carkey"))
    }

    @Test
    fun `volume to the phone makes no volume key local`() {
        for (code in volumeKeys) {
            for (src in listOf("carkey", "remote-keycode", "projection")) {
                assertFalse(ProjectionKeyPolicy.isLocalKey(code, emptyMap(), true, src))
            }
        }
    }

    @Test
    fun `the mute command source is never local`() {
        assertFalse(ProjectionKeyPolicy.isLocalKey(KeyEvent.KEYCODE_VOLUME_MUTE, emptyMap(), false, "remote-mute"))
        assertTrue(ProjectionKeyPolicy.isLocalKey(KeyEvent.KEYCODE_VOLUME_MUTE, emptyMap(), false, "remote-keycode"))
    }

    @Test
    fun `mute key 91 back and media keys are not volume keys`() {
        for (code in listOf(KeyEvent.KEYCODE_MUTE, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MEDIA_NEXT)) {
            assertFalse(ProjectionKeyPolicy.isVolumeKey(code))
            assertFalse(ProjectionKeyPolicy.isLocalKey(code, emptyMap(), false, "carkey"))
        }
    }

    @Test
    fun `a locked unit keeps only volume keys`() {
        for (code in volumeKeys) assertTrue(ProjectionKeyPolicy.lockKeepsKey(code, true))
        assertFalse(ProjectionKeyPolicy.lockKeepsKey(up, false))
        assertFalse(ProjectionKeyPolicy.lockKeepsKey(KeyEvent.KEYCODE_MEDIA_NEXT, true))
    }

    @Test
    fun `an unbound volume key stays with the firmware`() {
        for (code in volumeKeys) {
            assertTrue(ProjectionKeyPolicy.firmwareKeepsKey(code, emptyMap(), false, false, false))
        }
    }

    @Test
    fun `a bound volume key or volume to the phone is not left to the firmware`() {
        val bindings = mapOf(up to InputAction.AaKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertFalse(ProjectionKeyPolicy.firmwareKeepsKey(up, bindings, false, false, false))
        assertFalse(ProjectionKeyPolicy.firmwareKeepsKey(up, emptyMap(), true, false, false))
    }

    @Test
    fun `a locked unit leaves every volume key to the firmware`() {
        val bindings = mapOf(up to InputAction.AaKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertTrue(ProjectionKeyPolicy.firmwareKeepsKey(up, bindings, true, true, false))
        assertFalse(ProjectionKeyPolicy.firmwareKeepsKey(KeyEvent.KEYCODE_MEDIA_NEXT, emptyMap(), false, true, false))
    }

    @Test
    fun `an ignored volume key is not left to the firmware`() {
        assertFalse(ProjectionKeyPolicy.firmwareKeepsKey(up, mapOf(up to InputAction.Ignore), false, false, false))
    }

    @Test
    fun `physical key routes drop an ignored key`() {
        for (src in listOf("projection", "mediasession", "carkey", "key-broadcast", "remote-keycode", "carkey-topway")) {
            assertTrue(src, ProjectionKeyPolicy.isIgnored(mapOf(87 to InputAction.Ignore), 87, src))
        }
    }

    @Test
    fun `routes with no wheel press keep an ignored key`() {
        for (src in listOf("media-key-intent", "transport-control", "remote-command", "remote-mute",
            "auto-resume", "assistant", "volume-to-phone", "unknown")) {
            assertFalse(src, ProjectionKeyPolicy.isIgnored(mapOf(87 to InputAction.Ignore), 87, src))
        }
    }

    @Test
    fun `a key with another binding or none is not ignored`() {
        assertFalse(ProjectionKeyPolicy.isIgnored(mapOf(88 to InputAction.Ignore), 87, "carkey"))
        assertFalse(ProjectionKeyPolicy.isIgnored(mapOf(87 to InputAction.AaKey(85)), 87, "carkey"))
        assertFalse(ProjectionKeyPolicy.isIgnored(mapOf(87 to InputAction.None), 87, "carkey"))
    }

    @Test
    fun `an unbound volume key while learning is not left to the firmware`() {
        assertFalse(ProjectionKeyPolicy.firmwareKeepsKey(up, emptyMap(), false, false, true))
        assertFalse(ProjectionKeyPolicy.firmwareKeepsKey(up, emptyMap(), false, true, true))
    }

    @Test
    fun `other keys and no key are not left to the firmware`() {
        assertFalse(ProjectionKeyPolicy.firmwareKeepsKey(KeyEvent.KEYCODE_MEDIA_NEXT, emptyMap(), false, false, false))
        assertFalse(ProjectionKeyPolicy.firmwareKeepsKey(null, emptyMap(), false, true, false))
    }
}
