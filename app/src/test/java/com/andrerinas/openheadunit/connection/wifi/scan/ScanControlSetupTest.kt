package com.andrerinas.openheadunit.connection.wifi.scan

import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.connection.wifi.scan.WifiScanControl.State
import org.junit.Assert.*
import org.junit.Test

class ScanControlSetupTest {
    private fun actions(installed: Boolean = true, running: Boolean = false, allowed: Boolean = false,
                        legacy: Boolean = false, fyt: Boolean = true, pending: Boolean = false,
                        busy: Boolean = false, state: State = State.OFF, supported: Boolean = true) =
        ScanControlSetup.actions(installed, running, allowed, legacy, fyt, pending, busy, state, supported)

    @Test fun `FYT setup works before enabling the option or selecting a supported transport`() {
        for (state in listOf(State.OFF, State.READY, State.UNSUPPORTED)) {
            val ui = actions(state = state, supported = false)
            assertEquals(R.string.wifi_scan_detail_fyt_available, ui.fytDetail)
            assertTrue(ui.fytEnabled)
        }
    }

    @Test fun `FYT displays the actual prerequisite rather than hardware availability alone`() {
        assertEquals(R.string.wifi_scan_detail_fyt_install, actions(installed = false).fytDetail)
        assertEquals(R.string.wifi_scan_detail_fyt_running, actions(running = true).fytDetail)
        assertEquals(R.string.wifi_scan_detail_fyt_recovery, actions(pending = true).fytDetail)
        assertEquals(R.string.wifi_scan_detail_fyt_busy, actions(busy = true).fytDetail)
        assertEquals(R.string.wifi_scan_detail_fyt_busy, actions(state = State.WORKING).fytDetail)
        assertEquals(R.string.wifi_scan_detail_fyt_unavailable, actions(fyt = false).fytDetail)
    }

    @Test fun `available text always means the FYT button is enabled`() {
        val flags = listOf(false, true)
        for (installed in flags) for (running in flags) for (pending in flags)
            for (busy in flags) for (supported in flags) for (state in State.values()) {
                val ui = actions(installed = installed, running = running, pending = pending,
                    busy = busy, fyt = supported, state = state)
                val expected = installed && !running && !pending && !busy && supported && state != State.WORKING
                assertEquals(expected, ui.fytEnabled)
                assertEquals(expected, ui.fytDetail == R.string.wifi_scan_detail_fyt_available)
            }
    }

    @Test fun `manager action remains available when setup is needed even with the option off`() {
        assertEquals(R.string.wifi_scan_install, actions(installed = false).managerLabel)
        assertTrue(actions(installed = false).managerEnabled)
        assertEquals(R.string.wifi_scan_open_shizuku, actions().managerLabel)
        assertTrue(actions().managerEnabled)
        assertFalse(actions(busy = true).managerEnabled)
    }

    @Test fun `approval depends on service access rather than the scan option or transport`() {
        assertFalse(actions().permissionEnabled)
        assertTrue(actions(running = true, supported = false).permissionEnabled)
        assertTrue(actions(installed = false, running = true).permissionEnabled)
        assertFalse(actions(running = true, allowed = true).permissionEnabled)
        assertFalse(actions(running = true, legacy = true).permissionEnabled)
        assertFalse(actions(running = true, busy = true).permissionEnabled)
    }

    @Test fun `retry cannot substitute for missing setup but recovery works on unsupported transports`() {
        assertFalse(actions(state = State.FAILED).retryEnabled)
        assertFalse(actions(running = true, state = State.FAILED).retryEnabled)
        assertTrue(actions(running = true, allowed = true, state = State.FAILED).retryEnabled)
        assertFalse(actions(running = true, allowed = true, state = State.ACTIVE).retryEnabled)
        assertFalse(actions(running = true, allowed = true, state = State.FAILED, supported = false).retryEnabled)
        assertTrue(actions(running = true, allowed = true, state = State.RECOVERY, supported = false).retryEnabled)
    }
}
