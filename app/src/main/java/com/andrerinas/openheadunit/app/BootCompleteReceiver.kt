package com.andrerinas.openheadunit.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import android.os.UserManager
import android.os.Build
import com.andrerinas.openheadunit.main.FloatingButtonManager

class BootCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action !in BOOT_ACTIONS) return

        AppLog.i("Boot auto-start: received action=$action")

        val isLocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                      !(context.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked

        if (isLocked) {
            AppLog.w("BootCompleteReceiver: Device is locked. Cannot start AapService yet. Waiting for user unlock.")
            return
        }

        val settings = Settings(context)
        val bootEnabled = Settings.isAutoStartOnBootEnabled(context)
        val screenOnEnabled = Settings.isAutoStartOnScreenOnEnabled(context)
        val usbEnabled = Settings.isAutoStartOnUsbEnabled(context)
        val wifiEnabled = Settings.isAutoStartOnWifiEnabled(context)
        val floatingButtonEnabled = settings.enableFloatingButton

        if (floatingButtonEnabled) {
            AppLog.i("Boot auto-start: starting floating button overlay (trigger=$action)")
            FloatingButtonManager.update(context)
        }

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
            val serviceIntent = Intent(context, AapService::class.java).apply {
                putExtra(EXTRA_BOOT_START, true)
            }
            // A refused start never runs the service that clears the strike, so give it back.
            if (!startService(context, serviceIntent, action)) Settings.setBootLoopStrikes(context, previousStrikes)
        } else if (screenOnEnabled) {
            // "Start on screen on" needs the service alive to register its dynamic
            // SCREEN_ON receiver. On Quick Boot devices this is a real reboot, so
            // the service must be started after boot to listen for future SCREEN_ON.
            AppLog.i("Boot auto-start: screen-on auto-start enabled, starting AapService to register SCREEN_ON receiver (trigger=$action)")
            val serviceIntent = Intent(context, AapService::class.java)
            startService(context, serviceIntent, action)
        } else if (usbEnabled) {
            // On hibernating head units, USB_DEVICE_ATTACHED may not fire after wake.
            // Start the service in the background so it can register its UsbReceiver
            // and check for already-connected USB devices.
            AppLog.i("Boot auto-start: USB auto-start enabled, starting AapService to check USB (trigger=$action)")
            val serviceIntent = Intent(context, AapService::class.java).apply {
                this.action = AapService.ACTION_CHECK_USB
            }
            startService(context, serviceIntent, action)
        } else if (wifiEnabled) {
            // Start the service to listen for WiFi connectivity changes dynamically.
            AppLog.i("Boot auto-start: WiFi auto-start enabled, starting AapService to listen for WiFi (trigger=$action)")
            val serviceIntent = Intent(context, AapService::class.java)
            startService(context, serviceIntent, action)
        } else if (!floatingButtonEnabled) {
            AppLog.i("Boot auto-start: disabled, skipping")
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

        private val BOOT_ACTIONS = setOf(
            // Standard Android boot
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
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
