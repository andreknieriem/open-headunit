package com.andrerinas.openheadunit.main

/**
 * Which auto-start triggers raise the screen, so the overlay permission is theirs to need.
 *
 * The screen used to delete the configuration when the permission was missing. It never had to:
 * ActivityLaunchPolicy falls back to a notification, so the trigger still works, and deleting the
 * user's choice reads as a setting that will not save.
 */
object AutoStartOverlayPolicy {

    /** Auto-disconnect is deliberately absent: it launches nothing. */
    fun launches(
        onBoot: Boolean,
        onScreenOn: Boolean,
        onUsb: Boolean,
        onWifi: Boolean,
        btTriggerCount: Int,
        onBle: Boolean = false,
    ): Boolean = onBoot || onScreenOn || onUsb || onWifi || btTriggerCount > 0 || onBle

    /** The screen says what is missing and keeps the configuration. */
    fun showsNotice(overlayGranted: Boolean, launches: Boolean): Boolean =
        !overlayGranted && launches
}
