package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/** Decides whether a command that names a phone may wake it. The order of the checks is fixed. */
object ConnectByNameGate {

    enum class Refusal(val logText: String) {
        NOT_NATIVE("not Native AA"),
        NO_PERMISSION("Bluetooth permission missing"),
        NOT_FOUND("no paired phone named"),
        AMBIGUOUS("more than one paired phone named"),
        NOT_A_PHONE("not a phone"),
        NOT_PAIRED("not paired")
    }

    sealed class Decision {
        data class Wake(val mac: String) : Decision()
        data class Refuse(val reason: Refusal) : Decision()
        object AlreadyConnected : Decision()
        object RefuseLiveSession : Decision()
    }

    data class Live(val live: Boolean, val mac: String?)

    /**
     * A phone still in its Bluetooth handshake or its join is live too: the socket is not up yet,
     * and a wake aimed elsewhere would take the gate from it. A wired session names no phone.
     */
    fun live(
        connected: Boolean,
        busy: Boolean,
        wirelessSession: Boolean,
        lastNativeMac: String?,
        handoffPeer: String?
    ): Live = when {
        connected -> Live(true, if (wirelessSession) lastNativeMac else null)
        handoffPeer != null -> Live(true, handoffPeer)
        else -> Live(busy, null)
    }

    fun decide(
        nativeMode: Boolean,
        bluetoothReadable: Boolean,
        resolve: () -> NamedPhonePolicy.Result,
        sessionLive: Boolean,
        projectingMac: String?
    ): Decision {
        if (!nativeMode) return Decision.Refuse(Refusal.NOT_NATIVE)
        if (!bluetoothReadable) return Decision.Refuse(Refusal.NO_PERMISSION)
        val mac = when (val r = resolve()) {
            is NamedPhonePolicy.Result.Match -> r.mac
            is NamedPhonePolicy.Result.Ambiguous -> return Decision.Refuse(Refusal.AMBIGUOUS)
            NamedPhonePolicy.Result.NotAPhone -> return Decision.Refuse(Refusal.NOT_A_PHONE)
            NamedPhonePolicy.Result.NotFound -> return Decision.Refuse(Refusal.NOT_FOUND)
            NamedPhonePolicy.Result.NotPaired -> return Decision.Refuse(Refusal.NOT_PAIRED)
        }
        if (!sessionLive) return Decision.Wake(mac)
        return if (projectingMac != null && projectingMac.equals(mac, ignoreCase = true)) {
            Decision.AlreadyConnected
        } else {
            Decision.RefuseLiveSession
        }
    }
}
