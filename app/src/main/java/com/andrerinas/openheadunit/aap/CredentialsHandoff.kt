package com.andrerinas.openheadunit.aap

/** Everything the phone needs in order to join the network this head unit is offering it. */
data class NativeNetworkCredentials(
    val ssid: String,
    val psk: String,
    val ip: String,
    val bssid: String
)

/**
 * Carries one set of credentials from the transport that resolved them to the listener that
 * forwards them, in whichever order the two arrive.
 *
 * A plain callback field cannot do this, and the difference is a whole dead connection rather than a
 * late one. The hotspot transport resolves on a background thread the instant it is started, and on
 * a unit whose access point is already up that takes tens of milliseconds. Measured at 44 ms on the
 * unit this was written for, while the service that owns the callback was still inside `onCreate`.
 * The delivery landed on a null field and was dropped. Nothing retried, because a transport that has
 * resolved its network stops looking: there is no second delivery to catch. The head unit then holds
 * a perfectly good access point and an open Bluetooth listener and never wakes the phone, which is
 * indistinguishable from idling.
 *
 * So the value waits for the listener rather than the other way round. Latest-value, not a queue:
 * credentials describe a network as it is now, and an older set describes one the phone can no
 * longer join.
 *
 * Callers run on different threads by construction (resolve on IO, registration on main), hence the
 * lock; delivery happens outside it, because the listener reaches all the way into the Bluetooth
 * handshake and holding a lock across that invites the deadlock this class exists to avoid.
 */
class CredentialsHandoff {

    private val lock = Any()
    private var listener: ((NativeNetworkCredentials) -> Unit)? = null
    private var pending: NativeNetworkCredentials? = null

    /**
     * Hands [credentials] over, or latches them until someone is listening.
     *
     * @return whether they were delivered. `false` means they are held for the next [setListener],
     *   which is worth saying out loud: it is the ordering fault above, and it is silent otherwise.
     */
    fun publish(credentials: NativeNetworkCredentials): Boolean {
        val target = synchronized(lock) {
            val current = listener
            pending = if (current == null) credentials else null
            current
        }
        target?.invoke(credentials)
        return target != null
    }

    /** Registers [callback], and gives it anything [publish] latched before it existed. */
    fun setListener(callback: (NativeNetworkCredentials) -> Unit) {
        val replay = synchronized(lock) {
            listener = callback
            pending.also { pending = null }
        }
        replay?.let { callback(it) }
    }

    /**
     * Drops anything latched but keeps the listener.
     *
     * For a transport being stopped. The listener is registered once for the life of the service and
     * has to survive every stop and re-arm, but credentials from a network that has since been taken
     * down must not be replayed to the next run as though they were current.
     */
    fun clear() {
        synchronized(lock) { pending = null }
    }
}
