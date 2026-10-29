package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import org.junit.Assert.*
import org.junit.Test

class GalPresentationControllerTest {
    private val media = Channel.ID_AUD
    private val video = Channel.ID_VID
    private fun options(av: GalRuntimeOptions.AvSync = GalRuntimeOptions.AvSync()) =
        GalRuntimeOptions(avSyncRequested = true, avSync = av)
    private fun controller(opts: GalRuntimeOptions = options()) = GalPresentationController().also {
        it.start(media, 10, opts)
        it.start(video, 20, opts)
    }

    @Test fun `disabled policy preserves immediate release and stale generations never render`() {
        val c = controller(GalRuntimeOptions())
        assertEquals(0L, c.audioStartDelayNs(media, 10, 0, 1))
        assertEquals(0L, c.videoReleaseTimeNs(20, 0, 1, 1))
        c.start(video, 21, GalRuntimeOptions())
        assertEquals(Long.MIN_VALUE, c.videoReleaseTimeNs(20, 0, 1, 1))
        c.close()
        assertEquals(Long.MIN_VALUE, c.videoReleaseTimeNs(21, 0, 1, 1))
        c.start(video, 22, GalRuntimeOptions())
        assertEquals(Long.MIN_VALUE, c.videoReleaseTimeNs(22, 0, 1, 1))
    }

    @Test fun `single audio master preserves source offsets without comparing clock uptimes`() {
        val c = controller(options(GalRuntimeOptions.AvSync(syncOffsetNs = 20_000_000)))
        c.observeAudio(media, 10, 8_000_000_000, 1_000_000_000)
        assertEquals(1_060_000_000L, c.videoReleaseTimeNs(20, 8_000_040_000, 1_000_000_000, 1_000_000_000))
        c.update(video, 20, options(GalRuntimeOptions.AvSync(maxVideoDelayNs = 25_000_000)))
        assertEquals(1_025_000_000L, c.videoReleaseTimeNs(20, 8_000_040_000, 1_000_000_000, 1_000_000_000))
        assertEquals(Long.MIN_VALUE, c.videoReleaseTimeNs(20, 7_999_800_000, 1_000_000_000, 1_000_000_000))
    }

    @Test fun `initial wait has one deadline only media waits and readiness releases it`() {
        val c = controller()
        c.start(Channel.ID_AU1, 30, options())
        assertEquals(100_000_000L, c.audioStartDelayNs(media, 10, 0, 0))
        assertEquals(50_000_000L, c.audioStartDelayNs(media, 10, 0, 50_000_000))
        assertEquals(0L, c.audioStartDelayNs(Channel.ID_AU1, 30, 0, 0))
        assertEquals(0L, c.audioStartDelayNs(media, 10, 0, 100_000_000))
        c.update(media, 10, options())
        assertEquals(0L, c.audioStartDelayNs(media, 10, 0, 110_000_000))
        c.start(media, 11, options())
        assertEquals(100_000_000L, c.audioStartDelayNs(media, 11, 0, 200_000_000))
        c.videoReleaseTimeNs(20, 0, 200_000_000, 200_000_000)
        assertEquals(0L, c.audioStartDelayNs(media, 11, 0, 200_000_001))
    }

    @Test fun `sync interval and historical weight control measured corrections`() {
        val c = controller(options(GalRuntimeOptions.AvSync(syncIntervalNs = 200_000_000,
            maxVideoDelayNs = 250_000_000, averageLagWeight = 3)))
        c.observeAudio(media, 10, 0, 1_000_000_000)
        c.observeAudio(media, 10, 80_000, 1_100_000_000)
        assertEquals(1_150_000_000L, c.videoReleaseTimeNs(20, 150_000, 1_100_000_000, 1_100_000_000))
        c.observeAudio(media, 10, 200_000, 1_250_000_000)
        assertEquals(1_262_500_000L, c.videoReleaseTimeNs(20, 250_000, 1_250_000_000, 1_250_000_000))
    }

    @Test fun `stale discontinuous invalidated and stopped audio cannot claim synchronization`() {
        val timing = GalRuntimeOptions.VideoTiming(10_000_000, 50_000_000)
        val c = controller(options().copy(videoTiming = timing))
        c.observeAudio(media, 10, 1_000_000, 1_000_000_000)
        assertEquals(1_610_000_000L, c.videoReleaseTimeNs(20, 1_600_000, 1_600_000_000, 1_600_000_000))
        c.observeAudio(media, 10, 100_000, 1_610_000_000) // Backwards source clock.
        assertEquals(1_620_000_000L, c.videoReleaseTimeNs(20, 200_000, 1_610_000_000, 1_610_000_000))
        c.observeAudio(media, 10, 110_000, 1_620_000_000)
        assertEquals(1_660_000_000L, c.videoReleaseTimeNs(20, 150_000, 1_620_000_000, 1_620_000_000))
        c.invalidateAudio(media, 10)
        c.stop(media, 10)
        c.start(media, 11, options())
        c.observeAudio(media, 10, 900_000, 1_620_000_000)
        assertEquals(1_630_000_000L, c.videoReleaseTimeNs(20, null, 1_620_000_000, 1_620_000_000))
    }

    @Test fun `arrival window pacing is idempotent bounded and drops late output`() {
        val opts = GalRuntimeOptions(videoTiming = GalRuntimeOptions.VideoTiming(10_000_000, 50_000_000))
        val c = controller(opts)
        assertEquals(1_010_000_000L, c.videoReleaseTimeNs(20, 0, 1_000_000_000, 1_000_000_000))
        assertEquals(1_043_000_000L, c.videoReleaseTimeNs(20, 33_000, 1_005_000_000, 1_005_000_000))
        assertEquals(1_043_000_000L, c.videoReleaseTimeNs(20, 33_000, 1_005_000_000, 1_020_000_000))
        assertEquals(Long.MIN_VALUE, c.videoReleaseTimeNs(20, 33_000, 1_005_000_000, 1_060_000_000))
        assertEquals(1_070_000_000L, c.videoReleaseTimeNs(20, Long.MAX_VALUE, 1_060_000_000, 1_060_000_000))
        assertEquals(0L, c.videoReleaseTimeNs(20, 0, Long.MAX_VALUE - 1, Long.MAX_VALUE - 1))
    }

    @Test fun `older queued video callback adjusts lag without requiring latest source`() {
        val c = controller(options(GalRuntimeOptions.AvSync(averageLagWeight = 0,
            syncIntervalNs = 1_000_000, maxVideoDelayNs = 250_000_000)))
        c.observeAudio(media, 10, 0, 1_000_000_000)
        val outputEpoch = c.beginVideoOutput(20)
        assertEquals(1_040_000_000L, c.videoReleaseTimeNs(20, 40_000, 1_000_000_000, 1_000_000_000))
        c.videoReleaseTimeNs(20, 80_000, 1_000_000_001, 1_000_000_001)
        c.observeVideoOutput(20, outputEpoch, 40_000, 1_050_000_000)
        assertEquals(1_110_000_000L, c.videoReleaseTimeNs(20, 120_000, 1_060_000_000, 1_060_000_000))
    }

    @Test fun `disabled fastpath gates follow active generations and ignore delayed observations`() {
        val c = controller(GalRuntimeOptions())
        assertTrue(c.isVideoSessionActive(20))
        assertFalse(c.isVideoTimingEnabled(20))
        assertFalse(c.isAudioSyncEnabled(media, 10))
        c.observeAudio(media, 10, 0, 1_000_000_000)
        assertEquals(0L, c.audioObservationCount)
        c.update(media, 10, options())
        assertTrue(c.isAudioSyncEnabled(media, 10))
        assertTrue(c.isVideoTimingEnabled(20))
        assertFalse(c.isAudioSyncEnabled(media, 9))
        c.stop(media, 10)
        assertFalse(c.isVideoTimingEnabled(20))
        c.stop(video, 20)
        assertFalse(c.isVideoSessionActive(20))
    }

    @Test fun `long correction interval still projects from fresh audio without a false clock discontinuity`() {
        val c = controller(options(GalRuntimeOptions.AvSync(syncIntervalNs = 5_000_000_000,
            maxVideoDelayNs = 250_000_000)))
        c.observeAudio(media, 10, 0, 1_000_000_000)
        c.observeAudio(media, 10, 3_000_000, 4_000_000_000)
        assertEquals(0L, c.audioCorrectionCount)
        assertEquals(4_040_000_000L, c.videoReleaseTimeNs(20, 3_040_000, 4_000_000_000, 4_000_000_000))
    }
}
