package com.andrerinas.openheadunit.connection.usb

import com.andrerinas.openheadunit.connection.usb.UsbBluetoothControllerPolicy.Family
import com.andrerinas.openheadunit.connection.usb.UsbBluetoothControllerPolicy.RadioFacts
import com.andrerinas.openheadunit.connection.usb.UsbDeviceIdentityPolicy.Device
import com.andrerinas.openheadunit.connection.usb.UsbDeviceIdentityPolicy.Interface
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.ExternalBtTransportPolicy.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbBluetoothControllerPolicyTest {

    private val hci = Interface(0xE0, 0x01, 0x01)

    private fun controller(vid: Int, pid: Int = 0x0001) =
        Device(vid, pid, 0x00, listOf(hci, Interface(0xFF, 0xFF, 0xFF)))

    @Test
    fun `an HCI interface classifies and a device-level HCI triple does too`() {
        assertEquals(Family.BROADCOM, UsbBluetoothControllerPolicy.classify(controller(0x0A5C))!!.family)
        val deviceLevel = Device(0x0A5C, 0x21E8, 0xE0, emptyList(), deviceSubclass = 0x01, deviceProtocol = 0x01)
        assertEquals(Family.BROADCOM, UsbBluetoothControllerPolicy.classify(deviceLevel)!!.family)
    }

    @Test
    fun `the UB500 shape reads as Realtek that needs firmware`() {
        val ub500 = Device(0x2357, 0x0604, 0xE0, List(7) { hci })
        val c = UsbBluetoothControllerPolicy.classify(ub500)!!
        assertEquals(Family.REALTEK, c.family)
        assertEquals(true, c.needsFirmware)
    }

    @Test
    fun `RNDIS and ordinary devices do not classify`() {
        assertNull(UsbBluetoothControllerPolicy.classify(Device(0x04E8, 0x6863, 0x00, listOf(Interface(0xE0, 0x01, 0x03)))))
        assertNull(UsbBluetoothControllerPolicy.classify(UsbDeviceFixtures.phoneMtp))
        assertNull(UsbBluetoothControllerPolicy.classify(UsbDeviceFixtures.phoneAdb))
        assertNull(UsbBluetoothControllerPolicy.classify(UsbDeviceFixtures.asixComposite))
        assertNull(UsbBluetoothControllerPolicy.classify(UsbDeviceFixtures.asixVendorOnly))
    }

    @Test
    fun `an audio-only Bluetooth dongle does not classify and stays rejected`() {
        val dongle = Device(
            0x0A12, 0x4007, 0x00,
            listOf(
                Interface(0x03, 0x00, 0x00), Interface(0x03, 0x00, 0x00),
                Interface(0x01, 0x01, 0x00), Interface(0x01, 0x02, 0x00),
                Interface(0x01, 0x02, 0x00), Interface(0x01, 0x02, 0x00),
            ),
        )
        assertNull(UsbBluetoothControllerPolicy.classify(dongle))
        assertEquals("rejected: no Android interface", UsbDeviceIdentityPolicy.evaluate(dongle).toString())
    }

    @Test
    fun `each known vendor maps to its family`() {
        val expected = mapOf(
            0x0A5C to Family.BROADCOM, 0x0BDA to Family.REALTEK, 0x0A12 to Family.CSR,
            0x0CF3 to Family.QUALCOMM, 0x8087 to Family.INTEL, 0x0E8D to Family.MEDIATEK,
        )
        for ((vid, family) in expected) {
            val c = UsbBluetoothControllerPolicy.classify(controller(vid))!!
            assertEquals(family, c.family)
            assertEquals(vid, c.vendorId)
            assertTrue(c.needsFirmware != null)
        }
    }

    @Test
    fun `an unknown vendor is UNKNOWN with no firmware guess`() {
        val c = UsbBluetoothControllerPolicy.classify(controller(0x1234))!!
        assertEquals(Family.UNKNOWN, c.family)
        assertNull(c.needsFirmware)
    }

    @Test
    fun `the attach line is exact`() {
        val c = UsbBluetoothControllerPolicy.classify(Device(0x2357, 0x0604, 0xE0, List(7) { hci }))!!
        assertEquals(
            "USB: Bluetooth controller 2357:0604 REALTEK seen, not usable as the Native AA radio",
            UsbBluetoothControllerPolicy.attachLine(c),
        )
    }

    @Test
    fun `a path is a first sighting once, again after a detach, and again after it leaves the bus`() {
        val s = UsbBluetoothControllerPolicy.Sightings()
        assertTrue(s.firstSighting("/dev/bus/usb/001/005", listOf("/dev/bus/usb/001/005")))
        assertFalse(s.firstSighting("/dev/bus/usb/001/005", listOf("/dev/bus/usb/001/005")))
        s.detached("/dev/bus/usb/001/005")
        assertTrue(s.firstSighting("/dev/bus/usb/001/005", listOf("/dev/bus/usb/001/005")))
        assertFalse(s.firstSighting("/dev/bus/usb/001/005", listOf("/dev/bus/usb/001/005")))
        assertTrue(s.firstSighting("/dev/bus/usb/001/006", listOf("/dev/bus/usb/001/006")))
        assertTrue(s.firstSighting("/dev/bus/usb/001/005", listOf("/dev/bus/usb/001/005", "/dev/bus/usb/001/006")))
    }

    private fun facts(
        mode: Int = 3, refusal: Boolean? = false, route: Route = Route.NORMAL,
        relay: Boolean = false, adapter: Boolean = true,
    ) = RadioFacts(mode, refusal, route, relay, adapter)

    @Test
    fun `a measured refusal or no adapter on the Android route wants the notice`() {
        assertEquals(true, UsbBluetoothControllerPolicy.noticeWanted(facts(refusal = true, route = Route.BLOCKED)))
        assertEquals(true, UsbBluetoothControllerPolicy.noticeWanted(facts(adapter = false)))
    }

    @Test
    fun `a unit with a working route does not want it`() {
        for (route in listOf(Route.NORMAL, Route.ZBT, Route.HCT, Route.ZJ_SOCKET, Route.BLINK)) {
            assertEquals(route.name, false, UsbBluetoothControllerPolicy.noticeWanted(facts(route = route)))
        }
    }

    @Test
    fun `a module route with no Android adapter does not want it`() {
        for (route in listOf(Route.ZBT, Route.HCT, Route.ZJ_SOCKET, Route.BLINK)) {
            assertEquals(route.name, false, UsbBluetoothControllerPolicy.noticeWanted(facts(route = route, adapter = false)))
        }
    }

    @Test
    fun `a pending daemon measurement keeps the record as it is`() {
        for (adapter in listOf(true, false)) {
            assertNull(UsbBluetoothControllerPolicy.noticeWanted(
                facts(refusal = null, route = Route.BLOCKED, adapter = adapter)))
        }
    }

    @Test
    fun `a saved relay means a route exists`() {
        assertEquals(false, UsbBluetoothControllerPolicy.noticeWanted(facts(refusal = true, relay = true)))
        assertEquals(false, UsbBluetoothControllerPolicy.noticeWanted(facts(adapter = false, relay = true)))
    }

    @Test
    fun `outside mode 3 the notice is never wanted`() {
        for (mode in 0..2) {
            assertEquals(false, UsbBluetoothControllerPolicy.noticeWanted(facts(mode = mode, refusal = true)))
        }
    }

    @Test
    fun `with no controller the record never stands, and with one the answer passes through`() {
        for (wanted in listOf(true, false, null)) {
            assertEquals(false, UsbBluetoothControllerPolicy.noticeStanding(false, wanted))
            assertEquals(wanted, UsbBluetoothControllerPolicy.noticeStanding(true, wanted))
        }
    }
}
