package com.andrerinas.openheadunit.connection.usb

/**
 * A process-wide "an AOA switch is running" flag, staked by `UsbAttachedActivity`.
 *
 * The activity switches on its own thread and cannot reach [UsbLauncherManager], which may not
 * exist yet on a cold start; both live in the same process, so a static is what is visible early
 * enough. Only the attach fallback reads it - see [UsbLauncherManager.isActivitySwitchInFlight].
 */
object UsbSwitchClaim {

    @Volatile
    private var claimUntilMs = 0L

    fun stake() {
        claimUntilMs = UsbAccessoryHandoffPolicy.claimExpiryFrom(System.currentTimeMillis())
    }

    fun release() {
        claimUntilMs = 0L
    }

    @Volatile
    private var switches = 0L

    /** A launcher attempt compares this count to learn the activity switched the device meanwhile. */
    fun noteSwitched() { switches++ }

    fun switches(): Long = switches

    fun isLive(): Boolean =
        UsbAccessoryHandoffPolicy.switchClaimIsLive(System.currentTimeMillis(), claimUntilMs)
}
