package com.andrerinas.openheadunit.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.main.FloatingButtonManager
import com.andrerinas.openheadunit.connection.usb.UsbDeviceCompat

class BootCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action !in BOOT_ACTIONS) return

        AppLog.i("Boot auto-start: received action=$action")

        val isLocked = !App.isUserUnlocked(context)

        if (isLocked) {
            AppLog.w("BootCompleteReceiver: Device is locked. Cannot start AapService yet. Waiting for user unlock.")
            return
        }

        // BOOT_COMPLETED is delivered when first unlock finishes, including to a replacement
        // process, and grants the foreground-service start exemption. USER_UNLOCKED does not.
        // App has preserved this pending input's DP decision before refreshing its CE mirrors.
        val systemBoot = action == Intent.ACTION_BOOT_COMPLETED
        val boot = bootNumber(context)
        val unlockPrefs = usbUnlockPrefs(context)
        if (systemBoot && boot >= 0 && unlockPrefs.getInt(KEY_HANDLED_BOOT, -1) == boot) return
        val pendingUsb = systemBoot && unlockPrefs.contains(KEY_PENDING_BOOT) &&
            unlockPrefs.getInt(KEY_PENDING_BOOT, -1) == boot

        val settings = Settings(context)
        val bootEnabled = Settings.isAutoStartOnBootEnabled(context)
        val screenOnEnabled = Settings.isAutoStartOnScreenOnEnabled(context)
        val usbEnabled = if (pendingUsb && unlockPrefs.contains(KEY_UNLOCK_USB_ENABLED))
            unlockPrefs.getBoolean(KEY_UNLOCK_USB_ENABLED, false)
            else Settings.isAutoStartOnUsbEnabled(context)
        val wifiEnabled = Settings.isAutoStartOnWifiEnabled(context)
        val floatingButtonEnabled = settings.enableFloatingButton

        if (floatingButtonEnabled) {
            AppLog.i("Boot auto-start: starting floating button overlay (trigger=$action)")
            FloatingButtonManager.update(context)
        }

        // Unlock completion / boot is the input. Mint its authority here at the exempt broadcast,
        // not at locked attach and not again when the service receives this asynchronous relay.
        // Every start below scans USB, including the ordinary boot-start branch.
        fun bootIntent(action: String?) = AapService.usbCheckIntent(context,
            com.andrerinas.openheadunit.App.provide(context).commManager.usbRecheckNow(), action)
        var startupAccepted = true
        if (bootEnabled) {
            // Take a strike before starting. The service clears it once this run has lasted long
            // enough to count as healthy; if the device dies first, the strike stands and the next
            // boot is one closer to pausing wireless bring-up. Counted here rather than in the
            // service because only this side knows the start came from a boot — EXTRA_BOOT_START
            // does not reach AapService until onStartCommand, after onCreate has already run.
            val previousStrikes = Settings.getBootLoopStrikes(context)
            val strikes = BootLoopPolicy.nextStrikes(previousStrikes)
            Settings.setBootLoopStrikes(context, strikes)
            AppLog.i("Boot auto-start: starting AapService with BOOT_START (trigger=$action, boot-start #$strikes since the last healthy run)")
            val serviceIntent = bootIntent(null).apply {
                putExtra(EXTRA_BOOT_START, true)
            }
            // A refused start never runs the service that clears the strike, so give it back.
            startupAccepted = startService(context, serviceIntent, action)
            if (!startupAccepted) Settings.setBootLoopStrikes(context, previousStrikes)
        } else if (screenOnEnabled) {
            // "Start on screen on" needs the service alive to register its dynamic
            // SCREEN_ON receiver. On Quick Boot devices this is a real reboot, so
            // the service must be started after boot to listen for future SCREEN_ON.
            AppLog.i("Boot auto-start: screen-on auto-start enabled, starting AapService to register SCREEN_ON receiver (trigger=$action)")
            val serviceIntent = bootIntent(null)
            startupAccepted = startService(context, serviceIntent, action)
        } else if (usbEnabled && (!pendingUsb || UsbDeviceCompat.usbManager(context)
                ?.deviceList?.values?.any {
                    UsbDeviceCompat.isConnectable(context, it)
                } == true)) {
            // On hibernating head units, USB_DEVICE_ATTACHED may not fire after wake.
            // Start the service in the background so it can register its UsbReceiver
            // and check for already-connected USB devices.
            AppLog.i("Boot auto-start: USB auto-start enabled, starting AapService to check USB (trigger=$action)")
            val serviceIntent = bootIntent(AapService.ACTION_CHECK_USB)
            startupAccepted = startService(context, serviceIntent, action)
        } else if (wifiEnabled) {
            // Start the service to listen for WiFi connectivity changes dynamically.
            AppLog.i("Boot auto-start: WiFi auto-start enabled, starting AapService to listen for WiFi (trigger=$action)")
            val serviceIntent = bootIntent(null)
            startupAccepted = startService(context, serviceIntent, action)
        } else if (!floatingButtonEnabled) {
            AppLog.i("Boot auto-start: disabled, skipping")
        }
        // Consume only an accepted startup or an input that has nothing eligible to start. A
        // refused platform call is not completion; no retry is scheduled and no new authority is
        // minted. A later system broadcast remains a new input, rather than a hidden retry.
        if (pendingUsb && startupAccepted) {
            unlockPrefs.edit().remove(KEY_PENDING_BOOT).remove(KEY_UNLOCK_USB_ENABLED)
                .putInt(KEY_HANDLED_BOOT, boot).commit()
        }
    }

    // From API 31 a background start can be refused, and API 35 also sends the boot broadcasts
    // when a stopped app first starts, without the boot exemption. Log it rather than crash.
    private fun startService(context: Context, intent: Intent, trigger: String?): Boolean = try {
        ContextCompat.startForegroundService(context, intent)
        true
    } catch (e: IllegalStateException) {
        AppLog.w("Boot auto-start: Android refused to start AapService (trigger=$trigger): ${e.message}")
        false
    }

    companion object {
        const val EXTRA_BOOT_START = "com.andrerinas.openheadunit.EXTRA_BOOT_START"

        private const val USB_UNLOCK_PREFS = "usb-unlock"
        private const val KEY_PENDING_BOOT = "pending-boot"
        private const val KEY_HANDLED_BOOT = "handled-boot"
        private const val KEY_UNLOCK_USB_ENABLED = "unlock-usb-enabled"

        private fun usbUnlockPrefs(context: Context): SharedPreferences =
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                context.createDeviceProtectedStorageContext() else context)
                .getSharedPreferences(USB_UNLOCK_PREFS, Context.MODE_PRIVATE)

        private fun bootNumber(context: Context): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                AndroidSettings.Global.getInt(context.contentResolver, AndroidSettings.Global.BOOT_COUNT, -1)
            else -1

        /**
         * Called for an input the activity already observed while locked. Do not re-read the lock:
         * UserManager can change before its Main-thread unlock callback runs and drains this input.
         * This stores data only; it never constructs the app component.
         */
        internal fun deferLockedUsb(context: Context) {
            if (!Settings.isAutoStartOnUsbEnabled(context)) return
            usbUnlockPrefs(context).edit().putInt(KEY_PENDING_BOOT, bootNumber(context))
                .remove(KEY_UNLOCK_USB_ENABLED).commit()
        }

        /**
         * Preserve the unlock-time DP decision before App refreshes its CE mirrors. This is only
         * data preparation: a registered USER_UNLOCKED callback cannot start a foreground service.
         * Do not overwrite a prepared decision when the process is replaced before BOOT_COMPLETED.
         */
        internal fun prepareLockedUsbUnlock(context: Context) {
            if (!App.isUserUnlocked(context)) return
            val prefs = usbUnlockPrefs(context)
            if (!prefs.contains(KEY_PENDING_BOOT)) return
            if (prefs.getInt(KEY_PENDING_BOOT, -1) != bootNumber(context)) {
                prefs.edit().remove(KEY_PENDING_BOOT).remove(KEY_UNLOCK_USB_ENABLED).commit()
                return
            }
            if (!prefs.contains(KEY_UNLOCK_USB_ENABLED)) {
                prefs.edit().putBoolean(KEY_UNLOCK_USB_ENABLED, Settings.isAutoStartOnUsbEnabled(context)).commit()
            }
        }

        private val BOOT_ACTIONS = setOf(
            // Standard Android boot
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            // Generic / OEM quick boot
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            // MediaTek IPO (Instant Power On)
            "com.mediatek.intent.action.QUICKBOOT_POWERON",
            "com.mediatek.intent.action.BOOT_IPO",
            // FYT / GLSX head units (ACC ignition wake)
            "com.fyt.boot.ACCON",
            "com.glsx.boot.ACCON",
            "android.intent.action.ACTION_MT_COMMAND_SLEEP_OUT",
            // Microntek / MTCD / PX3 head units (ACC wake)
            "com.cayboy.action.ACC_ON",
            "com.carboy.action.ACC_ON",
            // XYAuto head units (ACC wake)
            "xy.android.acc.on",
            // Autochips / MediaTek QuickBoot units (ACC wake)
            "autochips.intent.action.QB_POWERON"
        )
    }
}
