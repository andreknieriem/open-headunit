package com.andrerinas.openheadunit.utils

import androidx.annotation.StringRes
import com.andrerinas.openheadunit.R

/**
 * The flavor seam for sharing navigation updates with apps the user names.
 *
 * Passing road names, distances and the ETA to another app may need a data-sharing disclosure on
 * Google Play, so only the github build offers it. The playstore copy of this file answers no and
 * carries no copy. Every caller in `main` asks [isAvailable] first, and
 * [com.andrerinas.openheadunit.aap.NavigationUpdateSharePolicy] sends nothing extra when it is
 * false.
 */
object NavigationShareControl {

    fun isAvailable(): Boolean = true

    /**
     * The settings row's copy, which lives in `app/src/github/res/values/strings.xml`. `main` cannot
     * say `R.string.navigation_share_apps`: the strings do not exist in the playstore flavor.
     */
    @StringRes
    val rowNameRes: Int = R.string.navigation_share_apps
    @StringRes
    val rowDescriptionRes: Int = R.string.navigation_share_apps_summary
    @StringRes
    val rowHelpRes: Int = R.string.navigation_share_apps_help
    @StringRes
    val rowOffRes: Int = R.string.navigation_share_apps_off
    @StringRes
    val noAppsRes: Int = R.string.navigation_share_apps_none_installed
}
