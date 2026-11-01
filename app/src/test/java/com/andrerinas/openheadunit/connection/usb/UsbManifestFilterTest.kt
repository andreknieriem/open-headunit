package com.andrerinas.openheadunit.connection.usb

import com.andrerinas.openheadunit.connection.usb.UsbDeviceIdentityPolicy.Device
import com.andrerinas.openheadunit.connection.usb.UsbDeviceIdentityPolicy.Interface
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbManifestFilterTest {

    private fun matches(device: Device) = UsbManifestFilter.matches(device)

    @Test
    fun `the Kotlin copy equals the shipped XML`() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(java.io.File("src/main/res/xml/usb_device_filter.xml"))
        val nodes = doc.getElementsByTagName("usb-device")
        val shipped = HashSet<UsbManifestFilter.Entry>()
        for (i in 0 until nodes.length) {
            val element = nodes.item(i)
            fun attr(name: String): Int? = element.attributes.getNamedItem(name)?.nodeValue?.toInt()
            shipped.add(
                UsbManifestFilter.Entry(
                    vendorId = attr("vendor-id"), productId = attr("product-id"),
                    deviceClass = attr("class"), subclass = attr("subclass"), protocol = attr("protocol"),
                )
            )
        }
        assertEquals(nodes.length, shipped.size)
        assertEquals(shipped, UsbManifestFilter.ENTRIES.toSet())
    }

    @Test
    fun `every identity the policy accepts is in the filter`() {
        val fixtures = listOf(
            UsbDeviceFixtures.phoneMtp, UsbDeviceFixtures.phoneAdb, UsbDeviceFixtures.phoneAccessory,
            UsbDeviceFixtures.phoneAccessoryPlain, UsbDeviceFixtures.samsungFileTransfer,
        )
        for (device in fixtures) {
            assertTrue(UsbDeviceIdentityPolicy.evaluate(device).accepted)
            assertTrue("%04X:%04X".format(device.vendorId, device.productId), matches(device))
        }
        // 2D04 and 2D05 are accepted by the policy and absent from the filter; the app never
        // sends the AOA audio request, so a phone never takes them.
        for (pid in listOf(0x2D04, 0x2D05)) {
            val audio = Device(0x18D1, pid, 0x00, emptyList())
            assertTrue(UsbDeviceIdentityPolicy.evaluate(audio).accepted)
            assertFalse(matches(audio))
        }
    }

    @Test
    fun `the normal and accessory identities of the phone match`() {
        assertTrue(matches(UsbDeviceFixtures.phoneMtp))
        assertTrue(matches(Device(0x18D1, 0x2D01, 0x00, emptyList())))
    }

    @Test
    fun `a vendor-id entry ignores the class triples`() {
        assertTrue(matches(Device(0x18D1, 0x2D00, 0xFF, emptyList())))
    }

    @Test
    fun `the device triple alone can match`() {
        val device = Device(
            0x1234, 0x5678, 0xEF, listOf(Interface(0x03, 0x00, 0x00)),
            deviceSubclass = 0x04, deviceProtocol = 0x01,
        )
        assertTrue(matches(device))
    }

    @Test
    fun `the stock Carlinkit CCPA matches nothing`() {
        assertFalse(matches(UsbDeviceFixtures.carlinkitCcpa))
    }

    @Test
    fun `a flash drive and a hub match nothing`() {
        val flash = Device(0x090C, 0x1000, 0x00, listOf(Interface(0x08, 0x06, 0x50)))
        val hub = Device(0x05E3, 0x0610, 0x09, listOf(Interface(0x09, 0x00, 0x01)))
        assertFalse(matches(flash))
        assertFalse(matches(hub))
    }

    @Test
    fun `the ethernet adapter matches, and the identity policy still rejects it`() {
        assertTrue(matches(UsbDeviceFixtures.asixComposite))
        assertFalse(UsbDeviceIdentityPolicy.evaluate(UsbDeviceFixtures.asixComposite).accepted)
    }
}
