package com.andrerinas.openheadunit.connection.usb

import java.io.File

/**
 * Below Android 5, UsbDevice carries no manufacturer or product name, but the kernel exports the
 * same descriptor strings in sysfs and any app may read them. That keeps the blacklist key a name.
 */
object UsbSysfsNames {

    data class Names(val manufacturer: String?, val product: String?)

    private val DEVICE_PATH = Regex("/dev/bus/usb/(\\d+)/(\\d+)")

    fun busAndDevice(deviceName: String): Pair<Int, Int>? =
        DEVICE_PATH.matchEntire(deviceName)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

    /** Null when the path is not a USB device node or sysfs has no readable entry for it. */
    fun namesFor(deviceName: String, root: File = File("/sys/bus/usb/devices")): Names? {
        val (bus, dev) = busAndDevice(deviceName) ?: return null
        val entries = try { root.listFiles() } catch (_: SecurityException) { null } ?: return null
        for (entry in entries) {
            if (readInt(File(entry, "busnum")) != bus || readInt(File(entry, "devnum")) != dev) continue
            val names = Names(read(File(entry, "manufacturer")), read(File(entry, "product")))
            return names.takeIf { it.manufacturer != null || it.product != null }
        }
        return null
    }

    private fun read(file: File): String? = try {
        file.readText().trim().takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    private fun readInt(file: File): Int? = read(file)?.toIntOrNull()
}
