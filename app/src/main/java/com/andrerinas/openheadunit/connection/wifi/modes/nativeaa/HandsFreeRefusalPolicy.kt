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
     * Whether a hands-free link [linkedMac] completed retires the refusal record. Only the phone that
     * refused, or any phone when none here is known to have raised it: another phone's link says nothing.
     */
    fun retiresRefusal(linkedMac: String, refuserMac: String?): Boolean =
        refuserMac == null || refuserMac.equals(linkedMac, ignoreCase = true)

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

    /**
     * A chosen phone that still refuses after its ordinary rounds, and when it was last asked.
     * [namedByDriver] is fixed when the pick starts refusing, not read from the live pick later.
     */
    data class RefusingPick(
        val mac: String,
        val firstRefusalAtMs: Long,
        val lastAskAtMs: Long,
        val namedByDriver: Boolean = false
    )

    /**
     * The refusing pick the wake plan asks although the plan leaves it out, or null. Only a phone the
     * driver named: "Only these phones" still wakes a phone picked by hand, and nothing else.
     */
    fun unplannedAsk(pick: RefusingPick?, planned: Collection<String>): String? =
        pick?.takeIf { it.namedByDriver && planned.none { mac -> mac.equals(it.mac, ignoreCase = true) } }?.mac

    /** What a chosen wake does once only the refusal keeps it going. [GIVE_WAY] hands over once a wake loop waits. */
    enum class AfterRounds { KEEP_SLOT, GIVE_WAY, HAND_OVER }

    /**
     * The slow asks must not keep the wake slot from another phone of the plan. A phone the driver
     * named never starts the plan itself, nor does one with no credentials to start a loop with.
     * [planCanStart] false (a handshake in flight, listeners down, a session) keeps the slot too.
     */
    fun afterRounds(
        otherPhones: Boolean,
        credentialsReady: Boolean,
        namedByDriver: Boolean,
        wakeLoopWaits: Boolean,
        planCanStart: Boolean
    ): AfterRounds = when {
        !otherPhones -> AfterRounds.KEEP_SLOT
        !credentialsReady || !planCanStart || (namedByDriver && !wakeLoopWaits) -> AfterRounds.GIVE_WAY
        else -> AfterRounds.HAND_OVER
    }

    /**
     * Whether the wake plan asks a phone that refused it, at the gaps of a refusing pick. A phone with
     * no [refusal], or one past its hour, keeps the ordinary cadence: each ask opens Android Auto.
     */
    fun planAskDue(refusal: RefusingPick?, nowMs: Long): Boolean =
        refusal == null || !keepsAsking(refusal.firstRefusalAtMs, nowMs) || askDue(refusal, nowMs)

    /** [mac]'s refusal after the plan asked it: a refusal opens one, an ask moves an open one, the hour ends it. */
    fun afterPlanAsk(mac: String, refusal: RefusingPick?, refused: Boolean, nowMs: Long): RefusingPick? {
        val open = refusal?.takeIf { keepsAsking(it.firstRefusalAtMs, nowMs) }
        return when {
            open != null -> open.copy(lastAskAtMs = nowMs)
            refused -> RefusingPick(mac, nowMs, nowMs)
            else -> null
        }
    }

    /** Whether the wake plan asks [pick] on this pass: the same gaps and the same end as the chosen wake. */
    fun askDue(pick: RefusingPick, nowMs: Long): Boolean =
        keepsAsking(pick.firstRefusalAtMs, nowMs) &&
            nowMs - pick.lastAskAtMs >= gapMs(pick.firstRefusalAtMs, pick.lastAskAtMs, RETRY_GAP_MS)
}
