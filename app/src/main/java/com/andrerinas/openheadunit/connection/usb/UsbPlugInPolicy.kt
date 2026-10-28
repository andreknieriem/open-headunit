package com.andrerinas.openheadunit.connection.usb

/**
 * "Connect when plugged in" decides whether a plug-in switches a phone that is still in its normal
 * identity. Only a plug-in is gated: every other trigger has its own setting, read where it fires.
 */
object UsbPlugInPolicy {
    const val DEFAULT_CONNECT_ON_PLUG_IN = true

    /** A re-attach this soon after a session that the user did not end is a cable glitch. */
    const val GLITCH_WINDOW_MS = 30_000L

    enum class Trigger { PLUG_IN, USER, APP_START, RECONNECT, WAKE, BOOT }

    fun connectsOnPlugIn(connectOnPlugIn: Boolean, listen: Boolean): Boolean = connectOnPlugIn && listen

    fun maySwitchNormal(trigger: Trigger, connectOnPlugIn: Boolean, listen: Boolean, uncleanEndAgoMs: Long?): Boolean =
        when (trigger) {
            Trigger.PLUG_IN -> connectsOnPlugIn(connectOnPlugIn, listen) ||
                (uncleanEndAgoMs != null && uncleanEndAgoMs < GLITCH_WINDOW_MS)
            else -> true
        }
}
