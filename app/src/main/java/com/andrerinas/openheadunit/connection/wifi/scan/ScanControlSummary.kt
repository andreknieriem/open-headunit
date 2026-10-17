package com.andrerinas.openheadunit.connection.wifi.scan

import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.connection.wifi.scan.WifiScanControl.State

/** Read-only presentation: readiness must not acquire a lease or prompt for permission. */
internal object ScanControlSummary {
    /** Ordered Shizuku setup steps shown on the sub-screen while the option is not ready. */
    enum class Step { INSTALL, START, ALLOW }

    /**
     * The first unfinished setup step, or null once Open Headunit is allowed. Pre-v11 Shizuku
     * cannot grant access, so it stays on [Step.ALLOW] and the screen asks for an update.
     */
    fun nextStep(installed: Boolean, running: Boolean, allowed: Boolean): Step? = when {
        allowed -> null
        // A running Binder proves Shizuku exists even if the package lookup is hidden.
        !installed && !running -> Step.INSTALL
        !running -> Step.START
        else -> Step.ALLOW
    }

    /**
     * One status line for both screens. Ready and actively applied share the same Enabled
     * label: the user only needs to know whether something still has to be done.
     */
    fun detail(state: State, sdk: Int, nativeHost: Boolean, hotspot: Boolean,
               stationOff: Boolean, running: Boolean, allowed: Boolean,
               legacyShizuku: Boolean = false, installed: Boolean = true): Int = when (state) {
        State.ACTIVE -> R.string.wifi_scan_status_enabled
        State.WORKING -> R.string.wifi_scan_status_working
        State.RECOVERY -> R.string.wifi_scan_summary_recovery
        State.CHANGED -> R.string.wifi_scan_summary_changed
        State.FAILED -> R.string.wifi_scan_summary_failed
        // READY means that no session owns the controller. It says nothing about whether
        // Shizuku is running or the selected transport supports this Android version.
        else -> when {
            !nativeHost -> R.string.wifi_scan_summary_native
            sdk < 26 -> R.string.wifi_scan_summary_unsupported
            // Shizuku setup comes before the Wi-Fi-off condition: it is done once, while
            // turning regular Wi-Fi off only matters when a hotspot connection starts.
            else -> when (nextStep(installed, running, allowed)) {
                Step.INSTALL -> R.string.wifi_scan_summary_install
                Step.START -> R.string.wifi_scan_summary_start
                Step.ALLOW -> if (legacyShizuku) R.string.wifi_scan_summary_update
                    else R.string.wifi_scan_summary_permission
                null -> if (sdk < 33 && hotspot && !stationOff) R.string.wifi_scan_summary_wifi_off
                    else R.string.wifi_scan_status_enabled
            }
        }
    }

    /** Applies the user's switch: a disabled option reads Off unless something needs attention. */
    fun compact(detail: Int, enabled: Boolean): Int = when (detail) {
        // Pending rollback and an in-progress restore stay visible after switching the option off.
        R.string.wifi_scan_status_working, R.string.wifi_scan_summary_recovery,
        R.string.wifi_scan_summary_adb_recovery,
        // Unavailability explains why switching the option on would not help.
        R.string.wifi_scan_summary_native, R.string.wifi_scan_summary_unsupported -> detail
        else -> if (enabled) detail else R.string.wifi_scan_off
    }
}
