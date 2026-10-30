package com.andrerinas.openheadunit.decoder.video

/** The Surface's consumer, not the decoder backend, determines timed-release semantics.
 * SurfaceTexture/ImageReader consumers can receive a buffer immediately with the requested
 * timestamp attached. Their consumer-side timestamps are not evidence of display presentation.
 * Only a directly composed SurfaceView target opts into platform scheduling and clock feedback.
 */
enum class VideoSurfaceKind {
    DIRECT_DISPLAY,
    TEXTURE_CONSUMER,
    UNKNOWN;

    fun usesPlatformTiming(sdk: Int): Boolean = this == DIRECT_DISPLAY && sdk >= 21
    fun supportsRenderedFeedback(sdk: Int): Boolean = this == DIRECT_DISPLAY && sdk >= 23
}
