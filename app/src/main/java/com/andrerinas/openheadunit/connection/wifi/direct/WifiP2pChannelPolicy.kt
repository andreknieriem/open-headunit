package com.andrerinas.openheadunit.connection.wifi.direct

/**
 * Whether the channel a P2P group came up on is one a phone can actually join.
 *
 * Android picks the group owner's operating channel itself; below API 29 there is no way to ask for
 * one. On a device whose WiFi country code is unset or permissive the driver can pick 2.4 GHz
 * channel 12 or 13, which most phones will refuse - a client in the FCC domain is limited to
 * channels 1-11 and generally will not even list an access point above that in a scan. The result
 * is a group that is up and beaconing while the phone reports only that it cannot find the network.
 *
 * This only names the condition; nothing acts on it beyond telling the user. The channel is settled
 * when the WiFi radio initialises, not when the group is created, so recreating the group re-picks
 * the same one - measured on a unit where this failed as six recreates all landing on 2467 MHz.
 * Restarting WiFi, or a country code the driver honours, is what moves it.
 */
object WifiP2pChannelPolicy {

    /** Channel 1. Below this is not a 2.4 GHz channel. */
    private const val CHANNEL_1_MHZ = 2412

    /** Channel 11, the top of the range every regulatory domain lets a client associate on. */
    private const val MAX_CLIENT_SAFE_2GHZ_MHZ = 2462

    /** Channel 13, the top of the regular 5 MHz spacing. */
    private const val CHANNEL_13_MHZ = 2472

    /**
     * Channel 14: Japan only, 802.11b only, and off the 5 MHz grid.
     *
     * Public because [WifiP2pOperatingChannelPolicy] converts the other way and needs the same exception.
     * The two were written independently and only this one had it, so the number is shared rather
     * than repeated.
     */
    const val CHANNEL_14_MHZ = 2484

    /** True for a 2.4 GHz frequency, by the span the band occupies rather than by exact channel. */
    fun is24GHz(frequencyMhz: Int): Boolean = frequencyMhz in CHANNEL_1_MHZ..CHANNEL_14_MHZ

    /**
     * The 2.4 GHz channel number for [frequencyMhz], or 0 when it is not one. Channels 1-13 are
     * 5 MHz apart starting at 2412; channel 14 breaks the pattern and sits at 2484.
     */
    fun channelFor(frequencyMhz: Int): Int = when {
        frequencyMhz == CHANNEL_14_MHZ -> 14
        frequencyMhz < CHANNEL_1_MHZ || frequencyMhz > CHANNEL_13_MHZ -> 0
        (frequencyMhz - CHANNEL_1_MHZ) % 5 != 0 -> 0
        else -> (frequencyMhz - CHANNEL_1_MHZ) / 5 + 1
    }

    /**
     * True when a group on [frequencyMhz] is one many phones cannot join: 2.4 GHz above channel 11.
     *
     * Deliberately false for an unknown frequency - 0 is what the pre-API-29 reflection returns when
     * no field name matches - and for 5 GHz. Telling a user their channel is the problem on the
     * strength of a measurement we do not have would send them chasing the wrong thing.
     */
    fun isClientUnfriendly(frequencyMhz: Int): Boolean =
        is24GHz(frequencyMhz) && frequencyMhz > MAX_CLIENT_SAFE_2GHZ_MHZ

    /** How the channel should read in a log line: "channel 12", or "unknown channel" for a 0. */
    fun describe(frequencyMhz: Int): String {
        val channel = channelFor(frequencyMhz)
        return if (channel == 0) "unknown channel" else "channel $channel"
    }
}
