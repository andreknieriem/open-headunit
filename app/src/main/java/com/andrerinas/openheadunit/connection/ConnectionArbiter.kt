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

    class Claim internal constructor(val tier: Tier, val owner: Owner, val what: String, val sinceMs: Long) {
        override fun toString() = "$what ($tier)"
    }

    /** What the arbiter cannot do itself. Installed by `AapService`. */
    interface Actions {
        /** Ends [loser]'s attempt. */
        fun preempt(loser: Claim)
        /** Stops the wireless stack; false when it was not running. */
        fun standDownWireless(by: Claim): Boolean
        /** Re-arms wireless and, or, asks the USB bus again. */
        fun giveBack(wireless: Boolean, usb: Boolean)
        /** Runs [block] after [delayMs]. */
        fun schedule(delayMs: Long, block: () -> Unit)
    }

    @Volatile var actions: Actions? = null
    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }

    private var holder: Claim? = null
    private var wirelessOwed = false
    private var usbOwed = false
    private var owedAfterSession = false

    /** Bumped by every claim, so a scheduled give-back can tell it was overtaken. */
    private var generation = 0L
    private var quietPending = false
    private var usbEpisodeSinceMs = 0L
    private var usbEpisode = 0L
    private var usbEpisodeSpent = false

    /** Takes the arbiter for an attempt. Null means a higher attempt holds it: do not start. */
    fun claim(tier: Tier, owner: Owner, what: String): Claim? {
        val now = clock()
        val loser: Claim?
        val claim: Claim
        val spent: Boolean
        var episodeToWatch = -1L
        synchronized(this) {
            val held = holder
            val verdict = ConnectionPriorityPolicy.decide(
                tier, owner, held?.tier, held?.owner, held?.let { now - it.sinceMs } ?: 0L
            )
            if (verdict == Verdict.REFUSE) {
                if (owner == Owner.USB) usbOwed = true
                AppLog.i("ConnectionArbiter: $what ($tier) refused while $held is in flight")
                return null
            }
            loser = if (verdict == Verdict.PREEMPT) held else null
            if (loser?.owner == Owner.USB) usbOwed = true
            if (owner == Owner.USB) {
                usbOwed = false
                if (usbEpisodeSinceMs == 0L) {
                    usbEpisodeSinceMs = now
                    episodeToWatch = ++usbEpisode
                }
            }
            generation++
            quietPending = false
            spent = usbEpisodeSpent
            claim = Claim(tier, owner, what, now)
            holder = claim
        }
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
        val delayMs: Long
        val token: Long
        synchronized(this) {
            if (holder !== claim) return
            holder = null
            if (sessionFormed) {
                if (wirelessOwed || usbOwed) owedAfterSession = true
                endUsbEpisode()
                return
            }
            if (!wirelessOwed && !usbOwed && usbEpisodeSinceMs == 0L) return
            delayMs = ConnectionPriorityPolicy.giveBackDelayMs(claim.owner)
            token = generation
            quietPending = true
        }
        if (delayMs > 0 && !usbEpisodeSpent()) {
            AppLog.i("ConnectionArbiter: $claim ended with no session; USB has ${delayMs}ms to try again")
        }
        val act = actions
        if (act == null || delayMs == 0L) settle(token, claim) else act.schedule(delayMs) { settle(token, claim) }
    }

    /** Gives back what is owed unless another claim came along after [token]. */
    private fun settle(token: Long, ended: Claim) {
        val wireless: Boolean
        val usb: Boolean
        synchronized(this) {
            if (token != generation || holder != null) return
            quietPending = false
            wireless = wirelessOwed
            usb = usbOwed
            clearDebts()
            endUsbEpisode()
        }
        if (!wireless && !usb) return
        AppLog.i("ConnectionArbiter: $ended ended with no session; giving back " +
            listOfNotNull("wireless".takeIf { wireless }, "USB".takeIf { usb }).joinToString(" and "))
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
        if (wireless) actions?.giveBack(wireless = true, usb = false)
    }

    /** Whether [claim] is still the attempt in flight. */
    fun holds(claim: Claim): Boolean = synchronized(this) { holder === claim }

    /** Whether this plug-in's USB attempts have stopped holding wireless down. */
    fun usbEpisodeSpent(): Boolean = synchronized(this) { usbEpisodeSpent }

    /** A session formed by a claim that took something has ended, so give it back now. */
    fun sessionEnded(wirelessAlreadyRearmed: Boolean, userExit: Boolean) {
        val wireless: Boolean
        val usb: Boolean
        synchronized(this) {
            if (!owedAfterSession || holder != null) return
            // The user ended it: reconnecting on their behalf is the one thing not to do.
            if (userExit) { clearDebts(); return }
            wireless = wirelessOwed && !wirelessAlreadyRearmed
            usb = usbOwed
            clearDebts()
        }
        if (wireless || usb) actions?.giveBack(wireless, usb)
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

    /** A USB check that met another owner's connect in flight; it is asked again when that ends. */
    fun holdUsbCheck() {
        val held: Claim
        synchronized(this) {
            val current = holder ?: return
            if (current.owner == Owner.USB) return
            usbOwed = true
            held = current
        }
        AppLog.i("ConnectionArbiter: a USB check waits for $held")
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
        usbOwed = false
        owedAfterSession = false
    }

    /** Tests only. */
    internal fun reset() = synchronized(this) {
        holder = null
        generation = 0L
        quietPending = false
        clearDebts()
        endUsbEpisode()
    }
}
