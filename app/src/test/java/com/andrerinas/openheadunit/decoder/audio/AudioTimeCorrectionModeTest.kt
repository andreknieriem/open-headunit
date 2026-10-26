package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class AudioTimeCorrectionModeTest {
    @Test fun `missing and unknown preferences use OHU`() {
        for (saved in listOf(null, "", "unknown")) {
            assertEquals("saved=$saved", AudioTimeCorrectionMode.OHU,
                AudioTimeCorrectionMode.fromPreference(saved))
        }
    }

    @Test fun `native engines apply only to supported PCM media sinks`() {
        for (selected in AudioTimeCorrectionMode.entries) {
            for (media in listOf(false, true)) for (pcm in listOf(false, true)) {
                for (available in listOf(false, true)) {
                    var probes = 0
                    val effective = selected.forSink(media, pcm) { probes++; available }
                    val eligible = selected != AudioTimeCorrectionMode.OHU && media && pcm
                    assertEquals("selected=$selected media=$media pcm=$pcm available=$available",
                        if (eligible && available) selected else AudioTimeCorrectionMode.OHU, effective)
                    assertEquals("ineligible sinks must not load the native library", if (eligible) 1 else 0, probes)
                }
            }
        }
    }

}
