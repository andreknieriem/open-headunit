package com.andrerinas.openheadunit.connection.carplaynative.usb

import com.andrerinas.openheadunit.connection.carplaynative.usb.UsbReadPolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class UsbReadPolicyTest {
    @Test fun dataResetsTheFastCount() {
        val step = UsbReadPolicy.decide(100, 1, 1000, 3)
        assertEquals(Verdict.Data, step.verdict); assertEquals(0, step.fastFailures)
    }

    @Test fun anIdleTimeoutIsRetriedAndResetsTheCount() {
        val step = UsbReadPolicy.decide(-1, 1000, 1000, 3)
        assertEquals(Verdict.Retry, step.verdict); assertEquals(0, step.fastFailures)
    }

    @Test fun aZeroLengthReadIsRetriedWithoutCounting() {
        val step = UsbReadPolicy.decide(0, 0, 1000, 2)
        assertEquals(Verdict.Retry, step.verdict); assertEquals(2, step.fastFailures)
    }

    @Test fun fiveInstantFailuresGiveUp() {
        var fast = 0
        repeat(UsbReadPolicy.MAX_FAST_FAILURES - 1) {
            val step = UsbReadPolicy.decide(-1, 0, 1000, fast)
            assertEquals(Verdict.Retry, step.verdict); fast = step.fastFailures
        }
        assertEquals(Verdict.GiveUp, UsbReadPolicy.decide(-1, 0, 1000, fast).verdict)
    }

    @Test fun anIdleReadBetweenFastFailuresKeepsTheLinkAlive() {
        var fast = 0
        repeat(UsbReadPolicy.MAX_FAST_FAILURES - 1) { fast = UsbReadPolicy.decide(-1, 0, 1000, fast).fastFailures }
        fast = UsbReadPolicy.decide(-1, 999, 1000, fast).fastFailures
        assertEquals(Verdict.Retry, UsbReadPolicy.decide(-1, 0, 1000, fast).verdict)
    }
}
