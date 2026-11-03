package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zjsocket

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.JoinRefusalPolicy

/**
 * What a connect to the ZJ daemon's Unix socket proves, and how long the answer is trusted.
 *
 * A file that exists proves nothing about a daemon that accepts, so only a connect counts.
 */
object ZjSocketPolicy {

    enum class Outcome { CONNECTS, REFUSED, DENIED, TIMED_OUT, MISSING }

    /** [path] is the entry that decided the answer, or null when none exists. */
    data class Probe(val path: String?, val outcome: Outcome)

    data class Verdict(val connects: Boolean, val atMs: Long)

    /** A connect that never returns is not an answer; the probe closes the socket after this. */
    const val CONNECT_DEADLINE_MS = 2_000L

    /** A refusal can be a daemon still starting, so it is asked again once it is this old. */
    const val NEGATIVE_MAX_AGE_MS = 120_000L

    /** Gap between dials when nothing is wrong; refused connects double from it. */
    const val BASE_RETRY_MS = 3_000L

    /** Ceiling of the gap after refused connects. */
    const val CONNECT_BACKOFF_CAP_MS = 30_000L

    enum class Action { DIAL, WAIT, STOP }

    fun connects(outcome: Outcome): Boolean = outcome == Outcome.CONNECTS

    /** Android words a denied connect as "Permission denied" and any other failure as a refusal. */
    fun failureOutcome(message: String?): Outcome =
        if (message?.contains("Permission denied", ignoreCase = true) == true) Outcome.DENIED
        else Outcome.REFUSED

    /** Walks [paths] in order; a missing path costs no connect and the first accepted one wins. */
    fun probe(paths: List<String>, exists: (String) -> Boolean, connect: (String) -> Outcome): Probe {
        var last = Probe(null, Outcome.MISSING)
        for (path in paths) {
            if (!exists(path)) continue
            val outcome = connect(path)
            if (connects(outcome)) return Probe(path, outcome)
            last = Probe(path, outcome)
        }
        return last
    }

    /** A positive answer holds for the process; a negative one until it is [NEGATIVE_MAX_AGE_MS] old. */
    fun cachedAnswer(prior: Verdict?, nowMs: Long): Boolean? = when {
        prior == null -> null
        prior.connects -> true
        nowMs - prior.atMs < NEGATIVE_MAX_AGE_MS -> false
        else -> null
    }

    /** Whether a fresh arming keeps [prior]; only a positive survives it. */
    fun keptAtArming(prior: Verdict?): Boolean = prior?.connects == true

    /**
     * The carrier's next step. STOP comes only from the manager stopping: a session that closes
     * the Android listeners must not end the carrier, or the next drive finds nobody dialling.
     */
    fun decide(
        running: Boolean,
        sessionUp: Boolean,
        handshakeInFlight: Boolean,
        settling: Boolean,
        mayServe: Boolean
    ): Action = when {
        !running -> Action.STOP
        sessionUp || handshakeInFlight || settling || !mayServe -> Action.WAIT
        else -> Action.DIAL
    }

    /** A cancelled handshake ends the carrier only when the carrier itself was stopped or cancelled. */
    fun cancellationEndsCarrier(stopped: Boolean, carrierActive: Boolean): Boolean =
        stopped || !carrierActive

    /** The socket is asked once, at ZBT's first refusal, so a unit without 3152 skips its 30 s window. */
    fun probesDuringZbtWait(refusals: Int, zjAnswer: Boolean?): Boolean = refusals == 1 && zjAnswer == null

    /** A socket that connects makes a ZBT refusal final; a daemon that answers its first dial still wins. */
    fun endsZbtWait(refusals: Int, zjConnects: Boolean?): Boolean = refusals >= 1 && zjConnects == true

    /** The gap before the next dial; [count] is the join refusals or the refused connects in a row. */
    fun retryDelayMs(afterServed: Boolean, count: Int): Long =
        if (afterServed) JoinRefusalPolicy.retryDelayMs(count, BASE_RETRY_MS)
        else minOf(BASE_RETRY_MS shl (count - 1).coerceIn(0, 6), CONNECT_BACKOFF_CAP_MS)
}
