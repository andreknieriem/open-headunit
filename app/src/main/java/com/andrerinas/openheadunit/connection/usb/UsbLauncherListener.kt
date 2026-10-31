package com.andrerinas.openheadunit.connection.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.widget.Toast
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy.Tier
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.UsbRecheck
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
        // The scan this attach asks for, minted as it is received, and the Save it may continue,
        // read then too. The 2 s fallback below carries both: a user exit or Stop before it fires
        // withdraws the scan, and a Save that is no longer pending is not adopted by it.
        val recheck = commManager.usbRecheckNow()
        val save = manager.pendingSettingsRestart()

        service.userExitedAA = false

        if (UsbDeviceCompat.isInAccessoryMode(device)) {
            // Device already in AOA mode (re-enumerated after UsbAttachedActivity switched it).
            AppLog.i("USB accessory device attached, connecting.")
            service.launchMainActivityIfNeeded("USB accessory attach")
            manager.checkAlreadyConnected(force = true, settingsRestart = save, recheck = recheck)
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
                // The manager retains this scan if its own AOA switch is still running.
                // An Activity-owned switch keeps its separate handoff guard.
                if (!commManager.isConnected && !manager.isActivitySwitchInFlight()) {
                    AppLog.i("UsbAttachedActivity didn't handle $deviceName. Trying from service...")
                    manager.checkAlreadyConnected(force = true, settingsRestart = stillPending(save), recheck = recheck)
                }
            }
        }
    }

    override fun onUsbDetach(device: UsbDevice) {
        manager.noteRecoveryDeviceDetached(device)
        val commManager = App.provide(service).commManager

        service.userExitedAA = false

        val attempt = commManager.currentConnectionAttempt()
        if (attempt != null && commManager.isConnectedToUsbDevice(device)) {
            // Cable physically removed — the USB connection is already dead, so skip the
            // ByeByeRequest send (which would block ~1 s trying to write to a gone device).
            commManager.disconnectOwned(attempt, sendByeBye = false, isUserExit = false)
        }
    }

    override fun onUsbAccessoryDetach() {
        val commManager = App.provide(service).commManager
        // Minted as the detach is received, with the Save pending then; the re-sync carries both.
        val recheck = commManager.usbRecheckNow()
        val save = manager.pendingSettingsRestart()

        AppLog.i("USB Accessory detached. This might be a transient state (e.g., 100% battery). Attempting to re-sync...")

        service.userExitedAA = false
        val attempt = commManager.currentConnectionAttempt()
        if (attempt != null && commManager.isConnected) {
            commManager.disconnectOwned(attempt, sendByeBye = false, isUserExit = false)
        }

        // Wait a bit and check if the device is still there in normal mode
        service.serviceScope.launch {
            delay(1500) // Give the phone/system time to settle its USB state
            AppLog.i("Accessory detach cooldown finished. Checking for re-connection...")
            manager.checkAlreadyConnected(force = true, settingsRestart = stillPending(save), recheck = recheck)
        }
    }

    /** [save], captured at the input, only while it is still the pending Save; never a newer one. */
    private fun stillPending(save: CommManager.ConnectionState.Disconnected?) =
        save?.takeIf { it === manager.pendingSettingsRestart() }

    override fun onUsbRecoveryPermission(token: String, granted: Boolean, device: UsbDevice) {
        manager.onRecoveryPermission(token, granted, device)
    }

    override fun onUsbPermission(granted: Boolean, connect: Boolean, device: UsbDevice, recheck: UsbRecheck, token: String?) {
        if (!UsbDeviceCompat.isConnectable(service, device)) {
            AppLog.i("Ignoring USB permission callback (VID: ${device.vendorId}): ${UsbDeviceCompat.matchReason(device)}")
            return
        }
        val usbManager = UsbDeviceCompat.usbManager(service) ?: return
        val deviceName = UsbDeviceCompat(device).uniqueName
        // The scan the dialog was raised for: a withdrawn one is not revived by a late grant.
        val permission = manager.takePermissionAttempt(device, recheck, token) ?: return
        val commManager = App.provide(service).commManager
        if (!commManager.isCurrent(permission.recheck)) return
        val restart = permission.settingsRestart
        // Permission is not a new user request for projection. An explicit stop still wins.
        if (granted && manager.cancelledByUser) return
        if (granted) {
            if (restart?.isSettingsRestartCancelled == true) return
            if (restart != null &&
                (!restart.acceptsSettingsRestart(App.provide(service).commManager.connectionState.value) ||
                    SystemClock.elapsedRealtime() >= restart.settingsRestartUntilMs)) {
                // A grant may outlive Save or arrive after another connection has come and
                // gone. Use ordinary admission (including the Settings hold), without
                // renewing Save. Explicit cancellation above still rejects this old grant.
                manager.checkAlreadyConnected(force = true, recheck = permission.recheck)
                return
            }
            AppLog.i("USB permission granted for $deviceName")
            if (!manager.beginAttempt(Tier.USB, "USB $deviceName", restart, permission.recheck)) return
            if (UsbDeviceCompat.isInAccessoryMode(device)) {
                manager.launchAttempt { request ->
                    manager.connectWithRetry(device, request)
                }
            } else {
                val settings = App.provide(service).settings
                val usbMode = UsbAccessoryMode(usbManager)
                manager.launchAttempt(Dispatchers.IO) { request ->
                    if (usbMode.connectAndSwitch(device, settings.useLibusb, manager.switchAdmission(request))) {
                        manager.noteAccessorySwitchRequested()
                        AppLog.i("Successfully requested switch to accessory mode for $deviceName")
                    } else {
                        AppLog.w("USB permission granted but connectAndSwitch failed for $deviceName")
                    }
                }
            }
        } else {
            AppLog.w("USB permission denied for $deviceName")
            ToastUtils.showToast(service, service.getString(R.string.usb_permission_denied), Toast.LENGTH_LONG)
        }
    }
}
