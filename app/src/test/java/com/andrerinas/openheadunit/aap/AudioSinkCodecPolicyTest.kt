package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.decoder.audio.AudioSinkCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioSinkCodecPolicyTest {

    @Test
    fun `PCM and AAC framing remain distinct`() {
        assertEquals(AudioSinkCodec.PCM, AudioSinkCodecPolicy.codecFor(1))
        assertEquals(AudioSinkCodec.AAC_LC, AudioSinkCodecPolicy.codecFor(2))
        assertEquals(AudioSinkCodec.AAC_LC_ADTS, AudioSinkCodecPolicy.codecFor(4))
    }

    @Test
    fun `a video codec or an unknown type answers nothing`() {
        // The caller falls back to the setting rather than guessing from a type it cannot read.
        assertNull(AudioSinkCodecPolicy.codecFor(3))
        assertNull(AudioSinkCodecPolicy.codecFor(7))
        assertNull(AudioSinkCodecPolicy.codecFor(0))
    }
}
