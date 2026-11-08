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
}
