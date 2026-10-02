package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class AacDecoderRetryTest {
    @Test fun `construction failure arms recovery without requiring a codec callback`() {
        val recovery = AacDecoderRecovery()
        recovery.request()
        assertEquals(AacDecoderRecovery.Action.REBUILD, recovery.next(0))
        assertEquals(1, recovery.attempts)
    }

    @Test fun `a failed replacement keeps its retry pending throughout cooldown`() {
        val recovery = AacDecoderRecovery()
        recovery.request()
        recovery.next(0)
        recovery.request()
        for (now in 1L..999L) {
            assertEquals(AacDecoderRecovery.Action.WAIT, recovery.next(now))
            assertTrue(recovery.pending)
            assertEquals(1, recovery.attempts)
        }
        assertEquals(AacDecoderRecovery.Action.REBUILD, recovery.next(1000))
        assertEquals(2, recovery.attempts)
    }

    @Test fun `terminal failure stops churn and a new playback can explicitly rearm`() {
        val recovery = AacDecoderRecovery()
        repeat(3) {
            recovery.request()
            assertEquals(AacDecoderRecovery.Action.REBUILD, recovery.next(it * 10000L))
        }
        recovery.request()
        assertEquals(AacDecoderRecovery.Action.STOP, recovery.next(30000))
        repeat(10) {
            recovery.request()
            assertEquals(AacDecoderRecovery.Action.NONE, recovery.next(40000))
        }
        recovery.retryIfExhausted()
        assertEquals(AacDecoderRecovery.Action.REBUILD, recovery.next(40001))
    }

    @Test fun `a playback request cannot reset a rebuild in progress or its cooldown`() {
        val recovery = AacDecoderRecovery()
        recovery.request()
        recovery.next(0)
        recovery.retryIfExhausted()
        assertEquals(1, recovery.attempts)
        assertEquals(AacDecoderRecovery.Action.NONE, recovery.next(1))
        recovery.request()
        recovery.retryIfExhausted()
        assertEquals(1, recovery.attempts)
        assertEquals(AacDecoderRecovery.Action.WAIT, recovery.next(2))
    }

    @Test fun `Start racing the last failure is retained on either side of the callback`() {
        for (startBeforeFailure in listOf(false, true)) {
            val recovery = AacDecoderRecovery()
            repeat(3) {
                recovery.request()
                assertEquals(AacDecoderRecovery.Action.REBUILD, recovery.next(it * 10000L))
            }
            if (startBeforeFailure) recovery.retryIfExhausted()
            recovery.request()
            if (!startBeforeFailure) recovery.retryIfExhausted()
            assertEquals(AacDecoderRecovery.Action.REBUILD, recovery.next(20001))
            assertEquals(1, recovery.attempts)
        }
    }
}
