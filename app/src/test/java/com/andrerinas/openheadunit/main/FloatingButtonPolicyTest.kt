package com.andrerinas.openheadunit.main

import android.content.Context
import android.content.SharedPreferences
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.SettingsBackupManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

class FloatingButtonPolicyTest {

    @Test
    fun floatingButtonAndConnectionStatusModeAreOffByDefault() {
        val mockContext = mock(Context::class.java)
        val mockPrefs = mock(SharedPreferences::class.java)

        `when`(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockPrefs)
        `when`(mockPrefs.getBoolean(anyString(), anyBoolean())).thenAnswer { it.getArgument<Boolean>(1) }

        val settings = Settings(mockContext)

        assertFalse(settings.enableFloatingButton)
        assertFalse(settings.floatingButtonConnectionStatusMode)
        verify(mockPrefs).getBoolean(eq("enable-floating-button"), eq(false))
        verify(mockPrefs).getBoolean(eq("floating-button-connection-status-mode"), eq(false))
    }

    @Test
    fun floatingButtonSettingsClampsSizeBetween32And120Dp() {
        val mockContext = mock(Context::class.java)
        val mockPrefs = mock(SharedPreferences::class.java)
        val mockEditor = mock(SharedPreferences.Editor::class.java)

        `when`(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockPrefs)
        `when`(mockPrefs.edit()).thenReturn(mockEditor)
        `when`(mockEditor.putInt(anyString(), anyInt())).thenReturn(mockEditor)

        val settings = Settings(mockContext)

        // Lower bound clamp
        settings.floatingButtonSizeDp = 10
        verify(mockEditor).putInt(eq("floating-button-size-dp"), eq(32))

        // Upper bound clamp
        settings.floatingButtonSizeDp = 200
        verify(mockEditor).putInt(eq("floating-button-size-dp"), eq(120))

        // Valid in-range value
        settings.floatingButtonSizeDp = 72
        verify(mockEditor).putInt(eq("floating-button-size-dp"), eq(72))
    }

    @Test
    fun floatingButtonPositionCoordinatesAreClampedToPercentage() {
        val mockContext = mock(Context::class.java)
        val mockPrefs = mock(SharedPreferences::class.java)
        val mockEditor = mock(SharedPreferences.Editor::class.java)

        `when`(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockPrefs)
        `when`(mockPrefs.edit()).thenReturn(mockEditor)
        `when`(mockEditor.putInt(anyString(), anyInt())).thenReturn(mockEditor)

        val settings = Settings(mockContext)

        // X coordinate clamping
        settings.floatingButtonXPercent = -15
        verify(mockEditor).putInt(eq("floating-button-x-percent"), eq(0))

        settings.floatingButtonXPercent = 150
        verify(mockEditor).putInt(eq("floating-button-x-percent"), eq(100))

        // Y coordinate clamping
        settings.floatingButtonYPercent = -5
        verify(mockEditor).putInt(eq("floating-button-y-percent"), eq(0))

        settings.floatingButtonYPercent = 110
        verify(mockEditor).putInt(eq("floating-button-y-percent"), eq(100))
    }

    @Test
    fun floatingButtonOpacityIsClampedToPercentage() {
        val mockContext = mock(Context::class.java)
        val mockPrefs = mock(SharedPreferences::class.java)
        val mockEditor = mock(SharedPreferences.Editor::class.java)

        `when`(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockPrefs)
        `when`(mockPrefs.edit()).thenReturn(mockEditor)
        `when`(mockEditor.putInt(anyString(), anyInt())).thenReturn(mockEditor)

        val settings = Settings(mockContext)

        settings.floatingButtonOpacityPercent = -50
        verify(mockEditor).putInt(eq("floating-button-opacity-percent"), eq(10))

        settings.floatingButtonOpacityPercent = 120
        verify(mockEditor).putInt(eq("floating-button-opacity-percent"), eq(100))
    }

    @Test
    fun floatingButtonKeysAreRegisteredInBackupManager() {
        val expectedKeys = listOf(
            "enable-floating-button" to SettingsBackupManager.ValueType.BOOLEAN,
            "floating-button-x-percent" to SettingsBackupManager.ValueType.INT,
            "floating-button-y-percent" to SettingsBackupManager.ValueType.INT,
            "floating-button-opacity-percent" to SettingsBackupManager.ValueType.INT,
            "floating-button-size-dp" to SettingsBackupManager.ValueType.INT,
        )

        for ((key, expectedType) in expectedKeys) {
            val registeredType = SettingsBackupManager.backupKeys[key]
            assertNotNull("$key must be registered in SettingsBackupManager", registeredType)
            assertEquals(expectedType, registeredType)
        }
    }
}
