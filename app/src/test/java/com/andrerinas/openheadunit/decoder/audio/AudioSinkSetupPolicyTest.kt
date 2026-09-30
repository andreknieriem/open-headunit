package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSinkSetupPolicyTest {

    @Test
    fun `no sink yet means build one`() {
        assertTrue(AudioSinkSetupPolicy.rebuilds(hasLiveTrack = false, builtCodec = null, setupCodec = AudioSinkCodec.AAC_LC))
        assertTrue(AudioSinkSetupPolicy.rebuilds(hasLiveTrack = false, builtCodec = AudioSinkCodec.PCM, setupCodec = AudioSinkCodec.PCM))
    }

    @Test
    fun `a repeated setup on the same codec keeps the sink`() {
        assertFalse(AudioSinkSetupPolicy.rebuilds(hasLiveTrack = true, builtCodec = AudioSinkCodec.AAC_LC, setupCodec = AudioSinkCodec.AAC_LC))
        assertFalse(AudioSinkSetupPolicy.rebuilds(hasLiveTrack = true, builtCodec = AudioSinkCodec.PCM, setupCodec = AudioSinkCodec.PCM))
    }

    @Test
    fun `a setup that changes the codec rebuilds`() {
        assertTrue(AudioSinkSetupPolicy.rebuilds(hasLiveTrack = true, builtCodec = AudioSinkCodec.PCM, setupCodec = AudioSinkCodec.AAC_LC))
        assertTrue(AudioSinkSetupPolicy.rebuilds(hasLiveTrack = true, builtCodec = AudioSinkCodec.AAC_LC, setupCodec = AudioSinkCodec.PCM))
    }

    @Test
    fun `AAC framing changes rebuild but repeated ADTS keeps its sink`() {
        assertTrue(AudioSinkSetupPolicy.rebuilds(true, AudioSinkCodec.AAC_LC, AudioSinkCodec.AAC_LC_ADTS))
        assertTrue(AudioSinkSetupPolicy.rebuilds(true, AudioSinkCodec.AAC_LC_ADTS, AudioSinkCodec.AAC_LC))
        assertFalse(AudioSinkSetupPolicy.rebuilds(true, AudioSinkCodec.AAC_LC_ADTS, AudioSinkCodec.AAC_LC_ADTS))
    }

    @Test
    fun `a setup naming no audio codec leaves a live sink alone`() {
        assertFalse(AudioSinkSetupPolicy.rebuilds(hasLiveTrack = true, builtCodec = AudioSinkCodec.AAC_LC, setupCodec = null))
    }

    @Test
    fun `a track we cannot read the codec of is rebuilt`() {
        assertTrue(AudioSinkSetupPolicy.rebuilds(hasLiveTrack = true, builtCodec = null, setupCodec = AudioSinkCodec.AAC_LC))
    }
}
