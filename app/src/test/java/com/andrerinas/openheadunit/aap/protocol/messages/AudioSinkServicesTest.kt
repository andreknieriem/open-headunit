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
    @Test fun `48k requires both options and the negotiated version while system remains compatible`() {
        for (pcm in listOf(false, true)) for (prefer48 in listOf(false, true)) {
            for (supported in listOf(false, true)) for (aac in listOf(false, true)) {
                val session = config(aac, pcm).copy(prefer48kGuidance = prefer48)
                val services = AudioSinkServices.create(session, aac, false, supported)
                    .map { Control.Service.parseFrom(it.toByteArray()) }
                for (service in services) {
                    val expectedRates = when (service.id) {
                        6 -> listOf(48000)
                        4 -> if (pcm && prefer48 && supported) listOf(16000, 48000) else listOf(16000)
                        else -> listOf(16000)
                    }
                    assertEquals(expectedRates, service.mediaSinkService.audioConfigsList.map { it.sampleRate })
                    assertEquals(expectedRates.indices.toList(), session.configurationIndicesFor(service.id,
                        supported, session.codecFor(service.id, aac)))
                }
                // An unexpected but valid AAC Setup must not authorize the PCM-only index.
                assertEquals(listOf(0), session.configurationIndicesFor(4, supported,
                    com.andrerinas.openheadunit.decoder.audio.AudioSinkCodec.AAC_LC))
            }
        }
        for (enabled in listOf(false, true)) for (self in listOf(false, true)) {
            val session = config(true, true, enabled).copy(prefer48kGuidance = true)
            val services = AudioSinkServices.create(session, true, self, true)
            assertEquals(if (enabled && !self) listOf(5, 4, 6) else listOf(5), services.map { it.id })
            assertEquals(listOf(16000), services.first().mediaSinkService.audioConfigsList.map { it.sampleRate })
        }
    }

}
