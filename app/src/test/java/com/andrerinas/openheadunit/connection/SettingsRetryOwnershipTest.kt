package com.andrerinas.openheadunit.connection

import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import kotlin.coroutines.CoroutineContext

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

    private object Never : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
    }

    /** A USB attempt of [save], open at [state]; returns the state after one ordinary end. */
    private fun endAt(state: CommManager.ConnectionState, save: CommManager.ConnectionState.Disconnected?,
        isUserExit: Boolean = false, explicit: CommManager.ConnectionState.Disconnected? = null,
    ): CommManager.ConnectionState.Disconnected {
        val savedLog = AppLog.LOGGER
        AppLog.LOGGER = object : AppLog.Logger { override fun println(priority: Int, tag: String, msg: String) = Unit }
        try {
            val manager = mock(CommManager::class.java, CALLS_REAL_METHODS)
            val states = MutableStateFlow(state)
            fun field(name: String, value: Any?) = CommManager::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.set(manager, value)
            field("transportLifecycleLock", Any())
            field("_connectionState", states)
            field("connectionState", states)
            field("usbRecoveryOwner", Any())
            field("_scope", CoroutineScope(SupervisorJob() + Never))
            field("settings", mock(Settings::class.java))
            field("physicalConnectionReached", true)
            field("usbSaveOwner", save)
            manager.disconnect(sendByeBye = false, isUserExit = isUserExit, honorKillOnDisconnect = false, settingsRetryOwner = explicit)
            return states.value as CommManager.ConnectionState.Disconnected
        } finally { AppLog.LOGGER = savedLog }
    }

    @Test fun `a Save survives an end after the USB open and before SSL`() {
        val saved = save()
        val ended = endAt(CommManager.ConnectionState.StartingTransport, saved, explicit = saved)
        assertSame(saved, ended.settingsRetryOwner)
    }

    @Test fun `a detach with no owner keeps the attempt's Save`() {
        val saved = save()
        assertSame(saved, endAt(CommManager.ConnectionState.Connected, saved).settingsRetryOwner)
        assertSame(saved, endAt(CommManager.ConnectionState.StartingTransport, saved).settingsRetryOwner)
    }

    @Test fun `an end after SSL drops the Save`() {
        assertNull(endAt(CommManager.ConnectionState.HandshakeComplete, save()).settingsRetryOwner)
    }

    @Test fun `a user exit drops the Save`() {
        assertNull(endAt(CommManager.ConnectionState.Connected, save(), isUserExit = true).settingsRetryOwner)
    }
}
