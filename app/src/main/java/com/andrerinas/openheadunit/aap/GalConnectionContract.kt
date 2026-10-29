package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.aap.protocol.proto.Media

internal data class GalOptionsPolicy(
    val embedded: Boolean,
    val standalone: Boolean
)

internal class GalMediaPolicy internal constructor(
    private val audio: GalOptionsPolicy,
    private val video: GalOptionsPolicy,
    val laboratory: GalLaboratoryOptions = GalLaboratoryOptions()
) {
    fun optionsFor(channel: Int): GalOptionsPolicy = when {
        Channel.isAudio(channel) -> audio
        channel == Channel.ID_VID -> video
        else -> NO_OPTIONS
    }

    companion object {
        val NO_OPTIONS = GalOptionsPolicy(false, false)
    }
}

/**
 * Built once after the final version response. Only this factory maps versions to receiver
 * behavior. Handlers consume policies, while the actual Start still owns ACK mode and format.
 * Display settings are captured before negotiation so opening settings cannot alter this contract.
 */
internal class GalConnectionContract private constructor(
    val negotiation: GalSessionProfile,
    val media: GalMediaPolicy,
    val display: GalDisplayContract,
    val guidance48Khz: Boolean
) {
    companion object {
        fun resolve(negotiation: GalSessionProfile, display: GalDisplaySettings,
                    laboratory: GalLaboratoryOptions = GalLaboratoryOptions()): GalConnectionContract {
            require(negotiation.finalResponse.accepted &&
                negotiation.finalResponse.version >= negotiation.requestedVersion)
            val options = laboratory.normalized()
            val capabilities = GalLaboratoryCapabilities.forProfile(negotiation.profile)
            val media = GalMediaPolicy(
                GalOptionsPolicy(capabilities.mediaOptions, capabilities.audioUpdates && options.audioUpdates),
                GalOptionsPolicy(capabilities.mediaOptions, capabilities.videoUpdates && options.videoUpdates),
                options
            )
            return GalConnectionContract(negotiation, media,
                GalDisplayContract(display, capabilities.modernDisplayConfig && options.modernDisplayConfig),
                capabilities.guidance48Khz && options.guidance48Khz)
        }
    }
}

/** Append the actual guidance offer, preserving the 16 kHz alternative and its selection by Start. */
internal fun GalConnectionContract.applyGuidanceConfigurations(
    sink: Control.Service.MediaSinkService.Builder,
    baseline: Media.AudioConfiguration
) {
    if (guidance48Khz) sink.addAudioConfigs(baseline.toBuilder().setSampleRate(48000).build())
    sink.addAudioConfigs(baseline)
}

/** Format entries come from the protobuf actually sent, never from settings read again at Start. */
internal sealed class GalMediaFormat {
    data class Audio(val value: Media.AudioConfiguration) : GalMediaFormat()
    data class Video(val value: Control.Service.MediaSinkService.VideoConfiguration) : GalMediaFormat()
}

internal class GalMediaOffer private constructor(
    private val sinks: Map<Int, List<GalMediaFormat>>,
    private val codecs: Map<Int, Int>
) {
    fun codec(channel: Int): Int? = codecs[channel]
    fun indices(channel: Int): List<Int> = sinks[channel]?.indices?.toList() ?: emptyList()
    fun format(channel: Int, index: Int): GalMediaFormat? = sinks[channel]?.getOrNull(index)

    companion object {
        fun from(discovery: Control.ServiceDiscoveryResponse): GalMediaOffer {
            val sinks = mutableMapOf<Int, List<GalMediaFormat>>()
            val codecs = mutableMapOf<Int, Int>()
            for (service in discovery.servicesList) {
                if (service.id == Channel.ID_MIC && service.hasMediaSourceService()) {
                    codecs[service.id] = service.mediaSourceService.type.number
                }
                if (!service.hasMediaSinkService()) continue // Includes microphone source exclusion.
                val sink = service.mediaSinkService
                val formats = when {
                    Channel.isAudio(service.id) -> sink.audioConfigsList.map { GalMediaFormat.Audio(it) }
                    service.id == Channel.ID_VID -> sink.videoConfigsList.map { GalMediaFormat.Video(it) }
                    else -> continue
                }
                require(!sinks.containsKey(service.id)) { "Duplicate media sink ${service.id}" }
                sinks[service.id] = formats
                codecs[service.id] = sink.availableType.number
            }
            return GalMediaOffer(sinks.toMap(), codecs.toMap())
        }
    }
}
