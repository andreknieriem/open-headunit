package com.andrerinas.openheadunit.connection.usb

import com.andrerinas.openheadunit.connection.usb.UsbDeviceIdentityPolicy.Device
import com.andrerinas.openheadunit.connection.usb.UsbDeviceIdentityPolicy.Interface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The descriptors are transcribed from `UsbHostManager` dumps taken on hardware, in
 * `evidence/usb-device-diagnostics-round1/` on the transfer branch. The ASIX and the phone in MTP
 * mode present the same FF/FF/00 triple with the same endpoint shape, so any rule that separates
 * them by the triple or by endpoint count alone will pass one of these tests and fail the other.
 *
 * The adapter has two enumerations and both are fixtures here, because covering only the composite
 * one shipped a fix that did nothing on hardware: it alternates between them plug to plug, and its
 * vendor-only form carries no CDC interface for the sibling rule to find.
 */
class UsbDeviceIdentityPolicyTest {

    private fun evaluate(device: Device) = UsbDeviceIdentityPolicy.evaluate(device)

    private fun vendorClass(name: String?) = UsbDeviceFixtures.vendorClass(name)
    private val asixComposite = UsbDeviceFixtures.asixComposite
    private val asixVendorOnly = UsbDeviceFixtures.asixVendorOnly
    private val samsungFileTransfer = UsbDeviceFixtures.samsungFileTransfer
    private val phoneMtp = UsbDeviceFixtures.phoneMtp

    @Test
    fun `a USB ethernet adapter is not a phone, however much its control interface looks like one`() {
        val verdict = evaluate(asixComposite)
        assertFalse(verdict.accepted)
        assertEquals("rejected: CDC network adapter", verdict.toString())
    }

    @Test
    fun `a phone in file transfer mode is accepted on the same triple that rejects the adapter`() {
        val verdict = evaluate(phoneMtp)
        assertTrue(verdict.accepted)
        assertEquals("accepted: MTP", verdict.toString())
    }

    @Test
    fun `the endpoint shape does not separate them, so it cannot be the discriminator`() {
        val adapterInterface = asixComposite.interfaces.first()
        val phoneInterface = phoneMtp.interfaces.first()
        assertEquals(adapterInterface.hasBulkIn, phoneInterface.hasBulkIn)
        assertEquals(adapterInterface.hasBulkOut, phoneInterface.hasBulkOut)
        assertEquals(adapterInterface.ifaceClass, phoneInterface.ifaceClass)
        assertEquals(adapterInterface.subclass, phoneInterface.subclass)
        assertEquals(adapterInterface.protocol, phoneInterface.protocol)
    }

    @Test
    fun `a real accessory names its own interface and is accepted whatever else is on the device`() {
        val accessory = Device(
            vendorId = 0x1234, productId = 0x5678, deviceClass = 0x00,
            interfaces = listOf(vendorClass("Android Accessory Interface"), Interface(0x0A, 0x00, 0x00)),
        )
        assertEquals("accepted: AOAP", evaluate(accessory).toString())
    }

    @Test
    fun `the adapter's other form is rejected on its device class, having nothing else to go on`() {
        val verdict = evaluate(asixVendorOnly)
        assertFalse(verdict.accepted)
        assertEquals("rejected: vendor-class device, not a phone", verdict.toString())
    }

    @Test
    fun `that form and a phone in file transfer differ only in the device class byte`() {
        assertEquals(asixVendorOnly.interfaces.size, phoneMtp.interfaces.size)
        val adapter = asixVendorOnly.interfaces.first()
        val phone = phoneMtp.interfaces.first()
        assertEquals(adapter.ifaceClass, phone.ifaceClass)
        assertEquals(adapter.subclass, phone.subclass)
        assertEquals(adapter.protocol, phone.protocol)
        assertEquals(adapter.hasBulkIn, phone.hasBulkIn)
        assertEquals(adapter.hasBulkOut, phone.hasBulkOut)
        assertEquals(0xFF, asixVendorOnly.deviceClass)
        assertEquals(0x00, phoneMtp.deviceClass)
    }

    @Test
    fun `an unnamed vendor interface with no CDC siblings is still accepted`() {
        // Pre-API-21 reads no interface name, and an open firmware dongle may not set one. Nothing
        // says this is a phone, but nothing says it is not, and the probe is the real test.
        val device = Device(0x18D1, 0x4EE1, 0x00, listOf(vendorClass(null)))
        assertEquals("accepted: AOAP (vendor interface named nothing)", evaluate(device).toString())
    }

    @Test
    fun `the verdict carries the interface name, because that is what a wrong one is read from`() {
        val device = Device(0x2717, 0xFF40, 0x00, listOf(vendorClass("Some_Vendor_Thing")))
        assertEquals(
            "accepted: AOAP (vendor interface named 'Some_Vendor_Thing')",
            evaluate(device).toString(),
        )
    }

    @Test
    fun `a billboard device names its class rather than falling through`() {
        val billboard = Device(0x1D5C, 0x7102, 0x11, listOf(Interface(0x11, 0x00, 0x00)))
        assertEquals("rejected: device class 0x11 is not a phone", evaluate(billboard).toString())
    }

    @Test
    fun `a vendor-class device is still accepted when it declares an unambiguous interface`() {
        // The veto sits in the accessory branch, so a device that says ADB or PTP outright never
        // reaches it. Nothing captured looks like this; the ordering is what the test pins.
        val device = Device(
            0x1234, 0x5678, 0xFF,
            listOf(Interface(0xFF, 0x42, 0x01, "ADB Interface", hasBulkIn = true, hasBulkOut = true)),
        )
        assertEquals("accepted: ADB", evaluate(device).toString())
    }

    @Test
    fun `a vendor interface with no bulk pair is not an accessory interface`() {
        val device = Device(
            0x1234, 0x5678, 0x00,
            listOf(Interface(0xFF, 0xFF, 0x00, hasBulkIn = true, hasBulkOut = false)),
        )
        assertEquals("rejected: no Android interface", evaluate(device).toString())
    }

    @Test
    fun `a Samsung in file transfer is accepted, CDC serial interfaces and all`() {
        assertEquals("accepted: PTP", evaluate(samsungFileTransfer).toString())
    }

    @Test
    fun `a dongle that is only a serial gadget is named as one`() {
        // liaoyuan A2A, 0525:A4A7, the Linux serial gadget half of a wireless adapter that flips
        // identities while it hunts for a phone. Rejected either way; the reason is the diagnosis.
        val device = Device(
            vendorId = 0x0525, productId = 0xA4A7, deviceClass = 0x00,
            interfaces = listOf(
                Interface(0x02, 0x02, 0x01),
                Interface(0x0A, 0x00, 0x00, hasBulkIn = true, hasBulkOut = true),
            ),
        )
        assertEquals("rejected: CDC-ACM serial device, not a phone", evaluate(device).toString())
    }

    @Test
    fun `a CDC serial pair is not a network, even on an otherwise ambiguous device`() {
        // The same phone with a vendor MTP interface instead of a PTP one, and no interface name to
        // settle it. Only the ACM control subclass keeps this apart from the ethernet adapter.
        val device = Device(
            0x04E8, 0x6860, 0x00,
            listOf(
                vendorClass(null),
                Interface(0x02, 0x02, 0x01),
                Interface(0x0A, 0x00, 0x00, hasBulkIn = true, hasBulkOut = true),
            ),
        )
        assertTrue(evaluate(device).accepted)
    }

    @Test
    fun `a tethering phone survives the network adapter rule because RNDIS is decided first`() {
        // It carries the CDC data interface the rule keys on, so order is the only thing saving it.
        val tethering = Device(
            vendorId = 0x22B8, productId = 0x2E24, deviceClass = 0x00,
            interfaces = listOf(
                Interface(0xE0, 0x01, 0x03),
                Interface(0x0A, 0x00, 0x00, hasBulkIn = true, hasBulkOut = true),
            ),
        )
        assertEquals("accepted: RNDIS", evaluate(tethering).toString())
    }

    @Test
    fun `every USB mode of the phone that was swept on hardware is still accepted`() {
        val adb = Interface(0xFF, 0x42, 0x01, "ADB Interface", hasBulkIn = true, hasBulkOut = true)
        val ptp = Interface(0x06, 0x01, 0x01, null, hasBulkIn = true, hasBulkOut = true)
        val modes = mapOf(
            "adb" to Device(0x22B8, 0x2E81, 0x00, listOf(adb)),
            "mtp" to phoneMtp,
            "ptp" to Device(0x22B8, 0x2E83, 0x00, listOf(ptp)),
            "ptp_adb" to Device(0x22B8, 0x2E84, 0x00, listOf(ptp, adb)),
            "midi_adb" to Device(
                0x18D1, 0x4EE9, 0x00,
                listOf(
                    Interface(0x01, 0x01, 0x00, "MIDI function"),
                    Interface(0x01, 0x03, 0x00, null, hasBulkIn = true, hasBulkOut = true),
                    adb,
                ),
            ),
        )
        for ((mode, device) in modes) {
            assertTrue("$mode should be accepted", evaluate(device).accepted)
        }
    }

    @Test
    fun `the two devices round 1 rejected are still rejected, for the same stated reason`() {
        val flashDisk = Device(
            0x090C, 0x1000, 0x00,
            listOf(Interface(0x08, 0x06, 0x50, null, hasBulkIn = true, hasBulkOut = true)),
        )
        val bluetoothAudioDongle = Device(
            0x0A12, 0x4007, 0x00,
            listOf(
                Interface(0x03, 0x00, 0x00), Interface(0x03, 0x00, 0x00),
                Interface(0x01, 0x01, 0x00), Interface(0x01, 0x02, 0x00),
                Interface(0x01, 0x02, 0x00), Interface(0x01, 0x02, 0x00),
            ),
        )
        assertEquals("rejected: no Android interface", evaluate(flashDisk).toString())
        assertEquals("rejected: no Android interface", evaluate(bluetoothAudioDongle).toString())
    }

    @Test
    fun `a device already in accessory mode is accepted before any interface is read`() {
        // The four AOSP accepts: accessory, +ADB, +audio, +audio+ADB.
        for (pid in listOf(0x2D00, 0x2D01, 0x2D04, 0x2D05)) {
            val device = Device(0x18D1, pid, 0x00, emptyList())
            assertEquals(
                "accepted: already in accessory mode",
                evaluate(device).toString(),
            )
        }
    }

    @Test
    fun `the audio-only accessory modes are not accepted, having no accessory interface`() {
        for (pid in listOf(0x2D02, 0x2D03)) {
            val device = Device(0x18D1, pid, 0x00, emptyList())
            assertFalse(evaluate(device).accepted)
        }
    }

    @Test
    fun `Apple is refused even when it presents a matching interface`() {
        val device = Device(0x05AC, 0x12A8, 0x00, listOf(vendorClass("Android Accessory Interface")))
        assertEquals("rejected: Apple VID", evaluate(device).toString())
    }

    @Test
    fun `a device that has declared itself a hub or a printer is dropped on its device class`() {
        assertEquals("rejected: device class 0x09 is not a phone", evaluate(Device(0x1234, 0x1, 0x09, emptyList())).toString())
        assertEquals("rejected: device class 0x07 is not a phone", evaluate(Device(0x1234, 0x1, 0x07, emptyList())).toString())
    }

    @Test
    fun `the stock Carlinkit CCPA is not a phone on either of its product ids`() {
        for (pid in listOf(0x1520, 0x1521)) {
            val box = UsbDeviceFixtures.carlinkitCcpa.copy(productId = pid)
            assertEquals("rejected: no Android interface", evaluate(box).toString())
        }
    }

    @Test
    fun `an empty bus entry is rejected rather than throwing`() {
        assertFalse(evaluate(Device(0x1234, 0x5678, 0x00, emptyList())).accepted)
    }
}
