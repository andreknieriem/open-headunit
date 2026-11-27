package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/**
 * The one channel this unit can name in a WPP version request, or why it cannot name one.
 *
 * The version request carries both the endpoint the phone is told to remember and the channel the
 * group runs on. A phone that matches the channel accepts the exchange; one that matches nothing
 * answers `NO_SUPPORTED_WIFI_CHANNELS(-8)` and stops talking, and an endpoint it already stored is
 * dialled in preference to Bluetooth for as long as Android Auto runs. So an endpoint and a channel
 * have to be decided together: a request that names no channel must not carry an endpoint either.
 */
sealed class WppSessionChannel {

    /** A real centre frequency the group is measured on, or inferred to be on. */
    data class Measured(val mhz: Int) : WppSessionChannel()

    /** Nothing real can be named, and [reason] says which reading was missing. */
    data class Unavailable(val reason: String) : WppSessionChannel()
}

/**
 * Resolves the channel for a session, in the order that keeps a measurement ahead of an inference.
 *
 * `WifiP2pGroup.getFrequency()` exists from API 29 only, so a unit below it never reports one. That
 * is not the end of the question on such a unit: while this unit is associated to a Wi-Fi network
 * it is a concurrent STA+P2P device, and the platform places the group on the station's channel
 * because the single radio cannot sit on two. The station frequency is readable from API 21 and
 * needs no Location, unlike the SSID, so it is a real reading rather than a guess.
 *
 * When neither reading exists the answer is [WppSessionChannel.Unavailable], never a default: a
 * fabricated channel describes a group that is not there, and the phone is entitled to refuse it.
 */
object WppSessionChannelPolicy {

    /**
     * `[groupFrequencyMhz]` is the group's own reading, 0 when unreadable (below API 29).
     * `[stationFrequencyMhz]` is `WifiInfo.getFrequency()`, 0 when there is no station.
     */
    fun resolve(
        groupFrequencyMhz: Int,
        stationFrequencyMhz: Int,
        associated: Boolean,
    ): WppSessionChannel = when {
        WppChannelListPolicy.isRealChannel(groupFrequencyMhz) ->
            WppSessionChannel.Measured(groupFrequencyMhz)

        associated && WppChannelListPolicy.isRealChannel(stationFrequencyMhz) ->
            WppSessionChannel.Measured(stationFrequencyMhz)

        else -> WppSessionChannel.Unavailable(
            "this unit cannot name the group's channel: it reports no group frequency " +
                "(WifiP2pGroup carries one from Android 10 only) and it is not joined to a WiFi " +
                "network whose channel the group would share"
        )
    }

    /** The frequencies to put in version request field 4: at most one, and only a real one. */
    fun channelsFor(channel: WppSessionChannel): List<Int> = when (channel) {
        is WppSessionChannel.Measured -> WppChannelListPolicy.forGroup(channel.mhz)
        is WppSessionChannel.Unavailable -> emptyList()
    }

    /** The resolved frequency, or 0 when nothing real was resolved. */
    fun mhzOf(channel: WppSessionChannel?): Int = (channel as? WppSessionChannel.Measured)?.mhz ?: 0

    /**
     * Whether the phone has already refused exactly this channel.
     *
     * Compared against the channel in hand rather than remembered as a flag, so a group that
     * genuinely moves to another channel is advertised again without anyone clearing a latch.
     */
    fun withheldAfterRefusal(channel: WppSessionChannel, refusedChannelMhz: Int): Boolean =
        channel is WppSessionChannel.Measured &&
            refusedChannelMhz > 0 &&
            refusedChannelMhz == channel.mhz

    /** Why a refused channel stops the advertisement, written to be logged as-is. */
    fun refusalReason(channel: WppSessionChannel): String =
        "the phone already answered NO_SUPPORTED_WIFI_CHANNELS(-8) to this unit's channel " +
            "${mhzOf(channel)}MHz, so it is not being given an endpoint for it again"
}
