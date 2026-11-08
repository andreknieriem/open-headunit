package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.MacAddressPolicy

/**
 * Which phones the wake poke and the driver pick consider, and in what order.
 * A learned phone is one that formed a session; it widens a chosen list and never replaces it.
 */
object WakePlanPolicy {

    /** Canonical, de-duplicated MACs from a comma list; blanks and non-addresses are dropped. */
    fun parseMacs(raw: String?): List<String> =
        (raw ?: "").split(',').mapNotNull { MacAddressPolicy.parse(it, allowBare = true) }.distinct()

    fun formatMacs(macs: List<String>): String = macs.joinToString(",")

    /** [mac] first, once, case ignored; the others keep their order. */
    fun movedToFront(list: List<String>, mac: String): List<String> {
        val canonical = MacAddressPolicy.parse(mac, allowBare = true) ?: return list
        return listOf(canonical) + list.filter { !it.equals(canonical, ignoreCase = true) }
    }

    /** The first read of the learned list: the last connected phone, or nothing. A stored list is never reseeded. */
    fun seedLearned(stored: String?, lastConnected: String): List<String> =
        if (stored != null) parseMacs(stored) else parseMacs(lastConnected)

    /** The phone to learn at SSL: only a wireless session counts, and only a phone that was awaiting one. */
    fun phoneToLearn(awaiting: String?, wirelessSession: Boolean): String? =
        if (wirelessSession) awaiting else null

    /**
     * A pick the selector did not name (the WiFi button, a task) is not learned before a session.
     * One the driver picked is, only when its own records say phone.
     */
    fun learnsExplicitPick(unpinned: DriverCandidatePolicy.Verdict, fromPlan: Boolean): Boolean =
        !fromPlan && unpinned == DriverCandidatePolicy.Verdict.PHONE

    /** How long a phone that did not answer waits behind the others. */
    const val BACKOFF_MS = 300_000L

    /**
     * [order] is who to wake or pick, first to last; [known] are the bonded phones listed, learned or linked.
     * [chosen] are the bonded phones listed or learned: a link alone does not choose between two linked phones.
     */
    data class WakePlan(val order: List<String>, val known: Set<String>, val chosen: Set<String>)

    /** A phone is backed off while [now] is before its deadline. */
    fun isBackedOff(mac: String, backedOffUntil: Map<String, Long>, now: Long): Boolean =
        (backedOffUntil[mac.uppercase()] ?: 0L) > now

    /**
     * The phones to wake or pick, in order: the Phones list, the learned phones, then the rest.
     * Linked phones go first, backed-off ones last (never removed), an explicit pick before all
     * unless Only these phones leaves it out.
     */
    fun plan(
        phonesList: List<String>,
        onlyListed: Boolean,
        learned: List<String>,
        explicitPick: String?,
        linked: Set<String>,
        phones: List<String>,
        backedOffUntil: Map<String, Long>,
        now: Long
    ): WakePlan {
        val bonded = phones.associateBy { it.uppercase() }
        fun bondedOf(macs: Collection<String>) = macs.mapNotNull { bonded[it.uppercase()] }
        val listed = bondedOf(phonesList)
        val base = if (onlyListed && phonesList.isNotEmpty()) listed
        else listed + bondedOf(learned) + phones
        val linkedUpper = linked.map { it.uppercase() }.toSet()
        var order = base.distinctBy { it.uppercase() }
        order = order.filter { it.uppercase() in linkedUpper } + order.filter { it.uppercase() !in linkedUpper }
        fun backed(mac: String) = isBackedOff(mac, backedOffUntil, now)
        order = order.filter { !backed(it) } + order.filter { backed(it) }
        val pick = explicitPick?.let { bonded[it.uppercase()] }?.takeIf { p -> base.any { it.equals(p, ignoreCase = true) } }
        if (pick != null && !backed(pick)) order = listOf(pick) + order.filter { !it.equals(pick, ignoreCase = true) }
        val chosenUpper = (phonesList + learned).map { it.uppercase() }.toSet()
        val knownUpper = chosenUpper + linkedUpper
        return WakePlan(
            order,
            order.filterTo(LinkedHashSet()) { it.uppercase() in knownUpper },
            order.filterTo(LinkedHashSet()) { it.uppercase() in chosenUpper }
        )
    }

    /** Whether [plan] holds a phone other than [mac]. */
    fun hasAnother(plan: WakePlan, mac: String): Boolean = plan.order.any { !it.equals(mac, ignoreCase = true) }

    /**
     * The phone the automatic pick names, or null rather than a blind pick among unknown phones.
     * [connected] are the bonded phones with a live link. A linked phone that Only these phones
     * leaves out is not a pick.
     */
    fun autoPick(plan: WakePlan, connected: Collection<String>): String? {
        val linked = connected.filter { c -> plan.order.any { it.equals(c, ignoreCase = true) } }
        if (linked.size == 1) return linked.first()
        val connectedUpper = linked.map { it.uppercase() }.toSet()
        // Among two or more linked phones only a listed or learned one is a pick, never the first bond.
        val pickable = if (linked.size >= 2) plan.chosen else plan.known
        plan.order.firstOrNull { it in pickable && (linked.isEmpty() || it.uppercase() in connectedUpper) }
            ?.let { return it }
        return if (linked.isEmpty() && plan.order.size == 1) plan.order.first() else null
    }

    /**
     * Whether a handshake link closing means [awaiting] failed before any session formed. [onOurNetwork]
     * is a fast re-wake of a phone that joined us: a back-off would wake another phone over it.
     */
    fun failedBeforeSession(
        awaiting: String?, linkPeer: String?, abortedLocally: Boolean, sessionUp: Boolean, onOurNetwork: Boolean
    ): Boolean =
        awaiting != null && awaiting.equals(linkPeer, ignoreCase = true) && !abortedLocally && !sessionUp && !onOurNetwork

}
