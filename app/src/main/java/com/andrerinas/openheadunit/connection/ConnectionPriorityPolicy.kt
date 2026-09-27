package com.andrerinas.openheadunit.connection

/**
 * Which connection attempt goes ahead when two want the phone at once. A higher tier ends a lower
 * one mid-attempt and holds it off, and what was given up comes back once the higher one ends.
 */
object ConnectionPriorityPolicy {

    /** Lower rank wins. The armed wireless stack sits below all three and is never a holder. */
    enum class Tier(val rank: Int) { USER(1), USB(2), WIRELESS_HANDSHAKE(3) }

    /** Who runs an attempt. One owner's claims hand over to each other rather than compete. */
    enum class Owner { USB, WIRELESS_STACK, MANUAL }

    enum class Verdict { PROCEED, PREEMPT, REFUSE }

    /** A claim this old is a leak rather than an attempt, so it stops holding anything off. */
    const val STALE_CLAIM_MS = 90_000L

    fun decide(
        incoming: Tier,
        incomingOwner: Owner,
        holder: Tier?,
        holderOwner: Owner?,
        holderAgeMs: Long,
    ): Verdict = when {
        holder == null || holderOwner == null || holderAgeMs >= STALE_CLAIM_MS -> Verdict.PROCEED
        holderOwner == incomingOwner -> Verdict.PROCEED
        incoming.rank < holder.rank -> Verdict.PREEMPT
        // Two different things the user asked for: the newer request is the one they mean.
        incoming == Tier.USER && holder == Tier.USER -> Verdict.PREEMPT
        else -> Verdict.REFUSE
    }

    /**
     * How long USB may go quiet between its own tries before what it took comes back. A dongle
     * whose handshake fails re-attaches 5.5 s later and the service re-checks at 3 s.
     */
    const val USB_QUIET_MS = 8_000L

    /** How long one plug-in may keep failing before wireless comes back beside it. */
    const val USB_EPISODE_BUDGET_MS = 60_000L

    /**
     * Anything from outside the wireless stack stands it down, since the stack is the lowest tier;
     * USB stops doing so once its plug-in has spent its budget.
     */
    fun standsDownWireless(owner: Owner, usbEpisodeSpent: Boolean = false): Boolean = when (owner) {
        Owner.WIRELESS_STACK -> false
        Owner.USB -> !usbEpisodeSpent
        Owner.MANUAL -> true
    }

    fun usbEpisodeSpent(msSinceEpisodeStart: Long): Boolean = msSinceEpisodeStart >= USB_EPISODE_BUDGET_MS

    /**
     * A USB attempt that ended with no session waits out [USB_QUIET_MS] for its next try, spent or
     * not: a spent plug-in that ended early would start a fresh budget on the next retry.
     */
    fun giveBackDelayMs(owner: Owner): Long = if (owner == Owner.USB) USB_QUIET_MS else 0L

    /**
     * Whether an automatic wireless bring-up waits for the attempt in flight. Only a holder from
     * outside the stack holds it off; the stack's own re-arms during its handshake are its business.
     */
    fun refusesBackground(outsideHolderInFlight: Boolean, userRequested: Boolean): Boolean =
        outsideHolderInFlight && !userRequested
}
