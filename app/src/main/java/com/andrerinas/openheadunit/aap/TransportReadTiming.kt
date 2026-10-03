package com.andrerinas.openheadunit.aap

/** Wall-clock stage costs include OS scheduling inside each operation, not just network time. */
internal data class TransportReadTiming(
    val channel: Int,
    val readerGapMs: Long,
    val headerMs: Long,
    val bodyMs: Long,
    val decryptMs: Long,
    val dispatchMs: Long
) {
    companion object {
        // Waiting for the next header is normal socket idle time, not decoder work.
        fun isProcessingSlow(readerGapMs: Long, afterHeaderMs: Long): Boolean =
            readerGapMs >= 50 || afterHeaderMs >= 50
    }
}
