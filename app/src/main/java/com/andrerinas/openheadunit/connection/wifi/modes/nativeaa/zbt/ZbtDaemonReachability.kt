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
 * The dial blocks for up to about seven seconds, so it must never run on the main thread. Callers on
 * a UI path read [cached] instead, which is a volatile read and does no I/O.
 */
object ZbtDaemonReachability {

    /**
     * How long to wait for the daemon's first frame once it has taken our `RequestInit`. One read
     * timeout: a daemon with nothing else on is immediate. Silence past it is not an answer, only
     * a busy daemon, so it never decides the route on its own.
     */
    const val HELLO_BUDGET_MS = 3_000L

    /** How long an answer is trusted. A daemon down at app start can be up by the next attempt. */
    const val RECHECK_AFTER_MS = 10 * 60_000L

    // Preserve dialOnce / ZbtReachabilityPolicy handling for established external-module markers:
    // a listening-but-silent port lets the carrier try, but does not identify ZBT. Firmware
    // candidates require a compatible greeting, so keep that answer out of the legacy cache.
    private data class IdentityAnswer(val reachable: Boolean, val atMs: Long)
    @Volatile private var identityAnswer: IdentityAnswer? = null

    private const val SLEEP_SLICE_MS = 250L
    // Broad firmware markers also match units without an active ZBT backend. Give booting
    // daemons a short grace period, not the known-module route's 30-second bring-up window.
    private const val CANDIDATE_REFUSAL_WINDOW_MS = 3_000L
    private const val CANDIDATE_BOOT_WINDOW_MS = 90_000L

    @Volatile
    private var answer: Boolean? = null

    @Volatile
    private var answeredAt = 0L

    @Volatile
    private var carrierHoldsClient = false

    @Volatile
    private var carrierWantsSlot = false

    /**
     * Whether this app's own carrier currently holds the daemon's one client slot.
     *
     * The daemon serves one program at a time, so a second connection is accepted and then never
     * answered. Anything that would dial asks this first, or it measures its own session as a dead
     * daemon.
     */
    fun carrierLive(): Boolean = carrierHoldsClient

    /** Called by [ZbtAaCarrier] as it takes and releases the daemon's client slot. */
    fun setCarrierLive(live: Boolean) {
        carrierHoldsClient = live
    }

    /**
     * Whether the real connection needs the daemon's one client slot, open or reopening.
     *
     * [carrierLive] only covers a channel the carrier already holds. A probe that took the slot
     * first keeps it until its own run ends, which cost a connection 90 seconds on #978's GT6-CAR,
     * so the probe polls this as well and gives way: a session outranks a test.
     */
    fun carrierWantsClient(): Boolean = carrierWantsSlot

    /** Called by [ZbtAaCarrier] around its whole run, reopen attempts included. */
    fun setCarrierWantsClient(wants: Boolean) {
        carrierWantsSlot = wants
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

    /** Reconsider an old candidate answer at a fresh arming, never in the middle of a session. */
    fun expireIdleIdentity(nowMs: Long = SystemClock.elapsedRealtime()) {
        if (carrierLive() || carrierWantsClient()) return
        val held = identityAnswer ?: return
        if (nowMs - held.atMs >= RECHECK_AFTER_MS) identityAnswer = null
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
        cached(nowMs(), requireIdentity)?.let { return it }
        // A caller stopped while queued on the lock must not dial for an arming that is gone.
        if (!keepTrying()) return false
        val firstRefusalAt = nowMs()
        var refusals = 0
        while (true) {
            val identity = if (requireIdentity) {
                identityDial?.invoke() ?: dial?.invoke()?.let {
                    if (it) ZbtIdentityProbe.Outcome.IDENTIFIED else ZbtIdentityProbe.Outcome.UNCONFIRMED
                } ?: ZbtIdentityProbe.probe(keepTrying = {
                    keepTrying() && !carrierLive() && !carrierWantsClient()
                })
            } else null
            if (!keepTrying()) return false
            if (requireIdentity && (carrierLive() || carrierWantsClient())) {
                return identityAnswer?.reachable == true
            }
            val reachable = if (requireIdentity) identity == ZbtIdentityProbe.Outcome.IDENTIFIED
                else (dial ?: ::dialOnce).invoke()
            if (!keepTrying()) return false
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
                        identityAnswer = IdentityAnswer(false, nowMs())
                    }
                } else record(false, nowMs())
                if (requireIdentity) AppLog.i(
                    "NativeAA: [ZBT candidate] no confirmed ZBT greeting; retaining Android Bluetooth route."
                )
                if (retryRefusals && !requireIdentity) AppLog.i(
                    "NativeAA: [ZBT] nothing has listened on 127.0.0.1:3152 for " +
                        "${ZbtReachabilityPolicy.REFUSAL_WINDOW_MS / 1000}s, so the module cannot " +
                        "carry Android Auto on this unit."
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
            if (carrierLive()) return true
        }
    }

    /**
     * Connect, send `RequestInit`, and wait for one frame back. [ZbtByteChannel.open] does the
     * connect and the send, so a reply is the whole answer.
     */
    private fun dialOnce(): Boolean {
        val channel = try {
            ZbtByteChannel.open(bufferRfcommData = false)
        } catch (e: ZbtByteChannel.NotConnected) {
            return report(e.verdict, e.message)
        } catch (e: Exception) {
            // Anything else got past the connect, so the port is held whatever went wrong after.
            return report(ZbtReachabilityPolicy.classify(e.javaClass.simpleName, false), e.message)
        }
        try {
            return report(awaitGreeting(channel, System.currentTimeMillis() + HELLO_BUDGET_MS), null)
        } finally {
            channel.close()
        }
    }

    /** The byte-channel deadline is wall time. A closed stream cannot produce another frame. */
    internal fun awaitGreeting(channel: ZbtByteChannel, deadlineMs: Long): ZbtReachabilityPolicy.Verdict {
        while (System.currentTimeMillis() < deadlineMs) {
            when (channel.pumpOnce(deadlineMs)) {
                ZbtByteChannel.Pump.FRAME -> return ZbtReachabilityPolicy.Verdict.ANSWERED
                ZbtByteChannel.Pump.QUIET -> Unit
                ZbtByteChannel.Pump.ENDED, ZbtByteChannel.Pump.EXPIRED -> break
            }
        }
        return ZbtReachabilityPolicy.Verdict.LISTENING_SILENT
    }

    /** Say what the dial proved, and hand back whether the module route is worth taking. */
    private fun report(verdict: ZbtReachabilityPolicy.Verdict, detail: String?): Boolean {
        when (verdict) {
            ZbtReachabilityPolicy.Verdict.ANSWERED -> AppLog.i(
                "NativeAA: [ZBT] the vendor daemon answered on 127.0.0.1:3152, so this unit can " +
                    "carry Android Auto over its Bluetooth module."
            )
            // Present and busy is not absent, and this daemon goes quiet while a phone is linked
            // to the module. Taking the route lets the carrier keep asking; refusing it here told
            // a reporter whose module works that their hardware cannot do Bluetooth wireless.
            ZbtReachabilityPolicy.Verdict.LISTENING_SILENT -> AppLog.w(
                "NativeAA: [ZBT] the daemon is on 127.0.0.1:3152 but did not answer within " +
                    "${HELLO_BUDGET_MS / 1000}s, which is what it does while a phone is linked to " +
                    "the module. Taking the module route anyway and letting the connection retry" +
                    (detail?.let { " ($it)" } ?: "") + "."
            )
            ZbtReachabilityPolicy.Verdict.NOTHING_LISTENING -> AppLog.i(
                "NativeAA: [ZBT] nothing is listening on 127.0.0.1:3152" +
                    (detail?.let { " ($it)" } ?: "") + "."
            )
        }
        return ZbtReachabilityPolicy.reachable(verdict)
    }
}
