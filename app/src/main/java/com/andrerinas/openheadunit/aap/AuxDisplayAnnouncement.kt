package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import com.andrerinas.openheadunit.decoder.video.AuxDisplayProfilePolicy

/**
 * Android Auto counts the inputs of each display, and none or two ends the session. The aux input
 * carries no touch and no keys, because no aux output returns touch.
 */
object AuxDisplayAnnouncement {

    const val AUX_DISPLAY_ID = 1

    /** The aux sink, then its input. A [contentKeycode] of null leaves the content to the phone. */
    fun services(
        profile: AuxDisplayProfilePolicy.Profile,
        role: AuxDisplayProfilePolicy.Role,
        contentKeycode: Int?,
    ): List<Control.Service> {
        val sink = Control.Service.newBuilder().also { service ->
            service.id = Channel.ID_VID2
            service.mediaSinkService = Control.Service.MediaSinkService.newBuilder().also { sink ->
                // H.264 baseline, which is the only codec the protocol allows a video sink.
                sink.availableType = Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
                sink.audioType = Media.AudioStreamType.NONE
                sink.displayId = AUX_DISPLAY_ID
                sink.displayType = AuxDisplayProfilePolicy.displayType(role)
                contentKeycode?.let { sink.initialContentKeycode = it }
                sink.addVideoConfigs(Control.Service.MediaSinkService.VideoConfiguration.newBuilder().apply {
                    codecResolution = profile.resolution
                    frameRate = profile.frameRate
                    setDensity(profile.density)
                    setMarginWidth(profile.widthMargin)
                    setMarginHeight(profile.heightMargin)
                    setVideoCodecType(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
                }.build())
            }.build()
        }.build()
        val input = Control.Service.newBuilder().also { service ->
            service.id = Channel.ID_INP2
            service.inputSourceService = Control.Service.InputSourceService.newBuilder().also {
                it.displayId = AUX_DISPLAY_ID
            }.build()
        }.build()
        return listOf(sink, input)
    }
}
