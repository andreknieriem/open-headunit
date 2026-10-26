package com.andrerinas.openheadunit.utils

/**
 * Immersive full screen on Android 4.1 to 4.3 (API 16 to 18), which have no sticky immersive
 * mode: the first touch brings the navigation bar back and nothing hides it again, so it covers
 * the edge of the window (on a settings screen, the Save button). The app hides it again after a
 * pause, the way sticky immersive does on its own from API 19.
 */
object LegacyImmersivePolicy {

    /** The pause before the bars hide again; the projection screen uses the same one. */
    const val REHIDE_DELAY_MS = 2000L

    private const val API_KITKAT = 19

    // View.SYSTEM_UI_FLAG_HIDE_NAVIGATION, spelled out so the rule stays testable on the JVM.
    private const val FLAG_HIDE_NAVIGATION = 0x00000002

    fun applies(sdkInt: Int, mode: Settings.FullscreenMode): Boolean =
        sdkInt < API_KITKAT &&
            (mode == Settings.FullscreenMode.IMMERSIVE || mode == Settings.FullscreenMode.IMMERSIVE_WITH_NOTCH)

    /** True when the system has shown the navigation bar again (it clears the hide flag). */
    fun barsShown(systemUiVisibility: Int): Boolean = (systemUiVisibility and FLAG_HIDE_NAVIGATION) == 0
}
