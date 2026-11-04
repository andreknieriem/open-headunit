package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * Bridge from the blocking framed reader to the handshake FSM. A retained HCT stream has
 * a bounded physical inbox: suspending this bridge when the FSM waits preserves that bound
 * instead of moving an arbitrary backlog to another queue. The physical reader can still
 * answer Ping, and closes ownership if projection input overflows its own inbox.
 */
internal class WppInboundMessages<T>(retained: Boolean, private val read: () -> T) {
    val channel = Channel<T>(if (retained) 16 else Channel.UNLIMITED)

    suspend fun readUntilClosed() {
        try {
            while (currentCoroutineContext().isActive) channel.send(read())
        } finally { channel.close() }
    }
}
