package com.andrerinas.openheadunit.connection.carplaynative

import com.andrerinas.openheadunit.connection.carplaynative.usb.IphoneUsbPolicy

/**
 * Why native CarPlay cannot run on this unit, in the order the attach verdicts check it, and the log line of each
 * verdict. The settings toggle and the attach path both ask here, so one rule answers both.
 */
object CarPlayNativeAvailability {

    enum class Unavailable(val verdict: IphoneUsbPolicy.Attach) {
        BelowApiFloor(IphoneUsbPolicy.Attach.BelowApiFloor),
        NoTunnel(IphoneUsbPolicy.Attach.NoTunnel),
        NoSigner(IphoneUsbPolicy.Attach.NoSigner),
    }

    /** The first reason, or null when the unit can run a session. */
    fun check(sdk: Int, tunAvailable: Boolean, signerChoice: CarPlaySignerPolicy.Choice): Unavailable? = when {
        sdk < IphoneUsbPolicy.API_FLOOR -> Unavailable.BelowApiFloor
        !tunAvailable -> Unavailable.NoTunnel
        signerChoice == CarPlaySignerPolicy.Choice.NONE -> Unavailable.NoSigner
        else -> null
    }

    /** A stored `true` counts only while the unit can run a session, so a restored or stale value cannot act. */
    fun effectiveEnabled(stored: Boolean, unavailable: Unavailable?): Boolean = stored && unavailable == null

    /** The log line for a verdict that stops the attach, or null for a verdict that logs nothing here. */
    fun line(verdict: IphoneUsbPolicy.Attach, sdk: Int): String? = when (verdict) {
        IphoneUsbPolicy.Attach.BelowApiFloor -> IphoneUsbPolicy.belowFloorLine(sdk)
        IphoneUsbPolicy.Attach.NoTunnel -> "CarPlayNative: refused, this build has no VPN tunnel for CarPlay"
        IphoneUsbPolicy.Attach.NoSigner -> "CarPlayNative: refused, no MFi signer is set up"
        IphoneUsbPolicy.Attach.AlreadyLive -> "CarPlayNative: iPhone attached; a session is live"
        IphoneUsbPolicy.Attach.UserExited -> "CarPlayNative: iPhone not opened; the user closed the session"
        IphoneUsbPolicy.Attach.RefusedAaLive -> "CarPlayNative: refused, an Android Auto session is live"
        else -> null
    }
}
