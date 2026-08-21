package com.andrerinas.openheadunit.decoder

/**
 * The order in which to try optional `MediaFormat` keys when configuring a decoder, and what to fall
 * back to when one is rejected.
 *
 * Every optional key this project ever added was removed again, and the comment in [VideoDecoder.start]
 * records why: `KEY_PRIORITY` and `KEY_OPERATING_RATE` were measured being answered with
 * `codec does not support config priority (err -1010)` on the head units they were meant to help. The
 * problem was never the keys, it was that a rejected key took the whole session with it, so the only
 * safe number of optional keys was zero.
 *
 * A ladder changes that. Configure is attempted with the richest key set first and, if it throws, the
 * next attempt drops one tier. The last tier is always empty, so the worst case is exactly the
 * behaviour with no optional keys at all - which is what shipped before this existed. That makes any
 * future key cheap to try and impossible to regress on.
 *
 * The keys themselves come from Moonlight's `MediaCodecHelper`, which carries the only device-attributed
 * table of them anyone maintains. The official `KEY_LOW_LATENCY` only exists from API 30, and the five
 * vendor spellings below are what the same feature was called before that - including
 * `vdec-lowlatency`, which MediaTek's fork of ACodec translates to
 * `OMX.MTK.index.param.video.LowLatencyDecode`, and which is the decoder in the 1 GB unit from #839.
 *
 * Nothing here decides *whether* to try the optional tiers; [VideoDecoder] gates that on a setting
 * which is off by default, because this project's rule is that no vendor key ships enabled without a
 * log from a device that accepted it. The ladder's tier log is how that log gets produced.
 */
object DecoderConfigLadder {

    /** One rung: a label for the log and the integer keys to set. */
    data class Tier(val label: String, val integerKeys: Map<String, Int>)

    /** Android 11's official low-latency key. Named here so the string is not repeated. */
    const val KEY_LOW_LATENCY = "low-latency"

    /** API level at which [KEY_LOW_LATENCY] exists. */
    const val LOW_LATENCY_API = 30

    /**
     * MediaTek, via their modified ACodec. Also plumbed on Amazon's Amlogic devices, where per
     * Moonlight it does more than reduce latency - it is what makes their HEVC decoder emit frames at
     * all.
     */
    const val MTK_LOW_LATENCY = "vdec-lowlatency"

    const val AMLOGIC_LOW_LATENCY = "vendor.low-latency.enable"
    const val QUALCOMM_LOW_LATENCY = "vendor.qti-ext-dec-low-latency.enable"
    const val EXYNOS_LOW_LATENCY = "vendor.rtc-ext-dec-low-latency.enable"
    const val HISILICON_LOW_LATENCY_REQ =
        "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req"
    const val HISILICON_LOW_LATENCY_RDY =
        "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy"

    /** The empty rung. Always last, and always reached, so the fallback is the pre-existing behaviour. */
    val NO_OPTIONAL_KEYS = Tier("none", emptyMap())

    /**
     * Vendor low-latency keys for a decoder, matched on its name, or empty if the family is unknown.
     *
     * Matched by name rather than by `Build` properties because the decoder name is what the key
     * belongs to: a device can carry components from more than one vendor, and it is the component
     * that either understands the key or throws.
     */
    fun vendorLowLatencyKeys(codecName: String): Map<String, Int> {
        val name = codecName.lowercase()
        return when {
            name.contains(".mtk.") || name.contains("mediatek") -> mapOf(MTK_LOW_LATENCY to 1)
            name.contains("amlogic") -> mapOf(AMLOGIC_LOW_LATENCY to 1)
            name.contains(".qcom.") || name.contains(".qti.") -> mapOf(QUALCOMM_LOW_LATENCY to 1)
            name.contains("exynos") -> mapOf(EXYNOS_LOW_LATENCY to 1)
            name.contains(".hisi.") || name.contains("hisilicon") ->
                mapOf(HISILICON_LOW_LATENCY_REQ to 1, HISILICON_LOW_LATENCY_RDY to -1)
            else -> emptyMap()
        }
    }

    /**
     * The rungs to try, richest first, always ending in [NO_OPTIONAL_KEYS].
     *
     * [lowLatencyRequested] false returns the single empty rung, which is the shipped behaviour. When
     * it is true, the official key is preferred wherever the component advertises `FEATURE_LowLatency`
     * and the platform is new enough for the key to mean anything; otherwise the vendor spelling for
     * that component is tried. The two are never combined - a component that understands the official
     * key has no need of the older private one, and setting both only widens what a single rejection
     * can take down.
     */
    fun tiers(
        codecName: String,
        sdkInt: Int,
        advertisesLowLatencyFeature: Boolean,
        lowLatencyRequested: Boolean,
    ): List<Tier> {
        if (!lowLatencyRequested) return listOf(NO_OPTIONAL_KEYS)

        if (sdkInt >= LOW_LATENCY_API && advertisesLowLatencyFeature) {
            return listOf(Tier("low-latency", mapOf(KEY_LOW_LATENCY to 1)), NO_OPTIONAL_KEYS)
        }

        val vendorKeys = vendorLowLatencyKeys(codecName)
        if (vendorKeys.isEmpty()) return listOf(NO_OPTIONAL_KEYS)
        return listOf(Tier("vendor", vendorKeys), NO_OPTIONAL_KEYS)
    }
}
