package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

/**
 * What one dial at the vendor daemon actually proved.
 *
 * A port that refuses and a port that is there but busy are different answers, and collapsing them
 * made the app tell a reporter whose module works that their hardware cannot do Bluetooth wireless.
 */
object ZbtReachabilityPolicy {

    enum class Verdict {
        /** The daemon answered a frame. This unit's module carries Android Auto. */
        ANSWERED,

        /** Something holds the port but did not answer. Present, and busy is not absent. */
        LISTENING_SILENT,

        /** The port refused the connection: no daemon here, whatever the hardware markers say. */
        NOTHING_LISTENING
    }

    /** `ConnectException` is the only refusal; a loopback port with nobody on it always gives it. */
    private const val REFUSAL = "ConnectException"

    /**
     * @param connectFailure the simple name of what `connect()` threw, or null if it succeeded
     * @param answered whether a frame came back within the hello budget
     */
    fun classify(connectFailure: String?, answered: Boolean): Verdict = when {
        connectFailure == REFUSAL -> Verdict.NOTHING_LISTENING
        // A timeout means the backlog is not being drained, which needs a listener to not drain it.
        // Anything else unexpected is read the same way: on this hardware the alternative to trying
        // is refusing Native AA outright, and the carrier retries where a one-shot dial cannot.
        connectFailure != null -> Verdict.LISTENING_SILENT
        answered -> Verdict.ANSWERED
        else -> Verdict.LISTENING_SILENT
    }

    /**
     * Whether the module route is worth taking. Only an outright refusal rules it out: a daemon
     * that is there and busy is retried by [ZbtAaCarrier], which a one-shot dial cannot do.
     */
    fun reachable(verdict: Verdict): Boolean = verdict != Verdict.NOTHING_LISTENING

    /** How long a bring-up keeps re-dialling a refusing daemon before ruling the route out. */
    const val REFUSAL_WINDOW_MS = 30_000L

    private val REDIAL_GAPS_MS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L)

    /**
     * The wait before the next dial after [refusalsSoFar] refusals, [sinceFirstRefusalMs] after
     * the first, clamped to what is left of the window. Null once the window is spent.
     */
    fun redialAfterMs(refusalsSoFar: Int, sinceFirstRefusalMs: Long): Long? {
        val left = REFUSAL_WINDOW_MS - sinceFirstRefusalMs
        if (left <= 0L) return null
        val gap = REDIAL_GAPS_MS[(refusalsSoFar - 1).coerceIn(0, REDIAL_GAPS_MS.size - 1)]
        return minOf(gap, left)
    }
}
