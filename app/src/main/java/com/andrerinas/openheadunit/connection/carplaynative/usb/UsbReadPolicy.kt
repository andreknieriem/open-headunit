package com.andrerinas.openheadunit.connection.carplaynative.usb

/**
 * What the reader does with one bulk read result. A -1 after the full timeout is an idle wire and is retried.
 * A -1 that returns at once is a dead pipe, and the reader gives up after [MAX_FAST_FAILURES] of them in a row.
 */
object UsbReadPolicy {
    const val MAX_FAST_FAILURES = 5

    enum class Verdict { Data, Retry, GiveUp }

    class Step(val verdict: Verdict, val fastFailures: Int)

    fun decide(transferred: Int, elapsedMs: Long, timeoutMs: Int, fastFailures: Int): Step = when {
        transferred > 0 -> Step(Verdict.Data, 0)
        transferred == 0 -> Step(Verdict.Retry, fastFailures)
        elapsedMs >= timeoutMs / 2 -> Step(Verdict.Retry, 0)
        fastFailures + 1 >= MAX_FAST_FAILURES -> Step(Verdict.GiveUp, fastFailures + 1)
        else -> Step(Verdict.Retry, fastFailures + 1)
    }
}
