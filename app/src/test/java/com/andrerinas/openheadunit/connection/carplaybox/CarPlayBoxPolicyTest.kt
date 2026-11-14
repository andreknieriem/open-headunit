package com.andrerinas.openheadunit.connection.carplaybox

import com.andrerinas.openheadunit.connection.carplaybox.CarPlayBoxPolicy.Attach
import com.andrerinas.openheadunit.connection.usb.UsbDeviceFixtures
import com.andrerinas.openheadunit.connection.usb.UsbDeviceIdentityPolicy
import com.andrerinas.openheadunit.connection.usb.UsbDeviceIdentityPolicy.Device
import com.andrerinas.openheadunit.connection.usb.UsbDeviceIdentityPolicy.Interface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayBoxPolicyTest {

    @Test
    fun `the accepted box shapes`() {
        assertTrue(CarPlayBoxPolicy.isBox(UsbDeviceFixtures.carlinkitCcpm))
        assertTrue(CarPlayBoxPolicy.isBox(UsbDeviceFixtures.boxStock1520))
        assertTrue(CarPlayBoxPolicy.isBox(UsbDeviceFixtures.boxStock1521))
    }

    @Test
    fun `shapes that are not a box`() {
        assertFalse(CarPlayBoxPolicy.isBox(UsbDeviceFixtures.box1521Bare))
        assertFalse(CarPlayBoxPolicy.isBox(UsbDeviceFixtures.carlinkitCcpa))
        assertFalse(CarPlayBoxPolicy.isBox(UsbDeviceFixtures.phoneAccessory))
        assertFalse(CarPlayBoxPolicy.isBox(UsbDeviceFixtures.phoneMtp))
        val pixel = Device(0x18D1, 0x4EE1, 0, listOf(UsbDeviceFixtures.vendorClass(null)))
        assertFalse(CarPlayBoxPolicy.isBox(pixel))
        val apple = Device(0x05AC, 0x12A8, 0, listOf(Interface(0xFF, 0xFD, 0x01, hasBulkIn = true, hasBulkOut = true)))
        assertFalse(CarPlayBoxPolicy.isBox(apple))
        val mtpExtra = Device(0x1314, 0x1520, 0, listOf(Interface(0xFF, 0xFF, 0x00, "MTP", true, true, extraEndpoints = 1)))
        assertFalse(CarPlayBoxPolicy.isBox(mtpExtra))
        val two = UsbDeviceFixtures.boxStock1520.copy(
            interfaces = UsbDeviceFixtures.boxStock1520.interfaces + UsbDeviceFixtures.boxStock1520.interfaces,
        )
        assertFalse(CarPlayBoxPolicy.isBox(two))
    }

    private fun attach(
        isBox: Boolean = true, enabled: Boolean = true, live: Boolean = false,
        exited: Boolean = false, aa: Boolean = false, permission: Boolean = true,
    ) = CarPlayBoxPolicy.attach(isBox, enabled, live, exited, aa, permission)

    @Test
    fun `attach verdicts in order`() {
        assertEquals(Attach.NotABox, attach(isBox = false, enabled = false))
        assertEquals(Attach.Disabled, attach(enabled = false, live = true))
        assertEquals(Attach.AlreadyLive, attach(live = true, exited = true, aa = true))
        assertEquals(Attach.UserExited, attach(exited = true, aa = true))
        assertEquals(Attach.RefusedAaLive, attach(aa = true, permission = false))
        assertEquals(Attach.AskPermission, attach(permission = false))
        assertEquals(Attach.Open, attach())
    }

    @Test
    fun `a live session wins over permission and the exit latch wins over permission`() {
        assertEquals(Attach.AlreadyLive, attach(live = true, permission = true))
        assertEquals(Attach.UserExited, attach(exited = true, permission = true))
    }

    @Test
    fun `the Android Auto identity verdicts are unchanged`() {
        val ccpm = UsbDeviceIdentityPolicy.evaluate(UsbDeviceFixtures.carlinkitCcpm)
        assertFalse(ccpm.accepted)
        assertEquals("Carlinkit box on its own protocol (bare bulk pair), not a phone", ccpm.reason)
        for (box in listOf(UsbDeviceFixtures.boxStock1520, UsbDeviceFixtures.boxStock1521)) {
            val v = UsbDeviceIdentityPolicy.evaluate(box)
            assertFalse(v.accepted)
            assertEquals("no Android interface", v.reason)
        }
        assertTrue(UsbDeviceIdentityPolicy.evaluate(UsbDeviceFixtures.box1521Bare).accepted)
    }

    @Test
    fun `the box label is bare lowercase hex`() {
        assertEquals("1314:1520", CarPlayBoxPolicy.label(0x1314, 0x1520))
        assertEquals("1314:1521", CarPlayBoxPolicy.label(0x1314, 0x1521))
        assertEquals("0abc:00ef", CarPlayBoxPolicy.label(0x0ABC, 0x00EF))
    }
}
