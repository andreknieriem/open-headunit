package com.andrerinas.openheadunit.decoder.video

/**
 * Notices when the stream's parameter sets stop being the ones the codec was configured from.
 *
 * Observes parameter-set changes independently of decoder configuration and recovery caching.
 * It does not modify stored CSD, dimensions, or the live codec. The platform decoder's output
 * format remains the authority for a running component's dimensions.
 *
 * ### Why the change is latched
 *
 * Parameter sets are re-sent with every keyframe, so "the sets arrived again" is the ordinary case
 * and says nothing. Only a difference counts, and a difference counts once: VLC latches its version
 * on read for exactly this reason. Without that, one genuine change would be re-reported on every
 * keyframe for the rest of the session.
 *
 * One slot per kind rather than VLC's by-id table. A stream that alternates parameter-set ids would
 * defeat this, and Android Auto is not known to send one; if a capture ever shows a change reported
 * on every keyframe with the bytes flipping between two values, that is what it means.
 *
 * Pure: no clock, no logging, no Android. Copies what it stores, because the caller's buffer is
 * reused.
 */
class ParameterSetTracker {

    enum class Kind { VPS, SPS, PPS }

    /**
     * A genuine difference, reported once.
     *
     * [ordinal] counts changes on this tracker, so a log line can say which one it is rather than
     * leaving a reader to count lines. [kinds] is what actually differed, in VPS/SPS/PPS order.
     */
    data class Change(val ordinal: Int, val kinds: List<Kind>)

    private val stored = arrayOfNulls<ByteArray>(Kind.entries.size)
    private val pending = LinkedHashSet<Kind>()
    private var changesReported = 0

    /**
     * Offers one parameter set.
     *
     * Returns true when it differs from the one held for that kind. The first of each kind is not a
     * change - there was nothing to differ from - which is what keeps a fresh session silent.
     */
    fun offer(kind: Kind, data: ByteArray, offset: Int, length: Int): Boolean {
        if (length <= 0 || offset < 0 || offset + length > data.size) return false
        val previous = stored[kind.ordinal]
        val copy = data.copyOfRange(offset, offset + length)
        stored[kind.ordinal] = copy
        if (previous == null) return false
        if (previous.contentEquals(copy)) return false
        pending.add(kind)
        return true
    }

    /**
     * The change since the last call, or null.
     *
     * Reading clears it. That is the whole reason a caller can run this on every access unit that
     * carries parameter sets without a change being reported on every keyframe that follows it.
     */
    fun takeChange(): Change? {
        if (pending.isEmpty()) return null
        changesReported++
        val kinds = Kind.entries.filter { it in pending }
        pending.clear()
        return Change(changesReported, kinds)
    }

    /** Forgets everything. For a new session, not for a codec restart - the stream is unchanged. */
    fun reset() {
        stored.fill(null)
        pending.clear()
        changesReported = 0
    }
}
