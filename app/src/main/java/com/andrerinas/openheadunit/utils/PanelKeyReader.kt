package com.andrerinas.openheadunit.utils

import android.app.Activity
import android.os.Build
import android.util.DisplayMetrics

/** The one reader of the panel key, so the settings screens and the projection see the same value. */
object PanelKeyReader {

    /** The key of the panel this Activity shows on; null when unreadable. Updates the latch in [settings]. */
    fun read(activity: Activity, settings: Settings): String? {
        if (Build.VERSION.SDK_INT < PanelDensityPolicy.MIN_SDK) return null
        val key = try {
            val display = if (Build.VERSION.SDK_INT >= 30) activity.display
            else @Suppress("DEPRECATION") activity.windowManager.defaultDisplay
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION") display?.getRealMetrics(metrics)
            PanelDensityPolicy.key(metrics.widthPixels, metrics.heightPixels)
        } catch (e: Exception) {
            AppLog.w("PanelKeyReader: panel size unreadable: ${e.message}")
            null
        } ?: return null
        val before = PanelDensityPolicy.Panels(settings.dpiPanelFirstKey, settings.dpiPanelsActive)
        val after = PanelDensityPolicy.observe(before, key)
        if (after.firstKey != before.firstKey) settings.dpiPanelFirstKey = after.firstKey
        if (after.active != before.active) {
            settings.dpiPanelsActive = true
            AppLog.i("PanelKeyReader: second panel $key after ${before.firstKey}, one DPI per panel from now on")
        }
        return key
    }
}
