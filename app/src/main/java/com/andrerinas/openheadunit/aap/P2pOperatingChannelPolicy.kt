package com.andrerinas.openheadunit.aap

/**
 * Which operating channel to ask the P2P stack for, on the devices that have no band API.
 *
 * [NativeGroupBandPolicy] asks for a *band*, and that request only exists from API 29
 * (`WifiP2pConfig.Builder.setGroupOperatingBand`). Below it the app calls the no-argument
 * `createGroup` and the driver picks unaided, which is what every pre-Android-10 head unit in the
 * reports has been doing: `Standard createGroup SUCCESS!`, then `Freq: 0 MHz (unknown)`, with the
 * band unrecorded on our side and unchosen on theirs.
 *
 * There is one lever left on those devices. `WifiP2pManager.setWifiP2pChannels(c, listen, operating,
 * listener)` is hidden but present, and in AOSP 8.1's `SupplicantP2pIfaceHal` an operating channel is
 * turned into `setDisallowedFrequencies` around the single frequency it names - channel 36 becomes
 * 5180 MHz and everything either side of it is disallowed, so the group owner has nowhere else to go.
 * Reflection reaches it because the non-SDK blocklist only starts at API 28, and this app already
 * reflects into the same class for `setDeviceName`.
 *
 * Two constraints come from the platform and both are encoded here rather than left to the caller:
 *
 *  - The channel must be set **while no group exists**. `WifiP2pServiceImpl` handles `SET_CHANNEL`
 *    only in its inactive state, so a request made after `createGroup` is dropped silently.
 *  - The **listen** channel must be left alone. Discovery happens on the 2.4 GHz social channels
 *    (1, 6, 11) whatever the group runs on, and restricting it would make the unit undiscoverable.
 *    [LISTEN_CHANNEL_UNCHANGED] is the value that means "do not touch it".
 *
 * The request is tried as a ladder rather than once - see [attemptChannels] - because a frequency
 * list a unit cannot satisfy costs it the group rather than the band.
 *
 * Channel 36 rather than any other: it is the bottom of UNII-1, it is not a DFS channel, and it is
 * what the reference implementations use - `WirelessAndroidAutoDongle` brings its access point up on
 * channel 36, and the AAWireless dongle's own filing lists 5180-5240 and 5745-5825 while avoiding the
 * DFS range between them. [CHANNEL_UPPER] is offered for the same reason the filing lists two ranges:
 * a unit whose regulatory domain refuses UNII-1 may still take UNII-3.
 */
object P2pOperatingChannelPolicy {

    /** Ask for nothing and leave the platform's own choice alone. */
    const val CHANNEL_UNRESTRICTED = 0

    /** Pass this as the listen channel: discovery must stay where the phone looks for it. */
    const val LISTEN_CHANNEL_UNCHANGED = 0

    /** 5180 MHz, bottom of UNII-1. Not a DFS channel. */
    const val CHANNEL_LOWER = 36

    /** 5745 MHz, bottom of UNII-3, for a regulatory domain that refuses UNII-1. */
    const val CHANNEL_UPPER = 149

    /**
     * 2437 MHz, the middle of the three non-overlapping 2.4 GHz channels (1, 6 and 11).
     *
     * The rung the ladder falls to when 5 GHz will not host a group owner. The middle one rather
     * than an edge because nothing here knows what else is on the air, and 6 is the conventional
     * default when there is nothing to choose on.
     */
    const val CHANNEL_24_GHZ = 6

    /** The last 2.4 GHz channel, and the one the linear formula does not describe. */
    const val CHANNEL_24_GHZ_TOP = 14

    /** The band request exists from here up, so below it is where this policy applies. */
    const val FIRST_API_WITH_BAND_REQUEST = 29

    /**
     * True when asking for an operating channel is the only way to influence the band.
     *
     * From API 29 the band request is a supported call with a supported fallback, and it is what
     * [NativeGroupBandPolicy] already drives; reaching for a hidden method there would be trading a
     * guarantee for a reflection.
     */
    fun appliesTo(sdkInt: Int): Boolean = sdkInt < FIRST_API_WITH_BAND_REQUEST

    /**
     * The operating channels to ask for, best first, or empty where asking is not the lever.
     *
     * A ladder rather than a single answer, because the request is a *disallowed-frequency list*
     * and that is the whole hazard: a unit whose P2P firmware cannot host a group owner on the band
     * it names is not left on the other one, it is left with nowhere legal to put a group and fails
     * outright. Naming 2.4 GHz explicitly on the way down gives it somewhere to land, and the caller
     * clears the restriction entirely once this list is spent - so the worst case is the behaviour
     * that shipped before any of this existed, reached one bring-up later.
     *
     * That ladder is also what makes 5 GHz safe to try by default here. It used to be an opt-in
     * precisely because a wrong answer cost a pre-Q unit its connection rather than its band; with
     * somewhere to fall back to, a wrong answer costs a round trip.
     *
     * @param sdkInt this device's API level. Empty from [FIRST_API_WITH_BAND_REQUEST] up, where the
     *   supported band request does this properly and reaching for a hidden method would be trading
     *   a guarantee for a reflection.
     * @param preference the user's band choice.
     * @param useUpperBand ask for UNII-3 instead of UNII-1 on the 5 GHz rung.
     */
    fun attemptChannels(
        sdkInt: Int,
        preference: P2pBandPreference,
        useUpperBand: Boolean = false
    ): List<Int> {
        if (!appliesTo(sdkInt)) return emptyList()
        val fiveGhz = if (useUpperBand) CHANNEL_UPPER else CHANNEL_LOWER
        return when (preference) {
            P2pBandPreference.AUTO -> listOf(fiveGhz, CHANNEL_24_GHZ)
            P2pBandPreference.FORCE_5GHZ -> listOf(fiveGhz)
            P2pBandPreference.FORCE_2_4GHZ -> listOf(CHANNEL_24_GHZ)
        }
    }

    /**
     * The frequency a channel number names: 2.4 GHz channels count from 2407 MHz, 5 GHz channels from
     * 5000 MHz, and channel 14 is the standard's own exception to both.
     *
     * Here so a test can assert what the app is actually asking the driver for, and so the log can
     * name the frequency rather than a channel number the reader has to convert.
     */
    fun frequencyMhzFor(channel: Int): Int = when {
        channel !in 1..165 -> 0
        // Channel 14 sits 12 MHz above 13 rather than 5, so the arithmetic does not reach it and
        // AOSP special-cases it in ScanResult.convertChannelToFrequencyMhzIfSupported for the same
        // reason. It is Japan-only and 802.11b-only, so attemptChannels() will never name it -
        // but a converter that quietly answers 2477 is worse than one that refuses. The constant is
        // P2pChannelPolicy's because that object converts the other way and must agree with this one.
        channel == CHANNEL_24_GHZ_TOP -> P2pChannelPolicy.CHANNEL_14_MHZ
        channel <= CHANNEL_24_GHZ_TOP -> 2407 + channel * 5
        else -> 5000 + channel * 5
    }

    /** A channel the platform will accept. Outside this it rejects the whole request. */
    fun isRequestable(channel: Int): Boolean = channel == CHANNEL_UNRESTRICTED || channel in 1..165
}
