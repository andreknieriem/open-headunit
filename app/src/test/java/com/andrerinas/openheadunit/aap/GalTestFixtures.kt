package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.aap.protocol.proto.Media

internal fun galTestContract(profile: GalProfile = GalProfile.GAL_6_1): GalConnectionContract {
    val response = AapVersionNegotiation.Result(profile.version.major, profile.version.minor, 0, null)
    return GalConnectionContract.resolve(GalSessionProfile(profile, response),
        GalDisplaySettings(false, false, false))
}

internal fun galTestDiscovery(videoFallback: Boolean = false): Control.ServiceDiscoveryResponse {
    val response = Control.ServiceDiscoveryResponse.newBuilder().setMake("test").setModel("test")
        .setYear("2026").setVehicleId("test").setDriverPosition(Control.DriverPosition.DRIVER_POSITION_LEFT)
        .setHeadUnitMake("test").setHeadUnitModel("test").setHeadUnitSoftwareBuild("test")
        .setHeadUnitSoftwareVersion("test").setCanPlayNativeMediaDuringVr(false)
    for (channel in listOf(Channel.ID_AUD, Channel.ID_AU1, Channel.ID_AU2)) {
        val audio = Media.AudioConfiguration.newBuilder().setSampleRate(if (channel == Channel.ID_AUD) 48000 else 16000)
            .setNumberOfBits(16).setNumberOfChannels(if (channel == Channel.ID_AUD) 2 else 1)
        response.addServices(Control.Service.newBuilder().setId(channel).setMediaSinkService(
            Control.Service.MediaSinkService.newBuilder().setAvailableType(Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM)
                .addAudioConfigs(audio)))
    }
    response.addServices(Control.Service.newBuilder().setId(Channel.ID_MIC).setMediaSourceService(
        Control.Service.MediaSourceService.newBuilder().setType(Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM)
            .setAudioConfig(Media.AudioConfiguration.newBuilder().setSampleRate(16000)
                .setNumberOfBits(16).setNumberOfChannels(1))))
    val video = Control.Service.MediaSinkService.VideoConfiguration.newBuilder()
        .setCodecResolution(Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1280x720)
        .setFrameRate(Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType._30)
        .setMarginWidth(0).setMarginHeight(0).setDensity(160).build()
    val sink = Control.Service.MediaSinkService.newBuilder().setAvailableType(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
        .addVideoConfigs(video)
    if (videoFallback) sink.addVideoConfigs(video.toBuilder()
        .setCodecResolution(Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480))
    response.addServices(Control.Service.newBuilder().setId(Channel.ID_VID).setMediaSinkService(sink))
    return response.build()
}

internal fun galTestSessions(profile: GalProfile = GalProfile.GAL_6_1): GalMediaSessions =
    GalMediaSessions().apply { advertise(galTestContract(profile).media, GalMediaOffer.from(galTestDiscovery())) }
