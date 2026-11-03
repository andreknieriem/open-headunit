package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import org.junit.Assert.*
import org.junit.Test

class GalMediaStatisticsTest {
    private val channel = Channel.ID_AUD
    private val options = Media.MediaOptions.newBuilder().setEnableMediaStats(true)
        .setMediaStatsReportInterval(GalMediaStatistics.durationMs(500)).build()
    private fun start() = Media.Start.newBuilder().setSessionId(7).setConfigurationIndex(0).setOptions(options).build()

    @Test fun `reports measured bank and event deltas with unknown fields absent`() {
        val session = galTestSessions().start(channel, start())!!
        val stats = GalMediaStatistics()
        val owner = Any()
        fun sample(depth: Long, underruns: Long) = GalPlaybackSample(owner, depth, 5, underruns)
        assertNull(stats.observe(channel, session, sample(100_000, 9), 1000))
        assertNull(stats.observe(channel, session, sample(200_000, 10), 1250))
        val report = stats.observe(channel, session, sample(0, 12), 1500)!!
        assertEquals(0L, report.audioOverflowCount)
        assertEquals(3L, report.audioUnderrunCount)
        assertEquals(100_000L, report.estimatedAudioBufferUs.average)
        assertEquals(3L, report.estimatedAudioBufferUs.count)
        assertEquals(500_000_000L, GalRuntimeOptions.nanos(report.observationWindow))
        assertFalse(report.hasFrames())
        assertFalse(report.hasAudioDelayUs())
        assertFalse(report.hasAvLagUs())
        assertFalse(report.hasAudioOutputLatencyOffsetUs())
        assertEquals(report, Media.MediaStats.parseFrom(report.toByteArray()))
        assertEquals(0x8013, Media.MsgType.MEDIA_MESSAGE_STATS_VALUE)
    }

    @Test fun `same wire ID restart update stop and track replacement retire old windows`() {
        val sessions = galTestSessions()
        var session = sessions.start(channel, start())!!
        val stats = GalMediaStatistics()
        var sample = GalPlaybackSample(Any(), 100L, 2, 3)
        stats.observe(channel, session, sample, 0)
        val oldGeneration = session.generation
        session = sessions.start(channel, start())!!
        assertNotEquals(oldGeneration, session.generation)
        assertNull(stats.observe(channel, session, sample, 500))
        sample = sample.copy(owner = Any(), audioUnderruns = 50)
        assertNull(stats.observe(channel, session, sample, 1000))
        assertEquals(0L, stats.observe(channel, session, sample, 1500)!!.audioUnderrunCount)
        sessions.update(channel, options.toBuilder().setMediaStatsReportInterval(GalMediaStatistics.durationMs(1000)).build())
        session = sessions.current(channel)!!
        assertNull(stats.observe(channel, session, sample, 2000))
        assertNull(stats.observe(channel, session, sample, 2500))
        sessions.stop(channel)
        assertNull(stats.observe(channel, sessions.current(channel)!!, sample, 3000))
        assertFalse(sessions.update(channel, options))
    }

    @Test fun `identical retransmitted options do not erase an observation window`() {
        val sessions = galTestSessions()
        val original = sessions.start(channel, start())!!
        val stats = GalMediaStatistics()
        val sample = GalPlaybackSample(Any(), 10, 0, 0)
        stats.observe(channel, original, sample, 0)
        sessions.update(channel, Media.MediaOptions.parseFrom(options.toByteArray()))
        val updated = sessions.current(channel)!!
        assertNotSame(original.options, updated.options)
        assertEquals(1L, stats.observe(channel, updated, sample.copy(audioUnderruns = 1), 500)!!.audioUnderrunCount)
    }

    @Test fun `missing source and backwards counters reset rather than invent measurements`() {
        val session = galTestSessions().start(channel, start())!!
        val stats = GalMediaStatistics()
        val owner = Any()
        stats.observe(channel, session, GalPlaybackSample(owner, videoFrames = 100), 0)
        assertNull(stats.observe(channel, session, GalPlaybackSample(owner, videoFrames = 1), 500))
        val report = stats.observe(channel, session, GalPlaybackSample(owner, videoFrames = 4), 1000)!!
        assertEquals(3L, report.frames)
        assertFalse(report.hasEstimatedAudioBufferUs())
        assertFalse(report.hasAudioUnderrunCount())
        stats.observe(channel, session, null, 1250)
        assertNull(stats.observe(channel, session, GalPlaybackSample(owner, videoFrames = 9), 1500))
    }
}
