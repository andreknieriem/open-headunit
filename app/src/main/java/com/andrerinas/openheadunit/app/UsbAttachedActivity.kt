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
import com.andrerinas.openheadunit.connection.AutoConnectHoldPolicy
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.ConnectionArbiter
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy
import com.andrerinas.openheadunit.connection.ConnectionStage
import com.andrerinas.openheadunit.connection.ConnectionStageTracker
import com.andrerinas.openheadunit.connection.usb.UsbAccessoryMode
import com.andrerinas.openheadunit.connection.usb.UsbAttachActivityPolicy
import com.andrerinas.openheadunit.connection.usb.UsbAttachPolicy
import com.andrerinas.openheadunit.connection.usb.UsbDeviceCompat
import com.andrerinas.openheadunit.connection.usb.UsbDeviceDiagnostics
import com.andrerinas.openheadunit.connection.usb.UsbPermissionAsks
import com.andrerinas.openheadunit.connection.usb.UsbPlugInPolicy
import com.andrerinas.openheadunit.connection.usb.UsbSwitchClaim
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.DeviceIntent
import com.andrerinas.openheadunit.utils.LocaleHelper
import com.andrerinas.openheadunit.main.MainActivity
import com.andrerinas.openheadunit.main.SettingsActivity
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.ToastUtils

class UsbAttachedActivity : Activity() {

    enum class DeviceSource { FROM_INTENT, FROM_FALLBACK, AMBIGUOUS }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    private fun resolveUsbDevice(intent: Intent?): UsbDevice? {
        val usbManager = UsbDeviceCompat.usbManager(this) ?: return null
        UsbDeviceDiagnostics.logDeviceList(this, usbManager, "USB attach")
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

        // Android started us for an attach, so a later scan for this path knows the plug.
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            UsbPermissionAsks.stampAttach(device.deviceName)
        }

        if (Settings.isUsbDeviceBlacklisted(this, device)) {
            AppLog.i("UsbAttachedActivity: Ignored blacklisted USB device (${Settings.formatUsbVidPidDisplay(device.vendorId, device.productId)})")
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

        val accessory = UsbDeviceCompat.isInAccessoryMode(device)
        val deviceCompat = UsbDeviceCompat(device)
        // Read from device storage so both work during locked boot, before credential storage.
        val storedConnect = settings?.usbConnectOnPlugIn ?: Settings.isUsbConnectOnPlugInEnabled(this)
        val autoStartOnUsb = Settings.isAutoStartOnUsbEnabled(this) && storedConnect
        // Android started us, so detection is on; a re-attach after a glitch switches at once.
        val connectOnPlugIn = UsbPlugInPolicy.maySwitchNormal(UsbPlugInPolicy.Trigger.PLUG_IN, storedConnect,
            listen = true, uncleanEndAgoMs = AapService.instance?.uncleanSessionEndAgoMs())

        // Google VID (0x18D1) devices or known AA dongles (e.g. AAWireless, smartBox, Carlinkit)
        // are almost certainly Android Auto devices. Always attempt the AOA switch for these — skipping
        // them would break dongle users who haven't explicitly configured allowlists.
        //
        // [BUG_FIX] Every other make used to be refused here, because the vendor id was the only
        // escape from the allow list. The device has already passed isAndroidDevice() above, so
        // an unconfigured allow list is no reason to drop the attach — it means the user never
        // set one, not that nothing is permitted. UsbAttachPolicy carries the reasoning.
        val isGoogleOrDongle = UsbAttachPolicy.isGoogleOrDongleVendor(
            vendorId = device.vendorId,
            productName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) device.productName else null,
            manufacturerName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) device.manufacturerName else null,
        )
        val allowList = settings?.allowedDevices ?: emptySet()
        val mayAttempt = settings == null || UsbAttachPolicy.shouldAttemptAoaSwitch(
            isGoogleVendor = isGoogleOrDongle,
            autoStartOnUsb = autoStartOnUsb,
            allowListConfigured = allowList.isNotEmpty(),
            deviceAllowed = settings.isConnectingDevice(deviceCompat),
        )
        // No switch, prompt or home screen while held: the service holds or refuses it and says why.
        val held = !isLocked && !AutoConnectHoldPolicy.raisesUi(
            SettingsActivity.isVisible, AapService.instance?.usbCancelledByUser() == true)
        val decision = UsbAttachActivityPolicy.decide(
            accessory = accessory,
            hasPermission = !accessory || UsbDeviceCompat.usbManager(this)?.hasPermission(device) == true,
            locked = isLocked,
            held = held,
            connectOnPlugIn = connectOnPlugIn,
            autoStart = autoStartOnUsb,
            mayAttempt = mayAttempt,
            switchLive = UsbSwitchClaim.isLive(),
        )

        when (decision) {
            UsbAttachActivityPolicy.Action.START_SERVICE -> {
                AppLog.i("Usb in accessory mode and has permission. Starting AapService.")
                ContextCompat.startForegroundService(this, Intent(this, AapService::class.java).apply {
                    action = AapService.ACTION_CHECK_USB
                })
                finish()
                return
            }
            UsbAttachActivityPolicy.Action.OPEN_HOME -> {
                AppLog.i("UsbAttachedActivity: Connect when plugged in is off; opening the app, not switching ${deviceCompat.uniqueName}")
                openHome("USB plug-in")
                finish()
                return
            }
            UsbAttachActivityPolicy.Action.HAND_OFF -> {
                when {
                    held -> AppLog.i("UsbAttachedActivity: settings on show or the status pill's X holding; handing ${device.deviceName} to the service")
                    // Android grants before it starts us, so the device changed; the service decides.
                    accessory -> AppLog.i("UsbAttachedActivity: accessory mode without permission, handing it to the service")
                    else -> {
                        launchForAutoStart(isLocked, autoStartOnUsb, deviceCompat)
                        // This activity is what the system launches once it is the default
                        // handler, so a bare finish() here would drop the plug-in.
                        AppLog.i("Not switching ${deviceCompat.uniqueName} here (not on the allow list); letting the service decide")
                    }
                }
                handOffToService()
                finish()
                return
            }
            UsbAttachActivityPolicy.Action.FINISH -> {
                when {
                    accessory -> AppLog.w("Usb in accessory mode, but the user has not unlocked yet and a session needs credential storage. Waiting for unlock.")
                    !connectOnPlugIn -> AppLog.i("UsbAttachedActivity: Connect when plugged in is off and the device is locked; leaving ${deviceCompat.uniqueName}")
                    isLocked && !autoStartOnUsb -> AppLog.w("Device is locked and USB auto-start is disabled. Cannot check allowed devices. Finishing.")
                    else -> {
                        launchForAutoStart(isLocked, autoStartOnUsb, deviceCompat)
                        // Each attach lands in a fresh instance (noHistory, no task affinity), so without this
                        // the 0x2D00 re-enumeration would start a second switch on the same device.
                        AppLog.i("A USB accessory switch is already in flight; leaving ${deviceCompat.uniqueName} to it")
                    }
                }
                finish()
                return
            }
            UsbAttachActivityPolicy.Action.SWITCH -> launchForAutoStart(isLocked, autoStartOnUsb, deviceCompat)
        }

        val usbManager = UsbDeviceCompat.usbManager(this) ?: run { finish(); return }

        // The switch starts the USB attempt, so it takes the connection arbiter here, before the
        // stage is reported: standing the wireless stack down clears the status pill.
        val arbiterClaim = ConnectionArbiter.claim(
            ConnectionPriorityPolicy.Tier.USB, ConnectionPriorityPolicy.Owner.USB,
            "USB switch of ${deviceCompat.uniqueName}"
        )
        if (arbiterClaim == null) {
            finish()
            return
        }
        ConnectionStageTracker.beginAttempt(ConnectionStage.USB_ATTACHED)
        ConnectionStageTracker.report(ConnectionStage.USB_SWITCHING)

        val usbMode = UsbAccessoryMode(usbManager)
        AppLog.i("Switching USB device to accessory mode " + deviceCompat.uniqueName)
        ToastUtils.showToast(this, getString(R.string.switching_usb_accessory_mode, deviceCompat.uniqueName), Toast.LENGTH_SHORT)
        val useLibusb = settings?.useLibusb ?: false
        // The claim keeps the service's 2 s attach fallback off a device we are already switching.
        // noHistory means this activity can be finished out from under the thread, so the release
        // is in a finally and the claim expires on its own if even that is missed.
        UsbSwitchClaim.stake()
        Thread {
            val result = try {
                usbMode.connectAndSwitch(device, useLibusb).also { if (it) UsbSwitchClaim.noteSwitched() }
            } catch (e: Exception) {
                AppLog.e("AOA switch threw for ${deviceCompat.uniqueName}", e)
                false
            } finally {
                UsbSwitchClaim.release()
                ConnectionArbiter.release(arbiterClaim, sessionFormed = false)
            }
            runOnUiThread {
                if (result) {
                    ToastUtils.showToast(this, getString(R.string.success), Toast.LENGTH_SHORT)
                } else {
                    ToastUtils.showToast(this, getString(R.string.failed), Toast.LENGTH_SHORT)
                    if (!App.provide(this).commManager.isConnected) ConnectionStageTracker.clear()
                }
                finish()
            }
        }.start()
    }

    /**
     * Ask [AapService] to look at what is plugged in. Used wherever this activity declines to act
     * itself: the system delivered the attach to us and nobody else will hear about it otherwise.
     * It is a plug-in, so "Connect when plugged in" still decides whether the phone is switched.
     */
    private fun handOffToService() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, AapService::class.java).apply {
                action = AapService.ACTION_CHECK_USB
                putExtra(AapService.EXTRA_USB_TRIGGER, AapService.USB_TRIGGER_PLUG_IN)
            })
        } catch (e: Exception) {
            AppLog.w("Could not hand the USB attach to the service: ${e.message}")
        }
    }

    /** "Open the app when plugged in": raise the home screen beside the switch. */
    private fun launchForAutoStart(isLocked: Boolean, autoStartOnUsb: Boolean, deviceCompat: UsbDeviceCompat) {
        if (!autoStartOnUsb || !(isLocked || !App.provide(this).commManager.isConnected)) return
        AppLog.i("USB auto-start: launching app for ${deviceCompat.uniqueName}")
        openHome("USB auto-start")
    }

    private fun openHome(source: String) {
        try {
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(MainActivity.EXTRA_LAUNCH_SOURCE, source)
            })
        } catch (e: Exception) {
            AppLog.w("Could not start UI from $source: ${e.message}")
        }
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

        if (Settings.isUsbDeviceBlacklisted(this, device)) {
            AppLog.i("UsbAttachedActivity: Ignored blacklisted USB device in onNewIntent (${Settings.formatUsbVidPidDisplay(device.vendorId, device.productId)})")
            finish()
            return
        }

        if (!isLocked && App.provide(this).commManager.connectionState.value !is CommManager.ConnectionState.TransportStarted) {
            // A normal-mode re-attach used to be dropped here. noHistory means most re-attaches
            // arrive as a fresh onCreate instead, but one that does reach this instance is the
            // same dead end as declining in onCreate: nobody else is told.
            if (UsbDeviceCompat.isInAccessoryMode(device)) {
                AppLog.i("Usb in accessory mode")
            } else {
                AppLog.i("Usb re-attached in normal mode; asking the service to check it")
            }
            handOffToService()
        } else {
            AppLog.e("Thread already running")
        }

        finish()
    }
}
