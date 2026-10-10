package com.andrerinas.openheadunit.connection.wifi.scan

import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.connection.wifi.scan.ScanControlSummary.Step
import com.andrerinas.openheadunit.connection.wifi.scan.WifiScanControl.State
import org.junit.Assert.*
import org.junit.Test

class ScanControlSummaryTest {
    private fun detail(state: State = State.READY, sdk: Int = 33, native: Boolean = true,
                       hotspot: Boolean = false, off: Boolean = false,
                       running: Boolean = true, allowed: Boolean = true,
                       legacy: Boolean = false, installed: Boolean = true) =
        ScanControlSummary.detail(state, sdk, native, hotspot, off, running, allowed, legacy, installed)

    @Test fun `ready and actively applied both read Enabled`() {
        assertEquals(R.string.wifi_scan_status_enabled, detail(State.READY))
        assertEquals(R.string.wifi_scan_status_enabled, detail(State.ACTIVE))
        assertEquals(R.string.wifi_scan_status_enabled, detail(State.ACTIVE, sdk = 30, hotspot = true, off = true))
        for (d in listOf(detail(State.READY), detail(State.ACTIVE))) {
            assertEquals(R.string.wifi_scan_status_enabled, ScanControlSummary.compact(d, enabled = true))
            assertEquals(R.string.wifi_scan_off, ScanControlSummary.compact(d, enabled = false))
        }
    }

    @Test fun `setup steps follow install, start, allow order`() {
        assertEquals(Step.INSTALL, ScanControlSummary.nextStep(installed = false, running = false, allowed = false))
        assertEquals(Step.START, ScanControlSummary.nextStep(installed = true, running = false, allowed = false))
        assertEquals(Step.ALLOW, ScanControlSummary.nextStep(installed = true, running = true, allowed = false))
        assertNull(ScanControlSummary.nextStep(installed = true, running = true, allowed = true))
        // A usable running Binder takes priority over a hidden manager package lookup.
        assertEquals(Step.ALLOW, ScanControlSummary.nextStep(installed = false, running = true, allowed = false))

        assertEquals(R.string.wifi_scan_summary_install, detail(installed = false, running = false, allowed = false))
        assertEquals(R.string.wifi_scan_summary_start, detail(running = false, allowed = false))
        assertEquals(R.string.wifi_scan_summary_permission, detail(allowed = false))
        assertEquals(R.string.wifi_scan_status_enabled, detail(installed = false))
    }

    @Test fun `legacy Shizuku needs an update rather than an impossible permission grant`() {
        assertEquals(R.string.wifi_scan_summary_update, detail(allowed = false, legacy = true))
    }

    @Test fun `setup reasons replace stale preparation states`() {
        assertEquals(R.string.wifi_scan_status_enabled, detail(State.NEEDS_SHIZUKU))
        assertEquals(R.string.wifi_scan_summary_start, detail(State.NEEDS_PERMISSION, running = false, allowed = false))
    }

    @Test fun `unavailable connection choices are explained even when switched off`() {
        for (state in listOf(State.OFF, State.READY, State.UNSUPPORTED)) {
            val native = detail(state, native = false, running = false)
            assertEquals(R.string.wifi_scan_summary_native, native)
            assertEquals(native, ScanControlSummary.compact(native, enabled = false))
            for (sdk in 26..32) {
                val unsupported = detail(state, sdk = sdk)
                assertEquals(R.string.wifi_scan_summary_unsupported, unsupported)
                assertEquals(unsupported, ScanControlSummary.compact(unsupported, enabled = false))
                assertEquals(R.string.wifi_scan_summary_wifi_off, detail(state, sdk = sdk, hotspot = true))
                assertEquals(R.string.wifi_scan_status_enabled, detail(state, sdk = sdk, hotspot = true, off = true))
            }
        }
    }

    @Test fun `Shizuku setup is reported before the hotspot Wi-Fi-off condition`() {
        assertEquals(R.string.wifi_scan_summary_start, detail(sdk = 30, hotspot = true, running = false, allowed = false))
        assertEquals(R.string.wifi_scan_summary_permission, detail(sdk = 30, hotspot = true, allowed = false))
    }

    @Test fun `modern Native transports do not require station Wi-Fi to be off`() {
        for (sdk in 33..36) for (hotspot in listOf(false, true)) for (off in listOf(false, true)) {
            assertEquals(R.string.wifi_scan_status_enabled, detail(sdk = sdk, hotspot = hotspot, off = off))
        }
    }

    @Test fun `session facts take precedence over readiness`() {
        val states = mapOf(State.WORKING to R.string.wifi_scan_status_working,
            State.RECOVERY to R.string.wifi_scan_summary_recovery,
            State.CHANGED to R.string.wifi_scan_summary_changed,
            State.FAILED to R.string.wifi_scan_summary_failed)
        for ((state, text) in states) {
            assertEquals(text, detail(state, native = false, running = false, allowed = false))
        }
    }

    @Test fun `pending rollback stays visible when the option is off`() {
        for (d in listOf(R.string.wifi_scan_summary_recovery, R.string.wifi_scan_summary_adb_recovery,
                R.string.wifi_scan_status_working)) {
            assertEquals(d, ScanControlSummary.compact(d, enabled = false))
        }
        for (d in listOf(R.string.wifi_scan_summary_changed, R.string.wifi_scan_summary_failed,
                R.string.wifi_scan_summary_permission)) {
            assertEquals(R.string.wifi_scan_off, ScanControlSummary.compact(d, enabled = false))
            assertEquals(d, ScanControlSummary.compact(d, enabled = true))
        }
    }
}
