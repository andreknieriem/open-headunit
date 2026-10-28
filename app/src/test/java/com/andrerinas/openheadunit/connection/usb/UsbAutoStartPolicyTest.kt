package com.andrerinas.openheadunit.connection.usb

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbAutoStartPolicyTest {

    @Test
    fun `open the app when plugged in acts only while a plug-in connects and is detected`() {
        for (autoStart in listOf(true, false)) for (connect in listOf(true, false)) for (listen in listOf(true, false)) {
            assertEquals("autoStart=$autoStart connect=$connect listen=$listen", autoStart && connect && listen,
                UsbAutoStartPolicy.effective(autoStart, connect, listen))
        }
    }

    @Test
    fun `a stored auto-start with connect off is not effective`() {
        assertEquals(false, UsbAutoStartPolicy.effective(autoStartOnUsb = true, connectOnPlugIn = false, listenForUsbDevices = true))
    }
}
