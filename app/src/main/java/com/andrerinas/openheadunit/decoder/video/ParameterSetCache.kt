package com.andrerinas.openheadunit.decoder.video

/** Session CSD indexed by syntax ID, in VPS/SPS/PPS replay order. Stored arrays are owned here. */
internal class ParameterSetCache {
    // AVC allows 32 SPS / 256 PPS IDs; HEVC allows 16 VPS / 16 SPS / 64 PPS IDs.
    private val spsBase = 16
    private val ppsBase = 48
    private var entries: Array<ByteArray?>? = null
    private val latest = arrayOfNulls<ByteArray>(3)
    /** Changes only when the retained configuration changes, not on byte-identical repetition. */
    var revision: Long = 0L
        private set
    var lastIndex: Int = -1
        private set

    operator fun get(index: Int): ByteArray? = entries?.get(index)

    fun latest(kind: Int): ByteArray? = latest[kindIndex(kind)]

    /** Copies only changed configuration, never a picture or the caller's reusable backing array. */
    fun store(data: ByteArray, offset: Int, length: Int, hevc: Boolean, kind: Int): ByteArray? {
        val id = ParameterSetInspector.parameterSetId(data, offset, length, hevc) ?: return null
        val group = kindIndex(kind)
        val slot = when (group) {
            0 -> id
            1 -> spsBase + id
            else -> ppsBase + id
        }
        val storage = entries ?: arrayOfNulls<ByteArray>(ppsBase + 256).also { entries = it }
        val previous = storage[slot]
        if (previous != null && previous.size == length + 4) {
            var same = true
            for (i in 0 until length) {
                if (previous[i + 4] != data[offset + i]) {
                    same = false
                    break
                }
            }
            if (same) {
                latest[group] = previous
                return previous
            }
        }
        val normalized = ByteArray(length + 4)
        normalized[3] = 1
        System.arraycopy(data, offset, normalized, 4, length)
        storage[slot] = normalized
        latest[group] = normalized
        revision++
        if (slot > lastIndex) lastIndex = slot
        return normalized
    }

    /** MediaCodec expects AVC SPS/PPS in separate buffers, or all HEVC configuration in csd-0. */
    fun codecSpecificData(hevc: Boolean, index: Int): ByteArray? {
        val storage = entries ?: return null
        val first = if (hevc) 0 else if (index == 0) spsBase else ppsBase
        val end = if (!hevc && index == 0) ppsBase else lastIndex + 1
        var only: ByteArray? = null
        var total = 0
        for (slot in first until end) {
            val data = storage[slot] ?: continue
            only = data
            total += data.size
        }
        if (total == 0) return null
        if (only!!.size == total) return only
        val combined = ByteArray(total)
        var offset = 0
        for (slot in first until end) {
            val data = storage[slot] ?: continue
            System.arraycopy(data, 0, combined, offset, data.size)
            offset += data.size
        }
        return combined
    }

    fun clear() {
        entries?.fill(null)
        latest.fill(null)
        lastIndex = -1
        revision++
    }

    private fun kindIndex(kind: Int): Int = when (kind) {
        32 -> 0
        7, 33 -> 1
        8, 34 -> 2
        else -> error("Not a parameter-set NAL: $kind")
    }
}
