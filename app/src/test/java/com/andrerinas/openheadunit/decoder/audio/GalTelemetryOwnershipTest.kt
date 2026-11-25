package com.andrerinas.openheadunit.decoder.audio

import com.andrerinas.openheadunit.aap.GalPlaybackSample
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class GalTelemetryOwnershipTest {
    @Suppress("UNCHECKED_CAST")
    private fun tracks(decoder: AudioDecoder) = AudioDecoder::class.java.getDeclaredField("audioTracks")
        .apply { isAccessible = true }.get(decoder) as ConcurrentHashMap<Int, AudioTrackWrapper>

    @Test fun `writer sample does not wait for decoder lifecycle monitor`() {
        val decoder = AudioDecoder()
        val session = decoder.captureSession()
        val track = mock<AudioTrackWrapper>()
        val sample = GalPlaybackSample(Any(), audioQueueUs = 42)
        whenever(track.galPlaybackSample()).thenReturn(sample)
        tracks(decoder)[6] = track
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread { synchronized(decoder) { locked.countDown(); release.await() } }
        val worker = Executors.newSingleThreadExecutor()
        holder.start()
        try {
            assertTrue(locked.await(2, TimeUnit.SECONDS))
            assertSame(sample, worker.submit<GalPlaybackSample?> { decoder.galPlaybackSample(6, session) }.get(1, TimeUnit.SECONDS))
        } finally {
            release.countDown(); holder.join(2000); worker.shutdownNow()
        }
    }

    @Test fun `replacement registration invalidates a sample already being read`() {
        val decoder = AudioDecoder()
        val session = decoder.captureSession()
        val track = mock<AudioTrackWrapper>()
        tracks(decoder)[6] = track
        whenever(track.galPlaybackSample()).thenAnswer {
            tracks(decoder)[6] = mock<AudioTrackWrapper>()
            GalPlaybackSample(Any(), audioQueueUs = 42)
        }
        assertNull(decoder.galPlaybackSample(6, session))
    }

    @Test fun `closed connection cannot report retained output tails`() {
        val decoder = AudioDecoder()
        val session = decoder.captureSession()
        val track = mock<AudioTrackWrapper>()
        tracks(decoder)[6] = track
        decoder.closeSession(session)
        assertNull(decoder.galPlaybackSample(6, session))
        verify(track, never()).galPlaybackSample()
    }
}
