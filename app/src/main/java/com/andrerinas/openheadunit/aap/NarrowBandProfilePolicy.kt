package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.utils.Settings

/**
 * What to ask the phone for when the only radio this unit has is a 2.4 GHz one.
 *
 * [com.andrerinas.openheadunit.aap.protocol.messages.ServiceDiscoveryResponse] announces one video
 * configuration, and the protocol has no bitrate field: resolution, 30-versus-60 fps and the codec
 * are the whole of what we can ask for less of. Audio has one lever, AAC instead of PCM, and on
 * such a link uncompressed music is the largest stream, so the cap covers it too.
 *
 * **This used to only advise.** Two units have now produced the same failure - the phone joins,
 * opens the video channel and closes the socket seconds later having sent no frame at all - and on
 * the second the radio has no 5 GHz band at all, so the band is not a remedy anyone can reach. A
 * lower profile held on the same access point in both cases, so the cap is now applied and
 * [Settings.narrowBandProfileCap] is how a user who disagrees turns it off.
 *
 * **A third reason was added for platforms that cannot be asked**, below API 21, where
 * `is5GHzBandSupported` does not exist and no transport reports a frequency: both inputs read
 * absent, so the cap could never fire on the oldest hardware, which is the hardware least able to
 * carry a full-rate stream. That arm rests on **one** measured unit, an Android 4.4.2 tablet that
 * died repeatedly at 1080p and held 720p indefinitely, not on the two the arms above have. The
 * same opt-out covers it and [advice] says exactly what was done.
 *
 * Pure, so the wording and every gate are a unit test rather than a device.
 */
object NarrowBandProfilePolicy {

    /** The frame rate the wire carries when the user has not asked for less. */
    const val FULL_FRAME_RATE = 60

    /** What the frame rate is lowered to. The only other value the announcement can carry. */
    const val CAPPED_FRAME_RATE = 30

    /** What the resolution is lowered to, and never below: 480p was measured, 720p is the ceiling. */
    val CAPPED_RESOLUTION = Settings.Resolution._1280x720

    /** Above this is 5 GHz. Zero is "the platform would not say", never 2.4 GHz. */
    const val MAX_24GHZ_FREQUENCY_MHZ = 4000

    /**
     * Whether this session runs on a 2.4 GHz link, for either of the two reasons there are.
     *
     * The radio having no 5 GHz band is the one this started as, and only a `false` counts: a
     * `true` describes the station side and a null means the platform would not answer, so neither
     * is grounds for lowering somebody's picture. The second is the network actually in use, which
     * a unit with a 5 GHz radio reaches by choosing 2.4 GHz or by a 5 GHz request falling back. The
     * link is equally narrow either way, and the app already tells that user to expect 720p.
     *
     * [bandUnreadable] is the third, and it is deliberately not a null [supports5Ghz]: a null also
     * means a WiFi service that refused on a unit that can be asked, and that unit must keep its
     * picture. This one means the platform has no such call, so a `true` here cannot have come
     * from a reading and does not save a unit from the cap.
     */
    fun runsNarrow(
        supports5Ghz: Boolean?,
        sessionFrequencyMhz: Int,
        bandUnreadable: Boolean = false,
    ): Boolean =
        supports5Ghz == false || bandUnreadable || sessionFrequencyMhz in 1..MAX_24GHZ_FREQUENCY_MHZ

    /**
     * Whether this session should be asked for less than the user's settings say.
     *
     * A wired session does not care what the radio is doing, and the user can say no; what is left
     * is [runsNarrow]. The frequency defaults to zero so a caller that cannot read one is answered
     * on the radio alone, which is every unit below API 29.
     *
     * [linkProvedTooSlow] is the second road to the same profile and is deliberately **not** behind
     * [capEnabled]: the band switch is a guess about a link, while this is that link having already
     * refused to carry the picture three sessions running
     * ([com.andrerinas.openheadunit.connection.VideoStarvationPolicy]). 720p with a picture beats
     * 1080p without one, and the user's way back is the resolution and frame rate themselves.
     */
    fun caps(
        supports5Ghz: Boolean?,
        wirelessSession: Boolean,
        capEnabled: Boolean,
        sessionFrequencyMhz: Int = 0,
        bandUnreadable: Boolean = false,
        linkProvedTooSlow: Boolean = false
    ): Boolean = wirelessSession &&
        (linkProvedTooSlow || (capEnabled && runsNarrow(supports5Ghz, sessionFrequencyMhz, bandUnreadable)))

    /**
     * The frame rate to announce. Only ever lowers: a user already on 30 is left there, and this
     * never raises anybody to 60.
     */
    fun cappedFrameRate(
        fpsLimit: Int,
        supports5Ghz: Boolean?,
        wirelessSession: Boolean,
        capEnabled: Boolean,
        sessionFrequencyMhz: Int = 0,
        bandUnreadable: Boolean = false,
        linkProvedTooSlow: Boolean = false
    ): Int =
        if (caps(supports5Ghz, wirelessSession, capEnabled, sessionFrequencyMhz, bandUnreadable, linkProvedTooSlow))
            minOf(fpsLimit, CAPPED_FRAME_RATE)
        else fpsLimit

    /**
     * Whether to announce AAC for the audio sinks: the user's choice, or the cap's. Only ever adds
     * AAC; a user who turned it on keeps it on every link.
     */
    fun useAac(
        userChoice: Boolean,
        supports5Ghz: Boolean?,
        wirelessSession: Boolean,
        capEnabled: Boolean,
        sessionFrequencyMhz: Int = 0,
        bandUnreadable: Boolean = false,
        linkProvedTooSlow: Boolean = false
    ): Boolean = userChoice ||
        caps(supports5Ghz, wirelessSession, capEnabled, sessionFrequencyMhz, bandUnreadable, linkProvedTooSlow)

    /**
     * The ceiling this link puts on the resolution, or null when it puts none.
     *
     * A ceiling rather than a value, because the caller already holds one from the panel and must
     * keep taking the lower of the two. Handing back a resolution would raise a user on 480p.
     */
    fun linkCeiling(
        supports5Ghz: Boolean?,
        wirelessSession: Boolean,
        capEnabled: Boolean,
        sessionFrequencyMhz: Int = 0,
        bandUnreadable: Boolean = false,
        linkProvedTooSlow: Boolean = false
    ): Settings.Resolution? =
        if (caps(supports5Ghz, wirelessSession, capEnabled, sessionFrequencyMhz, bandUnreadable, linkProvedTooSlow))
            CAPPED_RESOLUTION
        else null

    /**
     * One line for the log, or null when there is nothing worth saying.
     *
     * Said whenever the cap applies, including when it changed nothing, because a line that only
     * appears in the unusual case is a line whose absence tells a reader nothing.
     */
    fun advice(
        supports5Ghz: Boolean?,
        fpsLimit: Int,
        wirelessSession: Boolean,
        capEnabled: Boolean = true,
        sessionFrequencyMhz: Int = 0,
        bandUnreadable: Boolean = false,
        linkProvedTooSlow: Boolean = false,
        localHotspotNarrow: Boolean = false
    ): String? {
        if (!wirelessSession) return null
        // The earned cap answers first: it is the one reason that is a measurement of this link
        // rather than an inference about it, and it applies whatever the band switch says.
        if (linkProvedTooSlow) {
            return "Connections here have repeatedly set up every channel and then ended without " +
                "a single video frame arriving, which is a link that cannot carry the picture " +
                "this unit is asking for. The phone is being asked for at most " +
                "${CAPPED_RESOLUTION.resName} and $CAPPED_FRAME_RATE fps, and the music is sent " +
                "as AAC rather than uncompressed PCM. Change the resolution or the frame rate in " +
                "Video settings to take this back off."
        }
        if (!runsNarrow(supports5Ghz, sessionFrequencyMhz, bandUnreadable)) return null
        // Which of the three reasons this link is narrow, because the remedies differ: the first is
        // the hardware, the second a band this user chose and can choose again, and the third is
        // nothing the user can act on at all. A real reading is preferred to "cannot ask".
        val why = when {
            supports5Ghz == false -> "This unit has no 5 GHz band, so this session runs over 2.4 GHz"
            sessionFrequencyMhz in 1..MAX_24GHZ_FREQUENCY_MHZ -> "This session's network is on 2.4 GHz"
            localHotspotNarrow -> "This unit's local hotspot is on 2.4 GHz or on a band that could not be read"
            else -> "This unit's Android is too old to report which band it is on"
        }
        // The 2.4 GHz measurement belongs to the two arms it was taken on. The third arm has its
        // own unit and says so rather than borrowing theirs.
        val evidence = if (supports5Ghz == false || sessionFrequencyMhz in 1..MAX_24GHZ_FREQUENCY_MHZ || localHotspotNarrow)
            "on a 2.4 GHz access point, a full-rate stream died having sent no frame at all where " +
                "a lower one held indefinitely"
        else
            "on an Android 4.4 tablet, a full-rate stream died seconds into every session having " +
                "sent no frame at all where a 720p one held indefinitely"
        if (!capEnabled) {
            if (fpsLimit != FULL_FRAME_RATE) return null
            return "$why, and it is " +
                "being offered $FULL_FRAME_RATE fps because lowering the profile on a narrow band " +
                "is switched off in Video settings. Measured $evidence. Nothing here has been " +
                "changed for you."
        }
        return "$why. The phone is being " +
            "asked for at most ${CAPPED_RESOLUTION.resName} and $CAPPED_FRAME_RATE fps rather than " +
            "what Video settings say: measured $evidence. The music is sent as " +
            "AAC rather than uncompressed PCM, a fraction of the bytes. Turn off \"Lower video on " +
            "a 2.4 GHz link\" in Video settings to be given what you asked for instead."
    }
}
