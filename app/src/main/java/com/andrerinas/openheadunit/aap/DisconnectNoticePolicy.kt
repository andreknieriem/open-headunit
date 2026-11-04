package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.connection.CommManager.EndCause
import com.andrerinas.openheadunit.connection.projection.ProjectionConnection

/**
 * Picks the one notice a lost session earns. Only a formed session that the link dropped
 * qualifies, and only once per episode, so a recovery loop of failed attempts stays silent.
 */
object DisconnectNoticePolicy {
    enum class Notice { NONE, WIRELESS_TIP, USB_LOST }

    /** A pulled cable fails the transport before its detach arrives, so USB asks the bus this late. */
    const val USB_SETTLE_MS = 1_000L

    fun notice(
        endedBy: EndCause,
        transport: ProjectionConnection.Type?,
        reachedSsl: Boolean,
        wasLoopbackSession: Boolean,
        shownThisEpisode: Boolean,
        accessoryAttached: Boolean,
    ): Notice {
        if (endedBy != EndCause.LINK_FAILED || !reachedSsl || shownThisEpisode) return Notice.NONE
        return when (transport) {
            ProjectionConnection.Type.USB -> if (accessoryAttached) Notice.USB_LOST else Notice.NONE
            ProjectionConnection.Type.WIFI -> if (wasLoopbackSession) Notice.NONE else Notice.WIRELESS_TIP
            null -> Notice.NONE
        }
    }
}
