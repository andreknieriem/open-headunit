package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.utils.Settings

/** What the night overlay shows and how large and dark it is, read from settings. */
data class NightOverlayOptions(
    val textSize: Settings.NightOverlayTextSize = Settings.NightOverlayTextSize.NORMAL,
    val background: Settings.NightOverlayBackground = Settings.NightOverlayBackground.BLACK,
    val showClock: Boolean = true,
    val showNavigation: Boolean = true,
    val showTrack: Boolean = true
) {
    val textScale: Float
        get() = when (textSize) {
            Settings.NightOverlayTextSize.SMALL -> 0.8f
            Settings.NightOverlayTextSize.NORMAL -> 1.0f
            Settings.NightOverlayTextSize.LARGE -> 1.3f
        }

    val backgroundColor: Int
        get() = when (background) {
            Settings.NightOverlayBackground.BLACK -> 0xFF000000.toInt()
            Settings.NightOverlayBackground.DARK_GRAY -> 0xFF202020.toInt()
        }
}
