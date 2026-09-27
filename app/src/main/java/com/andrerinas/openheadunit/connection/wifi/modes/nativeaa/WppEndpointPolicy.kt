package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.direct.GroupIdentityStability

/** Whether this handshake tells the phone where to reach us over TCP, and why not when it does not. */
sealed class WppEndpointDecision {

    /** Advertise the endpoint on [port]. */
    data class Advertise(val port: Int) : WppEndpointDecision()

    /**
     * Send the version request without an endpoint. [reason] is written to be logged as-is.
     *
     * [ourOwnState] marks a withholding that describes this unit rather than the phone's record,
     * so a dial arriving while we are stopping is not reported as an endpoint the phone holds.
     */
    data class Withhold(val reason: String, val ourOwnState: Boolean = false) : WppEndpointDecision()
}

/**
 * Decides whether to advertise the WPP-over-TCP endpoint.
 *
 * The phone stores that endpoint alongside the network we hand it and from then on joins that
 * network instead of running the Bluetooth handshake again, with no fallback when it is gone. So an
 * endpoint is only safe on a network whose name and BSSID outlive the record. Either network
 * qualifies once this unit has shown, across two bring-ups, that its kept name comes back with the
 * same address (GroupIdentityStabilityPolicy); until then, and on a unit that re-addresses every
 * time, nothing is advertised. The access point is measured the same way, and its address too,
 * which tethering re-randomises per boot (SoftApEndpointStabilityPolicy).
 *
 * Withholding is not a cure, only a way of not causing it. An endpoint the phone was given earlier
 * survives for the life of its Android Auto process and is dialled in preference to Bluetooth, so
 * every refusal says how to clear one.
 */
object WppEndpointPolicy {

    /**
     * Whether the phone should keep these credentials (`AccessPointType.STATIC`) or discard them.
     *
     * DYNAMIC stops it persisting them at all, so it returns over Bluetooth instead of auto-joining
     * an address that may have moved. Same question field 6 asks, so the two cannot disagree.
     */
    fun keepsNetwork(identity: GroupIdentityStability): Boolean =
        identity == GroupIdentityStability.STABLE

    private const val HOW_TO_CLEAR =
        "Withholding one does not clear one the phone already has: Android Auto keeps an endpoint " +
            "it was given for as long as it is running, and dials it in preference to Bluetooth, " +
            "so if a connection will not start, forget this head unit on the phone"

    fun decide(
        strategy: NativeStrategy,
        listeningPort: Int?,
        identity: GroupIdentityStability,
    ): WppEndpointDecision {
        val network = if (strategy == NativeStrategy.HOTSPOT) "access point" else "WiFi Direct group"
        return when {
            identity == GroupIdentityStability.CHANGED ->
                WppEndpointDecision.Withhold(
                    "this unit gives its $network a new address every time it comes up, and the " +
                        "phone would keep dialling the one it stored. $HOW_TO_CLEAR"
                )
            identity == GroupIdentityStability.RENAMED ->
                WppEndpointDecision.Withhold(
                    "this unit's Android is too old to name its own WiFi Direct group, and the platform " +
                        "has picked a new name every create, so there is nothing for the phone to " +
                        "remember. $HOW_TO_CLEAR"
                )
            identity != GroupIdentityStability.STABLE ->
                WppEndpointDecision.Withhold(
                    "the $network's name and address have not yet been seen to repeat on " +
                        "this unit, so the phone is not told to remember it. The next bring-up " +
                        "decides. $HOW_TO_CLEAR"
                )
            listeningPort == null ->
                WppEndpointDecision.Withhold("the WPP TCP server is not listening", ourOwnState = true)
            else -> WppEndpointDecision.Advertise(listeningPort)
        }
    }
}
