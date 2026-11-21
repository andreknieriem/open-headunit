package com.andrerinas.openheadunit.secondscreen

import com.andrerinas.openheadunit.connection.usb.UsbDeviceIdentityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class UsbDisplayAdapterPolicyTest {

    @Test
    fun `the three MacroSilicon ids are recognised and nothing else is`() {
        for ((vid, pid) in listOf(0x534D to 0x6021, 0x534D to 0x0821, 0x345F to 0x9132)) {
            assertEquals(UsbDisplayAdapterPolicy.Kind.MS912X, UsbDisplayAdapterPolicy.kindOf(vid, pid, emptyList()))
        }
        assertNull(UsbDisplayAdapterPolicy.kindOf(0x18D1, 0x4EE7, emptyList()))
    }

    @Test
    fun `an Open Headunit display is recognised by its interface, whatever its ids`() {
        val display = UsbDisplayAdapterPolicy.InterfaceId(0xFF, 0x4F, 0x44)
        assertEquals(UsbDisplayAdapterPolicy.Kind.USB_DISPLAY, UsbDisplayAdapterPolicy.kindOf(0x1D6B, 0x0104, listOf(display)))
        val gadget = UsbDeviceIdentityPolicy.Device(
            0x1D6B, 0x0104, 0x00,
            listOf(UsbDeviceIdentityPolicy.Interface(0xFF, 0x4F, 0x44, hasBulkIn = true, hasBulkOut = true)),
        )
        assertFalse(UsbDeviceIdentityPolicy.evaluate(gadget).accepted)
    }

    @Test
    fun `an adapter is never taken for a phone, even with the accessory triple`() {
        val vendorBulk = UsbDeviceIdentityPolicy.Interface(0xFF, 0xFF, 0x00, hasBulkIn = true, hasBulkOut = true)
        val device = UsbDeviceIdentityPolicy.Device(0x534D, 0x6021, 0x00, listOf(vendorBulk))
        assertFalse(UsbDeviceIdentityPolicy.evaluate(device).accepted)
    }
}
