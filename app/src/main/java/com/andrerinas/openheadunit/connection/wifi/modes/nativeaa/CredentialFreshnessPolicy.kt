package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/**
 * Whether the credentials a handshake captured are still the ones to send.
 *
 * A group can change between the version/start messages and Type 3. Recheck at the write
 * boundary so the phone is never told to scan for a network that has already been replaced.
 */
object CredentialFreshnessPolicy {

    enum class Action {
        /** Nothing changed under us. */
        SEND_AS_CAPTURED,

        /** The group was replaced; the phone wants the one that exists now. */
        SEND_LIVE,

        /** There is no network to name, so saying nothing is better than naming a dead one. */
        ABORT,
    }

    fun isJoinable(credentials: NativeNetworkCredentials): Boolean =
        credentials.ssid.isNotBlank() && credentials.psk.isNotEmpty()

    /**
     * Whether [live] is still the network whose WPP endpoint this exchange stands behind.
     *
     * Two different addresses reach the phone, and they are easy to conflate. Type 4
     * (WifiVersionRequest) carries the WPP-over-TCP endpoint, port 5299, which the phone stores
     * against this network and dials on later connections instead of running Bluetooth. Type 1
     * (WifiStartRequest) carries the AAP projection address, port 5288, for this connection only.
     * [endpointNetwork] is the network behind the first: the one Type 4 advertised, or over TCP the
     * one the dial was admitted on, which is the phone already using a stored endpoint. Null when
     * no endpoint went out, as on a cold start whose Type 4 left before the network existed; that
     * exchange may go on to send whatever network becomes ready.
     *
     * Once an endpoint is out, only that network, still judged one the phone may keep, matches it.
     * A replacement on the same IP, or the same network re-judged as not worth keeping, would send
     * Type 3 under a promise made for something else, so the exchange ends instead and the next
     * one advertises what exists then. Identity re-judged as still keepable is not a replacement.
     */
    fun keepsEndpoint(endpointNetwork: NativeNetworkCredentials?, live: NativeNetworkCredentials?): Boolean =
        endpointNetwork == null ||
            (live != null && live.sameNetworkAs(endpointNetwork) && WppEndpointPolicy.keepsNetwork(live.identity))

    /**
     * What to do with Type 3, given [captured], the network Type 1 sent the phone to, and
     * [endpointNetwork] as for [keepsEndpoint].
     */
    fun decide(
        captured: NativeNetworkCredentials,
        live: NativeNetworkCredentials?,
        endpointNetwork: NativeNetworkCredentials? = null,
    ): Action = when {
        live == null -> Action.ABORT
        // A replacement arriving during the Type 3 delay has not passed the earlier setup
        // checks. Never substitute an incomplete group just because its endpoint is unchanged.
        !isJoinable(live) -> Action.ABORT
        !keepsEndpoint(endpointNetwork, live) -> Action.ABORT
        live.sameNetworkAs(captured) -> Action.SEND_AS_CAPTURED
        // Type 1 already told the phone where to dial. A different endpoint makes the whole
        // exchange stale, not just its last message.
        live.ip != captured.ip -> Action.ABORT
        else -> Action.SEND_LIVE
    }
}
