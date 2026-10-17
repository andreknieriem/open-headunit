package com.andrerinas.openheadunit.connection.wifi.direct

import com.andrerinas.openheadunit.connection.wifi.FiveGhzChannelPolicy

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

    /**
     * The band to request for [preference].
     *
     * [P2pBandPreference.FORCE_2_4GHZ] asks for 2.4 GHz, and so does [P2pBandPreference.AUTO] on a
     * radio that has told us it has no 5 GHz band. Asking anyway costs a bring-up rather than a
     * band: the request fails, [shouldRetryFor5Ghz] cannot fire because 5 GHz never arrived, and the
     * user is left finding the 2.4 GHz toggle by hand, which is what a reporter on such a unit did.
     *
     * @param supports5Ghz [com.andrerinas.openheadunit.connection.wifi.direct.WifiBandCapability.supports5Ghz],
     *   where null means the platform would not say. Only `false` changes anything here - a `true`
     *   describes the station side and is not a promise that a group owner can be hosted there, so
     *   AUTO keeps its own fallback rather than trusting it.
     */
    fun bandFor(preference: P2pBandPreference, supports5Ghz: Boolean? = null): Band = when {
        preference == P2pBandPreference.FORCE_2_4GHZ -> Band.GHZ_2_4
        preference == P2pBandPreference.AUTO && supports5Ghz == false -> Band.GHZ_2_4
        else -> Band.GHZ_5
    }

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

    /**
     * The frequency to pin the group request to on API 29+, or 0 to ask for a band instead.
     *
     * `WifiP2pConfig.Builder.setGroupOperatingBand(GROUP_OWNER_BAND_5GHZ)` does not name a channel,
     * and wpa_supplicant answers it by picking a *random* start index into
     * {5180, 5200, 5220, 5240, 5745, 5765, 5785, 5805} - so half of all bring-ups land in UNII-3,
     * which several regulatory domains forbid phones from joining. `setGroupOperatingFrequency` is
     * public API from 29 and takes a literal MHz, which is the only way to stop that being a coin
     * flip. The two are mutually exclusive: `build()` throws if both are set.
     *
     * Only for a 5 GHz request. A pinned 5 GHz channel on a group deliberately asked for on 2.4 GHz
     * would be a contradiction, and the setting is shared with the hotspot route, so it is set on
     * units that are not on this band at all.
     */
    fun requestedFrequencyMhz(band: Band, chosenChannel: Int): Int {
        if (band != Band.GHZ_5) return 0
        val channel = FiveGhzChannelPolicy.pinnedChannel(chosenChannel)
        if (channel == FiveGhzChannelPolicy.AUTOMATIC) return 0
        return WifiP2pOperatingChannelPolicy.frequencyMhzFor(channel)
    }

    /**
     * Whether this bring-up should skip the banded request and go straight to the no-band create.
     *
     * Measured on a MediaTek Helio P22 phone on Android 10: every `setGroupOperatingBand` create is
     * refused with ERROR (Internal Error), on every session, and the no-band create that follows
     * comes up on 5 GHz anyway. The ladder in front of it is four refusals two seconds apart, so
     * each connection paid about 8.5 s for an answer the unit had already given. The refusal is
     * a property of the radio firmware, not of the moment, so it is remembered against the build
     * fingerprint and the ladder is skipped while that build is the one running.
     *
     * Only for [P2pBandPreference.AUTO], whose fallback is the platform's choice anyway: the two
     * forced positions mean the user asked for that band, so they keep asking. Never with a pinned
     * channel either, which is an explicit choice with its own ladder. A firmware update changes
     * the fingerprint and so asks again, and changing the band setting clears the record.
     *
     * @param refusedOnFingerprint the build the refusal was recorded on, or null if none was.
     */
    fun skipsBandedRequest(
        preference: P2pBandPreference,
        channelPinned: Boolean,
        refusedOnFingerprint: String?,
        currentFingerprint: String,
    ): Boolean = preference == P2pBandPreference.AUTO &&
        !channelPinned &&
        !refusedOnFingerprint.isNullOrEmpty() &&
        refusedOnFingerprint == currentFingerprint

    /**
     * Whether a no-band group that formed after the banded ladder was exhausted should be recorded
     * as this unit's standing answer. Only under [P2pBandPreference.AUTO], for the reason above.
     */
    fun recordsBandedRefusal(preference: P2pBandPreference, ladderExhausted: Boolean): Boolean =
        ladderExhausted && preference == P2pBandPreference.AUTO

    /** What a failed Native AA group request does next. */
    enum class NextStep {
        /** Ask again for the same thing. */
        RETRY,

        /** Keep the band, drop the user's channel, and start the budget over. */
        DROP_PINNED_CHANNEL,

        /** Ask for nothing and let the platform choose. */
        STANDARD_FALLBACK,

        /** 5 GHz only, and it will not form one. No group. */
        GIVE_UP,
    }

    /**
     * Which of those a failure leads to.
     *
     * A pinned frequency is a *forced* frequency all the way down to wpa_supplicant, which does
     * `goto fail` rather than choosing something else when the regulatory domain or the driver
     * refuses it - so a channel the unit will not host costs the group, not the channel. Dropping it
     * after the budget is spent is what keeps a wrong choice from being a dead unit; the caller says
     * so in the log, because a link on a channel the phone's own domain forbids is a link that phone
     * cannot see, and the user needs to know their choice is not what they are looking at.
     */
    fun nextStepAfterFailure(
        preference: P2pBandPreference,
        channelPinned: Boolean,
        retriesSoFar: Int,
        maxRetries: Int,
    ): NextStep = when {
        retriesSoFar < maxRetries -> NextStep.RETRY
        channelPinned -> NextStep.DROP_PINNED_CHANNEL
        fallsBackToPlatformChoice(preference) -> NextStep.STANDARD_FALLBACK
        else -> NextStep.GIVE_UP
    }

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
    fun describePreference(preference: P2pBandPreference, supports5Ghz: Boolean? = null): String =
        when (preference) {
            // Says what AUTO is actually about to do rather than what it usually does: on a radio
            // with no 5 GHz band it no longer starts there, and a line claiming otherwise would
            // contradict the request logged on the next line.
            P2pBandPreference.AUTO -> if (supports5Ghz == false) {
                "automatic (2.4 GHz - this radio has no 5 GHz band)"
            } else {
                "automatic (5 GHz, then whatever this unit will host)"
            }
            P2pBandPreference.FORCE_5GHZ -> "5 GHz only, set by the user"
            P2pBandPreference.FORCE_2_4GHZ -> "2.4 GHz only, set by the user"
        }
}
