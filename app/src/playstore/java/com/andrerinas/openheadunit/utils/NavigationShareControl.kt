package com.andrerinas.openheadunit.utils

import androidx.annotation.StringRes

/**
 * The Play Store build does not share navigation updates with other apps, and this is the seam
 * that says so.
 *
 * Road names, distances and the ETA passed to another app may need a data-sharing disclosure on
 * Google Play, so this flavor keeps the broadcast exactly as before: signature permission only.
 * [isAvailable] answers false, the settings row is never rendered, and
 * [com.andrerinas.openheadunit.aap.NavigationUpdateSharePolicy] names no target.
 *
 * Keep this file's signatures identical to the github copy at
 * `app/src/github/java/com/andrerinas/openheadunit/utils/NavigationShareControl.kt`.
 */
object NavigationShareControl {

    fun isAvailable(): Boolean = false

    /** No copy: the strings live in the github flavor's resources. Nothing reads these here. */
    @StringRes
    val rowNameRes: Int = 0
    @StringRes
    val rowDescriptionRes: Int = 0
    @StringRes
    val rowHelpRes: Int = 0
    @StringRes
    val rowOffRes: Int = 0
    @StringRes
    val noAppsRes: Int = 0
}
