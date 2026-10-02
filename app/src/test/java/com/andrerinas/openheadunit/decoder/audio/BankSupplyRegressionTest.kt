package com.andrerinas.openheadunit.decoder.audio

import org.junit.Test

/** Synthetic clocks and real policy/bank classes; not a replay of the device excerpt. */
class BankSupplyRegressionTest {
    private enum class Mode { MERGED, INGRESS_SPLIT, PCM_SPLIT, BOTH_SPLIT }
    private data class Trace(val target: Int, val depth: Int, val dropped: Long, val rebanks: Long)

    private fun trace(mode: Mode, bump: Boolean = true): Trace {
        val bank = AdaptivePcmBuffer(latencyMultiplier = 2, isMediaSink = true)
        val out = ShortArray(960)
        val half = ShortArray(2048) { 1000 }
        val full = ShortArray(4096) { 1000 }
        var packet = 0
        for (now in 0L..70_000L) {
            if (!bump || now !in 10_000L..10_249L) {
                while (now >= packet * 2048L * 1000 / 48000) {
                    if (mode == Mode.INGRESS_SPLIT || mode == Mode.BOTH_SPLIT) {
                        repeat(2) { bank.noteArrival(now, 1024) }
                    } else bank.noteArrival(now, 2048)
                    if (mode == Mode.PCM_SPLIT || mode == Mode.BOTH_SPLIT) {
                        repeat(2) { bank.write(half, half.size, now) }
                    } else bank.write(full, full.size, now)
                    packet++
                }
            }
            if (now % 10 == 0L) bank.render(out, now)
        }
        return Trace(bank.targetFrames(), bank.depthFrames(), bank.droppedFrames, bank.rebanks)
    }

    @Test fun `splitting either clock cannot permanently pin a recovered target`() {
        val merged = trace(Mode.MERGED)
        for (mode in Mode.values()) {
            val result = trace(mode)
            check(result.target == merged.target) { "$mode $result versus $merged" }
            check(result.target <= 80 * 48 && result.depth <= 100 * 48) { "$mode $result" }
            check(result.dropped == 0L && result.rebanks == merged.rebanks) { "$mode $result" }
            println("SUPPLY $mode $result")
        }
    }

    @Test fun `normal zero one and eight millisecond callback spacing recover`() {
        for (spacing in listOf(0L, 1L, 8L)) {
            val p = AdaptiveJitterPolicy(48000, 2)
            p.onUnderrun(0)
            for (batch in 0L..2200L) {
                val first = batch * 2048 * 1000 / 48000
                p.onArrival(first, 1024); p.onPcmDelivery(first, 1024)
                p.onArrival(first + spacing, 1024); p.onPcmDelivery(first + spacing, 1024)
            }
            check(p.targetFrames in 2048 + 480..3840) { "spacing=$spacing target=${p.targetFrames}" }
        }
    }

    @Test fun `arbitrary frame splits use supplied durations rather than AAC AU counts`() {
        val p = AdaptiveJitterPolicy(48000, 2)
        p.onUnderrun(0)
        for (batch in 0L..2200L) {
            val now = batch * 2048 * 1000 / 48000
            p.onArrival(now, 2048)
            p.onPcmDelivery(now, 720)
            p.onPcmDelivery(now + 1, 1328)
        }
        check(p.targetFrames in 2048 + 480..3840)
    }

    @Test fun `paced subcycle packets do not coalesce forever`() {
        for (duration in listOf(1, 5, 10, 20)) {
            val p = AdaptiveJitterPolicy(48000, 2)
            p.onUnderrun(0)
            for (now in 0L..90_000L step duration.toLong()) {
                p.onArrival(now, duration * 48); p.onPcmDelivery(now, duration * 48)
            }
            check(p.targetFrames <= 3840) { "duration=$duration target=${p.targetFrames}" }
        }
    }

    @Test fun `continuous encoded input cannot erase the decoder batch reserve`() {
        for (batchMs in listOf(120, 240, 300, 320)) {
            val p = AdaptiveJitterPolicy(48000, 2)
            for (now in 0L..90_000L step 10) {
                p.onArrival(now, 480)
                if (now % batchMs == 0L) repeat(batchMs / 10) { p.onPcmDelivery(now, 480) }
            }
            check(p.targetFrames in (batchMs + 10) * 48..400 * 48) {
                "decoderBatch=$batchMs target=${p.targetFrames}"
            }
            println("RESERVE decoderBatchMs=$batchMs targetMs=${p.targetFrames / 48.0}")
        }
    }

    @Test fun `real excess delay on either clock still raises the reserve`() {
        for (delayIngress in listOf(false, true)) {
            val p = AdaptiveJitterPolicy(48000, 2)
            for (now in 0L..1000L step 20) {
                p.onArrival(now, 960); p.onPcmDelivery(now, 960)
            }
            val before = p.targetFrames
            for (now in 1020L..1240L step 20) {
                if (delayIngress) p.onPcmDelivery(now, 960) else p.onArrival(now, 960)
            }
            if (delayIngress) p.onArrival(1250, 960) else p.onPcmDelivery(1250, 960)
            check(p.targetFrames >= 250 * 48 && p.targetFrames > before) {
                "ingress=$delayIngress before=$before after=${p.targetFrames}"
            }
        }
    }

    @Test fun `a real extra delay after a complete batch is not hidden by aggregation`() {
        val p = AdaptiveJitterPolicy(48000, 2)
        for (batch in 0L..10L) {
            repeat(30) { p.onArrival(batch * 300, 480); p.onPcmDelivery(batch * 300, 480) }
        }
        val normal = p.targetFrames
        repeat(30) { p.onArrival(3550, 480); p.onPcmDelivery(3550, 480) }
        check(p.targetFrames > normal && p.targetFrames == 400 * 48)
    }

    @Test fun `old large batches age out and explicit Stop resets both supply clocks`() {
        val p = AdaptiveJitterPolicy(48000, 2)
        for (b in 0L..3L) repeat(30) { p.onArrival(b * 300, 480); p.onPcmDelivery(b * 300, 480) }
        check(p.targetFrames >= 300 * 48)
        for (now in 1200L..90_000L step 10) { p.onArrival(now, 480); p.onPcmDelivery(now, 480) }
        check(p.targetFrames <= 3840)
        val before = p.targetFrames
        p.resetArrival()
        p.onArrival(200_000, 480); p.onPcmDelivery(200_000, 480)
        check(p.targetFrames <= before)
        check(p.largestArrivalGapMs == 0L && p.largestPcmGapMs == 0L)
    }


    @Test fun `spread deliveries reserve their net supply peak instead of double counting elapsed time`() {
        val p = AdaptiveJitterPolicy(48000, 2)
        for (b in 0L..800L) {
            p.onArrival(b * 128, 3072); p.onPcmDelivery(b * 128, 3072)
            p.onArrival(b * 128 + 55, 3072); p.onPcmDelivery(b * 128 + 55, 3072)
        }
        // 64ms + 64ms - 55ms = 73ms peak, not an instantaneous 128ms burst.
        check(p.targetFrames in 83 * 48..110 * 48) { "target=${p.targetFrames}" }
    }


    @Test fun `small same timestamp deliveries form a complete supply batch`() {
        val p = AdaptiveJitterPolicy(48000, 2)
        for (b in 0L..900L) repeat(20) {
            p.onArrival(b * 100, 240); p.onPcmDelivery(b * 100, 240)
        }
        check(p.targetFrames in 110 * 48..150 * 48)
    }

    private fun warmed(deep: Boolean): Pair<AdaptivePcmBuffer, Long> {
        val bank = AdaptivePcmBuffer(latencyMultiplier = 2, isMediaSink = true)
        val out = ShortArray(960); val chunk = ShortArray(1920) { 1000 }
        var now = 0L
        // More than 34s of real playback exhausts the initial-media reserve.
        while (now < 40_000L) {
            if (now % 20L == 0L) { bank.noteArrival(now, 960); bank.write(chunk, chunk.size, now) }
            bank.render(out, now); now += 10
        }
        if (deep) {
            now += 450
            bank.noteArrival(now, 1024); bank.write(ShortArray(2048) { 1000 }, 2048, now)
        }
        bank.finish()
        repeat(200) { if (!bank.isIdle()) { bank.render(out, now); now += 10 } }
        check(bank.isIdle())
        return bank to now + 1000
    }

    @Test fun `warm media does not take the short escape below a learned deep target`() {
        val (bank, start) = warmed(true)
        val out = ShortArray(960); val chunk = ShortArray(2048) { 1000 }
        val before = bank.rebanks
        repeat(6) { bank.noteArrival(start, 1024); bank.write(chunk, chunk.size, start) }
        check(bank.targetFrames() == 19200)
        var first = -1L
        for (now in start..start + 800 step 10) {
            if (now == start + 400) repeat(19) { bank.noteArrival(now, 1024); bank.write(chunk, chunk.size, now) }
            if (bank.render(out, now) && out.any { it != 0.toShort() } && first < 0) first = now - start
        }
        check(first == 400L && bank.rebanks == before && bank.droppedFrames == 0L) {
            "first=$first rebanks=${bank.rebanks - before}"
        }
    }

    @Test fun `warm low latency resume starts when its normal target fills`() {
        val (bank, start) = warmed(false)
        val out = ShortArray(960); val chunk = ShortArray(1920) { 1000 }
        var first = -1L
        for (now in start..start + 200 step 10) {
            if ((now - start) % 20 == 0L) { bank.noteArrival(now, 960); bank.write(chunk, chunk.size, now) }
            if (bank.render(out, now) && first < 0) first = now - start
        }
        check(first in 40L..60L && bank.targetFrames() <= 3840) { "first=$first" }
    }

    @Test fun `deep warm short Stop tail bypasses preroll and preserves all frames`() {
        val (bank, start) = warmed(true)
        val beforeCompressed = bank.compressedFrames
        val beforeRebanks = bank.rebanks
        val data = ShortArray(1440) { if (it % 2 == 0) 1234 else -2345 }
        bank.noteArrival(start, 720); bank.write(data, data.size, start)
        val out = ShortArray(960)
        check(!bank.render(out, start + 99))
        bank.finish()
        check(bank.render(out, start + 100) && bank.depthFrames() == 240)
        check(out[958].toInt() == 1234 && out[959].toInt() == -2345)
        check(bank.render(out, start + 110) && bank.isIdle())
        check(out[478].toInt() == 1234 && out[479].toInt() == -2345)
        check(bank.droppedFrames == 0L && bank.compressedFrames == beforeCompressed && bank.rebanks == beforeRebanks)
    }

    @Test fun `a warm incomplete media stream without Stop has a bounded target based escape`() {
        val (bank, start) = warmed(true)
        val data = ShortArray(1920) { 1000 }; val out = ShortArray(960)
        bank.noteArrival(start, 960); bank.write(data, data.size, start)
        val deadline = maxOf(100L, bank.targetFrames() * 1000L / 48000 + 50)
        check(deadline in 440L..450L)
        check(!bank.render(out, start + deadline - 1))
        check(bank.render(out, start + deadline))
    }

    @Test fun `a short guidance prompt still uses its original deadline`() {
        val bank = AdaptivePcmBuffer(latencyMultiplier = 2, isMediaSink = false)
        bank.noteArrival(0, 480); bank.write(ShortArray(960) { 1000 }, 960, 0)
        val out = ShortArray(960)
        check(!bank.render(out, 99) && bank.render(out, 100))
    }

    @Test fun `target filling resumes earlier than the warm media deadline`() {
        val (bank, start) = warmed(true)
        val data = ShortArray(20000 * 2) { 1000 }
        bank.noteArrival(start, 1024); bank.write(data, data.size, start)
        check(bank.render(ShortArray(960), start))
        check(bank.droppedFrames == 0L)
    }
}
