package com.andrerinas.openheadunit.main

import com.andrerinas.openheadunit.utils.Settings

/**
 * Picks which of the three looks the home screen wears: day, dark or extreme dark.
 *
 * Dark follows the night mode the app runs in, whatever turned it on (the Dark theme, the light
 * sensor, sunset...). Extreme dark is the Extreme dark theme, or any night when the user asked for
 * extreme dark at night. These are the same rules the activities use to pick their window theme.
 */
object HomeThemePolicy {

    enum class Mode { DAY, DARK, EXTREME_DARK }

    fun resolve(appTheme: Settings.AppTheme, useExtremeDarkMode: Boolean, isNightActive: Boolean): Mode = when {
        appTheme == Settings.AppTheme.EXTREME_DARK -> Mode.EXTREME_DARK
        isNightActive && useExtremeDarkMode -> Mode.EXTREME_DARK
        isNightActive || appTheme == Settings.AppTheme.DARK -> Mode.DARK
        else -> Mode.DAY
    }
}
