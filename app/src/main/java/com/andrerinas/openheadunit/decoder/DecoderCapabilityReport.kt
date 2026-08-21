package com.andrerinas.openheadunit.decoder

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/**
 * What a decoder claims about a picture, asked in two places for two different reasons.
 *
 * **At configure time**, of the component already chosen: does it manage the stream it is about to
 * be handed? **At negotiation time**, of whatever component would be chosen: is the profile we are
 * about to ask the phone for one this device can carry?
 *
 * The second question is the one nothing has ever asked. `ServiceDiscoveryResponse` overrides the
 * user's codec choice and forces H.265 whenever the negotiated resolution is 1440p, without
 * consulting any decoder - and a #219 reporter's Galaxy Tab S7 FE runs exactly that: 2560x1440 HEVC
 * on `c2.qti.hevc.decoder`, shedding frames in bursts with up to 2019ms of a 5s window spent waiting
 * for an input buffer. No log has ever said whether that component claims to sustain the size and
 * rate we forced on it.
 *
 * Nothing here changes a decision. It produces the line that would justify changing one.
 */
object DecoderCapabilityReport {

    /**
     * One decoder's answer, plus the numbers behind it.
     *
     * [sustains] is deliberately nullable and distinct from [rateSupported]: below API 29 there are
     * no performance points, and "we could not ask" is not "the answer was no".
     */
    data class Capability(
        val codecName: String,
        val mimeType: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val sizeSupported: Boolean,
        val rateSupported: Boolean,
        val sustains: Boolean?,
        val lowLatency: Boolean,
        val adaptivePlayback: Boolean,
        val supportedWidths: String,
        val supportedHeights: String,
    ) {
        /**
         * Whether this decoder claims it can carry the picture.
         *
         * An unknown [sustains] counts as adequate. `areSizeAndRateSupported` is the weaker claim -
         * it says the component accepts the format, not that it keeps up - so treating a missing
         * performance point as failure would flag every pre-Android-10 device that is working fine.
         */
        val adequate: Boolean
            get() = sizeSupported && rateSupported && sustains != false

        override fun toString(): String =
            "codec=$codecName mime=$mimeType target=${width}x${height}@$fps " +
                "sizeSupported=$sizeSupported rateSupported=$rateSupported " +
                "sustains=${sustains ?: "unknown"} " +
                "widths=$supportedWidths heights=$supportedHeights " +
                "featureLowLatency=$lowLatency featureAdaptivePlayback=$adaptivePlayback"
    }

    /** Raw capabilities of [codecName] for [mimeType], or null when they cannot be read. */
    fun capabilitiesOf(codecName: String, mimeType: String): MediaCodecInfo.CodecCapabilities? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        return try {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .firstOrNull { it.name == codecName }
                ?.getCapabilitiesForType(mimeType)
        } catch (e: Exception) {
            null
        }
    }

    /** Whether the component itself claims Android 11's low-latency feature. */
    fun advertisesLowLatency(codecName: String, mimeType: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            capabilitiesOf(codecName, mimeType)
                ?.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency) == true
        } catch (e: Exception) {
            false
        }
    }

    /** The configure-time question: what does the component we picked say about this picture? */
    fun forCodec(codecName: String, mimeType: String, width: Int, height: Int, fps: Int): Capability? {
        val caps = capabilitiesOf(codecName, mimeType) ?: return null
        val video = caps.videoCapabilities ?: return null
        val rate = if (fps > 0) fps else DEFAULT_FPS
        return Capability(
            codecName = codecName,
            mimeType = mimeType,
            width = width,
            height = height,
            fps = rate,
            sizeSupported = runCatching { video.isSizeSupported(width, height) }.getOrDefault(false),
            rateSupported = runCatching {
                video.areSizeAndRateSupported(width, height, rate.toDouble())
            }.getOrDefault(false),
            // API 29+ only: a performance point is a claim about sustaining a rate, where
            // areSizeAndRateSupported is only a claim about accepting it.
            sustains = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching {
                    val target = MediaCodecInfo.VideoCapabilities.PerformancePoint(width, height, rate)
                    video.supportedPerformancePoints?.any { it.covers(target) }
                }.getOrNull()
            } else {
                null
            },
            lowLatency = advertisesLowLatency(codecName, mimeType),
            adaptivePlayback = runCatching {
                caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback)
            }.getOrDefault(false),
            supportedWidths = runCatching { video.supportedWidths.toString() }.getOrDefault("?"),
            supportedHeights = runCatching { video.supportedHeights.toString() }.getOrDefault("?"),
        )
    }

    /**
     * The negotiation-time question: of the decoders this device has for [mimeType], what would the
     * one we are going to pick say?
     *
     * Picks the first hardware component, matching `VideoDecoder.findBestCodec`'s order, and falls
     * back to the first of any kind so a software-only device still gets a line. The software test
     * is the same name heuristic `VideoDecoder.isHevcDecoderAvailable` uses - one heuristic, not two.
     */
    fun query(mimeType: String, width: Int, height: Int, fps: Int): Capability? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        val names = try {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mimeType, true) } }
                .map { it.name }
        } catch (e: Exception) {
            return null
        }
        val chosen = names.firstOrNull { !isSoftwareName(it) } ?: names.firstOrNull() ?: return null
        return forCodec(chosen, mimeType, width, height, fps)
    }

    /** Same test as `VideoDecoder.isHevcDecoderAvailable`, kept in one place. */
    fun isSoftwareName(codecName: String): Boolean {
        val name = codecName.lowercase()
        return name.startsWith("omx.google.") ||
            name.startsWith("c2.android.") ||
            name.startsWith("omx.ffmpeg.") ||
            name.contains(".sw.") ||
            name.contains("software")
    }

    /** What a zero or negative fps setting means when a rate has to be named. */
    const val DEFAULT_FPS = 30
}
