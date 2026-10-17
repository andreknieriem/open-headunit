package com.andrerinas.openheadunit.main

import com.andrerinas.openheadunit.main.HomeThemePolicy.Mode
import com.andrerinas.openheadunit.utils.Settings.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeThemePolicyTest {

    @Test
    fun `daytime is the colorful day look`() {
        assertEquals(Mode.DAY, HomeThemePolicy.resolve(AppTheme.AUTOMATIC, useExtremeDarkMode = false, isNightActive = false))
        assertEquals(Mode.DAY, HomeThemePolicy.resolve(AppTheme.CLEAR, useExtremeDarkMode = true, isNightActive = false))
    }

    @Test
    fun `any night is the dark look`() {
        assertEquals(Mode.DARK, HomeThemePolicy.resolve(AppTheme.LIGHT_SENSOR, useExtremeDarkMode = false, isNightActive = true))
        assertEquals(Mode.DARK, HomeThemePolicy.resolve(AppTheme.DARK, useExtremeDarkMode = false, isNightActive = true))
    }

    @Test
    fun `the dark theme is dark even before the night mode reaches the configuration`() {
        assertEquals(Mode.DARK, HomeThemePolicy.resolve(AppTheme.DARK, useExtremeDarkMode = false, isNightActive = false))
    }

    @Test
    fun `the extreme dark theme is always extreme dark`() {
        assertEquals(Mode.EXTREME_DARK, HomeThemePolicy.resolve(AppTheme.EXTREME_DARK, useExtremeDarkMode = false, isNightActive = false))
        assertEquals(Mode.EXTREME_DARK, HomeThemePolicy.resolve(AppTheme.EXTREME_DARK, useExtremeDarkMode = false, isNightActive = true))
    }

    @Test
    fun `extreme dark at night only applies at night`() {
        assertEquals(Mode.EXTREME_DARK, HomeThemePolicy.resolve(AppTheme.AUTO_SUNRISE, useExtremeDarkMode = true, isNightActive = true))
        assertEquals(Mode.DAY, HomeThemePolicy.resolve(AppTheme.AUTO_SUNRISE, useExtremeDarkMode = true, isNightActive = false))
    }
}
