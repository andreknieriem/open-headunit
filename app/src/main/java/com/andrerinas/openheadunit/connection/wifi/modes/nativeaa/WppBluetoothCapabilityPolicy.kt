package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/**
 * Channel capability list used only by the Bluetooth-to-Wi-Fi handoff.
 *
 * This is deliberately separate from [WppSessionChannelPolicy.channelsFor]: an unavailable
 * session channel must still withhold a WPP endpoint, but a 2.4-GHz-only unit can truthfully tell
 * the phone which channels its radio supports. The list is not a claim about the current group's
 * channel and must never feed endpoint advertisement.
 */
object WppBluetoothCapabilityPolicy {
    /** The TB-7304F's supported 2.4 GHz centre frequencies. */
    val TWO_GHZ_ONLY_CHANNELS: List<Int> = listOf(2412, 2437, 2462)

    fun channelsFor(sessionChannel: WppSessionChannel, supports5Ghz: Boolean?): List<Int> = when {
        sessionChannel is WppSessionChannel.Measured ->
            WppSessionChannelPolicy.channelsFor(sessionChannel)
        supports5Ghz == false -> TWO_GHZ_ONLY_CHANNELS
        else -> emptyList()
    }
}
