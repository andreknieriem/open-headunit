package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class AacOutputFormatTest {
    @Test fun `decoder must announce the negotiated PCM format before output is accepted`() {
        val state = AacOutputFormat(48000, 2)
        assertFalse(state.acceptsOutput)
        assertTrue(state.update(48000, 2, 2))
        assertTrue(AacOutputFormat(48000, 2).update(48000, 2, null)) // omitted by older codecs
        assertFalse(AacOutputFormat(48000, 2).update(96000, 2, 2)) // SBR cannot run at half speed.
        assertFalse(AacOutputFormat(48000, 2).update(48000, 1, 2))
        assertFalse(AacOutputFormat(48000, 2).update(48000, 2, 4)) // Float is not PCM16 bytes.
        assertFalse(AacOutputFormat(48000, 2).update(48000, 2, -1)) // present but unreadable
        assertFalse(state.update(-1, -1, -1))
        assertFalse(state.update(48000, 2, 2)) // a rejected generation must be replaced
    }

    @Test fun `late format events from an old decoder cannot validate a replacement`() {
        val old = AacOutputFormat(16000, 1)
        val replacement = AacOutputFormat(16000, 1)
        assertTrue(old.update(16000, 1, 2))
        assertFalse(replacement.acceptsOutput)
        assertTrue(replacement.update(16000, 1, 2))
        old.update(32000, 2, 2)
        assertTrue(replacement.acceptsOutput)
    }
}
