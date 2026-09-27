package com.andrerinas.openheadunit.connection

/**
 * Whether an automatic USB or Self Mode connection may start now, outside the wireless stack.
 *
 * The settings screen holds every one until it closes, and the status pill's X refuses them until
 * the user asks for a connection by hand. The wireless stack has its own pair of these.
 */
object AutoConnectHoldPolicy {

    enum class Verdict { PROCEED, HOLD_FOR_SETTINGS, CANCELLED_BY_USER }

    fun decide(
        settingsVisible: Boolean,
        sessionLive: Boolean,
        cancelledByUser: Boolean,
        userRequested: Boolean,
    ): Verdict = when {
        cancelledByUser && !userRequested -> Verdict.CANCELLED_BY_USER
        settingsVisible && !sessionLive -> Verdict.HOLD_FOR_SETTINGS
        else -> Verdict.PROCEED
    }

    /** Whether an automatic start may bring [com.andrerinas.openheadunit.main.MainActivity] forward. */
    fun raisesUi(settingsVisible: Boolean, cancelledByUser: Boolean): Boolean =
        !settingsVisible && !cancelledByUser

    /** A request held behind the settings screen is asked again once, when it closes. */
    fun replaysOnRelease(usbHeld: Boolean, bluetoothLaunchHeld: Boolean): Boolean =
        usbHeld || bluetoothLaunchHeld
}
