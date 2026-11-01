package com.andrerinas.openheadunit.connection.usb

import com.andrerinas.openheadunit.connection.usb.UsbDeviceIdentityPolicy.Device

/**
 * A copy of `res/xml/usb_device_filter.xml`, so the app can tell when Android offers it a
 * device. [matches] follows AOSP `DeviceFilter.matches`; a test fails when the two files differ.
 */
object UsbManifestFilter {

    /** One `usb-device` element. Null means the attribute is unset, which AOSP reads as -1. */
    data class Entry(
        val vendorId: Int? = null,
        val productId: Int? = null,
        val deviceClass: Int? = null,
        val subclass: Int? = null,
        val protocol: Int? = null,
    )

    val ENTRIES: List<Entry> = listOf(
        Entry(vendorId = 6353, productId = 11520),
        Entry(vendorId = 6353, productId = 11521),
        Entry(deviceClass = 255, subclass = 255, protocol = 0),
        Entry(deviceClass = 255, subclass = 66, protocol = 1),
        Entry(deviceClass = 6, subclass = 1, protocol = 1),
        Entry(deviceClass = 224, subclass = 1, protocol = 3),
        Entry(deviceClass = 239, subclass = 4, protocol = 1),
        Entry(deviceClass = 8, subclass = 6, protocol = 1),
    )

    fun matches(device: Device): Boolean = ENTRIES.any { matches(it, device) }

    private fun matches(entry: Entry, device: Device): Boolean {
        if (entry.vendorId != null && entry.vendorId != device.vendorId) return false
        if (entry.productId != null && entry.productId != device.productId) return false
        if (triple(entry, device.deviceClass, device.deviceSubclass, device.deviceProtocol)) return true
        return device.interfaces.any { triple(entry, it.ifaceClass, it.subclass, it.protocol) }
    }

    private fun triple(entry: Entry, cls: Int, subclass: Int, protocol: Int): Boolean =
        (entry.deviceClass == null || entry.deviceClass == cls) &&
            (entry.subclass == null || entry.subclass == subclass) &&
            (entry.protocol == null || entry.protocol == protocol)
}
