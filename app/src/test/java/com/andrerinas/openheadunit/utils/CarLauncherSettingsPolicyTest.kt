package com.andrerinas.openheadunit.utils

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class CarLauncherSettingsPolicyTest {

    private var savedLogger: AppLog.Logger? = null

    @Before
    fun setUp() {
        savedLogger = AppLog.LOGGER
        AppLog.LOGGER = object : AppLog.Logger {
            override fun println(priority: Int, tag: String, msg: String) {}
        }
    }

    @After
    fun tearDown() {
        savedLogger?.let { AppLog.LOGGER = it }
    }

    @Test
    fun `when car launcher is not active, raw settings are respected`() {
        val mockContext = mock(Context::class.java)
        val mockPrefs = mock(SharedPreferences::class.java)
        val mockPm = mock(PackageManager::class.java)

        `when`(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockPrefs)
        `when`(mockContext.packageManager).thenReturn(mockPm)

        `when`(mockPrefs.getBoolean(eq(Settings.KEY_ENABLE_CAR_LAUNCHER), eq(false))).thenReturn(false)
        `when`(mockPrefs.getBoolean(eq("kill-on-disconnect"), eq(false))).thenReturn(true)
        `when`(mockPrefs.getBoolean(eq("reopen-on-reconnection"), eq(true))).thenReturn(true)
        `when`(mockPrefs.getInt(eq("aa-exit-action"), eq(Settings.ExitAction.OEM_LAUNCHER.value)))
            .thenReturn(Settings.ExitAction.OEM_LAUNCHER.value)

        val settings = Settings(mockContext)
        assertFalse(settings.isCarLauncherActive)
        assertTrue(settings.killOnDisconnect)
        assertTrue(settings.reopenOnReconnection)
        assertEquals(Settings.ExitAction.OEM_LAUNCHER, settings.aaExitAction)
    }

    @Test
    fun `when car launcher is active, redundant settings are overridden safely`() {
        val mockContext = mock(Context::class.java)
        val mockPrefs = mock(SharedPreferences::class.java)
        val mockPm = mock(PackageManager::class.java)

        `when`(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockPrefs)
        `when`(mockContext.packageManager).thenReturn(mockPm)

        // Car launcher is enabled
        `when`(mockPrefs.getBoolean(eq(Settings.KEY_ENABLE_CAR_LAUNCHER), eq(false))).thenReturn(true)
        // Stored settings that would conflict or cause unwanted behavior
        `when`(mockPrefs.getBoolean(eq("kill-on-disconnect"), eq(false))).thenReturn(true)
        `when`(mockPrefs.getBoolean(eq("reopen-on-reconnection"), eq(true))).thenReturn(true)
        `when`(mockPrefs.getInt(eq("aa-exit-action"), eq(Settings.ExitAction.OEM_LAUNCHER.value)))
            .thenReturn(Settings.ExitAction.OEM_LAUNCHER.value)

        val settings = Settings(mockContext)
        assertTrue(settings.isCarLauncherActive)
        // Overrides applied:
        assertFalse(settings.killOnDisconnect)
        assertFalse(settings.reopenOnReconnection)
        // OEM_LAUNCHER is mapped to APP_HOME:
        assertEquals(Settings.ExitAction.APP_HOME, settings.aaExitAction)
        // Raw preferences are preserved:
        assertTrue(settings.rawKillOnDisconnect)
        assertTrue(settings.rawReopenOnReconnection)
        assertEquals(Settings.ExitAction.OEM_LAUNCHER, settings.rawAaExitAction)
    }
}
