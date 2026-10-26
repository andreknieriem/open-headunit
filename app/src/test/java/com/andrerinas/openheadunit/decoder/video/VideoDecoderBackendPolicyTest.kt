package com.andrerinas.openheadunit.decoder.video

import com.andrerinas.openheadunit.decoder.video.VideoDecoder.CodecType
import com.andrerinas.openheadunit.utils.Settings.SoftwareVideoDecoder
import org.junit.Assert.*
import org.junit.Test

class VideoDecoderBackendPolicyTest {
    @Test
    fun `both negotiated codecs select bundled only when forced selected and available`() {
        for (codec in CodecType.values()) {
            for (forceSoftware in listOf(false, true)) {
                for (source in SoftwareVideoDecoder.values()) {
                    for (available in listOf(false, true)) {
                        val bundled = VideoDecoderBackendPolicy.useBundled(codec, forceSoftware, source) { available }
                        assertEquals(
                            "$codec force=$forceSoftware source=$source available=$available",
                            forceSoftware && source == SoftwareVideoDecoder.BUNDLED_FFMPEG && available,
                            bundled,
                        )
                        assertEquals(if (bundled) 6 else 12, VideoDecoderBackendPolicy.videoAckWindow(true, bundled))
                        assertEquals(if (bundled) 8 else 16, VideoDecoderBackendPolicy.videoAckWindow(false, bundled))
                    }
                }
            }
        }
    }

    @Test
    fun `availability of one codec cannot enable the other codec`() {
        for (availableCodec in CodecType.values()) {
            for (streamCodec in CodecType.values()) {
                assertEquals(
                    streamCodec == availableCodec,
                    VideoDecoderBackendPolicy.useBundled(streamCodec, true, SoftwareVideoDecoder.BUNDLED_FFMPEG) {
                        it == availableCodec
                    },
                )
            }
        }
    }

    @Test
    fun `platform selection never loads or probes the optional native backend`() {
        for (codec in CodecType.values()) {
            for (source in SoftwareVideoDecoder.values()) {
                assertFalse(VideoDecoderBackendPolicy.useBundled(codec, false, source) {
                    throw AssertionError("hardware selection must not probe FFmpeg")
                })
            }
            assertFalse(VideoDecoderBackendPolicy.useBundled(codec, true, SoftwareVideoDecoder.DEVICE_MEDIACODEC) {
                throw AssertionError("device selection must not probe FFmpeg")
            })
        }
    }

}
