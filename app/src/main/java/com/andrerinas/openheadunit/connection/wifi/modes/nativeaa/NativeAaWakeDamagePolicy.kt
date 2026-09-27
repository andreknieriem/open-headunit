package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/**
 * Whether an escalated wake costs this head unit its hands-free link for good.
 *
 * A poke displaces the phone's single hands-free slot by design - the Audio Gateway closes the
 * connection it already holds to that address when it accepts ours - and no API can put the link
 * back. Whether the unit's own client re-establishes is a property of its stack, so it is measured
 * once rather than asked, and re-measured rather than latched where the verdict costs every session.
 */
object NativeAaWakeDamagePolicy {

    /**
     * How long the link is given to come back on its own, with the device left unpoked. Long enough
     * to cover a stack that reconnects on its own policy; the units that fail stayed down for
     * minutes, so nothing is gained by waiting longer.
     */
    const val PROBE_WINDOW_MS = 30_000L

    /**
     * Armings that reach the wake and produce no session before a DESTRUCTIVE unit is measured
     * again. One reading used to hold for the life of the install, which left units refusing every
     * poke where the poke was the only thing that ever connected them.
     */
    const val REPROBE_AFTER_ARMINGS = 5

    /** What this unit is known to do with its hands-free link when a wake takes the slot. */
    enum class Verdict {
        /** Never measured. The next escalated wake is the probe. */
        UNKNOWN,

        /** The link came back, or the wake bought a session. Waking costs a blip. */
        SAFE,

        /** The link did not come back and the wake bought nothing. */
        DESTRUCTIVE;

        companion object {
            /** Stored as an int so an unknown value from a newer build reads as unmeasured. */
            fun of(stored: Int): Verdict = entries.getOrNull(stored) ?: UNKNOWN
        }
    }

    /** Whether a measured failure has stood enough armings to be worth testing again. */
    fun shouldReprobe(verdict: Verdict, armingsWithoutSession: Int): Boolean =
        verdict == Verdict.DESTRUCTIVE && armingsWithoutSession >= REPROBE_AFTER_ARMINGS

    /** Whether a stand-down may yield at all. Only an unexpired measured failure withholds the wake. */
    fun allowsEscalation(verdict: Verdict, armingsWithoutSession: Int): Boolean =
        verdict != Verdict.DESTRUCTIVE || shouldReprobe(verdict, armingsWithoutSession)

    /** Whether this wake is the one being measured, so the probe window is armed behind it. */
    fun isProbe(verdict: Verdict, armingsWithoutSession: Int): Boolean =
        verdict == Verdict.UNKNOWN || shouldReprobe(verdict, armingsWithoutSession)

    /**
     * What the probe made of the wake. A session is the thing the wake was for, so one pays for
     * itself whatever the link reads. Otherwise a null reading is the adapter refusing to answer,
     * which is not evidence either way: stay unmeasured and probe again rather than condemn the unit.
     */
    fun verdictFrom(linkReturned: Boolean?, wakeStartedAaSession: Boolean): Verdict = when {
        wakeStartedAaSession -> Verdict.SAFE
        linkReturned == true -> Verdict.SAFE
        linkReturned == false -> Verdict.DESTRUCTIVE
        else -> Verdict.UNKNOWN
    }

    /** Whether a probe result is worth storing. An unreadable one would only overwrite a measurement. */
    fun recordable(verdict: Verdict): Boolean = verdict != Verdict.UNKNOWN
}
