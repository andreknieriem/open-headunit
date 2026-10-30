package com.andrerinas.openheadunit.connection

import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Head unit server sockets discovery opened, which the phone's server has already accepted and bound
 * to. Exactly one connect may take each; any other dial to that endpoint would leave it abandoned.
 */
object HeldServerSocket {
    private class Entry(val socket: Socket, val taken: Boolean)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun hold(endpoint: String, socket: Socket) {
        entries.put(endpoint, Entry(socket, taken = false))?.let { if (!it.taken) closeQuietly(it.socket) }
    }

    fun isHeld(endpoint: String): Boolean = entries[endpoint]?.taken == false

    /** Taken, and its connect has not yet recorded a claim. */
    fun isTaken(endpoint: String): Boolean = entries[endpoint]?.taken == true

    fun take(endpoint: String, expected: Socket? = null): Socket? {
        while (true) {
            val e = entries[endpoint] ?: return null
            if (e.taken || (expected != null && e.socket !== expected)) return null
            if (entries.replace(endpoint, e, Entry(e.socket, taken = true))) return e.socket
        }
    }

    // removeIf is API 24; minSdk is 16.
    /** Ends the hand-off once the taker's claim is decided. */
    fun settle(socket: Socket) {
        for ((endpoint, e) in entries) if (e.socket === socket) entries.remove(endpoint, e)
    }

    /** Drops a socket nobody took; false when a connect already took it, so the caller must not close it. */
    fun discard(socket: Socket): Boolean {
        for ((endpoint, e) in entries) if (e.socket === socket && !e.taken && entries.remove(endpoint, e)) return true
        return false
    }

    /** A taker cancelled before its claim gives the socket up, so the endpoint is not joined forever. */
    fun abandon(socket: Socket) {
        settle(socket)
        closeQuietly(socket)
    }

    /** Discovery stopped: nothing will take what it still holds. */
    fun discardAll() {
        entries.values.filter { !it.taken }.forEach { if (discard(it.socket)) closeQuietly(it.socket) }
    }

    private fun closeQuietly(socket: Socket) = try { socket.close() } catch (e: Exception) {}
}
