package com.andrerinas.openheadunit.connection

import android.os.SystemClock
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy.Owner
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy.Tier
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy.Verdict
import com.andrerinas.openheadunit.utils.AppLog

/**
 * The one connection attempt in flight, by [ConnectionPriorityPolicy]'s tiers. USB, a user's
 * connect and the wireless stack used to run side by side, each blind to the others.
 *
 * One USB plug-in is an episode: its retries keep wireless down between them, up to a budget.
 */
object ConnectionArbiter {

    /**
     * One attempt's hold on the arbiter. A USB claim carries the owed scan it runs for
     * ([usbRecheck]): if a higher claim ends it, that same scan is what is owed back.
     */
    class Claim internal constructor(val tier: Tier, val owner: Owner, val what: String, val sinceMs: Long,
        internal val usbRecheck: UsbRecheck? = null) {
        /**
         * Completes once this claim stops holding the arbiter: replaced by another claim, released,
         * yielded to the user or reset. Completed only after the arbiter's monitor is released, and
         * never given a completion handler, so nothing runs inside the frame that ends a claim.
         */
        internal val ended = kotlinx.coroutines.CompletableDeferred<Unit>()
        override fun toString() = "$what ($tier)"
    }

    /** What the arbiter cannot do itself. Installed by `AapService`. */
    interface Actions {
        /** Ends [loser]'s attempt. */
        fun preempt(loser: Claim)
        /** Stops the wireless stack; false when it was not running. */
        fun standDownWireless(by: Claim): Boolean
        /**
         * Re-arms wireless and, or, hands back the owed USB scan [usb]; its consumer asks the bus
         * only while that scan is still current (see [UsbRecheck]).
         */
        fun giveBack(wireless: Boolean, usb: UsbRecheck?)
        /** Runs [block] after [delayMs]. */
        fun schedule(delayMs: Long, block: () -> Unit)
    }

    @Volatile var actions: Actions? = null
    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }

    private var holder: Claim? = null
    private var wirelessOwed = false
    // The USB scan refused or preempted here, owed back when the holder ends. Its own validity,
    // not this record, decides whether it may still ask: a user exit or Stop withdraws it.
    private var usbOwed: UsbRecheck? = null
    private var owedAfterSession = false

    /** Bumped by every claim, so a scheduled give-back can tell it was overtaken. */
    private var generation = 0L
    private var quietPending = false
    private var usbEpisodeSinceMs = 0L
    private var usbEpisode = 0L
    private var usbEpisodeSpent = false

    /**
     * Takes the arbiter for an attempt. Null means a higher attempt holds it: do not start. A USB
     * claim names the owed scan [usbRecheck] it runs for, which is owed back if it is refused now
     * or preempted later.
     */
    fun claim(tier: Tier, owner: Owner, what: String, usbRecheck: UsbRecheck? = null): Claim? {
        val now = clock()
        val loser: Claim?
        val replaced: Claim?
        val claim: Claim
        val spent: Boolean
        var episodeToWatch = -1L
        synchronized(this) {
            val held = holder
            val verdict = ConnectionPriorityPolicy.decide(
                tier, owner, held?.tier, held?.owner, held?.let { now - it.sinceMs } ?: 0L
            )
            if (verdict == Verdict.REFUSE) {
                if (owner == Owner.USB) usbOwed = usbRecheck ?: usbOwed
                AppLog.i("ConnectionArbiter: $what ($tier) refused while $held is in flight")
                return null
            }
            loser = if (verdict == Verdict.PREEMPT) held else null
            replaced = held
            if (loser?.owner == Owner.USB) usbOwed = loser.usbRecheck ?: usbOwed
            if (owner == Owner.USB) {
                usbOwed = null
                if (usbEpisodeSinceMs == 0L) {
                    usbEpisodeSinceMs = now
                    episodeToWatch = ++usbEpisode
                }
            }
            generation++
            quietPending = false
            spent = usbEpisodeSpent
            claim = Claim(tier, owner, what, now, usbRecheck)
            holder = claim
        }
        replaced?.ended?.complete(Unit)
        if (episodeToWatch >= 0) {
            actions?.schedule(ConnectionPriorityPolicy.USB_EPISODE_BUDGET_MS) { spendUsbEpisode(episodeToWatch) }
        }
        if (loser != null) {
            AppLog.i("ConnectionArbiter: $claim preempts $loser")
            actions?.preempt(loser)
        }
        if (ConnectionPriorityPolicy.standsDownWireless(owner, spent) && actions?.standDownWireless(claim) == true) {
            AppLog.i("ConnectionArbiter: $claim stood the wireless stack down until it ends")
            synchronized(this) { wirelessOwed = true }
        }
        return claim
    }

    /** Ends [claim]; a claim another has since replaced is ignored. */
    fun release(claim: Claim?, sessionFormed: Boolean) {
        if (claim == null) return
        var released = false
        try {
            val delayMs: Long
            val token: Long
            synchronized(this) {
                if (holder !== claim) return
                holder = null
                released = true
                if (sessionFormed) {
                    if (wirelessOwed || usbOwed != null) owedAfterSession = true
                    endUsbEpisode()
                    return
                }
                if (!wirelessOwed && usbOwed == null && usbEpisodeSinceMs == 0L) return
                delayMs = ConnectionPriorityPolicy.giveBackDelayMs(claim.owner)
                token = generation
                quietPending = true
            }
            if (delayMs > 0 && !usbEpisodeSpent()) {
                AppLog.i("ConnectionArbiter: $claim ended with no session; USB has ${delayMs}ms to try again")
            }
            val act = actions
            if (act == null || delayMs == 0L) settle(token, claim) else act.schedule(delayMs) { settle(token, claim) }
        } finally {
            // After the monitor: the claim's end wakes its waiters outside the frame that ended it.
            if (released) claim.ended.complete(Unit)
        }
    }

    /** Gives back what is owed unless another claim came along after [token]. */
    private fun settle(token: Long, ended: Claim) {
        val wireless: Boolean
        val usb: UsbRecheck?
        synchronized(this) {
            if (token != generation || holder != null) return
            quietPending = false
            wireless = wirelessOwed
            usb = usbOwed
            clearDebts()
            endUsbEpisode()
        }
        if (!wireless && usb == null) return
        AppLog.i("ConnectionArbiter: $ended ended with no session; giving back " +
            listOfNotNull("wireless".takeIf { wireless }, "USB".takeIf { usb != null }).joinToString(" and "))
        actions?.giveBack(wireless, usb)
    }

    /** One plug-in has failed for its whole budget: wireless comes back and USB stops taking it. */
    private fun spendUsbEpisode(episode: Long) {
        val wireless: Boolean
        synchronized(this) {
            if (episode != usbEpisode || usbEpisodeSinceMs == 0L || usbEpisodeSpent) return
            if (!ConnectionPriorityPolicy.usbEpisodeSpent(clock() - usbEpisodeSinceMs)) return
            usbEpisodeSpent = true
            wireless = wirelessOwed
            wirelessOwed = false
        }
        AppLog.i("ConnectionArbiter: USB has tried for ${ConnectionPriorityPolicy.USB_EPISODE_BUDGET_MS / 1000} s " +
            "with no session; wireless comes back beside it")
        if (wireless) actions?.giveBack(wireless = true, usb = null)
    }

    /** Whether [claim] is still the attempt in flight. */
    fun holds(claim: Claim): Boolean = synchronized(this) { holder === claim }

    /** Transfer a prepared resource only while its claim still owns the arbiter. */
    internal fun runIfHeld(claim: Claim, action: () -> Unit): Boolean = synchronized(this) {
        if (holder !== claim) return@synchronized false
        // Claim replacement uses this same monitor, so it cannot split the check and transfer.
        action()
        true
    }

    /** Whether this plug-in's USB attempts have stopped holding wireless down. */
    fun usbEpisodeSpent(): Boolean = synchronized(this) { usbEpisodeSpent }

    /** Admit a wireless rearm and acknowledge its debt as one arbiter decision. */
    fun tryRearmWireless(): Boolean = synchronized(this) {
        // The check and debt update share the claim lock: a USB claim on IO must not have
        // its newly recorded debt erased by a fallback that checked permission earlier.
        if (refusesBackground(userRequested = false)) return@synchronized false
        // Keep the current WPP claim and any USB debt. sessionEnded cannot acknowledge
        // wireless while a claim is held, and would give it back a second time later.
        wirelessOwed = false
        if (usbOwed == null) owedAfterSession = false
        true
    }

    /** A session formed by a claim that took something has ended, so give it back now. */
    fun sessionEnded(wirelessAlreadyRearmed: Boolean, userExit: Boolean) {
        val wireless: Boolean
        val usb: UsbRecheck?
        synchronized(this) {
            if (!owedAfterSession || holder != null) return
            // The user ended it: reconnecting on their behalf is the one thing not to do.
            if (userExit) { clearDebts(); return }
            wireless = wirelessOwed && !wirelessAlreadyRearmed
            usb = usbOwed
            clearDebts()
        }
        if (wireless || usb != null) actions?.giveBack(wireless, usb)
    }

    /**
     * Whether an automatic wireless bring-up must wait. A refusal is remembered and re-armed when
     * the attempt holding the arbiter ends, or when USB stays quiet between its tries.
     */
    fun refusesBackground(userRequested: Boolean): Boolean {
        val reason: String
        synchronized(this) {
            if (userRequested) return false
            val current = holder
            if (current != null) {
                val live = clock() - current.sinceMs < ConnectionPriorityPolicy.STALE_CLAIM_MS
                val outside = live && ConnectionPriorityPolicy.standsDownWireless(current.owner, usbEpisodeSpent)
                if (!ConnectionPriorityPolicy.refusesBackground(outside, userRequested)) return false
                reason = "$current is in flight"
            } else {
                if (!quietPending || usbEpisodeSinceMs == 0L || usbEpisodeSpent) return false
                reason = "USB is between tries"
            }
            wirelessOwed = true
        }
        AppLog.i("ConnectionArbiter: wireless bring-up refused while $reason; it comes back when that ends")
        return true
    }

    /** The user asked for wireless by hand: end any outside attempt and owe nothing back. */
    fun yieldToUser(what: String) {
        val loser: Claim?
        synchronized(this) {
            loser = holder?.takeIf { ConnectionPriorityPolicy.standsDownWireless(it.owner) }
            if (loser != null) holder = null
            generation++
            quietPending = false
            clearDebts()
            endUsbEpisode()
        }
        loser?.ended?.complete(Unit)
        if (loser != null) {
            AppLog.i("ConnectionArbiter: $what preempts $loser")
            actions?.preempt(loser)
        }
    }

    private fun endUsbEpisode() {
        usbEpisodeSinceMs = 0L
        usbEpisodeSpent = false
        usbEpisode++
    }

    private fun clearDebts() {
        wirelessOwed = false
        usbOwed = null
        owedAfterSession = false
    }

    /** Tests only. */
    internal fun reset() {
        val ended = synchronized(this) {
            val held = holder
            holder = null
            generation = 0L
            quietPending = false
            clearDebts()
            endUsbEpisode()
            held
        }
        ended?.ended?.complete(Unit)
    }
}
