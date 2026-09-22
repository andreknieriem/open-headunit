package com.andrerinas.openheadunit.connection.usb

import com.andrerinas.openheadunit.secondscreen.UsbDisplayAdapterPolicy

/**
 * Decides whether a USB device is something we can project Android Auto to, and says why.
 *
 * The rules used to live inside [UsbDeviceCompat] against a live `UsbDevice`, so nothing could be
 * tested and a wrong verdict was only ever visible on hardware. This takes plain descriptors
 * instead, so a device that misbehaves in the field can be reproduced from its logged line.
 */
object UsbDeviceIdentityPolicy {

    /** One interface descriptor, plus the endpoint shape the accessory rule needs. */
    data class Interface(
        val ifaceClass: Int,
        val subclass: Int,
        val protocol: Int,
        val name: String? = null,
        val hasBulkIn: Boolean = false,
        val hasBulkOut: Boolean = false,
    )

    /**
     * A whole device. [interfaces] is every interface across every configuration, which is what
     * `UsbDevice.getInterfaceCount()` returns and is what makes the CDC-sibling rule below work.
     *
     * It is not enough on its own. The same ethernet adapter also enumerates as a single vendor
     * configuration with no CDC interface anywhere in the descriptor, and then only [deviceClass]
     * separates it from a phone.
     */
    data class Device(
        val vendorId: Int,
        val productId: Int,
        val deviceClass: Int,
        val interfaces: List<Interface>,
    )

    data class Verdict(val accepted: Boolean, val reason: String) {
        /** The form the diagnostic dump prints, e.g. "accepted: ADB". */
        override fun toString(): String = (if (accepted) "accepted: " else "rejected: ") + reason
    }

    private const val APPLE_VID = 0x05AC
    private const val GOOGLE_VID = 0x18D1

    /**
     * The four accessory PIDs that carry an accessory interface, matching AOSP's own
     * `car-usb-handler`. 0x2D02 and 0x2D03 are the audio-only modes and have nothing to talk to.
     */
    private val ACCESSORY_PIDS = setOf(
        0x2D00, // accessory
        0x2D01, // accessory + ADB
        0x2D04, // accessory + audio
        0x2D05, // accessory + audio + ADB
    )

    /**
     * `iInterface` on the vendor-class interface. Three different products present the identical
     * FF/FF/00 triple, and only this string tells them apart: the accessory gadget names itself,
     * Android's MTP function names itself, and a network adapter names its own thing.
     */
    private const val NAME_ACCESSORY = "Android Accessory Interface"
    private const val NAME_MTP = "MTP"

    /**
     * CDC control subclasses that mean "this device carries a network". Keyed on these rather than
     * on the CDC classes themselves because a phone in file transfer can ship a CDC ACM pair whose
     * data interface is byte-identical to an ethernet adapter's, and only the control subclass
     * tells a serial port from a NIC.
     */
    private val CDC_NETWORK_SUBCLASSES = setOf(
        0x06, // ECM
        0x0C, // EEM
        0x0D, // NCM
        0x0E, // MBIM
    )

    /** Device classes that describe the whole device and can never be a phone. */
    private val EXCLUDED_DEVICE_CLASSES = setOf(
        0x01, // audio
        0x07, // printer
        0x08, // mass storage
        0x09, // hub
        0x0E, // video
        0x11, // billboard, the alt-mode announcer inside every USB-C dock
    )

    fun evaluate(device: Device): Verdict {
        // Apple does not support Android Auto.
        if (device.vendorId == APPLE_VID) return Verdict(false, "Apple VID")

        if (isInAccessoryMode(device.vendorId, device.productId)) {
            return Verdict(true, "already in accessory mode")
        }

        // A second-screen adapter is ours to drive, never a phone to switch into accessory mode.
        val ids = device.interfaces.map { UsbDisplayAdapterPolicy.InterfaceId(it.ifaceClass, it.subclass, it.protocol, it.name) }
        UsbDisplayAdapterPolicy.kindOf(device.vendorId, device.productId, ids)?.let {
            return Verdict(false, "$it second-screen adapter, not a phone")
        }

        // Rarely fires, because a composite device reports class 0 and defers to its interfaces.
        // It is still the cheapest way to drop a device that has declared what it is.
        if (device.deviceClass in EXCLUDED_DEVICE_CLASSES) {
            return Verdict(false, "device class ${hex(device.deviceClass)} is not a phone")
        }

        // The unambiguous triples first, so a tethering or debugging phone is decided before the
        // vendor-class rule below ever has to guess.
        for (iface in device.interfaces) {
            unambiguousMatch(iface)?.let { return Verdict(true, it) }
        }

        for (iface in device.interfaces) {
            if (isVendorClass(iface) && iface.hasBulkIn && iface.hasBulkOut) {
                return accessoryVerdict(device, iface)
            }
        }

        // A device whose interfaces are only a CDC-ACM pair is a serial port: a vendor dongle's own
        // channel, not a phone. Named apart from the fall-through because "no Android interface"
        // reads like a descriptor we failed to find rather than one we recognised.
        if (device.interfaces.isNotEmpty() && device.interfaces.all { isCdcSerial(it) }) {
            return Verdict(false, "CDC-ACM serial device, not a phone")
        }

        return Verdict(false, "no Android interface")
    }

    /**
     * The post-switch identity. Kept here rather than in the caller so the accepted PID set has one
     * definition, because sixteen call sites read it.
     */
    fun isInAccessoryMode(vendorId: Int, productId: Int): Boolean =
        vendorId == GOOGLE_VID && productId in ACCESSORY_PIDS

    private fun unambiguousMatch(iface: Interface): String? = when {
        // ADB.
        iface.ifaceClass == 0xFF && iface.subclass == 0x42 && iface.protocol == 0x01 -> "ADB"
        // RNDIS tethering. Checked before the vendor-class rule because a tethering phone also
        // carries the CDC-data interface that rule treats as a network adapter.
        iface.ifaceClass == 0xE0 && iface.subclass == 0x01 && iface.protocol == 0x03 -> "RNDIS"
        // PTP, the still-image class.
        iface.ifaceClass == 0x06 && iface.subclass == 0x01 && iface.protocol == 0x01 -> "PTP"
        // Interface Association Descriptor.
        iface.ifaceClass == 0xEF && iface.subclass == 0x04 && iface.protocol == 0x01 -> "IAD"
        // Mass storage carrying MTP over CBI. Modern phones do not use this; kept because older
        // ones did. The name is what the rule has always claimed, so it stays.
        iface.ifaceClass == 0x08 && iface.subclass == 0x06 && iface.protocol == 0x01 -> "MTP"
        else -> null
    }

    /**
     * FF/FF/00 with a bulk pair is the real accessory triple, so it cannot simply be tightened.
     * A USB ethernet controller presents exactly that on its vendor control interface, and OHU
     * used to accept and auto-connect to one. The name settles it when there is one; failing that,
     * a vendor-class device descriptor or a CDC sibling means a peripheral rather than a phone.
     */
    private fun accessoryVerdict(device: Device, iface: Interface): Verdict = when {
        iface.name == NAME_ACCESSORY -> Verdict(true, "AOAP")
        iface.name == NAME_MTP -> Verdict(true, "MTP")
        // An Android gadget always defers to its interfaces and reports device class 0. A vendor
        // class on the device descriptor is the adapter saying the whole device is proprietary,
        // and it is the only thing separating one from a phone in file transfer, whose interface
        // carries the same triple and the same endpoints.
        device.deviceClass == 0xFF -> Verdict(false, "vendor-class device, not a phone")
        device.interfaces.any { it.ifaceClass == 0x02 && it.subclass in CDC_NETWORK_SUBCLASSES } ->
            Verdict(false, "CDC network adapter")
        // Nothing named it and nothing vetoed it, so probe rather than guess. The name is printed
        // because a wrong verdict here is only ever diagnosed from a reporter's log line.
        else -> Verdict(true, "AOAP (vendor interface named ${describeName(iface.name)})")
    }

    private fun describeName(name: String?): String =
        if (name.isNullOrBlank()) "nothing" else "'" + name + "'"

    private fun isVendorClass(iface: Interface): Boolean =
        iface.ifaceClass == 0xFF && iface.subclass == 0xFF && iface.protocol == 0x00

    /** The CDC control interface in ACM subclass, or the CDC-data interface that carries it. */
    private fun isCdcSerial(iface: Interface): Boolean =
        (iface.ifaceClass == 0x02 && iface.subclass == 0x02) || iface.ifaceClass == 0x0A

    private fun hex(value: Int): String = "0x%02X".format(value)
}
