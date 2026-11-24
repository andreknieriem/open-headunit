package com.andrerinas.openheadunit.connection.wifi.direct

/**
 * Whether [WifiDirectManager.stop] closes its P2P channel. An open channel keeps Wi-Fi Direct up,
 * which makes a local-only hotspot start fail with reason 2. A kept group needs the channel open:
 * the last channel closing disables P2P and takes the group with it.
 */
object P2pChannelClosePolicy {
    enum class Decision { KEEP_OPEN, CLOSE_NOW, CLOSE_AFTER_REMOVAL, NOT_CLOSABLE }

    /** How long a close waits for a `removeGroup` answer before it closes anyway. */
    const val CLOSE_FALLBACK_MS = 5_000L

    /** [Channel.close] exists from API 27. */
    private const val CLOSE_SINCE_SDK = 27

    fun decide(keepGroup: Boolean, removalOwed: Boolean, sdkInt: Int): Decision = when {
        keepGroup -> Decision.KEEP_OPEN
        sdkInt < CLOSE_SINCE_SDK -> Decision.NOT_CLOSABLE
        removalOwed -> Decision.CLOSE_AFTER_REMOVAL
        else -> Decision.CLOSE_NOW
    }
}
