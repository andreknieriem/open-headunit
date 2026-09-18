package com.andrerinas.openheadunit.decoder.audio

/**
 * Whether to take system audio focus away from other players on the head unit, for both routes that
 * do it: [shouldAcquire] for the dynamic path, which holds focus while an AA audio channel plays,
 * and [shouldAcquirePermanent] for static mode, which holds it for the whole session.
 *
 * Taking focus exists so a *local* player — typically the car's FM radio — pauses while AA audio
 * plays and resumes when it stops. It backfires when the other player is the head unit's Bluetooth
 * A2DP sink: AOSP's `A2dpSinkStreamHandler` answers the focus loss with an AVRCP passthrough PAUSE
 * to the source device, and that source is the very phone feeding the AA stream. On the dynamic
 * path the phone pauses, Android Auto tears the audio sink down, we release focus, the phone
 * resumes, and the whole thing repeats every few seconds. Nothing in the focus API distinguishes
 * the two cases, so this decides it from context instead.
 *
 * The dynamic path decides it by trying. A connected A2DP link used to veto the grab outright, but
 * plenty of units have one connected and idle while AA audio arrives over USB, and a factory head
 * unit that routes its amplifier from audio focus then plays nothing at all. So [shouldAcquire]
 * takes focus and lets the self-defeating detector — the thing that observes the harm instead of
 * guessing at it — stop us after two cycles. The veto stays on the static path, which has no
 * detector.
 *
 * The two entry points are exact complements on the `staticAudioFocus` axis: whichever route is
 * live, the other declines, so they can never both hold focus. A test asserts it.
 *
 * Pure and unit-tested; the I/O and the runtime bookkeeping live in [AapAudio] and [com.andrerinas.openheadunit.aap.AapService].
 */
object PlaybackFocusPolicy {

    /** User override for the automatic behaviour. Stored as an ordinal in settings. */
    enum class Mode(val value: Int) {
        /** Take focus until we observe it silencing the phone we are projecting. */
        AUTO(0),

        /** Always take focus. For units where the automatic answer comes out wrong. */
        ALWAYS(1),

        /** Never take focus. */
        NEVER(2);

        companion object {
            private val map = values().associateBy(Mode::value)
            fun fromInt(value: Int): Mode = map[value] ?: AUTO
        }
    }

    /**
     * @param isAudioChannel   the channel is one of AUDIO / AUDIO1 / AUDIO2.
     * @param staticAudioFocus static mode holds focus permanently and manages it elsewhere, so the
     *                         dynamic path must keep its hands off entirely.
     * @param selfDefeatingLatched we already observed the phone cut its own audio right after we
     *                          took focus, more than once. This is the answer that decides AUTO.
     * @param isLoopbackSession Self Mode, which announces no media or speech sink, so the player we
     *                          would evict is the one on this device. The latch cannot learn this
     *                          one: it counts only the media channel, and that channel never opens
     *                          here. Outranks every mode, [Mode.ALWAYS] included.
     */
    fun shouldAcquire(
        mode: Mode,
        staticAudioFocus: Boolean,
        audioSinkEnabled: Boolean,
        isAudioChannel: Boolean,
        selfDefeatingLatched: Boolean,
        isLoopbackSession: Boolean
    ): Boolean {
        if (isLoopbackSession) return false
        // Pre-existing gates, unchanged: these decide whether the dynamic path runs at all.
        if (staticAudioFocus || !audioSinkEnabled || !isAudioChannel) return false

        return when (mode) {
            Mode.NEVER -> false
            Mode.ALWAYS -> true
            Mode.AUTO -> !selfDefeatingLatched
        }
    }

    /**
     * The static-mode counterpart of [shouldAcquire]: whether to take the permanent
     * `AUDIOFOCUS_GAIN` that static mode holds for a whole session, grabbed at connect time before
     * any audio exists.
     *
     * Same harm as the dynamic path — an A2DP sink answers our focus loss by pausing the phone we
     * project — but a permanent `AUDIOFOCUS_LOSS` is not a `LOSS_TRANSIENT`: the sink pauses once and
     * does not resume when we abandon focus, so the failure is a session that starts silent rather
     * than one that cycles.
     *
     * That is why this path still refuses on a connected Bluetooth media link where the dynamic path
     * now tries: there is nothing here for the latch to observe. It counts media channels closing
     * shortly after a grab, and static mode grabs once, outside any channel's lifetime. A unit whose
     * probe reads nothing therefore needs [Mode.NEVER] set by hand.
     */
    fun shouldAcquirePermanent(
        mode: Mode,
        staticAudioFocus: Boolean,
        audioSinkEnabled: Boolean,
        btMediaLinkActive: Boolean,
        isLoopbackSession: Boolean
    ): Boolean {
        // Self Mode: a session-long GAIN is a permanent LOSS for the player on this device, which
        // pauses once and never resumes. See shouldAcquire.
        if (isLoopbackSession) return false
        // Pre-existing gates, unchanged: the permanent grab belongs to static mode alone.
        if (!staticAudioFocus || !audioSinkEnabled) return false

        return when (mode) {
            Mode.NEVER -> false
            Mode.ALWAYS -> true
            Mode.AUTO -> !btMediaLinkActive
        }
    }

    /**
     * Whether an audio channel that stopped this soon after we took focus should count towards the
     * latch. Measured on the reporting unit, the phone paused within 60-660 ms of the grant and
     * Android Auto dropped the sink 3.4-4.1 s later; a track a listener actually chose to stop
     * lasts far longer than the window.
     *
     * Only the media channel qualifies — speech and system-sound channels are short by nature, so
     * counting them would latch on a single navigation prompt.
     */
    fun countsAsSelfDefeating(isMediaChannel: Boolean, msSinceAcquire: Long): Boolean =
        isMediaChannel && msSinceAcquire in 0L until SELF_DEFEATING_WINDOW_MS

    /** Consecutive self-defeating stops before we stop taking focus. Remembered across sessions, so
     *  a unit that behaves this way pays for the trial once rather than at every connect. */
    const val SELF_DEFEATING_LIMIT = 2

    const val SELF_DEFEATING_WINDOW_MS = 5_000L
}
