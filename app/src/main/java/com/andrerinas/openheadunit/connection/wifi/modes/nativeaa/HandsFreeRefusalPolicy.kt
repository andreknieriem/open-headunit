package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/**
 * Whether a phone refused this unit's hands-free connection outright, and how long to keep asking.
 *
 * Measured on a Z Fold3 (2026-10-22 in the car, 2026-10-23 at home): with two other Bluetooth audio
 * devices connected - the car's own Bluetooth or a headset, plus earbuds - every wake poke was taken
 * and dropped. The phone logged "okToAcceptConnection: Blocked by isNewConnectionAcceptable" and the
 * whole link was gone 0.34 s later, because it holds two audio devices at a time and One UI offers
 * no setting to raise that. Android Auto opened wireless setup and gave up after 5 s, which is all
 * the driver saw, and the wake round ran out three tries later with nothing on this unit's screen.
 * With one device put away the same poke reached a session in 12 s.
 *
 * So a refusal is named on the main screen, and the chosen phone is asked again, slowly, until it
 * takes the link: putting the earbuds away has to be all it takes.
 */
object HandsFreeRefusalPolicy {

    /** When the phone's link is read after the poke connects. The Fold dropped it within 0.34 s. */
    const val LINK_PROBE_MS = 2_000L

    /** How long a refusing phone is asked at [RETRY_GAP_MS] after the first refusal of a wake. */
    const val PATIENCE_MS = 10 * 60_000L

    /** The gap between asks while it refuses: each ask opens Android Auto on the phone for 5 s. */
    const val RETRY_GAP_MS = 30_000L

    /** The gap after [PATIENCE_MS]: the asking slows rather than stops. */
    const val SLOW_RETRY_GAP_MS = 2 * 60_000L

    /**
     * How long a refusing phone is asked at all. Earbuds taken out a quarter of an hour into a drive
     * still connect by themselves, as the banner promises (the user's choice, 2026-10-24).
     */
    const val SLOW_PATIENCE_MS = 60 * 60_000L

    /**
     * Refused, as opposed to silent: the hands-free walk got no answer and the phone's whole link
     * went with it. A phone already serving another device's link stays connected and says nothing,
     * and a link that cannot be read is never taken as gone.
     */
    fun refused(stage: HfpSlcInitiator.Stage, linkAfterProbe: BluetoothWakePolicy.TargetLink): Boolean =
        (stage == HfpSlcInitiator.Stage.IDLE || stage == HfpSlcInitiator.Stage.BRSF) &&
            linkAfterProbe == BluetoothWakePolicy.TargetLink.ABSENT

    /**
     * Whether a chosen phone's wake keeps going past its ordinary rounds because it refused.
     *
     * The refusal decides, not the last round: on the Fold the ask after a refusal failed to connect
     * at all (2026-10-24), and a wake that needed every round to be a refusal stopped right there.
     * [firstRefusalAtMs] is 0 while the phone has not refused during this wake.
     */
    fun keepsAsking(firstRefusalAtMs: Long, nowMs: Long): Boolean =
        firstRefusalAtMs != 0L && nowMs - firstRefusalAtMs < SLOW_PATIENCE_MS

    /**
     * The gap before the next ask: [ordinaryGapMs] until the phone refuses, then [RETRY_GAP_MS] for
     * [PATIENCE_MS], then [SLOW_RETRY_GAP_MS].
     */
    fun gapMs(firstRefusalAtMs: Long, nowMs: Long, ordinaryGapMs: Long): Long = when {
        firstRefusalAtMs == 0L -> ordinaryGapMs
        nowMs - firstRefusalAtMs < PATIENCE_MS -> RETRY_GAP_MS
        else -> SLOW_RETRY_GAP_MS
    }
}
