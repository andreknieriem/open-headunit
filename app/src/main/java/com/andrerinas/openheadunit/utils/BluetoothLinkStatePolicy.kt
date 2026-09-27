package com.andrerinas.openheadunit.utils

/**
 * Names which Bluetooth profiles this unit holds during a session, so a stutter on a 2.4 GHz link
 * can be read against the Bluetooth traffic sharing the radio. Pure: the caller reads the states.
 */
object BluetoothLinkStatePolicy {

    /** `BluetoothProfile` values; the client roles a head unit plays are hidden from the SDK. */
    const val PROFILE_HEADSET = 1
    const val PROFILE_A2DP = 2
    const val PROFILE_A2DP_SINK = 11
    const val PROFILE_AVRCP_CONTROLLER = 12
    const val PROFILE_HEADSET_CLIENT = 16
    const val PROFILE_PBAP_CLIENT = 17
    const val PROFILE_MAP_CLIENT = 18

    /** Log order: the head unit's own roles first, then the phone-side roles some stacks use. */
    val PROFILES: List<Pair<String, Int>> = listOf(
        "hfpClient" to PROFILE_HEADSET_CLIENT,
        "a2dpSink" to PROFILE_A2DP_SINK,
        "avrcpCtl" to PROFILE_AVRCP_CONTROLLER,
        "pbapClient" to PROFILE_PBAP_CLIENT,
        "mapClient" to PROFILE_MAP_CLIENT,
        "headset" to PROFILE_HEADSET,
        "a2dp" to PROFILE_A2DP
    )

    private const val STATE_DISCONNECTED = 0
    private const val STATE_CONNECTING = 1
    private const val STATE_CONNECTED = 2
    private const val STATE_DISCONNECTING = 3

    /**
     * One reading. A null profile state means the adapter would not say, which is not the same as
     * off; [adapterOn] null means no adapter could be resolved. [audioMode] is `AudioManager.getMode`,
     * which is how a call on the unit shows without a profile proxy.
     */
    data class Snapshot(
        val adapterOn: Boolean?,
        val states: Map<String, Int?>,
        val audioMode: Int?
    )

    fun stateLabel(state: Int?): String = when (state) {
        null -> "?"
        STATE_CONNECTED -> "on"
        STATE_CONNECTING -> "connecting"
        STATE_DISCONNECTING -> "disconnecting"
        STATE_DISCONNECTED -> "off"
        else -> "state$state"
    }

    /** `AudioManager.MODE_*`; anything but normal during a session means a call or a ringtone. */
    fun audioModeLabel(mode: Int?): String = when (mode) {
        null -> "?"
        0 -> "normal"
        1 -> "ringtone"
        2 -> "inCall"
        3 -> "inCommunication"
        else -> "mode$mode"
    }

    fun describe(snapshot: Snapshot): String {
        if (snapshot.adapterOn == null) return "adapter unreadable"
        if (!snapshot.adapterOn) return "adapter off"
        val profiles = PROFILES.joinToString(" ") { (name, _) ->
            "$name=${stateLabel(snapshot.states[name])}"
        }
        return "$profiles audioMode=${audioModeLabel(snapshot.audioMode)}"
    }

    /** Whether [next] is worth a line of its own after [previous]; the first reading always is. */
    fun changed(previous: Snapshot?, next: Snapshot): Boolean = previous != next
}
