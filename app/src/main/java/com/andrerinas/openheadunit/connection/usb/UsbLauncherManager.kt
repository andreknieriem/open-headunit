package com.andrerinas.openheadunit.connection.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.AutoConnectHoldPolicy
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.ConnectionArbiter
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy.Owner
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy.Tier
import com.andrerinas.openheadunit.connection.ConnectionStage
import com.andrerinas.openheadunit.connection.ConnectionStageTracker
import com.andrerinas.openheadunit.main.SettingsActivity
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ToastUtils
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Master class for USB-related connection functionality.
 */
class UsbLauncherManager(val service: AapService) {

    var isRegistered = false
    private lateinit var receiver: UsbReceiver
    var projectionHandshakeFailures = 0

    /**
     * Guards against duplicate [UsbAccessoryMode.connectAndSwitch] calls AND duplicate
     * [connectWithRetry] calls for devices already in accessory mode.
     *
     * Set to `true` synchronously on the main thread before launching any background
     * USB connect/switch coroutine. Checked in [checkAlreadyConnected] to prevent
     * multiple concurrent connection attempts on the same device.
     * Cleared in the coroutine's finally block, or on disconnect.
     */
    private val isSwitchingToProjection = AtomicBoolean(false)

    fun isSwitchingToProjection() = this.isSwitchingToProjection.get()

    /**
     * True while `UsbAttachedActivity` is running its own AOA switch, so the attach fallback does
     * not start a second one. Deliberately not folded into [isSwitchingToProjection]: that would
     * also silence the accessory-attach connect and the detach re-sync, which are this path's
     * recovery rather than its competition.
     */
    fun isActivitySwitchInFlight() = UsbSwitchClaim.isLive()

    /** Ending an attempt, by any path, hands the connection arbiter back. */
    fun setSwitchingToProjection(value: Boolean) {
        isSwitchingToProjection.set(value)
        if (value) return
        val claim = attemptClaim
        attemptClaim = null
        // An opened transport's own claim has taken over by now, so this one formed nothing.
        ConnectionArbiter.release(claim, sessionFormed = false)
    }

    @Volatile private var attemptClaim: ConnectionArbiter.Claim? = null

    /** Claims the arbiter and marks an attempt in flight; false means a higher attempt holds it. */
    fun beginAttempt(tier: Tier, what: String): Boolean {
        val claim = ConnectionArbiter.claim(tier, Owner.USB, what) ?: return false
        attemptClaim = claim
        isSwitchingToProjection.set(true)
        return true
    }

    /** A higher attempt took over: end this one without the status pill's X semantics. */
    fun preemptAttempt() {
        attemptJob?.cancel()
        attemptJob = null
    }

    /** The status pill's X during a USB attempt: no automatic USB connection until one is asked for. */
    @Volatile var cancelledByUser = false
        private set

    /** The attempt this manager launched last, so the X can end its retry loop too. */
    internal var attemptJob: Job? = null

    fun stopForUser() {
        cancelledByUser = true
        attemptJob?.cancel()
        attemptJob = null
    }

    /** An explicit ask for USB. No-op unless the X is holding. */
    fun liftUserCancel(reason: String) {
        if (!cancelledByUser) return
        AppLog.i("UsbLauncher: $reason, so the stop from the status pill is lifted.")
        cancelledByUser = false
    }

    fun register() {
        if (isRegistered)
            return

        isRegistered = true
        receiver = UsbReceiver(UsbLauncherListener(this))

        ContextCompat.registerReceiver(
            service, receiver,
            UsbReceiver.createFilter(),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun unregister() {
        if (!isRegistered)
            return

        isRegistered = false

        try { service.unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    /**
     * A USB attempt ended with no session. The wireless stack used to clear the pill on its way up
     * or down, and on a cable-only unit it never runs, so the last USB step would stay on screen.
     */
    private fun endUsbAttemptStage() {
        if (App.provide(service).commManager.isConnected) return
        ConnectionStageTracker.clear()
    }

    private fun requestPermission(device: UsbDevice) {
        val usbManager = service.getSystemService(Context.USB_SERVICE) as UsbManager
        val permissionIntent = UsbReceiver.createPermissionPendingIntent(service)

        AppLog.i("Requesting USB permission for ${UsbDeviceCompat(device).uniqueName}")
        ConnectionStageTracker.report(ConnectionStage.USB_SWITCHING)

        try {
            ToastUtils.showToast(service, service.getString(R.string.requesting_usb_permission), Toast.LENGTH_SHORT)
            usbManager.requestPermission(device, permissionIntent)
        } catch (e: Exception) {
            AppLog.e("Failed to request USB permission: ${e.message}. This device might not support USB permission dialogs.", e)
            ToastUtils.showToast(service, service.getString(R.string.error_usb_permission_failed), Toast.LENGTH_LONG)
        }
    }

    /**
     * Called when a handshake fails. If an accessory-mode device is still present,
     * it's likely a stale wireless AA dongle. Force re-enumeration by sending AOA
     * descriptors — this resets the dongle's USB state so the next connection
     * starts with clean buffers.
     */
    fun onHandshakeFailed() {
        val usbManager = service.getSystemService(Context.USB_SERVICE) as UsbManager
        val accessoryDevice = usbManager.deviceList.values.firstOrNull {
            UsbDeviceCompat.isInAccessoryMode(it)
        } ?: return

        projectionHandshakeFailures++
        val deviceName = UsbDeviceCompat(accessoryDevice).uniqueName
        AppLog.w("Handshake failed on accessory device $deviceName (failure #$projectionHandshakeFailures)")

        if (projectionHandshakeFailures > MAX_STALE_ACCESSORY_RETRIES) {
            AppLog.i("Stale accessory detected: forcing re-enumeration via AOA descriptors for $deviceName")
            projectionHandshakeFailures = 0
            val settings = App.provide(service).settings
            val usbMode = UsbAccessoryMode(usbManager)
            if (!beginAttempt(Tier.USB, "USB re-enumeration of $deviceName")) return
            attemptJob = service.serviceScope.launch(Dispatchers.IO) {
                try {
                    if (usbMode.connectAndSwitch(accessoryDevice, settings.useLibusb)) {
                        AppLog.i("AOA re-enumeration requested for stale device $deviceName")
                    } else {
                        AppLog.w("AOA re-enumeration failed for $deviceName")
                    }
                } catch (e: Exception) {
                    AppLog.e("AOA re-enumeration for $deviceName failed with exception", e)
                } finally {
                    setSwitchingToProjection(false)
                    endUsbAttemptStage()
                }
            }
        }
    }

    /**
     * Scans currently connected USB devices and connects to any that are already in
     * Android Open Accessory (AOA) mode, or attempts to switch a known device into AOA mode.
     *
     * @param force When `true`, bypasses the [autoConnectLastSession] guard. Use `true` when
     *              called in response to an actual USB attach event or from [UsbAttachedActivity],
     *              because the user has explicitly plugged in a device. Use `false` (default)
     *              for the startup scan in [onCreate].
     * @param userRequested The user asked for this by hand, which lifts the status pill's X.
     */
    fun checkAlreadyConnected(force: Boolean = false, userRequested: Boolean = false) {
        val settings = App.provide(service).settings
        val commManager = App.provide(service).commManager
        val lastSession = settings.autoConnectLastSession
        val singleUsb = settings.autoConnectSingleUsbDevice
        val usbAutoStart = settings.autoStartOnUsb

        if (!force && !lastSession && !singleUsb && !usbAutoStart) return
        if (commManager.isConnected || isSwitchingToProjection.get()) return
        if (commManager.connectionState.value is CommManager.ConnectionState.Connecting) {
            ConnectionArbiter.holdUsbCheck()
            return
        }

        if (userRequested) liftUserCancel("a USB connection was asked for")
        val tier = if (userRequested) Tier.USER else Tier.USB
        when (AutoConnectHoldPolicy.decide(
            settingsVisible = SettingsActivity.isVisible,
            sessionLive = false, // Returned above.
            cancelledByUser = cancelledByUser,
            userRequested = userRequested,
        )) {
            AutoConnectHoldPolicy.Verdict.HOLD_FOR_SETTINGS -> {
                AppLog.i("UsbLauncher: USB auto-connect held while the settings screen is open; " +
                    "checking again when it closes.")
                service.holdUsbCheckForSettings()
                return
            }
            AutoConnectHoldPolicy.Verdict.CANCELLED_BY_USER -> {
                AppLog.i("UsbLauncher: USB auto-connect refused: the status pill's X holds it " +
                    "until the USB button.")
                return
            }
            AutoConnectHoldPolicy.Verdict.PROCEED -> Unit
        }

        val usbManager = service.getSystemService(Context.USB_SERVICE) as UsbManager
        UsbDeviceDiagnostics.logDeviceList(service, usbManager, "service scan (force=$force)")
        val deviceList = usbManager.deviceList.values.filter { UsbDeviceCompat.isConnectable(service, it) }

        // Check for devices already in accessory mode first.
        // After AOA switch the device re-enumerates and appears as a new USB device — we must
        // request permission for this new device before openDevice(), or SecurityException occurs.
        for (device in deviceList) {
            if (UsbDeviceCompat.isInAccessoryMode(device)) {
                val deviceName = UsbDeviceCompat(device).uniqueName
                AppLog.i("Found device already in accessory mode: $deviceName")
                if (!beginAttempt(tier, "USB $deviceName")) return
                ConnectionStageTracker.beginAttempt(ConnectionStage.USB_ATTACHED)
                attemptJob = service.serviceScope.launch {
                    try {
                        if (awaitAccessoryPermission(usbManager, device, deviceName)) {
                            connectWithRetry(device, tier = tier)
                        }
                    } finally {
                        setSwitchingToProjection(false)
                        endUsbAttemptStage()
                    }
                }
                return
            }
        }

        // Last-session mode: reconnect to a known/allowed device
        if (lastSession) {
            for (device in deviceList) {
                val deviceCompat = UsbDeviceCompat(device)
                if (settings.isConnectingDevice(deviceCompat)) {
                    if (usbManager.hasPermission(device)) {
                        AppLog.i("Found known USB device with permission: ${deviceCompat.uniqueName}. Switching to accessory mode.")
                        if (!beginAttempt(tier, "USB switch of ${deviceCompat.uniqueName}")) return
                        ConnectionStageTracker.beginAttempt(ConnectionStage.USB_ATTACHED)
                        ConnectionStageTracker.report(ConnectionStage.USB_SWITCHING)
                        val usbMode = UsbAccessoryMode(usbManager)
                        attemptJob = service.serviceScope.launch(Dispatchers.IO) {
                            try {
                                if (usbMode.connectAndSwitch(device, settings.useLibusb)) {
                                    AppLog.i("Successfully requested switch to accessory mode for ${deviceCompat.uniqueName}")
                                } else {
                                    AppLog.w("connectAndSwitch failed for ${deviceCompat.uniqueName}")
                                }
                            } finally {
                                setSwitchingToProjection(false)
                                endUsbAttemptStage()
                            }
                        }
                        return
                    } else {
                        AppLog.i("Found known USB device but no permission: ${deviceCompat.uniqueName}, requesting...")
                        requestPermission(device)
                        return
                    }
                }
            }
        }

        // USB auto-start mode: attempt AOA switch for any single non-accessory device
        if (usbAutoStart) {
            val nonAccessoryDevices = deviceList.filter { !UsbDeviceCompat.isInAccessoryMode(it) }
            if (nonAccessoryDevices.size == 1) {
                performSingleConnect(nonAccessoryDevices[0], tier)
                return
            }
        }

        // Single-USB mode: connect if there's exactly one candidate device.
        // If the user has marked specific devices as "Allowed" in the USB list,
        // only count those — so non-AA peripherals (dashcams, USB audio, etc.)
        // don't prevent auto-connect. Falls back to counting all devices when
        // no devices have been explicitly allowed (fresh install).
        if (singleUsb) {
            val nonAccessoryDevices = deviceList.filter { !UsbDeviceCompat.isInAccessoryMode(it) }
            val allowed = settings.allowedDevices
            val candidates = if (allowed.isNotEmpty()) {
                nonAccessoryDevices.filter { allowed.contains(UsbDeviceCompat(it).uniqueName) }
            } else {
                nonAccessoryDevices
            }
            if (allowed.isNotEmpty() && candidates.size != nonAccessoryDevices.size) {
                AppLog.i("Single USB auto-connect: ${nonAccessoryDevices.size} USB device(s) present, ${candidates.size} allowed")
            }
            if (candidates.size == 1) {
                performSingleConnect(candidates[0], tier)
                return
            }
        }

        // Fallback: if force=true and exactly one Android phone is attached in normal mode,
        // switch it to accessory mode. This handles cases where UsbAttachedActivity didn't fire.
        //
        // [BUG_FIX] This used to require vendor id 0x18D1, so it fired for a Pixel and for nothing
        // else; every other make reached the end of this function having done nothing and could
        // only be connected by hand. deviceList is already isAndroidDevice()-filtered.
        if (force) {
            val nonAccessoryDevices = deviceList.filter { !UsbDeviceCompat.isInAccessoryMode(it) }
            val allowList = settings.allowedDevices
            val candidates = nonAccessoryDevices.filter {
                UsbAttachPolicy.shouldAttemptAoaSwitch(
                    isGoogleVendor = it.vendorId == 0x18D1,
                    autoStartOnUsb = usbAutoStart,
                    allowListConfigured = allowList.isNotEmpty(),
                    deviceAllowed = settings.isConnectingDevice(UsbDeviceCompat(it)),
                )
            }
            if (candidates.size == 1) {
                AppLog.i("Fallback: force=true and found single normal-mode Android device ${UsbDeviceCompat(candidates[0]).uniqueName}. Switching to accessory mode.")
                performSingleConnect(candidates[0], tier)
            } else if (candidates.isNotEmpty()) {
                AppLog.i("Fallback: force=true but ${candidates.size} candidate USB devices are attached; not guessing which is the phone")
            }
        }
    }

    private fun performSingleConnect(device: UsbDevice, tier: Tier) {
        val settings = App.provide(service).settings
        val usbManager = service.getSystemService(Context.USB_SERVICE) as UsbManager

        if (usbManager.hasPermission(device)) {
            val deviceName = UsbDeviceCompat(device).uniqueName
            AppLog.i("Single USB auto-connect: connecting to $deviceName")
            if (!beginAttempt(tier, "USB switch of $deviceName")) return
            val usbMode = UsbAccessoryMode(usbManager)
            attemptJob = service.serviceScope.launch(Dispatchers.IO) {
                try {
                    if (usbMode.connectAndSwitch(device, settings.useLibusb)) {
                        AppLog.i("Successfully requested switch to accessory mode for single USB device. Waiting for re-enumeration...")
                    } else {
                        AppLog.w("Single USB auto-connect: connectAndSwitch failed for $deviceName")
                    }
                } finally {
                    setSwitchingToProjection(false)
                    endUsbAttemptStage()
                }
            }
        } else {
            AppLog.i("Single USB auto-connect: device found but no permission, requesting...")
            requestPermission(device)
        }
    }

    /**
     * Wait briefly for the manifest auto-grant on a freshly re-enumerated 0x2D00 before falling
     * back to a dialog. The grant arrives with the activity the system launches for the attach, so
     * the service's own receiver reaches this a few hundred ms early and used to raise a prompt the
     * dongle did not stay in accessory mode long enough to answer.
     */
    private suspend fun awaitAccessoryPermission(
        usbManager: UsbManager,
        device: UsbDevice,
        deviceName: String,
    ): Boolean {
        if (usbManager.hasPermission(device)) return true

        val startedAt = System.currentTimeMillis()
        while (UsbAccessoryHandoffPolicy.shouldKeepPollingForPermission(System.currentTimeMillis() - startedAt)) {
            delay(UsbAccessoryHandoffPolicy.PERMISSION_POLL_INTERVAL_MS)
            if (usbManager.hasPermission(device)) {
                AppLog.i("Accessory-mode permission arrived after ${System.currentTimeMillis() - startedAt}ms: $deviceName")
                ConnectionStageTracker.report(ConnectionStage.PHONE_ANSWERED)
                return true
            }
        }

        AppLog.i("Accessory-mode device has no permission (re-enumerated); requesting permission: $deviceName")
        requestPermission(device)
        return false
    }

    /**
     * Attempts a USB connection up to [maxRetries] times with a 1.5 s delay between attempts.
     *
     * USB accessories occasionally fail on the first attach (the device hasn't fully
     * enumerated yet), so retrying is necessary for reliability.
     */
    suspend fun connectWithRetry(device: UsbDevice, maxRetries: Int = 3, tier: Tier = Tier.USB) {
        val commManager = App.provide(service).commManager
        var retryCount = 0
        var success = false

        while (retryCount <= maxRetries && !success) {
            if (retryCount > 0) {
                AppLog.i("Retrying USB connection (attempt ${retryCount + 1}/$maxRetries)...")
                delay(1500)
                // A USB reattach during the delay could have already started a new connection;
                // bail out to avoid two parallel retry loops competing on the same device.
                if (commManager.isConnected ||
                    commManager.connectionState.value is CommManager.ConnectionState.Connecting) return
            }
            commManager.connect(device, tier)
            success = commManager.connectionState.value is CommManager.ConnectionState.Connected
            retryCount++
        }
    }


    companion object {

        /** Max handshake failures on a stale accessory device before forcing AOA re-enumeration. */
        const val MAX_STALE_ACCESSORY_RETRIES = 1

        /** Delay before AapService tries to handle a normal-mode USB attach as a fallback
         *  when UsbAttachedActivity doesn't fire (common on Chinese MediaTek headunits). */
        const val ATTACH_FALLBACK_DELAY_MS = 2000L
    }
}
