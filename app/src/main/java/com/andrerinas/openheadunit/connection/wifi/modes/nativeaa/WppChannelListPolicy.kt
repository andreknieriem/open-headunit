package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/**
 * The channel list in WifiVersionRequest field 4: the one channel our group runs on.
 *
 * The phone checks it against the 5 GHz DFS channels it can scan, so a 2.4 GHz group still gets
 * -8 by design. A value that names no real channel passes the check and describes nothing.
 */
object WppChannelListPolicy {
    private const val MIN_24_GHZ_MHZ = 2400
    private const val MAX_24_GHZ_MHZ = 2500
    private const val MIN_5_GHZ_MHZ = 4900
    private const val MAX_5_GHZ_MHZ = 5900
    private const val UNREAL_FREQUENCY_MHZ = 5000

    /**
     * Whether [frequencyMhz] is a centre frequency a real channel can be on.
     *
     * Also what [WppSessionChannelPolicy] decides on, so a value this returns false for can never
     * reach a version request through either route.
     */
    fun isRealChannel(frequencyMhz: Int): Boolean =
        frequencyMhz in MIN_24_GHZ_MHZ..MAX_24_GHZ_MHZ ||
            (frequencyMhz in MIN_5_GHZ_MHZ..MAX_5_GHZ_MHZ && frequencyMhz != UNREAL_FREQUENCY_MHZ)

    /** [frequencyMhz] is 0 when unreadable: below API 29, or a transport with no P2P group. */
    fun forGroup(frequencyMhz: Int): List<Int> =
        if (isRealChannel(frequencyMhz)) listOf(frequencyMhz) else emptyList()
}
