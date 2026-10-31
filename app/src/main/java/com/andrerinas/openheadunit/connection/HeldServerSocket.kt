package com.andrerinas.openheadunit.connection

import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Head unit server sockets discovery opened, which the phone's server has already accepted and bound
 * to. Exactly one connect may take each; any other dial to that endpoint would leave it abandoned.
 *
 * A socket is either held here, owned by nobody, or adopted: removed in one step by the request
 * CommManager admits for it. There is no taken-but-unclaimed state; while a request owns the socket,
 * that request is what a same-endpoint connect joins.
 */
object HeldServerSocket {
    private val entries = ConcurrentHashMap<String, Socket>()

    fun hold(endpoint: String, socket: Socket) {
        entries.put(endpoint, socket)?.let { if (it !== socket) closeQuietly(it) }
    }

    fun isHeld(endpoint: String): Boolean = entries.containsKey(endpoint)

    /** Takes the socket held for [endpoint]; only one caller ever gets it. */
    fun adopt(endpoint: String): Socket? = entries.remove(endpoint)

    /**
     * Takes [socket] out of the held set. True when it was held, so the caller now owns it, to hand
     * over or to close; false when it was never held or another caller already adopted it.
     */
    // removeIf is API 24; minSdk is 16.
    fun adopt(socket: Socket): Boolean {
        for ((endpoint, held) in entries) if (held === socket && entries.remove(endpoint, held)) return true
        return false
    }

    /** Detach exact sockets at cleanup admission; a later close cannot take a successor's hold. */
    internal fun takeAll(): List<Socket> = entries.values.toList().filter { adopt(it) }

    /** Discovery stopped: nothing will take what it still holds. */
    fun discardAll() {
        takeAll().forEach { closeQuietly(it) }
    }

    private fun closeQuietly(socket: Socket) = try { socket.close() } catch (e: Exception) {}
}
