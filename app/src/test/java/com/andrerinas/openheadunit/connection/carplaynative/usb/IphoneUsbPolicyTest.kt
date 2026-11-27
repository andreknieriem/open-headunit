package com.andrerinas.openheadunit.connection.carplaynative.usb

import com.andrerinas.openheadunit.connection.carplaynative.usb.IphoneUsbPolicy.Attach
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IphoneUsbPolicyTest {

    private fun attach(
        iphone: Boolean = true, enabled: Boolean = true, sdk: Int = 35, tun: Boolean = true, signer: Boolean = true,
        live: Boolean = false, exited: Boolean = false, aa: Boolean = false, permission: Boolean = true, configs: Int = 6,
    ) = IphoneUsbPolicy.attach(iphone, enabled, sdk, tun, signer, live, exited, aa, permission, configs)

    @Test
    fun `each verdict row in its fixed order`() {
        // Every earlier row is false while the later rows would also fire.
        assertEquals(Attach.NotAnIphone, attach(iphone = false, enabled = false, sdk = 17, tun = false, signer = false, live = true))
        assertEquals(Attach.Disabled, attach(enabled = false, sdk = 17, tun = false, signer = false, live = true))
        assertEquals(Attach.BelowApiFloor, attach(sdk = 20, tun = false, signer = false, live = true))
        assertEquals(Attach.NoTunnel, attach(tun = false, signer = false, live = true))
        assertEquals(Attach.NoSigner, attach(signer = false, live = true, exited = true))
        assertEquals(Attach.AlreadyLive, attach(live = true, exited = true, aa = true))
        assertEquals(Attach.UserExited, attach(exited = true, aa = true, permission = false))
        assertEquals(Attach.RefusedAaLive, attach(aa = true, permission = false, configs = 4))
        assertEquals(Attach.AskPermission, attach(permission = false, configs = 4))
        assertEquals(Attach.RequestMode, attach(configs = 4))
        assertEquals(Attach.Open, attach())
    }

    @Test
    fun `the setting off below API 21 is Disabled and on is BelowApiFloor`() {
        assertEquals(Attach.Disabled, attach(enabled = false, sdk = 17))
        assertEquals(Attach.BelowApiFloor, attach(enabled = true, sdk = 17))
        assertEquals(Attach.BelowApiFloor, attach(sdk = 20))
        assertEquals(Attach.Open, attach(sdk = 21))
    }

    @Test
    fun `the floor line names the unit API`() {
        assertEquals("CarPlayNative: iPhone attached; native CarPlay needs API 21, this unit is 19", IphoneUsbPolicy.belowFloorLine(19))
    }

    @Test
    fun `an iPad or iPod is no iPhone, by name and by product id`() {
        assertFalse(IphoneUsbPolicy.isIphone(0x05AC, 0x12A8, "iPad"))
        assertFalse(IphoneUsbPolicy.isIphone(0x05AC, 0x12A8, "Anna's IPOD touch"))
        assertFalse(IphoneUsbPolicy.isIphone(0x05AC, 0x12AB, null))
        assertFalse(IphoneUsbPolicy.isIphone(0x05AC, 0x12AA, null))
    }

    @Test
    fun `an Apple phone is an iPhone, and a name outranks the product id`() {
        assertTrue(IphoneUsbPolicy.isIphone(0x05AC, 0x12A8, "iPhone"))
        assertTrue(IphoneUsbPolicy.isIphone(0x05AC, 0x12A8, null))
        assertTrue(IphoneUsbPolicy.isIphone(0x05AC, 0x12AB, "iPhone"))
    }

    @Test
    fun `a device from another maker is no iPhone`() {
        assertFalse(IphoneUsbPolicy.isIphone(0x18D1, 0x12A8, "iPhone"))
        assertFalse(IphoneUsbPolicy.isIphone(0x05AB, 0x12A8, null))
    }

    @Test
    fun `the mode request is sent below six configurations only`() {
        assertTrue(IphoneUsbPolicy.needsModeRequest(4))
        assertTrue(IphoneUsbPolicy.needsModeRequest(5))
        assertFalse(IphoneUsbPolicy.needsModeRequest(6))
        assertFalse(IphoneUsbPolicy.needsModeRequest(7))
    }

    @Test
    fun `only one transferred byte is an accepted mode answer`() {
        listOf(-1, 0, 2, 3).forEach { assertTrue("count $it", IphoneUsbPolicy.modeAnswerIsRefusal(it)) }
        assertFalse(IphoneUsbPolicy.modeAnswerIsRefusal(1))
    }

    @Test
    fun `the refusal line carries the mode bytes in hex`() {
        assertEquals(
            "CarPlayNative: the iPhone refused wired CarPlay mode (mode 0100ff)",
            IphoneUsbPolicy.refusalLine(byteArrayOf(1, 0, -1)),
        )
    }
}
