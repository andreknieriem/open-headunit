package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.ConnectionAdmission
/**
 * A route that carries the Android Auto handshake over a head unit's external Bluetooth module
 * instead of this unit's own radio. The manager holds one of these while that route is up.
 */
interface ExternalModuleCarrier {

    /**
     * Whether [requestWake] actually sends something to the module. A carrier whose module wakes
     * the phone on its own must not make the connection pill claim the phone is being woken.
     */
    val sendsWake: Boolean get() = true

    /**
     * Ask the module side to bring the phone's Android Auto link up. Safe from any thread.
     *
     * @param userAsked the WiFi button; a carrier that paces its wakes may hold this one rather than drop it
     */
    fun requestWake(userAsked: Boolean = false, admission: ConnectionAdmission)

    /** End the carrier and unblock whatever is reading. Safe from any thread. */
    fun close()
}
