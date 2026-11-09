package com.andrerinas.openheadunit.input

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockedInputPolicyTest {

    @Test
    fun `setting off never holds input, locked or not`() {
        assertFalse(LockedInputPolicy.shouldEngage(enabled = false, deviceLocked = true, screenOff = true))
        assertFalse(LockedInputPolicy.shouldEngage(enabled = false, deviceLocked = null, screenOff = false))
    }

    @Test
    fun `setting on holds input while the unit needs its PIN`() {
        assertTrue(LockedInputPolicy.shouldEngage(enabled = true, deviceLocked = true, screenOff = false))
    }

    @Test
    fun `setting on releases input once the unit is unlocked`() {
        assertFalse(LockedInputPolicy.shouldEngage(enabled = true, deviceLocked = false, screenOff = false))
    }

    @Test
    fun `an unknown lock state counts as locked`() {
        assertTrue(LockedInputPolicy.shouldEngage(enabled = true, deviceLocked = null, screenOff = false))
    }

    @Test
    fun `screen off holds input even before the unit reports locked`() {
        assertTrue(LockedInputPolicy.shouldEngage(enabled = true, deviceLocked = false, screenOff = true))
    }

    @Test
    fun `not engaged lets every event through`() {
        assertTrue(LockedInputPolicy.allows(engaged = false, keycode = null))
        assertTrue(LockedInputPolicy.allows(engaged = false, keycode = KeyEvent.KEYCODE_SEARCH))
    }

    @Test
    fun `engaged drops touch, scroll and every key but the map key`() {
        assertFalse(LockedInputPolicy.allows(engaged = true, keycode = null))
        listOf(
            KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT,
            65536, 65537
        ).forEach { assertFalse("keycode $it", LockedInputPolicy.allows(engaged = true, keycode = it)) }
        assertTrue(LockedInputPolicy.allows(engaged = true, keycode = LockedInputPolicy.KEYCODE_AA_NAVIGATION))
    }
}
