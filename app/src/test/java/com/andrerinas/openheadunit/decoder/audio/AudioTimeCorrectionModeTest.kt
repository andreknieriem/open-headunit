package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class AudioTimeCorrectionModeTest {
    @Test fun `native engines apply only to supported PCM media sinks`() {
        for (selected in AudioTimeCorrectionMode.entries) {
            for (media in listOf(false, true)) for (pcm in listOf(false, true)) {
                for (api in listOf(16, 20, 21, 23, 37)) for (available in listOf(false, true)) {
                    var probes = 0
                    val effective = selected.forSink(media, pcm, api) { probes++; available }
                    val eligible = selected != AudioTimeCorrectionMode.OHU && media && pcm && api >= 21
                    assertEquals("selected=$selected media=$media pcm=$pcm api=$api available=$available",
                        if (eligible && available) selected else AudioTimeCorrectionMode.OHU, effective)
                    assertEquals("ineligible sinks must not load the native library", if (eligible) 1 else 0, probes)
                }
            }
        }
    }

}
