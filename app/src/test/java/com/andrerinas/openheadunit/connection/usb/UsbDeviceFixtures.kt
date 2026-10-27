package com.andrerinas.openheadunit.connection.usb

import com.andrerinas.openheadunit.connection.usb.UsbDeviceIdentityPolicy.Device
import com.andrerinas.openheadunit.connection.usb.UsbDeviceIdentityPolicy.Interface

/** Descriptors transcribed from hardware dumps, shared by the USB policy tests. */
internal object UsbDeviceFixtures {

    // A vendor-class interface with the accessory endpoint shape: bulk in, bulk out, one interrupt.
    fun vendorClass(name: String?) =
        Interface(0xFF, 0xFF, 0x00, name = name, hasBulkIn = true, hasBulkOut = true)

    /**
     * ASIX AX88179A/B, 0B95:1790, the gigabit-ethernet controller inside a common USB-C dock, in
     * its composite form: device class 0, all three configurations flattened into one list.
     */
    val asixComposite = Device(
        vendorId = 0x0B95, productId = 0x1790, deviceClass = 0x00,
        interfaces = listOf(
            vendorClass("Network_Interface"),
            Interface(0x02, 0x0D, 0x00),                                  // CDC NCM control
            Interface(0x0A, 0x00, 0x01),
            Interface(0x0A, 0x00, 0x01, hasBulkIn = true, hasBulkOut = true),
            Interface(0x02, 0x06, 0x00),                                  // CDC ECM control
            Interface(0x0A, 0x00, 0x00),
            Interface(0x0A, 0x00, 0x00, hasBulkIn = true, hasBulkOut = true),
        ),
    )

    /**
     * The same adapter's other form, seconds apart in the same capture: one vendor configuration,
     * one unnamed interface, no CDC anywhere. Only the device class is left to judge it on.
     */
    val asixVendorOnly = Device(
        vendorId = 0x0B95, productId = 0x1790, deviceClass = 0xFF,
        interfaces = listOf(vendorClass(null)),
    )

    /**
     * A Samsung in file transfer, USB debugging off, `04E8:6860`. It ships a CDC
     * ACM pair alongside MTP, and its data interface is byte-identical to the ethernet adapter's,
     * so the network veto has to key on the control subclass or this phone is rejected.
     */
    val samsungFileTransfer = Device(
        vendorId = 0x04E8, productId = 0x6860, deviceClass = 0x00,
        interfaces = listOf(
            Interface(0x06, 0x01, 0x01, "MTP", hasBulkIn = true, hasBulkOut = true),
            Interface(0x02, 0x02, 0x01, "CDC Abstract Control Model (ACM)"),
            Interface(0x0A, 0x00, 0x00, "CDC ACM Data", hasBulkIn = true, hasBulkOut = true),
        ),
    )

    /** motorola edge 30 neo in file-transfer mode. Charging only presents this same descriptor. */
    val phoneMtp = Device(
        vendorId = 0x22B8, productId = 0x2E82, deviceClass = 0x00,
        interfaces = listOf(vendorClass("MTP")),
    )

    /** motorola edge 30 neo, USB debugging on, file transfer off. */
    val phoneAdb = Device(
        vendorId = 0x22B8, productId = 0x2E81, deviceClass = 0x00,
        interfaces = listOf(Interface(0xFF, 0x42, 0x01, "ADB Interface", hasBulkIn = true, hasBulkOut = true)),
    )

    /** motorola edge 30 neo after the AOA switch: accessory plus ADB, `18D1:2D01`. */
    val phoneAccessory = Device(
        vendorId = 0x18D1, productId = 0x2D01, deviceClass = 0x00,
        interfaces = listOf(
            vendorClass("Android Accessory Interface"),
            Interface(0xFF, 0x42, 0x01, "ADB Interface", hasBulkIn = true, hasBulkOut = true),
        ),
    )

    /** Accessory without ADB, `18D1:2D00`. */
    val phoneAccessoryPlain = Device(
        vendorId = 0x18D1, productId = 0x2D00, deviceClass = 0x00,
        interfaces = listOf(vendorClass("Android Accessory Interface")),
    )

    /** The stock Carlinkit CCPA dongle, `1314:1520`: an IAD composite the filter does not list. */
    val carlinkitCcpa = Device(
        vendorId = 0x1314, productId = 0x1520, deviceClass = 0xEF,
        deviceSubclass = 0x02, deviceProtocol = 0x01,
        interfaces = listOf(
            Interface(0xFF, 0xF0, 0x00),
            Interface(0x02, 0x0D, 0x00),
            Interface(0x0A, 0x00, 0x01),
        ),
    )
}
