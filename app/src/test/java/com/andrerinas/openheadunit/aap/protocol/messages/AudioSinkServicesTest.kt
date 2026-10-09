package com.andrerinas.openheadunit.aap.protocol.messages

import com.andrerinas.openheadunit.aap.AudioSessionConfig
import com.andrerinas.openheadunit.aap.protocol.AudioConfigs
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import com.andrerinas.openheadunit.decoder.audio.PlaybackFocusPolicy
import org.junit.Assert.*
import org.junit.Test

class AudioSinkServicesTest {
    private fun config(aac: Boolean = false, pcmGuidance: Boolean = false, enabled: Boolean = true) =
        AudioSessionConfig(enabled, false, PlaybackFocusPolicy.Mode.AUTO, false, 3, 0, 5,
            aac, pcmGuidance, false)

    @Test fun `wire sinks preserve old modes and apply PCM override after the wireless AAC choice`() {
        // Use the real format table and service builder, including when AudioConfigs has already
        // been initialised. No Android collection mock or class-initialisation order is involved.
        assertEquals(16000, AudioConfigs.get(Channel.ID_AU2).sampleRate)
        for (savedAac in listOf(false, true)) {
            for (effectiveAac in listOf(false, true)) {
                for (mixed in listOf(false, true)) {
                    for (enabled in listOf(false, true)) {
                        for (selfMode in listOf(false, true)) {
                            val services = AudioSinkServices.create(config(savedAac, mixed, enabled), effectiveAac, selfMode)
                                .map { Control.Service.parseFrom(it.toByteArray()) }
                            val expectedIds = if (enabled && !selfMode) listOf(5, 4, 6) else listOf(5)
                            assertEquals(expectedIds, services.map { it.id })
                            services.forEach { service ->
                                val sink = service.mediaSinkService
                                val media = service.id == Channel.ID_AUD
                                val expectedAac = effectiveAac && (media || !mixed)
                                assertEquals(if (expectedAac) 2 else 1, sink.availableType.number)
                                assertEquals(1, sink.audioConfigsCount)
                                val format = sink.getAudioConfigs(0)
                                assertEquals(if (media) 48000 else 16000, format.sampleRate)
                                assertEquals(if (media) 2 else 1, format.numberOfChannels)
                                assertEquals(16, format.numberOfBits)
                                assertEquals(when(service.id) {
                                    6 -> Media.AudioStreamType.MEDIA
                                    4 -> Media.AudioStreamType.SPEECH
                                    else -> Media.AudioStreamType.SYSTEM
                                }, sink.audioType)
                            }
                        }
                    }
                }
            }
        }
    }
}
