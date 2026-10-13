package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt.ZbtDiscoveryPolicy

/**
 * Which Bluetooth route the Native AA handshake takes on this unit.
 *
 * [com.andrerinas.openheadunit.utils.ExternalBtPolicy] answers a different question — whether this
 * unit's Bluetooth is an external module — and the two must not be conflated. Detection identifies
 * a *class of hardware*; this decides what to *do* about it, and the sets are not the same. A unit
 * can carry every external-BT marker and still have nothing listening on the vendor daemon's port,
 * because at least one vendor family in that class reaches its module over Binder instead. So
 * detection alone can never be a reason to take the module route.
 *
 * So reachability is an input, and a *measured* one: the daemon is dialled once and its answer is
 * passed in here. A unit whose module answers takes that route without the user finding a setting,
 * and an established external-module unit whose daemon refuses keeps the existing refusal.
 * Firmware-only candidates instead require a compatible greeting and retain Android Bluetooth
 * when it is absent: installed files alone do not establish the hardware or active backend.
 * `Settings.externalBtZbtTransport` stays as a manual override for a slow or intermittent daemon,
 * rather than as the precondition it used to be.
 *
 * Pure, because the same decision is read at four call sites — `WifiLauncherNative.start`,
 * `NativeAaHandshakeManager.start`, `checkCompatibility` and the settings UI — and they drifted
 * apart once already when the condition was written out longhand at each of them.
 */
object ExternalBtTransportPolicy {

    enum class Route {
        /** Android Bluetooth, including unconfirmed firmware candidates and the radio override. */
        NORMAL,

        /** The external module, through the vendor daemon. */
        ZBT,

        /** External Bluetooth, and no route through it: refuse mode 3 and say why. */
        BLOCKED,

        /** FYT's external module, through `/dev/auto_serial`. Opt-in only. */
        BLINK
    }

    /**
     * The module route wins over the compatibility override when both are on.
     *
     * They are two different escapes from the same detection. `nativeAaIgnoreExternalBt` means
     * "use this unit's own radio anyway", and its own setting comment concedes it is unlikely to
     * help: on the unit examined closely the radio accepted every write, flushed, and put nothing
     * on the air. The module route is the one that can actually carry bytes, so a user who asked
     * for both gets it.
     *
     * @param discovery the unified ZBT decision and the result from its selected cache
     * @param zbtTransportEnabled the user's opt-in, `Settings.externalBtZbtTransport`
     * @param ignoreExternalBt the compatibility override, `Settings.nativeAaIgnoreExternalBt`
     * @param blinkTransportEnabled the user's opt-in, `Settings.externalBtBlinkTransport`
     * @param fytModuleEvidence what `ExternalBtPolicy.detectFytModule` found, or null
     */
    fun route(
        discovery: ZbtDiscoveryPolicy.Snapshot,
        zbtTransportEnabled: Boolean,
        ignoreExternalBt: Boolean,
        blinkTransportEnabled: Boolean = false,
        fytModuleEvidence: String? = null
    ): Route = when {
        // Before the evidence checks: an enabled toggle is a recovery control too, and must win
        // even where detection cannot see the node.
        blinkTransportEnabled -> Route.BLINK
        // An FYT module unit with the toggle off is refused with a reason that names the toggle,
        // rather than running RFCOMM listeners and pokes on a radio its phone is not paired to.
        // Never the ZLink daemon measurement or route: FYT units have no such daemon, so a ZBT
        // toggle left on (or offered because a ZLink marker also matched) is refused too.
        fytModuleEvidence != null -> if (ignoreExternalBt) Route.NORMAL else Route.BLOCKED
        !discovery.decision.available -> Route.NORMAL
        zbtTransportEnabled -> Route.ZBT
        ignoreExternalBt -> Route.NORMAL
        discovery.reachable == true -> Route.ZBT
        discovery.decision.allowsAndroidFallback -> Route.NORMAL
        else -> Route.BLOCKED
    }

    /**
     * Whether this unit refuses to bring Native AA up at all over its external Bluetooth.
     *
     * The exact complement of [needsDaemonMeasurement] on the BLOCKED arm: a daemon that has not
     * been asked yet is not a refusal, and treating it as one skipped the very code that asks.
     */
    fun refusesBringUp(
        discovery: ZbtDiscoveryPolicy.Snapshot,
        zbtTransportEnabled: Boolean,
        ignoreExternalBt: Boolean,
        blinkTransportEnabled: Boolean = false,
        fytModuleEvidence: String? = null
    ): Boolean =
        route(
            discovery, zbtTransportEnabled, ignoreExternalBt,
            blinkTransportEnabled, fytModuleEvidence
        ) == Route.BLOCKED &&
            !needsDaemonMeasurement(
                discovery, zbtTransportEnabled, ignoreExternalBt,
                blinkTransportEnabled, fytModuleEvidence
            )

    /**
     * Whether dialling the daemon would change this unit's answer.
     *
     * The fence that keeps the dial off ordinary hardware as much as an optimisation: false
     * wherever there are no markers or a setting has already decided, so the only units that ever
     * pay for a socket connect are the ones the answer can help.
     */
    fun needsDaemonMeasurement(
        discovery: ZbtDiscoveryPolicy.Snapshot,
        zbtTransportEnabled: Boolean,
        ignoreExternalBt: Boolean,
        blinkTransportEnabled: Boolean = false,
        fytModuleEvidence: String? = null
    ): Boolean = !blinkTransportEnabled && fytModuleEvidence == null &&
        discovery.decision.available &&
        !zbtTransportEnabled && !ignoreExternalBt && discovery.reachable == null

    enum class WifiButton { MODULE, REFUSED, ANDROID_RADIO }

    /**
     * A press on a measured ZLink refusal asks the daemon again, since it may be up by now. True
     * only for known external modules where [needsDaemonMeasurement] allows it; FYT and explicit
     * transport overrides stay out. An unconfirmed ZBT candidate keeps the Android button
     * so the user can select a phone after the passive probe falls back to the normal radio.
     */
    fun remeasuresOnPress(
        discovery: ZbtDiscoveryPolicy.Snapshot,
        zbtTransportEnabled: Boolean,
        ignoreExternalBt: Boolean,
        blinkTransportEnabled: Boolean = false,
        fytModuleEvidence: String? = null
    ): Boolean = discovery.decision.retriesOnPress && discovery.reachable == false && needsDaemonMeasurement(
        discovery.copy(reachable = null), zbtTransportEnabled, ignoreExternalBt,
        blinkTransportEnabled, fytModuleEvidence
    )

    /** External-module routes cannot use Android's bonded-device and driver-selection controls. */
    fun usesExternalModule(route: Route): Boolean = route == Route.ZBT || route == Route.BLINK

    /** BLINK keeps its module bridge open across sessions and must not touch Android RFCOMM. */
    fun rearmsWithoutAndroidRadio(route: Route): Boolean = route == Route.BLINK

    /**
     * Offered wherever the FYT module is detected, and kept while enabled even if detection stops
     * seeing it, so the control that turns it off is always reachable.
     */
    fun showBlinkToggle(fytModuleEvidence: String?, enabled: Boolean): Boolean =
        fytModuleEvidence != null || enabled

    /**
     * What the main screen's WiFi button arms. A daemon not measured yet, or one whose refusal
     * the press re-asks, goes to the module route, because the bring-up is what measures it.
     */
    fun wifiButton(
        discovery: ZbtDiscoveryPolicy.Snapshot,
        zbtTransportEnabled: Boolean,
        ignoreExternalBt: Boolean,
        blinkTransportEnabled: Boolean = false,
        fytModuleEvidence: String? = null
    ): WifiButton = when {
        remeasuresOnPress(
            discovery, zbtTransportEnabled, ignoreExternalBt,
            blinkTransportEnabled, fytModuleEvidence
        ) -> WifiButton.MODULE
        refusesBringUp(
            discovery, zbtTransportEnabled, ignoreExternalBt,
            blinkTransportEnabled, fytModuleEvidence
        ) -> WifiButton.REFUSED
        usesExternalModule(
            route(
                discovery, zbtTransportEnabled, ignoreExternalBt,
                blinkTransportEnabled, fytModuleEvidence
            )
        ) || needsDaemonMeasurement(
            discovery, zbtTransportEnabled, ignoreExternalBt,
            blinkTransportEnabled, fytModuleEvidence
        ) -> WifiButton.MODULE
        else -> WifiButton.ANDROID_RADIO
    }
}
