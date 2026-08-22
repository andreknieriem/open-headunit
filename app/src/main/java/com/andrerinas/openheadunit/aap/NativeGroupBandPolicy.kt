package com.andrerinas.openheadunit.aap

/** Which band the user wants the Native AA WiFi Direct group brought up on. */
enum class P2pBandPreference {
    /** Ask for 5 GHz, and take what the platform gives if it will not host one. The default. */
    AUTO,

    /** Ask for 5 GHz and stop there, rather than landing on a band that may carry no video. */
    FORCE_5GHZ,

    /** Ask for 2.4 GHz only, for a radio that will not host a 5 GHz group owner. */
    FORCE_2_4GHZ;

    companion object {
        /** [Settings.wifiDirectBand][com.andrerinas.openheadunit.utils.Settings.wifiDirectBand] as a
         *  preference, defaulting to [AUTO] for any value we do not recognise. */
        fun fromSetting(value: Int): P2pBandPreference = when (value) {
            1 -> FORCE_5GHZ
            2 -> FORCE_2_4GHZ
            else -> AUTO
        }
    }
}

/**
 * Which band the Native AA P2P group is requested on, and whether a group that came up on the other
 * one should be torn down and remade.
 *
 * Native AA asks for 5 GHz because that is the band the reporters' working sessions run on, and a
 * group that lands on 2.4 GHz anyway is recreated rather than used. That is what [P2pBandPreference
 * .AUTO] still does. What the other two positions exist for is the opposite case: two reporter
 * threads describe a link that dies for seconds at a time on a fixed cadence, on 2.4 GHz head units,
 * and nothing on the rig could ever be put on that band to look for it - `createQuietGroup` asks for
 * 5 GHz and `onGroupInfoAvailable` undoes any group that does not come up there, so the rig had two
 * independent reasons never to run the configuration the reports come from.
 *
 * Asking for 2.4 GHz turns off *both* of those, and it has to: leaving the mismatch retry armed
 * would tear the group down as soon as it succeeded. That coupling is the whole reason this is one
 * object with a test rather than two flags read in two places, and it now falls out of the request
 * itself - [shouldRetryFor5Ghz] only ever fires when 5 GHz was what was asked for.
 *
 * This started as a debug lever and is now a user preference, because the same question has a
 * user-facing answer on the hotspot route ([SoftApBandPolicy]) and having it on one transport and
 * not the other was the accident rather than the design. What each position costs is written down
 * in the hint string beside it and, for the measurement behind it, in [SoftApBandPolicy]'s KDoc.
 */
object NativeGroupBandPolicy {

    enum class Band {
        GHZ_2_4,
        GHZ_5,

        /**
         * No band was asked for - the standard `createGroup` fallback, where the platform picks.
         * A group nobody chose a band for cannot be on the wrong one, so it is never remade.
         */
        UNSPECIFIED,
    }

    /** Below this, a P2P group is on 2.4 GHz. The 5 GHz band starts at 5170 MHz. */
    private const val MAX_24GHZ_FREQUENCY_MHZ = 4000

    /** The band to request for [preference]. Only [P2pBandPreference.FORCE_2_4GHZ] asks for 2.4 GHz. */
    fun bandFor(preference: P2pBandPreference): Band =
        if (preference == P2pBandPreference.FORCE_2_4GHZ) Band.GHZ_2_4 else Band.GHZ_5

    /**
     * Whether an exhausted band request may drop to the no-band `createGroup` and let the platform
     * choose.
     *
     * True for [P2pBandPreference.AUTO], which is the behaviour that has always shipped: four failed
     * 5 GHz attempts and then a group on whatever the driver likes, because no group at all is worse
     * than a group on the wrong band. False for [P2pBandPreference.FORCE_5GHZ], which is what that
     * position means - a session on 2.4 GHz can connect, look entirely healthy and show nothing,
     * which is harder to diagnose than a group that never forms. [P2pBandPreference.FORCE_2_4GHZ]
     * never reaches this: its request is the band the fallback would have landed on anyway.
     */
    fun fallsBackToPlatformChoice(preference: P2pBandPreference): Boolean =
        preference != P2pBandPreference.FORCE_5GHZ

    /**
     * True when the group that came up must be torn down and remade because it is not on the band
     * that was asked for.
     *
     * Only ever true when 5 GHz was requested: a group we deliberately put on 2.4 GHz is on the band
     * it was asked for, so there is nothing to correct, and retrying it would recreate the group
     * every time it succeeded.
     */
    fun shouldRetryFor5Ghz(
        requested: Band,
        frequencyMhz: Int,
        retriesSoFar: Int,
        maxRetries: Int,
    ): Boolean = requested == Band.GHZ_5 &&
        frequencyMhz in 1..MAX_24GHZ_FREQUENCY_MHZ &&
        retriesSoFar < maxRetries

    /** The band label used in the log, so a capture says which band was asked for and which arrived. */
    fun label(band: Band): String = when (band) {
        Band.GHZ_2_4 -> "2.4GHz"
        Band.GHZ_5 -> "5GHz"
        Band.UNSPECIFIED -> "unspecified"
    }

    /**
     * How the user's choice reads in that same log line.
     *
     * Logged on every bring-up, including [P2pBandPreference.AUTO]: a line that only appears in the
     * unusual case is a line whose absence tells a reader nothing.
     */
    fun describePreference(preference: P2pBandPreference): String = when (preference) {
        P2pBandPreference.AUTO -> "automatic (5 GHz, then whatever this unit will host)"
        P2pBandPreference.FORCE_5GHZ -> "5 GHz only, set by the user"
        P2pBandPreference.FORCE_2_4GHZ -> "2.4 GHz only, set by the user"
    }
}
