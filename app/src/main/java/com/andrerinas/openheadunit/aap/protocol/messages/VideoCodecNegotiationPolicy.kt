package com.andrerinas.openheadunit.aap.protocol.messages

import com.andrerinas.openheadunit.aap.protocol.proto.Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType
import com.andrerinas.openheadunit.aap.protocol.proto.Media.MediaCodecType

/** Chooses the one codec advertised for every video configuration in this session. */
internal object VideoCodecNegotiationPolicy {
    fun select(
        preference: String,
        resolution: VideoCodecResolutionType,
        forceSoftware: Boolean,
        selectedSoftwareHevcAvailable: Boolean,
        hardwareHevcSupported: Boolean,
        hardwareHevcReliable: Boolean
    ): MediaCodecType {
        // Software capability alone must not turn an explicit H.264 (or Auto) choice into HEVC.
        val explicitSoftwareHevc = preference == "H.265" && forceSoftware && selectedSoftwareHevcAvailable
        val highResolutionHevc = hardwareHevcReliable || explicitSoftwareHevc
        if ((resolution == VideoCodecResolutionType._2560x1440 ||
                    resolution == VideoCodecResolutionType._1440x2560) && highResolutionHevc) {
            return MediaCodecType.MEDIA_CODEC_VIDEO_H265
        }
        return when (preference) {
            "H.265" -> if (hardwareHevcSupported || explicitSoftwareHevc) {
                MediaCodecType.MEDIA_CODEC_VIDEO_H265
            } else {
                MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
            }
            "Auto" -> if (resolution == VideoCodecResolutionType._3840x2160 && hardwareHevcReliable) {
                MediaCodecType.MEDIA_CODEC_VIDEO_H265
            } else {
                MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
            }
            else -> MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
        }
    }
}
