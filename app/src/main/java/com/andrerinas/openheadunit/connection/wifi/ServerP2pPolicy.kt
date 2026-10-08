package com.andrerinas.openheadunit.connection.wifi

object ServerP2pPolicy {
    fun skipsSavedWifiAddress(serverP2p: Boolean, lastSessionWasWifi: Boolean): Boolean =
        serverP2p && lastSessionWasWifi
}

/** Captured at connection entry; changing settings cannot reclassify an existing session. */
class ServerP2pAttempt {
    @Volatile
    var active = false
        private set

    fun begin(serverP2p: Boolean = false) { active = serverP2p }
    val failureIsUserExit: Boolean get() = !active
    val honorKillOnFailure: Boolean get() = !active

    fun honorKillOnTransportQuit(isUserExit: Boolean): Boolean = !active || isUserExit
}
