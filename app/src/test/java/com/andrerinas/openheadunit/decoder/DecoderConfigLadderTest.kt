package com.andrerinas.openheadunit.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules for trying optional decoder keys.
 *
 * The property that matters more than any individual key: the last rung is always empty, so whatever
 * the ladder decides, the fallback is the behaviour that shipped before it existed.
 */
class DecoderConfigLadderTest {

    private val mtk = "OMX.MTK.VIDEO.DECODER.HEVC"
    private val qcom = "OMX.qcom.video.decoder.avc"
    private val exynos = "OMX.Exynos.avc.dec"
    private val amlogic = "OMX.amlogic.hevc.decoder"
    private val hisi = "OMX.hisi.video.decoder.avc"
    private val unknown = "OMX.rk.video_decoder.avc"

    @Test
    fun `the last rung is always empty, whatever else is offered`() {
        val cases = listOf(mtk, qcom, exynos, amlogic, hisi, unknown, "")
        for (name in cases) {
            for (sdk in listOf(16, 21, 27, 29, 30, 34)) {
                for (feature in listOf(true, false)) {
                    for (requested in listOf(true, false)) {
                        val tiers = DecoderConfigLadder.tiers(name, sdk, feature, requested)
                        assertTrue("$name/$sdk: no rungs at all", tiers.isNotEmpty())
                        assertEquals(
                            "$name/$sdk/feature=$feature/requested=$requested must end with no optional keys",
                            DecoderConfigLadder.NO_OPTIONAL_KEYS,
                            tiers.last()
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `not asking for low latency is exactly the shipped behaviour`() {
        val tiers = DecoderConfigLadder.tiers(mtk, 34, advertisesLowLatencyFeature = true, lowLatencyRequested = false)
        assertEquals(listOf(DecoderConfigLadder.NO_OPTIONAL_KEYS), tiers)
    }

    @Test
    fun `the official key is preferred where the component advertises it`() {
        val tiers = DecoderConfigLadder.tiers(qcom, 34, advertisesLowLatencyFeature = true, lowLatencyRequested = true)
        assertEquals(2, tiers.size)
        assertEquals(mapOf(DecoderConfigLadder.KEY_LOW_LATENCY to 1), tiers[0].integerKeys)
    }

    @Test
    fun `the official key is not used below the API that has it, even if the feature is claimed`() {
        val tiers = DecoderConfigLadder.tiers(qcom, 29, advertisesLowLatencyFeature = true, lowLatencyRequested = true)
        assertEquals(
            "should fall back to the vendor spelling",
            mapOf(DecoderConfigLadder.QUALCOMM_LOW_LATENCY to 1),
            tiers[0].integerKeys
        )
    }

    @Test
    fun `the vendor spelling is used when the component does not advertise the feature`() {
        // The case that matters for #839: a MediaTek component on API 27, where the official key does
        // not exist at all.
        val tiers = DecoderConfigLadder.tiers(mtk, 27, advertisesLowLatencyFeature = false, lowLatencyRequested = true)
        assertEquals(2, tiers.size)
        assertEquals(mapOf(DecoderConfigLadder.MTK_LOW_LATENCY to 1), tiers[0].integerKeys)
    }

    @Test
    fun `the two spellings are never combined`() {
        for (name in listOf(mtk, qcom, exynos, amlogic, hisi)) {
            for (sdk in listOf(27, 30, 34)) {
                for (feature in listOf(true, false)) {
                    val rich = DecoderConfigLadder.tiers(name, sdk, feature, lowLatencyRequested = true).first()
                    assertTrue(
                        "$name/$sdk/feature=$feature set both spellings: ${rich.integerKeys.keys}",
                        !(rich.integerKeys.containsKey(DecoderConfigLadder.KEY_LOW_LATENCY) &&
                            rich.integerKeys.keys.any { it != DecoderConfigLadder.KEY_LOW_LATENCY })
                    )
                }
            }
        }
    }

    @Test
    fun `an unknown vendor gets nothing rather than a guess`() {
        val tiers = DecoderConfigLadder.tiers(unknown, 27, advertisesLowLatencyFeature = false, lowLatencyRequested = true)
        assertEquals(listOf(DecoderConfigLadder.NO_OPTIONAL_KEYS), tiers)
        assertTrue(DecoderConfigLadder.vendorLowLatencyKeys(unknown).isEmpty())
        assertTrue(DecoderConfigLadder.vendorLowLatencyKeys("").isEmpty())
    }

    @Test
    fun `each vendor family is matched by its component name`() {
        assertEquals(mapOf(DecoderConfigLadder.MTK_LOW_LATENCY to 1), DecoderConfigLadder.vendorLowLatencyKeys(mtk))
        assertEquals(mapOf(DecoderConfigLadder.QUALCOMM_LOW_LATENCY to 1), DecoderConfigLadder.vendorLowLatencyKeys(qcom))
        assertEquals(mapOf(DecoderConfigLadder.EXYNOS_LOW_LATENCY to 1), DecoderConfigLadder.vendorLowLatencyKeys(exynos))
        assertEquals(mapOf(DecoderConfigLadder.AMLOGIC_LOW_LATENCY to 1), DecoderConfigLadder.vendorLowLatencyKeys(amlogic))
        assertEquals(
            mapOf(
                DecoderConfigLadder.HISILICON_LOW_LATENCY_REQ to 1,
                DecoderConfigLadder.HISILICON_LOW_LATENCY_RDY to -1,
            ),
            DecoderConfigLadder.vendorLowLatencyKeys(hisi)
        )
    }

    @Test
    fun `the Codec2 spellings of the same families are matched too`() {
        assertEquals(mapOf(DecoderConfigLadder.MTK_LOW_LATENCY to 1), DecoderConfigLadder.vendorLowLatencyKeys("c2.mtk.hevc.decoder"))
        assertEquals(mapOf(DecoderConfigLadder.QUALCOMM_LOW_LATENCY to 1), DecoderConfigLadder.vendorLowLatencyKeys("c2.qti.avc.decoder"))
        assertEquals(mapOf(DecoderConfigLadder.EXYNOS_LOW_LATENCY to 1), DecoderConfigLadder.vendorLowLatencyKeys("c2.exynos.hevc.decoder"))
    }
}
