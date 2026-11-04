package com.andrerinas.openheadunit.connection.usb

import com.andrerinas.openheadunit.connection.usb.UsbAttachActivityPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class UsbAttachActivityPolicyTest {

    /** A normal-identity phone on an unlocked unit with defaults. */
    private fun decide(
        accessory: Boolean = false,
        hasPermission: Boolean = true,
        locked: Boolean = false,
        held: Boolean = false,
        connectOnPlugIn: Boolean = true,
        autoStart: Boolean = false,
        mayAttempt: Boolean = true,
        switchLive: Boolean = false,
    ) = UsbAttachActivityPolicy.decide(accessory, hasPermission, locked, held, connectOnPlugIn, autoStart, mayAttempt, switchLive)

    @Test
    fun `defaults switch the phone`() {
        assertEquals(Action.SWITCH, decide())
    }

    @Test
    fun `connect off with a normal identity opens the home screen, never a switch or a hand-off`() {
        assertEquals(Action.OPEN_HOME, decide(connectOnPlugIn = false))
        assertEquals(Action.OPEN_HOME, decide(connectOnPlugIn = false, mayAttempt = false))
        assertEquals(Action.OPEN_HOME, decide(connectOnPlugIn = false, autoStart = true, switchLive = true))
    }

    @Test
    fun `connect off before unlock opens nothing`() {
        assertEquals(Action.FINISH, decide(connectOnPlugIn = false, locked = true))
    }

    @Test
    fun `an accessory identity with permission starts the service whatever connect says`() {
        assertEquals(Action.START_SERVICE, decide(accessory = true, connectOnPlugIn = true))
        assertEquals(Action.START_SERVICE, decide(accessory = true, connectOnPlugIn = false))
    }

    @Test
    fun `an accessory identity without permission goes to the service, and waits for unlock`() {
        assertEquals(Action.HAND_OFF, decide(accessory = true, hasPermission = false))
        assertEquals(Action.FINISH, decide(accessory = true, locked = true))
    }

    @Test
    fun `held goes to the service before anything else`() {
        assertEquals(Action.HAND_OFF, decide(held = true, connectOnPlugIn = false))
        assertEquals(Action.HAND_OFF, decide(held = true, accessory = true))
    }

    @Test
    fun `a device off the allow list goes to the service`() {
        assertEquals(Action.HAND_OFF, decide(mayAttempt = false))
    }

    @Test
    fun `locked with auto-start off finishes, and a live switch is left alone`() {
        assertEquals(Action.FINISH, decide(locked = true, autoStart = false))
        assertEquals(Action.SWITCH, decide(locked = true, autoStart = true))
        assertEquals(Action.FINISH, decide(switchLive = true))
    }
}
