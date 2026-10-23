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

    /** How long a refusing phone is asked again after the first refusal of a wake. */
    const val PATIENCE_MS = 10 * 60_000L

    /** The gap between asks while it refuses: each ask opens Android Auto on the phone for 5 s. */
    const val RETRY_GAP_MS = 30_000L

    /**
     * Refused, as opposed to silent: the hands-free walk got no answer and the phone's whole link
     * went with it. A phone already serving another device's link stays connected and says nothing,
     * and a link that cannot be read is never taken as gone.
     */
    fun refused(stage: HfpSlcInitiator.Stage, linkAfterProbe: BluetoothWakePolicy.TargetLink): Boolean =
        (stage == HfpSlcInitiator.Stage.IDLE || stage == HfpSlcInitiator.Stage.BRSF) &&
            linkAfterProbe == BluetoothWakePolicy.TargetLink.ABSENT

    /** Whether a chosen phone's wake keeps going past its ordinary rounds because it refused. */
    fun keepsAsking(refusedLastRound: Boolean, sinceFirstRefusalMs: Long): Boolean =
        refusedLastRound && sinceFirstRefusalMs < PATIENCE_MS
}
