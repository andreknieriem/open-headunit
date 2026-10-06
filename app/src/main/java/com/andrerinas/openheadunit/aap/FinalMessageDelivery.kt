package com.andrerinas.openheadunit.aap

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Gives a final control message a bounded opportunity to leave the existing writer. */
internal object FinalMessageDelivery {
    enum class Result { SENT, FAILED, REJECTED, TIMED_OUT, INTERRUPTED }

    fun send(
        onWriterThread: Boolean,
        enqueueFirst: (Runnable) -> Boolean,
        write: () -> Boolean,
        timeoutMs: Long = 150,
    ): Result {
        fun attemptWrite(): Result = try {
            if (write()) Result.SENT else Result.FAILED
        } catch (_: Exception) {
            Result.FAILED
        }
        if (onWriterThread) return attemptWrite()
        val complete = CountDownLatch(1)
        var result = Result.FAILED
        val queued = enqueueFirst(Runnable {
            try {
                result = attemptWrite()
            } finally {
                complete.countDown()
            }
        })
        if (!queued) return Result.REJECTED
        return try {
            if (complete.await(timeoutMs, TimeUnit.MILLISECONDS)) result else Result.TIMED_OUT
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            Result.INTERRUPTED
        }
    }
}
