package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/**
 * Whether to run the handshake for a phone that dialled our WPP TCP port.
 *
 * Withholding the endpoint stops us handing out a new one; it does not stop a phone that already
 * has one from dialling. Serving that dial hands it this group's name, which the next create
 * replaces, and it then retries the dead one for as long as Android Auto runs instead of falling
 * back to Bluetooth. So a dial is answered exactly when [WppEndpointPolicy] would advertise.
 */
object WppTcpServePolicy {

    /** True when this decision is one we would have advertised, which is the only safe dial. */
    fun servesDial(decision: WppEndpointDecision): Boolean =
        decision is WppEndpointDecision.Advertise

    /**
     * Whether a refused dial is told so on the wire rather than dropped.
     *
     * A bare close reads to the phone as a connect failure, which it retries forever against the
     * same dead endpoint; a rejection is the only thing that sends it back to Bluetooth. So it goes
     * out exactly when there is a Bluetooth route left to send it back to: on a unit whose RFCOMM
     * listeners are not open, withdrawing the endpoint would leave the phone with no route at all.
     * Never onto a live session either, which is held silent for the same reason a served dial is.
     */
    fun rejectsDial(
        decision: WppEndpointDecision,
        canRunRfcomm: Boolean,
        projectionUp: Boolean = false,
    ): Boolean = blamesStaleEndpoint(decision) && canRunRfcomm && !projectionUp

    /**
     * Whether a refused dial is evidence the phone holds an endpoint we no longer honour.
     *
     * A server that is not listening yet, or is stopping, refuses too, and that says nothing about
     * the phone: reporting it raised the record on a unit whose endpoint was never stale.
     */
    fun blamesStaleEndpoint(decision: WppEndpointDecision): Boolean =
        decision is WppEndpointDecision.Withhold && !decision.ourOwnState

    /** Why a dial is refused, written to be logged as-is. */
    fun refusalReason(decision: WppEndpointDecision): String = when (decision) {
        is WppEndpointDecision.Withhold -> decision.reason
        is WppEndpointDecision.Advertise -> "it is served"
    }
}
