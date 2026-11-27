package com.andrerinas.openheadunit.connection.carplaynative.airplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSilenceTest {

    @Test fun theLimitItselfIsStillAlive() {
        val silence = PhoneSilence(1_000)
        assertFalse(silence.isSilent(1_000 + PhoneSilence.SILENT_MS))
        assertTrue(silence.isSilent(1_001 + PhoneSilence.SILENT_MS))
    }

    @Test fun aSoundMovesTheWindow() {
        val silence = PhoneSilence(0)
        silence.heard(9_000)
        assertFalse(silence.isSilent(18_000))
        assertTrue(silence.isSilent(19_001))
    }
}
