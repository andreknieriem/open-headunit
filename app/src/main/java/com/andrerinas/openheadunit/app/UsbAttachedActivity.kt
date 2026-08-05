package com.andrerinas.openheadunit.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.UserManager
import android.os.Build
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.UsbAccessoryMode
import com.andrerinas.openheadunit.connection.UsbDeviceCompat
import com.andrerinas.openheadunit.connection.UsbReceiver
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.DeviceIntent
import com.andrerinas.openheadunit.utils.LocaleHelper
import com.andrerinas.openheadunit.main.MainActivity
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.ToastUtils

class UsbAttachedActivity : Activity() {

    enum class DeviceSource { FROM_INTENT, FROM_FALLBACK, AMBIGUOUS }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    private fun resolveUsbDevice(intent: Intent?): UsbDevice? {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val androidDevices = usbManager.deviceList.values.filter { UsbDeviceCompat.isAndroidDevice(it) }
        return resolveDevice(intent, androidDevices)
    }

    companion object {
        /**
         * Determines which USB device to act on given the intent extras and the current
         * USB device list. Extracted for testability — the caller must pass both pieces
         * so tests can verify the logic without an Android Context.
         */
        @JvmStatic
        internal fun resolveDevice(intent: Intent?, androidDevices: Collection<UsbDevice>): UsbDevice? {
            DeviceIntent(intent).device?.let { return it }
            return when (androidDevices.size) {
                1 -> {
                    val device = androidDevices.first()
                    AppLog.i("No USB device in intent extras, falling back to single device: ${UsbDeviceCompat(device).uniqueName}")
                    device
                }
                else -> {
                    AppLog.e("No USB device in intent extras and ${androidDevices.size} Android devices present, cannot determine target")
                    null
                }
            }
        }

        /** Pure decision logic extracted for unit testing. */
        @JvmStatic
        internal fun pickDeviceSource(intentHasDevice: Boolean, fallbackCount: Int): DeviceSource = when {
            intentHasDevice -> DeviceSource.FROM_INTENT
            fallbackCount == 1 -> DeviceSource.FROM_FALLBACK
            else -> DeviceSource.AMBIGUOUS
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppLog.i("USB Intent: $intent")

        val device = resolveUsbDevice(intent)
        if (device == null || !UsbDeviceCompat.isAndroidDevice(device)) {
            if (device != null) {
                AppLog.i("Ignoring non-Android USB device in onCreate (VID: ${device.vendorId}): ${device.deviceName}")
            }
            finish()
            return
        }

        val isLocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && 
                      !(getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked

        val settings = if (!isLocked) Settings(this) else null

        if (settings != null && settings.isUsbDeviceBlacklisted(device.vendorId, device.productId)) {
            AppLog.i("UsbAttachedActivity: Ignored blacklisted USB device (VID: 0x${device.vendorId.toString(16)}, PID: 0x${device.productId.toString(16)})")
            finish()
            return
        }

        if (!isLocked) {
            if (App.provide(this).commManager.connectionState.value is CommManager.ConnectionState.TransportStarted) {
                AppLog.e("Thread already running")
                finish()
                return
            }
        }

        if (UsbDeviceCompat.isInAccessoryMode(device)) {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            if (!usbManager.hasPermission(device)) {
                AppLog.i("Usb in accessory mode but no permission. Requesting...")
                val permissionIntent = UsbReceiver.createPermissionPendingIntent(this)
                usbManager.requestPermission(device, permissionIntent)
                finish()
                return
            }
            AppLog.i("Usb in accessory mode and has permission. Starting AapService.")
            ContextCompat.startForegroundService(this, Intent(this, AapService::class.java).apply {
                action = AapService.ACTION_CHECK_USB
            })
            finish()
            return
        }

        val deviceCompat = UsbDeviceCompat(device)

        // Launch app UI if USB auto-start is enabled (for any device — a non-AA
        // device simply won't complete the AOA handshake, no harm done)
        // Use device-protected storage for the auto-start check so it works
        // during locked boot (before credential storage is available)
        val autoStartOnUsb = Settings.isAutoStartOnUsbEnabled(this)
        if (autoStartOnUsb && !App.provide(this).commManager.isConnected) {
            AppLog.i("USB auto-start: launching app for ${deviceCompat.uniqueName}")
            try {
                startActivity(Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(MainActivity.EXTRA_LAUNCH_SOURCE, "USB auto-start")
                })
            } catch (e: Exception) {
                AppLog.w("Could not start UI from USB auto-start: ${e.message}")
            }
        }

        // Google VID (0x18D1) devices are almost certainly Android Auto phones or AA dongles
        // (e.g. AAWireless). Always attempt the AOA switch for these — skipping them would
        // break dongle users who haven't explicitly configured allowlists.
        val isGoogleDevice = device.vendorId == 0x18D1
        if (!isGoogleDevice && settings != null && !autoStartOnUsb && !settings.isConnectingDevice(deviceCompat)) {
            AppLog.i("Skipping device ${deviceCompat.uniqueName} (not allowed and USB auto-start disabled)")
            finish()
            return
        }
        
        if (isLocked && !autoStartOnUsb) {
            AppLog.w("Device is locked and USB auto-start is disabled. Cannot check allowed devices. Finishing.")
            finish()
            return
        }

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val usbMode = UsbAccessoryMode(usbManager)
        AppLog.i("Switching USB device to accessory mode " + deviceCompat.uniqueName)
        ToastUtils.showToast(this, getString(R.string.switching_usb_accessory_mode, deviceCompat.uniqueName), Toast.LENGTH_SHORT)
        val useLibusb = settings?.useLibusb ?: false
        Thread {
            val result = usbMode.connectAndSwitch(device, useLibusb)
            runOnUiThread {
                if (result) {
                    ToastUtils.showToast(this, getString(R.string.success), Toast.LENGTH_SHORT)
                } else {
                    ToastUtils.showToast(this, getString(R.string.failed), Toast.LENGTH_SHORT)
                }
                finish()
            }
        }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val device = resolveUsbDevice(intent)
        if (device == null || !UsbDeviceCompat.isAndroidDevice(device)) {
            if (device != null) {
                AppLog.i("Ignoring non-Android USB device in onNewIntent (VID: ${device.vendorId}): ${device.deviceName}")
            }
            finish()
            return
        }

        AppLog.i(UsbDeviceCompat.getUniqueName(device))

        val isLocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && 
                      !(getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked

        val settings = if (!isLocked) Settings(this) else null
        if (settings != null && settings.isUsbDeviceBlacklisted(device.vendorId, device.productId)) {
            AppLog.i("UsbAttachedActivity: Ignored blacklisted USB device in onNewIntent (VID: 0x${device.vendorId.toString(16)}, PID: 0x${device.productId.toString(16)})")
            finish()
            return
        }

        if (!isLocked && App.provide(this).commManager.connectionState.value !is CommManager.ConnectionState.TransportStarted) {
            if (UsbDeviceCompat.isInAccessoryMode(device)) {
                AppLog.e("Usb in accessory mode")
                ContextCompat.startForegroundService(this, Intent(this, AapService::class.java).apply {
                    action = AapService.ACTION_CHECK_USB
                })
            }
        } else {
            AppLog.e("Thread already running")
        }

        finish()
    }
}
