package com.andrerinas.openheadunit.decoder.video

import com.andrerinas.openheadunit.utils.Settings

/** Shared by decoder startup and transport credits; availability always belongs to the stream codec. */
object VideoDecoderBackendPolicy {
    fun useBundled(
        codec: VideoDecoder.CodecType,
        forceSoftware: Boolean,
        softwareDecoder: Settings.SoftwareVideoDecoder,
        available: (VideoDecoder.CodecType) -> Boolean = { FfmpegVideoDecoder.isAvailable(it) },
    ): Boolean = forceSoftware &&
        softwareDecoder == Settings.SoftwareVideoDecoder.BUNDLED_FFMPEG && available(codec)

    fun videoAckWindow(wireless: Boolean, bundled: Boolean): Int = when {
        bundled -> if (wireless) 6 else 8
        else -> if (wireless) 12 else 16
    }
}
