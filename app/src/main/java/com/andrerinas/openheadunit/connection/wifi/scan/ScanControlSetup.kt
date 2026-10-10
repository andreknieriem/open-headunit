package com.andrerinas.openheadunit.connection.wifi.scan

import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.connection.wifi.scan.WifiScanControl.State

/** Setup is independent of the transport that will eventually use Shizuku. */
internal object ScanControlSetup {
    data class Actions(
        val managerLabel: Int,
        val managerEnabled: Boolean,
        val permissionEnabled: Boolean,
        val retryEnabled: Boolean,
        val fytDetail: Int
    ) {
        // The displayed reason and the button must describe the same decision, including
        // an already-running service and temporary recovery/operation blockers.
        val fytEnabled get() = fytDetail == R.string.wifi_scan_detail_fyt_available
    }

    fun actions(installed: Boolean, running: Boolean, allowed: Boolean, legacy: Boolean,
                fytSupported: Boolean, pendingAdb: Boolean, busy: Boolean, state: State,
                scanSupported: Boolean): Actions {
        val changing = busy || state == State.WORKING
        return Actions(
            managerLabel = if (installed) R.string.wifi_scan_open_shizuku else R.string.wifi_scan_install,
            managerEnabled = !changing,
            permissionEnabled = !changing && running && !legacy && !allowed,
            retryEnabled = !changing && running && !legacy && allowed &&
                (state == State.RECOVERY || (scanSupported && state in setOf(State.FAILED, State.CHANGED))),
            fytDetail = when {
                !fytSupported -> R.string.wifi_scan_detail_fyt_unavailable
                changing -> R.string.wifi_scan_detail_fyt_busy
                pendingAdb -> R.string.wifi_scan_detail_fyt_recovery
                running -> R.string.wifi_scan_detail_fyt_running
                !installed -> R.string.wifi_scan_detail_fyt_install
                else -> R.string.wifi_scan_detail_fyt_available
            }
        )
    }
}
