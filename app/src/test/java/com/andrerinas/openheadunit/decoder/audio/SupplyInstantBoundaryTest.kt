package com.andrerinas.openheadunit.decoder.audio

import org.junit.Test

/** Same supplied PCM at the same times, differing only in call-level partitioning.
 * Deterministic software tests, not a replay of the Xiaomi packet trace. */
class SupplyInstantBoundaryTest {
    private data class Result(val target: Int, val depth: Int, val drops: Long, val rebanks: Long)

    private fun run(parts: IntArray, splitIngress: Boolean, splitPcm: Boolean): Result {
        check(parts.sum() == 2048)
        val bank = AdaptivePcmBuffer(latencyMultiplier = 2, isMediaSink = true)
        val full = ShortArray(4096) { 1000 }
        val fragments = parts.map { ShortArray(it * 2) { 1000 } }
        val out = ShortArray(960)
        var packet = 0
        for (now in 0L..90_000L) {
            // Delay once, then deliver all pending frames. No source or bank drops are induced.
            if (now !in 10_000L..10_249L) {
                while (true) {
                    val at = (packet / 2) * 4096L * 1000 / 48000 + if (packet % 2 == 1) 30 else 0
                    if (at > now) break
                    val partition = packet % 2 == 0
                    if (partition && splitIngress) parts.forEach { bank.noteArrival(now, it) }
                    else bank.noteArrival(now, 2048)
                    if (partition && splitPcm) fragments.forEach { bank.write(it, it.size, now) }
                    else bank.write(full, full.size, now)
                    packet++
                }
            }
            if (now % 10 == 0L) bank.render(out, now)
        }
        return Result(bank.targetFrames(), bank.depthFrames(), bank.droppedFrames, bank.rebanks)
    }

    @Test fun `splitting one delivery instant cannot change recovery of a spread pair`() {
        val reference = run(intArrayOf(2048), false, false)
        check(reference.target <= 90 * 48 && reference.drops == 0L && reference.rebanks == 1L)
        for (ingress in listOf(false, true)) for (pcm in listOf(false, true)) {
            val actual = run(intArrayOf(1024, 1024), ingress, pcm)
            check(actual == reference) { "ingress=$ingress pcm=$pcm $actual versus $reference" }
        }
    }

    @Test fun `the final fragment size cannot change the next coalescing decision`() {
        val reference = run(intArrayOf(2048), false, false)
        for (parts in listOf(intArrayOf(2000, 48), intArrayOf(48, 2000), intArrayOf(720, 1328))) {
            val actual = run(parts, true, true)
            check(actual == reference) { "parts=${parts.contentToString()} $actual versus $reference" }
        }
    }

    @Test fun `new grouping still detects real stalls independently on each supply clock`() {
        for (delayIngress in listOf(false, true)) {
            val policy = AdaptiveJitterPolicy(48000, 2)
            // 80ms of source per period, split at two instants 30ms apart.
            for (base in 0L..4000L step 80) {
                repeat(2) { policy.onArrival(base, 960); policy.onPcmDelivery(base, 960) }
                policy.onArrival(base + 30, 1920); policy.onPcmDelivery(base + 30, 1920)
            }
            val normal = policy.targetFrames
            for (now in 4040L..4300L step 20) {
                if (delayIngress) policy.onPcmDelivery(now, 960) else policy.onArrival(now, 960)
            }
            if (delayIngress) policy.onArrival(4330, 960) else policy.onPcmDelivery(4330, 960)
            check(policy.targetFrames > normal && policy.targetFrames >= 300 * 48) {
                "delayIngress=$delayIngress normal=$normal target=${policy.targetFrames}"
            }
        }
    }
}
