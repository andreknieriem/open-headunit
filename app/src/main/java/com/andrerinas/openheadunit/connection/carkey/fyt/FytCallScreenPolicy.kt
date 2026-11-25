package com.andrerinas.openheadunit.connection.carkey.fyt

/**
 * FYT firmware draws its own call bar or call page over the projection. Its server app drops both
 * for one call when asked, and forgets the ask at every call end, so the ask repeats.
 */
object FytCallScreenPolicy {
    const val SERVER_PACKAGE = "com.syu.ms"
    const val ACTION = "com.android.ecar.send"
    const val EXTRA_KEY = "ecarSendKey"
    const val KEY_SUPPRESS = "BluetoothMakeCall"
    const val KEY_RESTORE = "HideCallUI"

    /** Shorter than a quick redial: at 3 s a call placed right after a hang-up still got the bar. */
    const val REPEAT_MS = 500L

    /** Only while the projection is in front: anywhere else the unit's call screen is wanted. */
    fun shouldSuppress(enabled: Boolean, fytUnit: Boolean, foreground: Boolean): Boolean =
        enabled && fytUnit && foreground

    /** The setting does something in Self Mode and on an FYT unit, and nowhere else. */
    fun showsSetting(showsSelf: Boolean, fytUnit: Boolean): Boolean = showsSelf || fytUnit
}
