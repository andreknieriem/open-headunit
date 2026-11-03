package com.andrerinas.openheadunit.decoder.video

import org.junit.Assert.*
import org.junit.Test

class VideoTimingMetadataTest {
    @Test fun `out of order output retains exact wire metadata and consumes only once`() {
        val ring = VideoTimingMetadata(3)
        val sample = VideoTimingMetadata.Sample()
        ring.put(10, 2, 700, 1_000)
        ring.put(20, 2, 900, 2_000)
        assertTrue(ring.take(20, sample))
        assertEquals(900L, sample.sourceTimeUs)
        assertEquals(2_000L, sample.arrivalNs)
        assertFalse(ring.take(20, sample))
        assertTrue(ring.take(10, sample))
        assertEquals(700L, sample.sourceTimeUs)
    }

    @Test fun `overflow evicts measurements rather than inventing timestamp associations`() {
        val ring = VideoTimingMetadata(2)
        val sample = VideoTimingMetadata.Sample()
        ring.put(10, 2, 700, 1_000)
        ring.put(20, 2, 900, 2_000)
        ring.put(30, 2, 1_100, 3_000)
        assertFalse(ring.take(10, sample))
        assertFalse(ring.take(29, sample))
        assertTrue(ring.take(30, sample))
        assertEquals(1_100L, sample.sourceTimeUs)
    }

    @Test fun `duplicate local keys invalidate ambiguous source mapping but repeated wire PTS do not`() {
        val ring = VideoTimingMetadata(4)
        val sample = VideoTimingMetadata.Sample()
        ring.put(10, 2, 700, 1_000)
        ring.put(10, 2, 900, 2_000)
        assertFalse(ring.take(10, sample))
        ring.put(20, 2, 900, 2_000)
        ring.put(30, 2, 900, 3_000)
        assertTrue(ring.take(20, sample))
        assertTrue(ring.take(30, sample))
        assertEquals(900L, sample.sourceTimeUs)
    }

    @Test fun `clear and rejected input remove ownership across epochs`() {
        val ring = VideoTimingMetadata(2)
        val sample = VideoTimingMetadata.Sample()
        ring.put(10, 2, 700, 1_000)
        ring.remove(10)
        assertFalse(ring.take(10, sample))
        ring.put(20, 2, 900, 2_000)
        ring.clear()
        ring.put(10, 3, 4, 3_000, 3_100)
        assertFalse(ring.take(20, sample))
        assertTrue(ring.take(10, sample))
        assertEquals(3L, sample.generation)
        assertEquals(4L, sample.sourceTimeUs)
        assertEquals(3_100L, sample.requestedNs)
    }

    @Test fun `wait slices handle expired drop immediate and overflow boundaries`() {
        assertEquals(0L, VideoPresentationWait.sliceNs(Long.MIN_VALUE, 10))
        assertEquals(0L, VideoPresentationWait.sliceNs(0, -10))
        assertEquals(0L, VideoPresentationWait.sliceNs(9, 10))
        assertEquals(1L, VideoPresentationWait.sliceNs(Long.MAX_VALUE, Long.MAX_VALUE - 1))
        assertEquals(VideoPresentationWait.SLICE_NS, VideoPresentationWait.sliceNs(Long.MAX_VALUE, -1))
    }
}
