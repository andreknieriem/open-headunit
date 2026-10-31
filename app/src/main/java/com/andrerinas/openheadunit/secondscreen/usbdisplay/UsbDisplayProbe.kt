package com.andrerinas.openheadunit.secondscreen.usbdisplay

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.andrerinas.openheadunit.utils.AppLog

/** Finds an attached Open Headunit USB display and asks it what it is. */
object UsbDisplayProbe {

    class Found(val device: UsbDevice, val iface: UsbInterface)

    fun find(usbManager: UsbManager): Found? {
        for (device in usbManager.deviceList.values) {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (UsbDisplayProtocol.isDisplayInterface(iface.interfaceClass, iface.interfaceSubclass, iface.interfaceProtocol)) {
                    return Found(device, iface)
                }
            }
        }
        return null
    }

    /** GET_INFO over an open connection. */
    fun readInfo(connection: UsbDeviceConnection, iface: UsbInterface): UsbDisplayProtocol.Info? {
        val buf = ByteArray(UsbDisplayProtocol.INFO_LENGTH)
        val read = connection.controlTransfer(
            UsbDisplayProtocol.REQUEST_TYPE_IN, UsbDisplayProtocol.REQ_GET_INFO, 0, iface.id,
            buf, buf.size, 1000,
        )
        val info = UsbDisplayProtocol.parseInfo(buf, read)
        if (info == null) AppLog.w("SecondScreen: the USB display answered GET_INFO with $read unusable bytes")
        return info
    }

    /** Opens the device just long enough to read its info; null without permission. */
    fun probe(usbManager: UsbManager, found: Found): UsbDisplayProtocol.Info? {
        if (!usbManager.hasPermission(found.device)) return null
        val connection = usbManager.openDevice(found.device) ?: return null
        return try {
            if (!connection.claimInterface(found.iface, true)) null else readInfo(connection, found.iface)
        } finally {
            connection.releaseInterface(found.iface)
            connection.close()
        }
    }
}
