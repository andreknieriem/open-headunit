package com.andrerinas.openheadunit.aap

/**
 * Which band the Native AA P2P group is requested on, and whether a group that came up on the other
 * one should be torn down and remade.
 *
 * Native AA asks for 5 GHz because that is the band the reporters' working sessions run on, and a
 * group that lands on 2.4 GHz anyway is recreated rather than used. That default is not in question
 * here. What this exists for is the opposite case: two reporter threads describe a link that dies
 * for seconds at a time on a fixed cadence, on 2.4 GHz head units, and nothing on the rig could ever
 * be put on that band to look for it - `createQuietGroup` asks for 5 GHz and
 * `onGroupInfoAvailable` undoes any group that does not come up there, so the rig had two
 * independent reasons never to run the configuration the reports come from.
 *
 * The override is a debug lever, off by default, and it turns off *both* of those: asking for 2.4
 * GHz while leaving the mismatch retry armed would tear the group down as soon as it succeeded.
 * That coupling is the whole reason this is one object with a test rather than two booleans read in
 * two places.
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

    fun bandFor(force24Ghz: Boolean): Band = if (force24Ghz) Band.GHZ_2_4 else Band.GHZ_5

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
}
