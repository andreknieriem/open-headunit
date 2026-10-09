package com.andrerinas.openheadunit.connection

import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class SettingsRetryOwnershipTest {
    private fun save() = CommManager.ConnectionState.Disconnected(
        reason = CommManager.DisconnectReason.SETTINGS_RESTART, settingsRestartUntilMs = 30_000,
    )

    @Test fun `only failures explicitly owned by Save extend its retry permission`() {
        val saved = save()
        repeat(4) {
            val failedOpen = CommManager.ConnectionState.Disconnected(settingsRetryOwner = saved)
            assertTrue(saved.acceptsSettingsRestart(failedOpen))
            assertFalse(save().acceptsSettingsRestart(failedOpen))
        }
        assertFalse(saved.acceptsSettingsRestart(CommManager.ConnectionState.Connecting))
        assertFalse(saved.acceptsSettingsRestart(CommManager.ConnectionState.Connected))
        assertFalse(saved.acceptsSettingsRestart(CommManager.ConnectionState.Disconnected()))
        assertFalse(saved.acceptsSettingsRestart(save()))
    }

    @Test fun `cancelling original Save revokes child failures and tracked USB work`() {
        val saved = save()
        val failedOpen = CommManager.ConnectionState.Disconnected(settingsRetryOwner = saved)
        val retry = Job()
        saved.trackSettingsLaunch(retry)
        saved.cancelSettingsRestart()
        assertTrue(retry.isCancelled)
        assertFalse(saved.acceptsSettingsRestart(failedOpen))
        assertFalse(saved.acceptsSettingsRestart(saved))
    }

    @Test fun `Native wake release does not cancel Save or extend its deadline`() {
        val saved = save()
        assertTrue(saved.holdsSettingsWake(1))
        saved.releaseSettingsWake()
        assertFalse(saved.holdsSettingsWake(1))
        assertTrue(saved.acceptsSettingsRestart(saved))
        assertEquals(30_000L, saved.settingsRestartUntilMs)
        val later = save()
        assertTrue(later.holdsSettingsWake(1))
    }
}
