package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

import android.os.SystemClock
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Whether this unit's vendor Bluetooth daemon will carry Android Auto.
 *
 * Detection marks a class of hardware, and part of that class reaches its module over Binder with
 * nothing on the daemon's port. Asking is the only way to tell those apart, so this dials,
 * remembers the answer, and hands it to [com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.ExternalBtTransportPolicy].
 *
 * Resolution may wait for another observer (up to ten seconds), retry refusals over the route's
 * bring-up window, or finish a partially received frame. Never run it on the main thread. UI
 * callers read [cached] instead, which is a volatile read and does no I/O.
 */
object ZbtDaemonReachability {

    /**
     * How long to wait for the daemon's first frame once it has taken our `RequestInit`. One read
     * timeout. Silence is not an application answer and does not identify which client owns the
     * server or whether initialization has completed, so it never rejects the route on its own.
     */
    const val HELLO_BUDGET_MS = 3_000L

    /** How long an answer is trusted. A daemon down at app start can be up by the next attempt. */
    const val RECHECK_AFTER_MS = 10 * 60_000L

    // Preserve dialOnce / ZbtReachabilityPolicy handling for established external-module markers:
    // a listening-but-silent port lets the carrier try, but does not identify ZBT. Firmware
    // candidates require a compatible greeting, so keep that answer out of the legacy cache.
    private data class IdentityAnswer(
        val reachable: Boolean,
        val atMs: Long,
        val refused: Boolean = false
    )
    @Volatile private var identityAnswer: IdentityAnswer? = null

    private const val SLEEP_SLICE_MS = 250L
    private const val OBSERVER_WAIT_MS = 10_000L
    // Broad firmware markers also match units without an active ZBT backend. Give booting
    // daemons a short grace period, not the known-module route's 30-second bring-up window.
    private const val CANDIDATE_REFUSAL_WINDOW_MS = 3_000L
    private const val CANDIDATE_BOOT_WINDOW_MS = 90_000L

    @Volatile
    private var answer: Boolean? = null

    @Volatile
    private var answeredAt = 0L

    // Separate from resolve's monitor: that monitor can be held across seconds of network I/O.
    private val clientsLock = Any()
    private val nextCarrierId = java.util.concurrent.atomic.AtomicLong()
    private val carriers = mutableSetOf<CarrierClaim>()
    private val liveCarriers = mutableSetOf<CarrierClaim>()
    // The value distinguishes a long-running manual diagnostic from route discovery.
    private val observers = mutableMapOf<ZbtChannelOpening, Boolean>()

    /** A per-run claim. Releasing an older run cannot release a replacement's claim. */
    class CarrierClaim internal constructor() : java.io.Closeable {
        val id: Long = nextCarrierId.incrementAndGet()
        fun setLive(live: Boolean) = synchronized(clientsLock) {
            if (this !in carriers) return@synchronized
            if (live) liveCarriers.add(this) else liveCarriers.remove(this)
            Unit
        }

        /** Idempotent, including late finally blocks and close racing with normal completion. */
        override fun close() = synchronized(clientsLock) {
            liveCarriers.remove(this)
            carriers.remove(this)
            Unit
        }
    }

    /**
     * Claim before opening/reopening. A real carrier preempts this process's diagnostic sockets;
     * the vendor's other processes are outside this bookkeeping and are not controlled here.
     */
    fun claimCarrier(): CarrierClaim {
        val claim = CarrierClaim()
        val toClose = synchronized(clientsLock) {
            carriers.add(claim)
            observers.keys.toList()
        }
        // Never hold the registry lock across a socket close or while acquiring an opening lock.
        toClose.forEach { it.close() }
        return claim
    }

    fun carrierLive(): Boolean = synchronized(clientsLock) { liveCarriers.isNotEmpty() }

    /** Includes carriers between opens, until their own run has unwound. */
    fun carrierWantsClient(): Boolean = synchronized(clientsLock) { carriers.isNotEmpty() }

    /**
     * Register a probe/measurement before connect, atomically with carrier claims. A later claim
     * can close its opening socket or live read immediately. Init admission checks the claim again;
     * a write that was already admitted can still finish during cancellation.
     */
    internal fun observerOpening(keepGoing: () -> Boolean): ZbtChannelOpening? =
        observerOpening(keepGoing, diagnostic = false)

    internal fun observerOpening(
        keepGoing: () -> Boolean,
        diagnostic: Boolean,
    ): ZbtChannelOpening? {
        val toClose: List<ZbtChannelOpening>
        synchronized(clientsLock) {
            if (carriers.isNotEmpty()) return null
            if (observers.isEmpty()) {
                return ZbtChannelOpening { keepGoing() && !carrierWantsClient() }
                    .also { observers[it] = diagnostic }
            }
            // Inspected Joying ZLink 5.4.57 libzbt-main.so accepts one client, sends InitInfo,
            // and serves that client until its read loop ends before accepting another. Two
            // "observers" are still two clients: discovery must not queue a socket beside the
            // settings diagnostic. Route discovery may retire that diagnostic, but it waits
            // for the old caller's finally to release its slot before opening another socket.
            toClose = if (diagnostic) emptyList() else observers.filterValues { it }.keys.toList()
        }
        // Socket shutdown and callbacks must never run under the registry lock.
        toClose.forEach { it.close() }
        throw ZbtChannelOpening.ObserverBusy()
    }

    internal fun releaseObserver(opening: ZbtChannelOpening) {
        synchronized(clientsLock) { observers.remove(opening) }
    }

    /**
     * Read without dialing. Legacy answers expire by age; identity answers remain fixed until
     * [expireIdleIdentity] runs at a fresh idle arming, so a live route cannot change under the UI.
     */
    fun cached(
        nowMs: Long = SystemClock.elapsedRealtime(),
        requireIdentity: Boolean = false
    ): Boolean? = if (requireIdentity) {
        // This answer chose the running transport. Expiry must not flip a live Android
        // button into a module button (or vice versa) without replacing that transport.
        // A fresh arming calls expireIdleIdentity before it decides whether to measure.
        identityAnswer?.reachable
    } else answer?.takeIf { nowMs - answeredAt < RECHECK_AFTER_MS }

    /** Reconsider a refusal or old candidate answer at a fresh arming, never during a session. */
    fun expireIdleIdentity(nowMs: Long = SystemClock.elapsedRealtime()) {
        if (carrierLive() || carrierWantsClient()) return
        val held = identityAnswer ?: return
        // A refused connection did not occupy the daemon's client slot. The daemon may
        // start late or restart after ACC resume, so the next arming may try again.
        // Keep the refusal settled during the current arming to let Android fallback start.
        // A silent or unrecognized listener may belong to another app: retain its normal
        // expiry instead of repeatedly connecting to a potentially occupied bridge.
        if (held.refused || nowMs - held.atMs >= RECHECK_AFTER_MS) identityAnswer = null
    }

    /** Record what actually happened. The carrier's own open is a better measurement than a dial. */
    fun record(reachable: Boolean, nowMs: Long = SystemClock.elapsedRealtime()) {
        answer = reachable
        answeredAt = nowMs
        // Traffic can extend an identity already checked, never create one from a silent port.
        if (reachable && identityAnswer?.reachable == true) {
            identityAnswer = IdentityAnswer(true, nowMs)
        }
    }

    /** How old the answer is, or null if there is none. */
    fun answerAgeMs(nowMs: Long = SystemClock.elapsedRealtime()): Long? =
        answer?.let { nowMs - answeredAt }

    /** Forget the answer, so the next caller measures again. */
    fun forget() {
        identityAnswer = null
        answer = null
        answeredAt = 0L
    }

    /**
     * The cached answer, dialling if there is none. Blocking; never call from the main thread.
     * A daemon restarting after ACC on refuses briefly, so with [retryRefusals] a refusal is
     * re-dialled across [ZbtReachabilityPolicy.REFUSAL_WINDOW_MS] and only the last one is cached.
     */
    @Synchronized
    fun resolve(
        nowMs: () -> Long = { SystemClock.elapsedRealtime() },
        dial: (() -> Boolean)? = null,
        carrierLive: () -> Boolean = ::carrierLive,
        retryRefusals: Boolean = false,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        keepTrying: () -> Boolean = { true },
        requireIdentity: Boolean = false,
        identityDial: (() -> ZbtIdentityProbe.Outcome)? = null,
        cacheIdentityRefusal: Boolean = true
    ): Boolean {
        // A live carrier is the answer, and dialling past it would cache its own silence as a no.
        if (requireIdentity && (carrierLive() || carrierWantsClient())) {
            // Ownership is not a negative measurement. A forced carrier may never have been
            // identified; leave that unknown so its next fresh arming can measure normally.
            return identityAnswer?.reachable == true
        }
        if (carrierLive()) return true
        // Another arming already selected this route. Let its carrier measure it; do not open a
        // second OHU socket or cache this deferral as proof of daemon reachability.
        if (carrierWantsClient()) return true
        cached(nowMs(), requireIdentity)?.let { return it }
        // A caller stopped while queued on the lock must not dial for an arming that is gone.
        if (!keepTrying()) return false
        val firstRefusalAt = nowMs()
        var refusals = 0
        while (true) {
            var identity: ZbtIdentityProbe.Outcome? = null
            val reachable = try {
                if (requireIdentity) {
                    identity = identityDial?.invoke() ?: dial?.invoke()?.let {
                        if (it) ZbtIdentityProbe.Outcome.IDENTIFIED else ZbtIdentityProbe.Outcome.UNCONFIRMED
                    } ?: ZbtIdentityProbe.probe(keepTrying = {
                        keepTrying() && !carrierLive() && !carrierWantsClient()
                    })
                    identity == ZbtIdentityProbe.Outcome.IDENTIFIED
                } else dial?.invoke() ?: dialOnce(keepTrying)
            } catch (_: ZbtChannelOpening.ObserverBusy) {
                // Occupancy is not a daemon refusal. Let a retiring diagnostic unwind without
                // caching a negative answer; both identity and legacy discovery use this gate.
                if (!keepTrying() || nowMs() - firstRefusalAt >= OBSERVER_WAIT_MS) return false
                sleep(SLEEP_SLICE_MS)
                if (!keepTrying()) return false
                if (carrierLive() || carrierWantsClient()) {
                    return if (requireIdentity) identityAnswer?.reachable == true else true
                }
                continue
            } catch (_: ZbtChannelOpening.Cancelled) {
                // A carrier preempting observation is not an identity or a negative measurement.
                return keepTrying() && if (requireIdentity) identityAnswer?.reachable == true
                    else carrierLive() || carrierWantsClient()
            }
            if (!keepTrying()) return false
            if (requireIdentity && (carrierLive() || carrierWantsClient())) return identityAnswer?.reachable == true
            if (carrierLive() || carrierWantsClient()) return true
            if (reachable) {
                if (requireIdentity) identityAnswer = IdentityAnswer(true, nowMs())
                else record(true, nowMs())
                return true
            }
            refusals++
            val candidateBootRetry = firstRefusalAt < CANDIDATE_BOOT_WINDOW_MS
            val windowMs = if (requireIdentity) CANDIDATE_REFUSAL_WINDOW_MS else ZbtReachabilityPolicy.REFUSAL_WINDOW_MS
            val spentMs = nowMs() - firstRefusalAt
            val wait = if (retryRefusals && spentMs < windowMs &&
                (!requireIdentity || (candidateBootRetry && identity == ZbtIdentityProbe.Outcome.REFUSED))) {
                ZbtReachabilityPolicy.redialAfterMs(refusals, spentMs)?.coerceAtMost(windowMs - spentMs)
            } else null
            if (wait == null) {
                if (requireIdentity) {
                    // A settings preview must not settle the arming's boot-refusal decision.
                    if (identity != ZbtIdentityProbe.Outcome.REFUSED || cacheIdentityRefusal) {
                        identityAnswer = IdentityAnswer(
                            false, nowMs(), refused = identity == ZbtIdentityProbe.Outcome.REFUSED
                        )
                    }
                } else record(false, nowMs())
                if (requireIdentity) AppLog.i(
                    "NativeAA: [ZBT candidate] no confirmed ZBT greeting; retaining Android Bluetooth route."
                )
                if (retryRefusals && !requireIdentity) AppLog.i(
                    "NativeAA: [ZBT] the local daemon check did not establish reachability during this " +
                        "attempt's ${ZbtReachabilityPolicy.REFUSAL_WINDOW_MS / 1000}s retry window. " +
                        "This does not determine whether the module can become available later."
                )
                return false
            }
            AppLog.i("NativeAA: [ZBT] dialling again in ${wait / 1000}s.")
            var left = wait
            while (left > 0L) {
                if (!keepTrying()) return false
                val slice = minOf(left, SLEEP_SLICE_MS)
                sleep(slice)
                left -= slice
            }
            if (!keepTrying()) return false
            if (requireIdentity && (carrierLive() || carrierWantsClient())) return identityAnswer?.reachable == true
            if (carrierLive() || carrierWantsClient()) return true
        }
    }

    /**
     * Connect, send `RequestInit`, and wait for one frame back. Init is protocol setup, not a passive
     * query: candidate firmware forwards enable_type to its Bluetooth enable callback. This path
     * must yield to the app's carrier even before init; a reply still does not prove working HFP.
     */
    private fun dialOnce(keepTrying: () -> Boolean): Boolean {
        val opening = observerOpening(keepTrying) ?: throw ZbtChannelOpening.Cancelled()
        try {
            val channel = try {
                ZbtByteChannel.open(bufferRfcommData = false, opening = opening)
            } catch (e: ZbtChannelOpening.Cancelled) {
                throw e
            } catch (e: ZbtByteChannel.NotConnected) {
                return report(e.verdict, e.message)
            } catch (e: Exception) {
                return report(ZbtReachabilityPolicy.classify(e.javaClass.simpleName, false), e.message)
            }
            try {
                return report(awaitGreeting(channel, SystemClock.elapsedRealtime() + HELLO_BUDGET_MS, opening), null)
            } finally {
                channel.close()
            }
        } finally {
            opening.close()
            releaseObserver(opening)
        }
    }

    /**
     * The byte-channel deadline uses elapsed realtime, unaffected by wall-clock corrections.
     * Cancellation is neither a daemon refusal nor a greeting; keep it out of the cache.
     */
    internal fun awaitGreeting(
        channel: ZbtByteChannel,
        deadlineMs: Long,
        opening: ZbtChannelOpening? = null
    ): ZbtReachabilityPolicy.Verdict {
        while (SystemClock.elapsedRealtime() < deadlineMs) {
            if (opening?.wasCancelled() == true) throw ZbtChannelOpening.Cancelled()
            val result = channel.pumpOnce(deadlineMs) { opening?.wasCancelled() != true }
            if (opening?.wasCancelled() == true) throw ZbtChannelOpening.Cancelled()
            when (result) {
                ZbtByteChannel.Pump.FRAME -> return ZbtReachabilityPolicy.Verdict.ANSWERED
                ZbtByteChannel.Pump.QUIET -> Unit
                ZbtByteChannel.Pump.ENDED, ZbtByteChannel.Pump.EXPIRED -> break
            }
        }
        if (opening?.wasCancelled() == true) throw ZbtChannelOpening.Cancelled()
        return ZbtReachabilityPolicy.Verdict.LISTENING_SILENT
    }

    /** Say what the dial proved, and hand back whether the module route is worth taking. */
    private fun report(verdict: ZbtReachabilityPolicy.Verdict, detail: String?): Boolean {
        when (verdict) {
            ZbtReachabilityPolicy.Verdict.ANSWERED -> AppLog.i(
                "NativeAA: [ZBT] received a framed message on 127.0.0.1:3152; retaining the module " +
                    "route. A phone's Android Auto exchange has not been confirmed by this check."
            )
            // No application reply cannot distinguish another client, pending initialization, or
            // another busy state. Retain the existing route decision without diagnosing the cause.
            ZbtReachabilityPolicy.Verdict.LISTENING_SILENT -> AppLog.w(
                "NativeAA: [ZBT] no usable module reply confirmed during this connection attempt; " +
                    "client ownership and phone state remain unknown. Retaining the module route" +
                    (detail?.let { " ($it)" } ?: "") + "."
            )
            ZbtReachabilityPolicy.Verdict.NOTHING_LISTENING -> AppLog.i(
                "NativeAA: [ZBT] connection attempt to 127.0.0.1:3152 classified as refused" +
                    (detail?.let { " ($it)" } ?: "") + "."
            )
        }
        return ZbtReachabilityPolicy.reachable(verdict)
    }
}
