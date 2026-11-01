package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.proto.Media
import org.junit.Assert.*
import org.junit.Test

class GalRuntimeOptionsTest {
    private fun duration(seconds: Long = 0, nanos: Int = 0) = Media.MediaDuration.newBuilder()
        .setSeconds(seconds).setNanos(nanos).build()

    @Test fun `duration accepts signed offsets but rejects sign range and long overflow`() {
        assertEquals(-1_001_000_000L, GalRuntimeOptions.nanos(duration(-1, -1_000_000)))
        assertEquals(0L, GalRuntimeOptions.nanos(duration()))
        assertNull(GalRuntimeOptions.nanos(duration(1, -1)))
        assertNull(GalRuntimeOptions.nanos(duration(-1, 1)))
        assertNull(GalRuntimeOptions.nanos(duration(0, 1_000_000_000)))
        assertNull(GalRuntimeOptions.nanos(duration(315_576_000_001)))
        assertNull(GalRuntimeOptions.nanos(duration(315_576_000_000)))
        assertEquals(Long.MAX_VALUE, GalRuntimeOptions.nanos(duration(9_223_372_036, 854_775_807)))
        assertNull(GalRuntimeOptions.nanos(duration(9_223_372_036, 854_775_808)))
        assertEquals(Long.MIN_VALUE, GalRuntimeOptions.nanos(duration(-9_223_372_036, -854_775_808)))
        assertNull(GalRuntimeOptions.nanos(duration(-9_223_372_036, -854_775_809)))
    }

    @Test fun `fresh request defaults and explicit disable leave telemetry inactive`() {
        val enabled = Media.MediaOptions.newBuilder().setEnableMediaStats(true)
            .setAudioBufferLimit(duration(nanos = 300_000_000)).build()
        assertEquals(5000L, GalRuntimeOptions.from(enabled).statisticsIntervalMs)
        assertEquals(300L, GalRuntimeOptions.from(enabled).audioBufferLimitMs)
        assertEquals(GalRuntimeOptions(), GalRuntimeOptions.from(Media.MediaOptions.getDefaultInstance()))
        assertNull(GalRuntimeOptions.from(enabled.toBuilder().setEnableMediaStats(false).build()).statisticsIntervalMs)
    }

    @Test fun `bad report cadence disables telemetry and valid fast cadence is bounded`() {
        val enabled = Media.MediaOptions.newBuilder().setEnableMediaStats(true)
        assertNull(GalRuntimeOptions.from(enabled.setMediaStatsReportInterval(duration()).build()).statisticsIntervalMs)
        assertNull(GalRuntimeOptions.from(enabled.setMediaStatsReportInterval(duration(-1)).build()).statisticsIntervalMs)
        assertEquals(250L, GalRuntimeOptions.from(enabled.setMediaStatsReportInterval(duration(nanos = 1)).build()).statisticsIntervalMs)
        assertEquals(60_000L, GalRuntimeOptions.from(enabled.setMediaStatsReportInterval(duration(3600)).build()).statisticsIntervalMs)
    }

    @Test fun `invalid video range cannot schedule long future releases`() {
        val builder = Media.MediaOptions.newBuilder().setEnableVideoTimingController(true)
            .setVideoTimingControllerMinDelay(duration(nanos = 9_000_000))
            .setVideoTimingControllerMaxDelay(duration(nanos = 17_000_000))
        assertEquals(GalRuntimeOptions.VideoTiming(9_000_000, 17_000_000), GalRuntimeOptions.from(builder.build()).videoTiming)
        assertNull(GalRuntimeOptions.from(builder.setVideoTimingControllerMaxDelay(duration(nanos = 8_000_000)).build()).videoTiming)
        assertNull(GalRuntimeOptions.from(builder.setVideoTimingControllerMaxDelay(duration(1)).build()).videoTiming)
        assertNull(GalRuntimeOptions.from(builder.clearVideoTimingControllerMaxDelay().build()).videoTiming)
    }

    @Test fun `AV fields are validated independently with signed offset and unsigned weight`() {
        val builder = Media.MediaOptions.newBuilder().setEnableAvSync(true)
            .setMaxLagTolerance(duration(nanos = 80_000_000))
            .setSyncInterval(duration(nanos = 20_000_000))
            .setMaxAudioDelayAllowed(duration(nanos = 30_000_000))
            .setMaxVideoDelayAllowed(duration(nanos = 40_000_000))
            .setAverageLagWeight(9).setSyncOffsetOverride(duration(nanos = -10_000_000))
        assertEquals(GalRuntimeOptions.AvSync(80_000_000, 20_000_000, 30_000_000,
            40_000_000, 9, -10_000_000), GalRuntimeOptions.from(builder.build()).avSync)
        val invalid = GalRuntimeOptions.from(builder.setAverageLagWeight(-1)
            .setSyncInterval(duration()).setMaxAudioDelayAllowed(duration(1))
            .setMaxVideoDelayAllowed(duration(-1)).setMaxLagTolerance(duration(nanos = -1))
            .setSyncOffsetOverride(duration(315_576_000_000)).build())
        assertEquals(GalRuntimeOptions.AvSync(), invalid.avSync)
        assertTrue(invalid.invalidFields.containsAll(listOf("average_lag_weight", "sync_interval",
            "max_audio_delay_allowed", "max_video_delay_allowed", "max_lag_tolerance", "sync_offset_override")))
        assertNull(GalRuntimeOptions.from(builder.setEnableAvSync(false).build()).avSync)
    }

    @Test fun `zero delay and zero historical weight remain usable policies`() {
        val options = Media.MediaOptions.newBuilder().setEnableAvSync(true)
            .setMaxLagTolerance(duration()).setMaxAudioDelayAllowed(duration())
            .setMaxVideoDelayAllowed(duration()).setAverageLagWeight(0).build()
        assertEquals(GalRuntimeOptions.AvSync(maxLagToleranceNs = 0, maxAudioDelayNs = 0,
            maxVideoDelayNs = 0, averageLagWeight = 0), GalRuntimeOptions.from(options).avSync)
    }
}
