package com.andrerinas.openheadunit.secondscreen.ms912x

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Userspace driver for a MacroSilicon MS9120/MS912x/MS9132 USB-HDMI adapter, over the plain USB
 * Host API: no root and no kernel driver.
 *
 * Ported from moriceh/open-headunit's MS9120Device (AGPLv3), whose protocol was recovered from the
 * vendor's app and the Linux ms912x driver. Commands are 8-byte HID feature reports on endpoint 0;
 * pixels go over bulk OUT in 16 KiB chunks, ended by a zero-length packet.
 */
class Ms912xDevice(
    private val usbManager: UsbManager,
    val device: UsbDevice,
    val mode: Ms912xMode,
    val format: Ms912xWireFormat,
) {
    private companion object {
        const val BULK_CHUNK_SIZE = 16384
        const val BULK_ENDPOINT_ADDRESS = 0x04
        const val TIMEOUT_MS = 1000

        const val REQ_SET_REPORT = 0x09
        const val REQ_GET_REPORT = 0x01
        const val REPORT_VALUE = 0x0300

        const val OP_VIDEO = 0xA6
        const val OP_READ_XDATA = 0xB5
        const val OP_WRITE_XDATA = 0xB6
        const val OP_WRITE_TWO_BYTES = 0x12

        const val SUBOP_VIDEO_IN = 0x01
        const val SUBOP_VIDEO_OUT = 0x02
        const val SUBOP_TRANS_MODE = 0x03
        const val SUBOP_TRANSFER = 0x04
        const val SUBOP_VIDEO_ENABLE = 0x05
        const val SUBOP_POWER = 0x07

        const val XDATA_HPD = 0x0032
        const val XDATA_DISPLAY_COLORSPACE = 0x0033
        const val XDATA_POWER_STATE = 0xC620
        const val XDATA_POWER_ON_SUCCESS = 0xC454
        const val XDATA_MUTE = 0xF004
        const val MUTE_BIT = 0x80
        const val XDATA_ANDROID_HPD_1 = 0xDEEE
        const val XDATA_ANDROID_HPD_2 = 0xF600
        const val XDATA_FRAME_TRANSFER_SWITCH = 0xF202
    }

    private var connection: UsbDeviceConnection? = null
    private var bulkOut: UsbEndpoint? = null
    private val claimed = mutableListOf<UsbInterface>()
    private var frameId = 0
    private var unmuted = false

    val transferWidth: Int = Ms912xModes.transferWidth(mode, format)
    val frameBytes: Int = Ms912xModes.frameBytes(mode, format)

    /** Opens the device and finds its bulk OUT endpoint. Needs the USB permission already granted. */
    fun open(): Boolean {
        val conn = usbManager.openDevice(device) ?: return false.also {
            AppLog.w("SecondScreen: MS912x openDevice returned nothing (no permission, or busy)")
        }
        connection = conn
        try {
            device.getConfiguration(0)?.let { conn.setConfiguration(it) }
        } catch (e: Exception) {
            AppLog.d("SecondScreen: MS912x setConfiguration failed: ${e.message}")
        }
        var fallback: UsbEndpoint? = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (conn.claimInterface(iface, true)) claimed.add(iface)
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.direction != UsbConstants.USB_DIR_OUT || ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.address == BULK_ENDPOINT_ADDRESS) bulkOut = ep else if (fallback == null) fallback = ep
            }
        }
        if (bulkOut == null) bulkOut = fallback
        if (bulkOut == null) {
            AppLog.w("SecondScreen: MS912x has no bulk OUT endpoint")
            close()
            return false
        }
        return true
    }

    private fun hidSet(data: ByteArray): Int =
        connection?.controlTransfer(0x21, REQ_SET_REPORT, REPORT_VALUE, 0, data, data.size, TIMEOUT_MS) ?: -1

    private fun hidGet(data: ByteArray): Int =
        connection?.controlTransfer(0xA1, REQ_GET_REPORT, REPORT_VALUE, 0, data, data.size, TIMEOUT_MS) ?: -1

    private fun report(vararg bytes: Int): ByteArray = ByteArray(8) { i -> bytes.getOrElse(i) { 0 }.toByte() }

    private fun xdataRead(addr: Int): Int {
        val buf = report(OP_READ_XDATA, addr shr 8, addr and 0xFF)
        if (hidSet(buf) < 0 || hidGet(buf) < 0) return -1
        return buf[3].toInt() and 0xFF
    }

    private fun xdataWrite(addr: Int, value: Int, extra: Int = 0) {
        hidSet(report(OP_WRITE_XDATA, addr shr 8, addr and 0xFF, value, extra))
    }

    private fun xdataModBits(addr: Int, value: Int, mask: Int) {
        val reg = xdataRead(addr)
        if (reg >= 0) xdataWrite(addr, (reg and mask.inv()) or (value and mask))
    }

    private fun video(subOp: Int, vararg args: Int) {
        hidSet(report(OP_VIDEO, subOp, *args))
    }

    private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) {}

    /** Powers the adapter up in [mode]. Blocks for about half a second; call off the main thread. */
    fun initDisplay(): Boolean {
        if (xdataRead(XDATA_HPD) == 0) AppLog.w("SecondScreen: MS912x sees no HDMI display attached")
        video(SUBOP_TRANSFER, 0)
        sleep(30)
        val power = xdataRead(XDATA_POWER_STATE)
        if (power < 0) {
            AppLog.w("SecondScreen: MS912x did not answer a register read")
            return false
        }
        if (power == 0) {
            video(SUBOP_POWER, 1, 2)
            for (i in 0 until 10) {
                if (xdataRead(XDATA_POWER_ON_SUCCESS) == 0) break
                sleep(100)
            }
        }
        xdataModBits(XDATA_MUTE, 0, MUTE_BIT)
        sleep(50)
        video(SUBOP_TRANS_MODE, 0)
        val w = transferWidth
        val h = mode.height
        val colorOut = xdataRead(XDATA_DISPLAY_COLORSPACE).coerceAtLeast(0)
        // The chip does not scale: video-in and video-out report the same size.
        video(SUBOP_VIDEO_IN, w shr 8, w and 0xFF, h shr 8, h and 0xFF, Ms912xModes.colorIn(format), 0)
        video(SUBOP_VIDEO_OUT, mode.vic, colorOut, w shr 8, w and 0xFF, h shr 8, h and 0xFF)
        video(SUBOP_TRANSFER, 1)
        sleep(50)
        video(SUBOP_VIDEO_ENABLE, 1)
        xdataWrite(XDATA_ANDROID_HPD_1, 1)
        xdataModBits(XDATA_ANDROID_HPD_2, 0, 1)
        AppLog.i("SecondScreen: MS912x mode ${mode.width}x${mode.height} (VIC ${mode.vic}), ${format.name}, rows of $w")
        return true
    }

    /** Sends one frame of exactly [frameBytes]. Returns false when the adapter stopped answering. */
    fun sendFrame(pixels: ByteArray): Boolean {
        val ep = bulkOut ?: return false
        val conn = connection ?: return false
        if (!unmuted) {
            xdataModBits(XDATA_MUTE, MUTE_BIT, MUTE_BIT)
            unmuted = true
        }
        hidSet(report(OP_WRITE_TWO_BYTES, 0xF2, 0x02, 0, frameId))
        xdataWrite(XDATA_FRAME_TRANSFER_SWITCH, 1, extra = 1)
        var off = 0
        while (off < frameBytes) {
            val len = minOf(BULK_CHUNK_SIZE, frameBytes - off)
            val sent = conn.bulkTransfer(ep, pixels, off, len, TIMEOUT_MS)
            // Zero as well as negative: a transfer that moves nothing would otherwise spin here.
            if (sent <= 0) return false
            off += sent
        }
        conn.bulkTransfer(ep, ByteArray(0), 0, TIMEOUT_MS)
        frameId = 1 - frameId
        return true
    }

    fun close() {
        try {
            video(SUBOP_VIDEO_ENABLE, 0)
            video(SUBOP_TRANSFER, 0)
            video(SUBOP_POWER, 0, 0)
        } catch (_: Exception) {}
        try {
            for (iface in claimed) connection?.releaseInterface(iface)
            connection?.close()
        } catch (e: Exception) {
            AppLog.d("SecondScreen: MS912x close failed: ${e.message}")
        }
        claimed.clear()
        connection = null
        bulkOut = null
        unmuted = false
    }
}
