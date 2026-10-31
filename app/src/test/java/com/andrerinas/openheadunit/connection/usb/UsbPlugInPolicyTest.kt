package com.andrerinas.openheadunit.connection.usb

import com.andrerinas.openheadunit.connection.usb.UsbPlugInPolicy.Trigger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbPlugInPolicyTest {

    @Test
    fun `an absent key keeps today's plug-and-connect`() {
        assertTrue(UsbPlugInPolicy.DEFAULT_CONNECT_ON_PLUG_IN)
    }

    @Test
    fun `a plug-in switches only with connect and detection on`() {
        assertTrue(UsbPlugInPolicy.maySwitchNormal(Trigger.PLUG_IN, connectOnPlugIn = true, listen = true, uncleanEndAgoMs = null))
        assertFalse(UsbPlugInPolicy.maySwitchNormal(Trigger.PLUG_IN, connectOnPlugIn = false, listen = true, uncleanEndAgoMs = null))
        assertFalse(UsbPlugInPolicy.maySwitchNormal(Trigger.PLUG_IN, connectOnPlugIn = true, listen = false, uncleanEndAgoMs = null))
    }

    @Test
    fun `a re-attach inside the glitch window after an unclean end switches`() {
        assertTrue(UsbPlugInPolicy.maySwitchNormal(Trigger.PLUG_IN, connectOnPlugIn = false, listen = true,
            uncleanEndAgoMs = UsbPlugInPolicy.GLITCH_WINDOW_MS - 1))
        assertTrue(UsbPlugInPolicy.maySwitchNormal(Trigger.PLUG_IN, connectOnPlugIn = false, listen = false, uncleanEndAgoMs = 0L))
    }

    @Test
    fun `the glitch window is closed at exactly its length`() {
        assertFalse(UsbPlugInPolicy.maySwitchNormal(Trigger.PLUG_IN, connectOnPlugIn = false, listen = true,
            uncleanEndAgoMs = UsbPlugInPolicy.GLITCH_WINDOW_MS))
    }

    @Test
    fun `every other trigger switches whatever the settings`() {
        for (trigger in Trigger.values().filter { it != Trigger.PLUG_IN }) {
            for (connect in listOf(true, false)) for (listen in listOf(true, false)) {
                assertTrue("$trigger connect=$connect listen=$listen",
                    UsbPlugInPolicy.maySwitchNormal(trigger, connect, listen, uncleanEndAgoMs = null))
            }
        }
    }

    @Test
    fun `connects on plug-in needs both settings`() {
        assertTrue(UsbPlugInPolicy.connectsOnPlugIn(connectOnPlugIn = true, listen = true))
        assertFalse(UsbPlugInPolicy.connectsOnPlugIn(connectOnPlugIn = false, listen = true))
        assertFalse(UsbPlugInPolicy.connectsOnPlugIn(connectOnPlugIn = true, listen = false))
    }
}
