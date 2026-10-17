package com.andrerinas.openheadunit.input

import com.andrerinas.openheadunit.input.MediaKeyRoutingPolicy.Declined
import com.andrerinas.openheadunit.input.MediaKeyRoutingPolicy.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaKeyRoutingPolicyTest {

    private fun forward(
        mode: Mode,
        isMediaKey: Boolean = true,
        bt: Boolean? = null,
        loopback: Boolean = false
    ) = MediaKeyRoutingPolicy.shouldForward(mode, isMediaKey, bt, loopback)

    @Test
    fun `everything that is not a media button is always forwarded`() {
        for (mode in Mode.values()) {
            for (bt in listOf(true, false, null)) {
                assertTrue("mode=$mode bt=$bt", forward(mode, isMediaKey = false, bt = bt))
            }
        }
    }

    @Test
    fun `always forwards whatever bluetooth is doing`() {
        for (bt in listOf(true, false, null)) {
            assertTrue("bt=$bt", forward(Mode.ALWAYS, bt = bt))
        }
    }

    @Test
    fun `never suppresses whatever bluetooth is doing`() {
        for (bt in listOf(true, false, null)) {
            assertFalse("bt=$bt", forward(Mode.NEVER, bt = bt))
        }
    }

    @Test
    fun `auto suppresses only while a bluetooth media link is up`() {
        assertFalse(forward(Mode.AUTO, bt = true))
        assertTrue(forward(Mode.AUTO, bt = false))
    }

    @Test
    fun `auto forwards when the bluetooth state cannot be read`() {
        // The opposite resolution to PlaybackFocusPolicy's unknown case, on purpose: a doubled skip
        // is an annoyance, media buttons that silently do nothing read as a broken app.
        assertTrue(forward(Mode.AUTO, bt = null))
    }

    @Test
    fun `a self mode session keeps its media buttons whatever the mode says`() {
        // The player is on this device, and a forwarded key is routed by Android Auto's input
        // focus, so it works only while the player is the app on screen.
        for (mode in Mode.values()) {
            for (bt in listOf(true, false, null)) {
                assertFalse("mode=$mode bt=$bt", forward(mode, bt = bt, loopback = true))
            }
        }
    }

    @Test
    fun `a self mode session still forwards everything that is not a media button`() {
        for (mode in Mode.values()) {
            assertTrue("mode=$mode", forward(mode, isMediaKey = false, loopback = true))
        }
    }

    @Test
    fun `an unset or damaged preference means always`() {
        assertEquals(Mode.ALWAYS, Mode.fromInt(0))
        assertEquals(Mode.ALWAYS, Mode.fromInt(-1))
        assertEquals(Mode.ALWAYS, Mode.fromInt(99))
        assertEquals(Mode.AUTO, Mode.fromInt(1))
        assertEquals(Mode.NEVER, Mode.fromInt(2))
    }

    @Test
    fun `self mode hands a declined key to the system, remapped or not`() {
        for (remapped in listOf(true, false)) {
            assertEquals(Declined.DISPATCH, MediaKeyRoutingPolicy.onDeclined(true, remapped))
        }
    }

    @Test
    fun `outside self mode a declined key falls through only under its own code`() {
        assertEquals(Declined.FALL_THROUGH, MediaKeyRoutingPolicy.onDeclined(false, false))
        assertEquals(Declined.DROP, MediaKeyRoutingPolicy.onDeclined(false, true))
    }
}
