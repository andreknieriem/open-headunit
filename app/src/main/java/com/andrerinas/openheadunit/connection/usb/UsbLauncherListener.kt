package com.andrerinas.openheadunit.connection.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.widget.Toast
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy.Tier
import com.andrerinas.openheadunit.connection.usb.UsbLauncherManager.Companion.ATTACH_FALLBACK_DELAY_MS
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.ToastUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Listens to USB attachements to ensure projection connections are automatically established.
 */
class UsbLauncherListener(private val manager: UsbLauncherManager) : UsbReceiver.Listener {

    private val service = manager.service

    override fun onUsbAttach(device: UsbDevice) {
        if (!UsbDeviceCompat.isConnectable(service, device)) {
            AppLog.i("Ignoring USB device attached in service (${Settings.formatUsbVidPidDisplay(device.vendorId, device.productId)}): ${UsbDeviceCompat.connectableReason(service, device)}")
            return
        }

        val commManager = App.provide(service).commManager

        service.userExitedAA = false

        if (UsbDeviceCompat.isInAccessoryMode(device)) {
            // Device already in AOA mode (re-enumerated after UsbAttachedActivity switched it).
            AppLog.i("USB accessory device attached, connecting.")
            service.launchMainActivityIfNeeded("USB accessory attach")
            manager.checkAlreadyConnected(force = true)
        } else {
            // UsbAttachedActivity normally handles normal-mode devices via a manifest intent
            // filter. However, some headunits (especially Chinese MediaTek units) don't
            // deliver USB_DEVICE_ATTACHED to activities on cold start. As a fallback,
            // check after a delay to give UsbAttachedActivity a chance to handle it first.
            val deviceName = UsbDeviceCompat(device).uniqueName

            AppLog.i("Normal USB device attached: $deviceName. Will check auto-connect in ${ATTACH_FALLBACK_DELAY_MS}ms...")

            service.launchMainActivityIfNeeded("USB normal attach ($deviceName)")
            service.serviceScope.launch {
                delay(ATTACH_FALLBACK_DELAY_MS)
                if (!commManager.isConnected && !manager.isSwitchingToProjection() &&
                    !manager.isActivitySwitchInFlight()) {
                    AppLog.i("UsbAttachedActivity didn't handle $deviceName. Trying from service...")
                    manager.checkAlreadyConnected(force = true)
                }
            }
        }
    }

    override fun onUsbDetach(device: UsbDevice) {
        val commManager = App.provide(service).commManager

        service.userExitedAA = false

        if (commManager.isConnectedToUsbDevice(device)) {
            // Cable physically removed — the USB connection is already dead, so skip the
            // ByeByeRequest send (which would block ~1 s trying to write to a gone device).
            commManager.disconnect(sendByeBye = false, isUserExit = false)
        }
    }

    override fun onUsbAccessoryDetach() {
        val commManager = App.provide(service).commManager

        AppLog.i("USB Accessory detached. This might be a transient state (e.g., 100% battery). Attempting to re-sync...")

        service.userExitedAA = false
        if (commManager.isConnected) {
            commManager.disconnect(sendByeBye = false, isUserExit = false)
        }

        // Wait a bit and check if the device is still there in normal mode
        service.serviceScope.launch {
            delay(1500) // Give the phone/system time to settle its USB state
            AppLog.i("Accessory detach cooldown finished. Checking for re-connection...")
            manager.checkAlreadyConnected(force = true)
        }
    }

    override fun onUsbPermission(granted: Boolean, connect: Boolean, device: UsbDevice) {
        if (!UsbDeviceCompat.isConnectable(service, device)) {
            AppLog.i("Ignoring USB permission callback (VID: ${device.vendorId}): ${UsbDeviceCompat.matchReason(device)}")
            return
        }
        val deviceName = UsbDeviceCompat(device).uniqueName
        if (granted) {
            AppLog.i("USB permission granted for $deviceName")
            if (!manager.beginAttempt(Tier.USB, "USB $deviceName")) return
            if (UsbDeviceCompat.isInAccessoryMode(device)) {
                manager.attemptJob = service.serviceScope.launch {
                    try {
                        manager.connectWithRetry(device)
                    } finally {
                        manager.setSwitchingToProjection(false)
                    }
                }
            } else {
                val usbManager = service.getSystemService(Context.USB_SERVICE) as UsbManager
                val settings = App.provide(service).settings
                val usbMode = UsbAccessoryMode(usbManager)
                manager.attemptJob = service.serviceScope.launch(Dispatchers.IO) {
                    try {
                        if (usbMode.connectAndSwitch(device, settings.useLibusb)) {
                            AppLog.i("Successfully requested switch to accessory mode for $deviceName")
                        } else {
                            AppLog.w("USB permission granted but connectAndSwitch failed for $deviceName")
                        }
                    } finally {
                        manager.setSwitchingToProjection(false)
                    }
                }
            }
        } else {
            AppLog.w("USB permission denied for $deviceName")
            ToastUtils.showToast(service, service.getString(R.string.usb_permission_denied), Toast.LENGTH_LONG)
        }
    }
}
