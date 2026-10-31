package com.andrerinas.openheadunit.connection.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.andrerinas.openheadunit.connection.ConnectionAdmission
import com.andrerinas.openheadunit.connection.ConnectionStage
import com.andrerinas.openheadunit.connection.ConnectionStageTracker
import com.andrerinas.openheadunit.utils.Utils
import com.andrerinas.openheadunit.utils.AppLog

class UsbAccessoryMode(private val usbMgr: UsbManager) {

    /** Every caller supplies the authority captured by its own request or locked-boot claim. */
    fun connectAndSwitch(device: UsbDevice, useLibusb: Boolean, admission: ConnectionAdmission): Boolean {
        if (!admission.run {}) return false
        val connection = try { usbMgr.openDevice(device) } catch (e: Throwable) {
            AppLog.e(e)
            return false
        } ?: run { AppLog.e("Cannot open device"); return false }
        return try {
            if (!admission.run {}) return false
            val result = if (useLibusb) {
                AppLog.i("UsbAccessoryMode: Performing AOA switch via native libusb...")
                val native = UsbNative()
                try {
                    if (!admission.run {}) return false
                    if (native.wrap(connection, 0, 0)) {
                        if (!admission.run {}) return false
                        val code = native.accModeSwitch()
                        if (code != 0) AppLog.w(AoaSwitchFailurePolicy.failureLine(code))
                        code == 0
                    } else {
                        AppLog.e("UsbAccessoryMode: Failed to wrap device for native AOA switch")
                        AppLog.w(AoaSwitchFailurePolicy.failureLine(AoaSwitchFailurePolicy.NO_HANDLE))
                        false
                    }
                } finally { native.close() }
            } else switch(connection, admission)
            AppLog.i("Result: $result")
            result
        } finally { connection.close() }
    }

    /** Resets only the device whose original recovery authority still holds. */
    fun resetDevice(device: UsbDevice, admission: ConnectionAdmission): Boolean {
        val name = UsbDeviceCompat(device).uniqueName
        if (!admission.run {}) return false
        val connection = try { usbMgr.openDevice(device) } catch (e: Throwable) {
            AppLog.e("UsbAccessoryMode: USB reset not issued to $name: cannot open", e)
            return false
        } ?: run { AppLog.w("UsbAccessoryMode: USB reset not issued to $name: cannot open"); return false }
        var native: UsbNative? = null
        return try {
            if (!admission.run {}) return false
            val wrapper = UsbNative()
            native = wrapper
            if (!admission.run {}) return false
            val wrapped = wrapper.wrap(connection, 0, 0)
            if (wrapped) {
                if (!admission.run {}) return false
                wrapper.reset()
                AppLog.i("UsbAccessoryMode: USB reset issued to $name")
            } else AppLog.w("UsbAccessoryMode: USB reset not issued to $name: cannot wrap for libusb")
            wrapped
        } finally {
            try { native?.close() } finally { connection.close() }
        }
    }

    private fun switch(connection: UsbDeviceConnection, admission: ConnectionAdmission): Boolean {
        // Do accessory negotiation and attempt to switch to accessory mode. Called only by usb_connect()
        val buffer = ByteArray(2)
        if (!admission.run {}) return false
        var len = connection.controlTransfer(UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_VENDOR, ACC_REQ_GET_PROTOCOL, 0, 0, buffer, 2, USB_TIMEOUT_IN_MS)
        if (len != 2) {
            AppLog.e("Error controlTransfer len: $len")
            // controlTransfer returns -1 for a stall and for a timeout alike, so name both. A
            // device that answers neither is not offering Android Auto over USB.
            AppLog.w(
                "UsbAccessoryMode: AOA switch failed: the device did not complete the Android " +
                    "Auto handshake (stalled or no answer), so it is not offering Android Auto " +
                    "over USB"
            )
            return false
        }
        val acc_ver = Utils.getAccVersion(buffer)
        // Get OAP / ACC protocol version
        AppLog.i("Success controlTransfer len: $len  acc_ver: $acc_ver")
        if (acc_ver < 1) {
            // If error or version too low...
            AppLog.e("No support acc")
            return false
        }
        AppLog.i("acc_ver: $acc_ver")

        // Send all accessory identification strings. Abort if any transfer fails — a partial
        // identification (e.g. manufacturer sent but model missing) can cause the phone to
        // ignore the ACC_REQ_START or fail to switch into accessory mode.
        if (!initStringControlTransfer(connection, admission, ACC_IDX_MAN, MANUFACTURER) ||
            !initStringControlTransfer(connection, admission, ACC_IDX_MOD, MODEL) ||
            !initStringControlTransfer(connection, admission, ACC_IDX_DES, DESCRIPTION) ||
            !initStringControlTransfer(connection, admission, ACC_IDX_VER, VERSION) ||
            !initStringControlTransfer(connection, admission, ACC_IDX_URI, URI) ||
            !initStringControlTransfer(connection, admission, ACC_IDX_SER, SERIAL)) {
            return false
        }

        AppLog.i("Sending acc start")
        // Send accessory start request. Device should re-enumerate as an accessory.
        if (!admission.run {}) return false
        len = connection.controlTransfer(UsbConstants.USB_TYPE_VENDOR, ACC_REQ_START, 0, 0, byteArrayOf(), 0, USB_TIMEOUT_IN_MS)

        // len == 0: clean ACK before re-enumeration (expected path).
        // len < 0: phone disconnected before the ACK because it started re-enumerating
        //          immediately upon receipt — the command was still received. Treat as success.
        AppLog.i("Acc start sent (len=$len). Waiting for re-enumeration...")
        ConnectionStageTracker.report(ConnectionStage.USB_SWITCHING)
        try { Thread.sleep(500) } catch (e: Exception) {}
        return true
    }

    private fun initStringControlTransfer(conn: UsbDeviceConnection, admission: ConnectionAdmission, index: Int, string: String): Boolean {
        if (!admission.run {}) return false
        val len = conn.controlTransfer(UsbConstants.USB_TYPE_VENDOR, ACC_REQ_SEND_STRING, 0, index, string.toByteArray(), string.length, USB_TIMEOUT_IN_MS)
        return if (len < 0) {
            // Negative means the USB transfer itself failed (e.g. device disconnected or
            // timed out). Abort the switch — ACC_REQ_START would be pointless.
            AppLog.e("Error controlTransfer len: $len  index: $index  string: \"$string\"")
            false
        } else {
            // len == string.length is the ideal ACK. Some phones return 0 for a successful
            // OUT control transfer (they accept the data but report 0 bytes in the data
            // stage). Treat any non-negative return as success; log a warning if unexpected.
            if (len != string.length) {
                AppLog.w("Unexpected controlTransfer len: $len (expected ${string.length})  index: $index  string: \"$string\"")
            } else {
                AppLog.i("Success controlTransfer len: $len  index: $index  string: \"$string\"")
            }
            true
        }
    }

    companion object {
        /**
         * 100 ms was under USB 2.0's own 500 ms allowance for the first data packet, so a slow
         * phone read as "not an AOA device". 1000 is what the native path (`usbhelper.c`) and the
         * other AOAP implementations use, and the timeout only bounds a device that is not replying.
         */
        private const val USB_TIMEOUT_IN_MS = 1000
        private const val MANUFACTURER = "Android"
        private const val MODEL = "Android Auto"
        private const val DESCRIPTION = "Android Auto"//"Android Open Automotive Protocol"
        private const val VERSION = "2.0.1"
        private const val URI = "https://developer.android.com/auto/index.html"
        private const val SERIAL = "HU-AAAAAA001"

        // Indexes for strings sent by the host via ACC_REQ_SEND_STRING:
        private const val ACC_IDX_MAN = 0
        private const val ACC_IDX_MOD = 1
        private const val ACC_IDX_DES = 2
        private const val ACC_IDX_VER = 3
        private const val ACC_IDX_URI = 4
        private const val ACC_IDX_SER = 5

        // OAP Control requests:
        private const val ACC_REQ_GET_PROTOCOL = 51
        private const val ACC_REQ_SEND_STRING = 52
        private const val ACC_REQ_START = 53
    }
}
