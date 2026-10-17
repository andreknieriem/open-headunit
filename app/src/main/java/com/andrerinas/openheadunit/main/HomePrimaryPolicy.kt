package com.andrerinas.openheadunit.main

/**
 * Which connection the home screen's main button stands for, what it says about it, and which
 * connections are left for the driver bar.
 *
 * The main button replaced a grid of equal buttons, so getting this wrong hides the one the user
 * needs behind the bar. Pure, so every combination is a unit test rather than a head unit.
 */
object HomePrimaryPolicy {

    /** In the order the bar lists them. */
    enum class Connection { USB, WIFI, SELF }

    enum class Status {
        /** A projection is running: the button only has to take the user back to it. */
        ACTIVE_SESSION,
        /** The auto-connect delay is counting down towards [Decision.primary]. */
        RECONNECTING,
        SEARCHING,
        PHONE_DETECTED,
        READY,
    }

    data class Input(
        /** The user's connection modes; empty means none chosen yet, so all of them. */
        val chosen: Set<Connection>,
        val lastConnection: Connection?,
        val usbPhoneDetected: Boolean,
        val sessionActive: Boolean,
        /** Set while the auto-connect delay runs, to the connection it is about to try. */
        val reconnectTarget: Connection?,
        val wifiScanning: Boolean,
    )

    data class Decision(
        val primary: Connection,
        val status: Status,
        val barConnections: List<Connection>,
    )

    fun decide(input: Input): Decision {
        val available = Connection.values().filter { input.chosen.isEmpty() || it in input.chosen }
        val primary = primaryOf(input, available)
        return Decision(primary, statusOf(input, primary), available - primary)
    }

    private fun primaryOf(input: Input, available: List<Connection>): Connection = when {
        // The countdown names what is about to happen, even for a mode the user later unchose.
        input.reconnectTarget != null -> input.reconnectTarget
        // A phone on the cable is a stronger hint than the history: it was plugged in to be used.
        input.usbPhoneDetected && Connection.USB in available -> Connection.USB
        input.lastConnection != null && input.lastConnection in available -> input.lastConnection
        else -> available.first()
    }

    private fun statusOf(input: Input, primary: Connection): Status = when {
        input.sessionActive -> Status.ACTIVE_SESSION
        input.reconnectTarget != null -> Status.RECONNECTING
        primary == Connection.WIFI && input.wifiScanning -> Status.SEARCHING
        primary == Connection.USB && input.usbPhoneDetected -> Status.PHONE_DETECTED
        else -> Status.READY
    }
}
