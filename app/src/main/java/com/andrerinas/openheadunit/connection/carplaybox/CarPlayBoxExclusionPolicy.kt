package com.andrerinas.openheadunit.connection.carplaybox

import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy

/** Keeps a CarPlay box session and Android Auto off the same bus: the first one to start wins. */
object CarPlayBoxExclusionPolicy {

    /** Every Android Auto attempt waits while a box session is live, at any age. */
    fun refusesAa(boxSessionLive: Boolean): Boolean = boxSessionLive

    /** Why a box session may not start now, or null. A holder at the stale age is a leak and blocks nothing. */
    fun boxRefusal(boxSessionLive: Boolean, aaSessionLive: Boolean, holderAgeMs: Long?): String? = when {
        boxSessionLive -> "a box session is live"
        aaSessionLive -> "an Android Auto session is live"
        holderAgeMs != null && holderAgeMs < ConnectionPriorityPolicy.STALE_CLAIM_MS -> "an attempt is in flight"
        else -> null
    }

    fun refusesBox(boxSessionLive: Boolean, aaSessionLive: Boolean, holderAgeMs: Long?): Boolean =
        boxRefusal(boxSessionLive, aaSessionLive, holderAgeMs) != null
}
