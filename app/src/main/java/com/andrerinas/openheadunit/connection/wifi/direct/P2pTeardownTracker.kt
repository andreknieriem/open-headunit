package com.andrerinas.openheadunit.connection.wifi.direct

/**
 * What the last [WifiDirectManager.stop] still owes: the group removal and the channel close.
 * [stoppedAtMs] is on `SystemClock.elapsedRealtime`.
 */
data class P2pTeardown(
    val stopped: Boolean,
    val groupRemoved: Boolean,
    val channelClosed: Boolean,
    val keptGroup: Boolean,
    val stoppedAtMs: Long,
)

/**
 * Process-level hand-off between the Wi-Fi Direct manager and the local hotspot start, so neither
 * imports the other. A stop that keeps its group registers two closures here. The next launcher
 * runs the release when it does not use Wi-Fi Direct, and the channel close when it does.
 */
object P2pTeardownTracker {
    @Volatile
    var latest: P2pTeardown? = null

    private val lock = Any()
    private var keptGroupRelease: (() -> Unit)? = null
    private var keptChannelClose: (() -> Unit)? = null

    fun update(transform: (P2pTeardown?) -> P2pTeardown?) {
        synchronized(lock) { latest = transform(latest) }
    }

    /** [release] removes the group and closes its channel; [closeChannel] only closes the channel. */
    fun registerKeptGroup(release: () -> Unit, closeChannel: () -> Unit) {
        synchronized(lock) {
            keptGroupRelease = release
            keptChannelClose = closeChannel
        }
    }

    /** Runs the kept-group release once, if one is owed. */
    fun releaseKeptGroup() {
        val release = synchronized(lock) {
            keptChannelClose = null
            keptGroupRelease.also { keptGroupRelease = null }
        }
        release?.invoke()
    }

    /**
     * The next Wi-Fi Direct manager reads the kept group, so nothing removes it. Returns the close of
     * the kept channel: run it once the new manager has opened its own, or P2P turns off.
     */
    fun adoptKeptGroup(): (() -> Unit)? = synchronized(lock) {
        keptGroupRelease = null
        keptChannelClose.also { keptChannelClose = null }
    }
}
