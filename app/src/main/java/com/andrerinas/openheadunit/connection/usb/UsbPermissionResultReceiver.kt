package com.andrerinas.openheadunit.connection.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Hands each permission answer to the record. A manifest receiver, so an answer that arrives
 * while the service is down still counts; the service's own receiver acts on the answer.
 */
class UsbPermissionResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UsbReceiver.ACTION_USB_DEVICE_PERMISSION) return
        val device = UsbReceiver.deviceOf(intent) ?: return
        val token = UsbReceiver.permissionToken(intent) ?: return
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        val ended = UsbPermissionAsks.answered(device.deviceName, token, granted)
        AppLog.i("UsbPermissionAsks: answer for ${device.deviceName} (granted=$granted); entry ${if (ended) "ended" else "kept"}")
    }
}
