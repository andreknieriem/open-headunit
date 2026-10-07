package com.andrerinas.openheadunit.connection

/**
 * Whether an automatic USB or Self Mode connection may start now, outside the wireless stack.
 *
 * The settings screen holds automatic starts until it closes. Save may explicitly resume its
 * own connection behind that screen; the status pill's X still refuses that retry. An explicit
 * connection button may lift X. The wireless stack has its own pair of these.
 */
object AutoConnectHoldPolicy {

    enum class Verdict { PROCEED, HOLD_FOR_SETTINGS, CANCELLED_BY_USER }

    fun decide(
        settingsVisible: Boolean,
        sessionLive: Boolean,
        cancelledByUser: Boolean,
        userRequested: Boolean,
        settingsRestart: Boolean = false,
    ): Verdict = when {
        cancelledByUser && !userRequested -> Verdict.CANCELLED_BY_USER
        settingsVisible && !sessionLive && !settingsRestart -> Verdict.HOLD_FOR_SETTINGS
        else -> Verdict.PROCEED
    }

    /** Whether an automatic start may bring [com.andrerinas.openheadunit.main.MainActivity] forward. */
    fun raisesUi(settingsVisible: Boolean, cancelledByUser: Boolean): Boolean =
        !settingsVisible && !cancelledByUser

    /** A request held behind the settings screen is asked again once, when it closes. */
    fun replaysOnRelease(usbHeld: Boolean, bluetoothLaunchHeld: Boolean): Boolean =
        usbHeld || bluetoothLaunchHeld
}
