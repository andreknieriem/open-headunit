package com.andrerinas.openheadunit.connection

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.os.Build
import com.andrerinas.openheadunit.aap.Utils
import java.util.Locale

class UsbDeviceCompat(val wrappedDevice: UsbDevice) {

    val deviceName: String
        get() = wrappedDevice.deviceName

    val vendorId: Int
        get() = wrappedDevice.vendorId

    val productId: Int
        get() = wrappedDevice.productId

    val uniqueName: String
        get() = getUniqueName(wrappedDevice)

    override fun toString(): String {
        return String.format(Locale.US, "%s - %s", uniqueName, wrappedDevice.toString())
    }

    val isInAccessoryMode: Boolean
        get() = isInAccessoryMode(wrappedDevice)

    companion object {
        private const val USB_VID_GOO = 0x18D1   // 6353   Nexus or ACC mode, see PID to distinguish
        private const val USB_PID_ACC = 0x2D00      // Accessory                  100
        private const val USB_PID_ACC_ADB = 0x2D01      // Accessory + ADB            110
        private const val APPLE_VID = 0x05AC

        fun getUniqueName(device: UsbDevice): String {
            val vendorId = device.vendorId
            val productId = device.productId
            val vidPid = "${Utils.hex_get(vendorId.toShort())}:${Utils.hex_get(productId.toShort())}"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val manufacturer = device.manufacturerName?.takeIf { it.isNotBlank() }
                val product = device.productName?.takeIf { it.isNotBlank() }
                if (manufacturer != null || product != null) {
                    // Include VID:PID so devices with identical strings but different
                    // hardware (e.g. internal multimedia module vs external phone) are
                    // treated as distinct entries.
                    return "${listOfNotNull(manufacturer, product).joinToString(" ")} ($vidPid)"
                }
            }

            return vidPid
        }

        fun isInAccessoryMode(device: UsbDevice): Boolean {
            val dev_vend_id = device.vendorId
            val dev_prod_id = device.productId
            return dev_vend_id == USB_VID_GOO &&
                (dev_prod_id == USB_PID_ACC || dev_prod_id == USB_PID_ACC_ADB)
        }

        fun isAndroidDevice(device: UsbDevice): Boolean {
            // Apple does not support Android Auto
            if (device.vendorId == APPLE_VID) return false

            if (isInAccessoryMode(device)) return true

            return hasAndroidInterface(device)
        }

        /**
         * Identifies an Android device by USB class/subclass
         * More reliable than a VID list
         */
        private fun hasAndroidInterface(device: UsbDevice): Boolean {
            for (i in 0 until device.interfaceCount) {
                val usbInterface = device.getInterface(i)
                val ifaceClass = usbInterface.interfaceClass
                val ifaceSubclass = usbInterface.interfaceSubclass
                val ifaceProtocol = usbInterface.interfaceProtocol

                // AOAP (Android Open Accessory Protocol)
                if (ifaceClass == 0xFF && ifaceSubclass == 0xFF && ifaceProtocol == 0x00) {
                    if (hasBulkEndpoint(usbInterface)) return true
                }

                // MTP (Media Transfer Protocol)
                if (ifaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                    ifaceSubclass == 0x06 && ifaceProtocol == 0x01) {
                    return true
                }

                // ADB (Android Debug Bridge)
                if (ifaceClass == 0xFF && ifaceSubclass == 0x42 && ifaceProtocol == 0x01) {
                    return true
                }

                // RNDIS (USB tethering)
                if (ifaceClass == 0xE0 && ifaceSubclass == 0x01 && ifaceProtocol == 0x03) {
                    return true
                }

                // IAD (Interface Association Descriptor) - современные Android
                if (ifaceClass == 0xEF && ifaceSubclass == 0x04 && ifaceProtocol == 0x01) {
                    return true
                }

                // PTP (Picture Transfer Protocol) - старые Android
                if (ifaceClass == 0x06 && ifaceSubclass == 0x01 && ifaceProtocol == 0x01) {
                    return true
                }
            }

            return false
        }

        /**
         * Checks for the presence of a bulk endpoint (needed for data transfer)
         */
        private fun hasBulkEndpoint(usbInterface: android.hardware.usb.UsbInterface): Boolean {
            for (j in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(j)
                if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    return true
                }
            }
            return false
        }
    }
}
