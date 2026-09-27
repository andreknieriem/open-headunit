package com.andrerinas.openheadunit.connection.wifi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsScreenPausePolicyTest {

    @Test
    fun `an open settings screen takes the stack down`() {
        assertTrue(
            SettingsScreenPausePolicy.pauses(
                settingsForeground = true, sessionLive = false, qrHold = false
            )
        )
    }

    @Test
    fun `a closed settings screen never pauses anything`() {
        for (sessionLive in listOf(false, true)) {
            for (qrHold in listOf(false, true)) {
                assertFalse(
                    "closed, session=$sessionLive qr=$qrHold",
                    SettingsScreenPausePolicy.pauses(false, sessionLive, qrHold)
                )
            }
        }
    }

    @Test
    fun `a live session is never torn down for the settings screen`() {
        assertFalse(
            SettingsScreenPausePolicy.pauses(
                settingsForeground = true, sessionLive = true, qrHold = false
            )
        )
    }

    @Test
    fun `the setup QR holds the stack up while its dialog is open`() {
        assertFalse(
            SettingsScreenPausePolicy.pauses(
                settingsForeground = true, sessionLive = false, qrHold = true
            )
        )
    }

    /** The PR #993 report: a visit that changed nothing must not restart AA Wireless Setup. */
    @Test
    fun `closing the screen with nothing wireless saved leaves the stack down`() {
        assertFalse(
            SettingsScreenPausePolicy.rearmsOnRelease(qrHold = false, wirelessRequestHeld = false)
        )
    }

    @Test
    fun `a Save or a Bluetooth arrival held behind the screen re-arms on close`() {
        assertTrue(
            SettingsScreenPausePolicy.rearmsOnRelease(qrHold = false, wirelessRequestHeld = true)
        )
    }

    @Test
    fun `the setup QR re-arms while the screen is still open`() {
        assertTrue(
            SettingsScreenPausePolicy.rearmsOnRelease(qrHold = true, wirelessRequestHeld = false)
        )
    }
}
