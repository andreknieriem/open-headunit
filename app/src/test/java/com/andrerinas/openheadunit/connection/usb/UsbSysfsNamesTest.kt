package com.andrerinas.openheadunit.connection.usb

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbSysfsNamesTest {

    private fun sysfs(vararg devices: Map<String, String>): File {
        val root = Files.createTempDirectory("usb-sysfs").toFile()
        devices.forEachIndexed { i, files ->
            val dir = File(root, "1-$i").apply { mkdirs() }
            files.forEach { (name, text) -> File(dir, name).writeText(text + "\n") }
        }
        File(root, "1-0:1.0").mkdirs() // an interface entry carries no bus or device number
        return root
    }

    @Test
    fun `the device node's bus and device numbers pick its sysfs entry`() {
        val root = sysfs(
            mapOf("busnum" to "1", "devnum" to "3", "manufacturer" to "Other", "product" to "Hub"),
            mapOf("busnum" to "1", "devnum" to "4", "manufacturer" to "motorola", "product" to "moto g"),
        )
        assertEquals(UsbSysfsNames.Names("motorola", "moto g"), UsbSysfsNames.namesFor("/dev/bus/usb/001/004", root))
    }

    @Test
    fun `the key from sysfs equals the key Android 5 and up builds from the API`() {
        val root = sysfs(mapOf("busnum" to "2", "devnum" to "17", "manufacturer" to " motorola ", "product" to "moto g"))
        val names = UsbSysfsNames.namesFor("/dev/bus/usb/002/017", root)
        assertEquals(
            UsbBlacklistPolicy.key("motorola", "moto g", 0x22b8, 0x2e82),
            UsbBlacklistPolicy.key(names?.manufacturer, names?.product, 0x22b8, 0x2e82),
        )
    }

    @Test
    fun `a device with no strings or no entry falls back to no names`() {
        val root = sysfs(mapOf("busnum" to "1", "devnum" to "5"))
        assertNull(UsbSysfsNames.namesFor("/dev/bus/usb/001/005", root))
        assertNull(UsbSysfsNames.namesFor("/dev/bus/usb/001/006", root))
        assertNull(UsbSysfsNames.namesFor("not-a-node", root))
        assertNull(UsbSysfsNames.namesFor("/dev/bus/usb/001/004", File(root, "missing")))
    }
}
