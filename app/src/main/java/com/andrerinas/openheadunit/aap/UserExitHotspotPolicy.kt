package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import com.andrerinas.openheadunit.connection.wifi.modes.helper.HelperStrategy
import com.andrerinas.openheadunit.connection.wifi.modes.native.NativeStrategy

/** What a user-initiated disconnect should do to this head unit's own access point. */
enum class HotspotExitAction {
    /** Not a hotspot route — this decision belongs to someone else. */
    NONE,

    /** Take the access point down, so the phone is actually put off the network. */
    DISABLE,

    /** Leave it alone and say what that costs: it is not ours to switch off. */
    WARN_LEFT_UP
}

/**
 * Whether ending a session by hand should also take down the access point the phone is sitting on.
 *
 * Closing the AAP socket and sending a `ByeByeRequest` does not make the phone leave the network:
 * it stays associated and Android Auto spins on its wireless-setup restart until it throttles
 * itself, because from its side the car walked away mid-session. The only lever this app has is
 * making the network go away — which is exactly what [AapService] already does for WiFi Direct by
 * removing the P2P group. This decides the same question for the two routes that run on a soft AP
 * instead, and is the deliberate complement of [WifiModePolicy.usesWifiDirect]: for any one
 * disconnect exactly one of the two can answer yes.
 *
 * The gate is ownership rather than symptom. A P2P group is always ours, created for the session
 * and safe to remove; a soft AP usually is not — the user switched it on, and switching one back on
 * is best effort that most unrooted units cannot do at all. Leaving somebody's hotspot off with no
 * way to restore it is worse than the stall, which they can clear themselves. So the access point
 * comes down only when they have already handed the app that job.
 */
object UserExitHotspotPolicy {

    /**
     * @param mode [com.andrerinas.openheadunit.utils.Settings.wifiConnectionMode]
     * @param helperStrategy [HelperStrategy]
     * @param nativeStrategy applies to [WifiLauncherMode.NATIVE] only
     * @param autoEnableHotspot [com.andrerinas.openheadunit.utils.Settings.autoEnableHotspot] — the
     *   user's standing permission for the app to drive this device's hotspot
     * @param teardownProvenUnsafe whether this device has already failed to bring its access point
     *   back after the app took it down. See the note on the [HotspotExitAction.WARN_LEFT_UP]
     *   branch below.
     */
    fun onUserExit(
        mode: WifiLauncherMode,
        helperStrategy: HelperStrategy,
        nativeStrategy: NativeStrategy,
        autoEnableHotspot: Boolean,
        teardownProvenUnsafe: Boolean = false
    ): HotspotExitAction = when {
        !usesHeadUnitHotspot(mode, helperStrategy, nativeStrategy) -> HotspotExitAction.NONE
        // Permission is not the only question; capability is the other one, and unlike permission
        // it can only be learned by trying. Some radios will not host an access point again once it
        // has been taken down — measured on a UNISOC unit where hostapd's channel scan raced the
        // driver re-creating the interface and lost, on the teardown and on every attempt after it,
        // including the auto-enable that was supposed to be the way back. On such a device the
        // choice is between one stranded hotspot and one per session, and the app only finds out
        // which kind it is by stranding the first.
        teardownProvenUnsafe -> HotspotExitAction.WARN_LEFT_UP
        autoEnableHotspot -> HotspotExitAction.DISABLE
        else -> HotspotExitAction.WARN_LEFT_UP
    }

    /** Whether this combination puts the phone on an access point this device is hosting. */
    fun usesHeadUnitHotspot(mode: WifiLauncherMode, helperStrategy: HelperStrategy, nativeStrategy: NativeStrategy): Boolean =
        (mode == WifiLauncherMode.NATIVE && nativeStrategy == NativeStrategy.HOTSPOT) ||
        (mode == WifiLauncherMode.HELPER && helperStrategy == HelperStrategy.HEADUNIT_HOTSPOT)
}
