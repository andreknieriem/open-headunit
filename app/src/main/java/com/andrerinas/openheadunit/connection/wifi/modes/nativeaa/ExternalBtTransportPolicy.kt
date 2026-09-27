package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

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
 * and a unit whose module refuses is refused exactly as before. `Settings.externalBtZbtTransport`
 * stays as a manual override for a daemon that is slow or intermittent, rather than as the
 * precondition it used to be.
 *
 * Pure, because the same decision is read at four call sites — `WifiLauncherNative.start`,
 * `NativeAaHandshakeManager.start`, `checkCompatibility` and the settings UI — and they drifted
 * apart once already when the condition was written out longhand at each of them.
 */
object ExternalBtTransportPolicy {

    enum class Route {
        /** This unit's own Bluetooth. What every unit without external-BT markers does. */
        NORMAL,

        /** The external module, through the vendor daemon. */
        ZBT,

        /** External Bluetooth, and no route through it: refuse mode 3 and say why. */
        BLOCKED
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
     * @param externalBtEvidence what `ExternalBtPolicy.detect` found, or null on ordinary hardware
     * @param zbtTransportEnabled the user's opt-in, `Settings.externalBtZbtTransport`
     * @param ignoreExternalBt the compatibility override, `Settings.nativeAaIgnoreExternalBt`
     * @param daemonReachable whether the vendor daemon answered, or null if it has not been asked
     */
    fun route(
        externalBtEvidence: String?,
        zbtTransportEnabled: Boolean,
        ignoreExternalBt: Boolean,
        daemonReachable: Boolean? = null
    ): Route = when {
        externalBtEvidence == null -> Route.NORMAL
        zbtTransportEnabled -> Route.ZBT
        ignoreExternalBt -> Route.NORMAL
        daemonReachable == true -> Route.ZBT
        else -> Route.BLOCKED
    }

    /**
     * Whether this unit refuses to bring Native AA up at all over its external Bluetooth.
     *
     * The exact complement of [needsDaemonMeasurement] on the BLOCKED arm: a daemon that has not
     * been asked yet is not a refusal, and treating it as one skipped the very code that asks.
     */
    fun refusesBringUp(
        externalBtEvidence: String?,
        zbtTransportEnabled: Boolean,
        ignoreExternalBt: Boolean,
        cachedDaemonReachable: Boolean?
    ): Boolean =
        route(externalBtEvidence, zbtTransportEnabled, ignoreExternalBt, cachedDaemonReachable) ==
            Route.BLOCKED &&
            !needsDaemonMeasurement(
                externalBtEvidence, zbtTransportEnabled, ignoreExternalBt, cachedDaemonReachable
            )

    /**
     * Whether dialling the daemon would change this unit's answer.
     *
     * The fence that keeps the dial off ordinary hardware as much as an optimisation: false
     * wherever there are no markers or a setting has already decided, so the only units that ever
     * pay for a socket connect are the ones the answer can help.
     */
    fun needsDaemonMeasurement(
        externalBtEvidence: String?,
        zbtTransportEnabled: Boolean,
        ignoreExternalBt: Boolean,
        cachedDaemonReachable: Boolean?
    ): Boolean = externalBtEvidence != null && !zbtTransportEnabled &&
        !ignoreExternalBt && cachedDaemonReachable == null

    enum class WifiButton { MODULE, REFUSED, ANDROID_RADIO }

    /**
     * What the main screen's WiFi button arms. A daemon not measured yet goes to the module route,
     * because the bring-up it triggers is what measures it.
     */
    fun wifiButton(
        externalBtEvidence: String?,
        zbtTransportEnabled: Boolean,
        ignoreExternalBt: Boolean,
        cachedDaemonReachable: Boolean?
    ): WifiButton = when {
        refusesBringUp(externalBtEvidence, zbtTransportEnabled, ignoreExternalBt, cachedDaemonReachable) ->
            WifiButton.REFUSED
        route(externalBtEvidence, zbtTransportEnabled, ignoreExternalBt, cachedDaemonReachable) == Route.ZBT ||
            needsDaemonMeasurement(externalBtEvidence, zbtTransportEnabled, ignoreExternalBt, cachedDaemonReachable) ->
            WifiButton.MODULE
        else -> WifiButton.ANDROID_RADIO
    }
}
