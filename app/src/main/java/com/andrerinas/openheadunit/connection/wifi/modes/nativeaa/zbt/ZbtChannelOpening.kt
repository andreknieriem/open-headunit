package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

import java.io.Closeable
import java.io.IOException

/**
 * One socket opening, published before connect so shutdown can reach it.
 *
 * Cancellation and init admission share a short lock. Socket I/O never runs under that lock:
 * close must not wait for a blocked connect/write. If init already won admission its write may
 * finish while close runs; cancellation prevents a not-yet-admitted init, not bytes already sent.
 */
class ZbtChannelOpening(private val keepOpening: () -> Boolean) : Closeable {
    class Cancelled : IOException("ZBT channel opening was retired")
    class ObserverBusy : IOException("Another OHU observer still owns the Bluetooth bridge")

    companion object { private val nextId = java.util.concurrent.atomic.AtomicLong() }
    val id: Long = nextId.incrementAndGet()

    private val lock = Any()
    private var cancelled = false
    private var resource: Closeable? = null
    private var initAdmitted = false

    /** Install before connect. Rejected resources are closed by this method. */
    internal fun attach(openingSocket: Closeable) {
        // The callback may enter the app lifecycle or the client registry. Evaluate it before
        // this opening's monitor; no socket work or cross-owner lock acquisition belongs inside.
        val current = keepOpening()
        val attached = synchronized(lock) {
            check(resource == null) { "An opening cannot be reused for another socket" }
            if (cancelled || !current) false else {
                resource = openingSocket
                true
            }
        }
        if (!attached) {
            runCatching { openingSocket.close() }
            throw Cancelled()
        }
    }

    /** The final admission point immediately before sending the initial frame. */
    internal fun admitInit() {
        if (!keepOpening()) throw Cancelled()
        synchronized(lock) {
            if (cancelled) throw Cancelled()
            check(!initAdmitted) { "RequestInit was already admitted for this opening" }
            initAdmitted = true
        }
    }

    /**
     * The final admission point immediately before an opener's later command (reconnect, version,
     * link poll). Same contract as [admitInit]: authority before the short lock, cancellation
     * under it, and the write outside both.
     * A carrier claim registered after this returns can still race the write already admitted; one
     * registered before it — even while the observer's socket close is still in progress — stops it.
     */
    internal fun admitCommand() {
        if (!keepOpening()) throw Cancelled()
        synchronized(lock) { if (cancelled) throw Cancelled() }
    }

    internal fun wasCancelled(): Boolean = synchronized(lock) { cancelled } || !keepOpening()
    internal fun initWasAdmitted(): Boolean = synchronized(lock) { initAdmitted }

    override fun close() {
        val toClose = synchronized(lock) {
            if (cancelled) return
            cancelled = true
            resource.also { resource = null }
        }
        runCatching { toClose?.close() }
    }
}
