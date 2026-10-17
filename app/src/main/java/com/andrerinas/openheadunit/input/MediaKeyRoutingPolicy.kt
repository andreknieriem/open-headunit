package com.andrerinas.openheadunit.input

/**
 * Whether a physical media button should be forwarded to Android Auto, or left to the Bluetooth
 * side that may already be acting on the same press.
 *
 * A head unit can end up with two consumers for one button. The OEM key handler delivers the press
 * to us *and* the Bluetooth media stack acts on it independently: AOSP's
 * `BluetoothMediaBrowserService` publishes a real media session whose skip-to-next issues an AVRCP
 * passthrough FORWARD to the source device — the same phone that is projecting to us. Both
 * consumers reach the same media app, so one press skips two tracks.
 *
 * Only the cumulative commands show it. Play/pause is a state-specific passthrough (PLAY or PAUSE
 * depending on the sink's own playback state), so two consumers converge on one result and the user
 * sees nothing wrong; next/previous add up. That asymmetry is the fingerprint of this failure, and
 * it is what rules out "the key is sent twice" faults inside this app, which would double both.
 *
 * Nothing in the platform arbitrates the two: the Bluetooth profile calls that would deactivate the
 * other consumer are `BLUETOOTH_PRIVILEGED`, aborting the broadcast does not work because the
 * framework's media-button route is single-target rather than an ordered fan-out, and taking audio
 * focus to deactivate the sink's session is exactly the behaviour [com.andrerinas.openheadunit.decoder.audio.PlaybackFocusPolicy] exists to
 * avoid. So the lever is which consumer *we* leave the key to.
 *
 * Pure and unit-tested; the Bluetooth probe and the send live in `CommManager`.
 */
object MediaKeyRoutingPolicy {

    /** User choice for who owns the media buttons. Stored as an ordinal in settings. */
    enum class Mode(val value: Int) {
        /**
         * Always forward. The value of an unset preference, so a head unit whose Bluetooth side
         * does nothing with these buttons keeps working with no setting touched.
         */
        ALWAYS(0),

        /**
         * Forward unless this head unit has a Bluetooth media link of its own, in which case that
         * link is very likely the phone we project and is already performing the action.
         */
        AUTO(1),

        /**
         * Never forward. For the case [AUTO] cannot see: the phone's Bluetooth link is to the car's
         * own system rather than to this head unit, so our adapter reports nothing while the factory
         * radio still acts on every press.
         */
        NEVER(2);

        companion object {
            private val map = values().associateBy(Mode::value)
            fun fromInt(value: Int): Mode = map[value] ?: ALWAYS
        }
    }

    /**
     * @param isMediaKey       the key is one of the transport controls. Everything else — the rotary
     *                         controller, D-pad, Back, Enter — is always forwarded, whatever the
     *                         mode: those have no Bluetooth consumer competing for them, and a
     *                         setting about media buttons that quietly disabled the controller would
     *                         be a worse bug than the one it fixes.
     * @param btMediaLinkActive whether a Bluetooth media (A2DP) link to this head unit is up, or
     *                         `null` when the adapter would not say. Unknown forwards: a doubled
     *                         skip is an annoyance, media buttons that silently do nothing read as a
     *                         broken app. Note this is the opposite resolution to
     *                         [com.andrerinas.openheadunit.decoder.audio.PlaybackFocusPolicy], where an unreadable state means "assume a link
     *                         is up" — there the cautious answer is to leave focus alone.
     * @param isLoopbackSession Self Mode. The player is on this device and holds a real session of
     *                         its own, while a forwarded key is routed by Android Auto's input focus
     *                         and so works only while the player is on screen. Outranks every mode,
     *                         [Mode.ALWAYS] included, which speaks for a session to a separate phone.
     */
    fun shouldForward(
        mode: Mode,
        isMediaKey: Boolean,
        btMediaLinkActive: Boolean?,
        isLoopbackSession: Boolean
    ): Boolean {
        if (!isMediaKey) return true
        if (isLoopbackSession) return false

        return when (mode) {
            Mode.ALWAYS -> true
            Mode.NEVER -> false
            Mode.AUTO -> btMediaLinkActive != true
        }
    }

    /** What happens to a media key [shouldForward] declined. */
    enum class Declined {
        /** Hand the logical key to the system. Not every route has an event to pass on, so Self Mode sends it itself. */
        DISPATCH,

        /** The caller's own event is the right key, so a caller holding one may pass it on. */
        FALL_THROUGH,

        /** The physical code means something else here, so passing the event on would be the wrong key. */
        DROP
    }

    fun onDeclined(isLoopbackSession: Boolean, isRemapped: Boolean): Declined = when {
        isLoopbackSession -> Declined.DISPATCH
        isRemapped -> Declined.DROP
        else -> Declined.FALL_THROUGH
    }
}
