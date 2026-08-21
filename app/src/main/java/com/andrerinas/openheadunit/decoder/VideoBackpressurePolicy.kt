package com.andrerinas.openheadunit.decoder

/**
 * Separates "the decoder could not keep up" from every other reason a frame goes missing.
 *
 * `VideoDecoder` already prints both numbers on every throughput line - `dropped=` and
 * `inputWait=` - and nothing reads them. Deciding what they mean has been a manual step in every
 * artifact investigation so far, and it is the step that has gone wrong: #830's round 6 could not
 * tell a rising drop count caused by a slow codec from one caused by a bursty link, and #219 has
 * been read as a reassembly fault for five months.
 *
 * `inputWait` is time the feed thread spent inside `dequeueInputBuffer`. When frames are shed *and*
 * that wait is a large share of the window, the codec is the bottleneck: it is not returning input
 * buffers fast enough, the feed queue fills, and the queue sheds. When frames are shed with the wait
 * near zero, the loss is upstream and the decoder is not the file to look in.
 *
 * ### Where the threshold comes from
 *
 * A #219 reporter's Galaxy Tab S7 FE, 2560x1440 HEVC on `c2.qti.hevc.decoder`, four captures. Of the
 * five 5s windows that shed frames, four waited 2019ms, 1333ms, 804ms and 705ms - 40%, 27%, 16% and
 * 14% of the window - and the fifth waited 180ms (3.6%). Across all 288 windows the median wait was
 * 188ms and the 90th percentile 587ms. Ten percent sits above the healthy median and below every
 * burst but one, so it separates the two populations that capture actually contains rather than a
 * number chosen for looking round.
 */
object VideoBackpressurePolicy {

    /** Share of a throughput window spent waiting for an input buffer before the codec is suspect. */
    const val WAIT_PERCENT = 10

    /**
     * Windows that both shed and waited before the codec is named.
     *
     * Two rather than one because a single window can be a scheduling hiccup, and more than two
     * because the bursts are isolated - they do not arrive consecutively, so anything demanding a
     * run of them would never fire on the capture this was built from.
     */
    const val WINDOWS_BEFORE_REPORT = 2

    /**
     * Whether this window's losses are attributable to the codec.
     *
     * Both halves are required. Waiting without shedding is a decoder under load that is still
     * keeping up, which is ordinary; shedding without waiting is a loss that happened somewhere
     * else entirely.
     */
    fun isBackpressureWindow(windowMs: Long, inputWaitMs: Long, dropped: Long): Boolean {
        if (dropped <= 0 || windowMs <= 0 || inputWaitMs <= 0) return false
        return inputWaitMs * 100 >= windowMs * WAIT_PERCENT
    }

    /** Whether enough of those windows have been seen to say so, once per decoder session. */
    fun shouldReport(backpressureWindows: Int, alreadyReported: Boolean): Boolean =
        !alreadyReported && backpressureWindows >= WINDOWS_BEFORE_REPORT
}
