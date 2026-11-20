package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import org.junit.Assert.*
import org.junit.Test

class GalMediaContractTest {
    private fun start(id: Int, mode: Media.Start.FlowControl? = null) = Media.Start.newBuilder()
        .setSessionId(id).setConfigurationIndex(0).apply { mode?.let { setFlowControl(it) } }.build()

    @Test fun `wire Start mode2 enables ackless without changing another channel`() {
        // Required fields1/2, then enum field3. Absence and modes0/1 retain legacy credit handling.
        val wire = byteArrayOf(8, 9, 16, 0, 24, 2)
        val sessions = galTestSessions()
        val audio = sessions.start(Channel.ID_AUD, Media.Start.parseFrom(wire))!!
        val video = sessions.start(Channel.ID_VID, start(10))!!
        assertTrue(audio.ackless)
        assertFalse(video.ackless)
        for (mode in listOf(Media.Start.FlowControl.MODE_0, Media.Start.FlowControl.MODE_1)) {
            assertFalse(sessions.start(Channel.ID_AUD, start(11, mode))!!.ackless)
        }
        // Worker retains its original policy even after the next Start switches mode.
        assertTrue(audio.ackless)
        assertNotSame(audio, sessions.current(Channel.ID_AUD))
        assertSame(video, sessions.current(Channel.ID_VID))
    }

    @Test fun `stop permits received tail but options cannot reactivate or leak between sessions`() {
        val sessions = galTestSessions()
        val options = Media.MediaOptions.newBuilder().setEnableAvSync(true).build()
        val old = sessions.start(Channel.ID_VID, start(4))!!
        assertTrue(sessions.update(Channel.ID_VID, options))
        sessions.stop(Channel.ID_VID)
        assertEquals(old.id, sessions.current(Channel.ID_VID)!!.id)
        assertFalse(sessions.update(Channel.ID_VID, options))
        assertNull(sessions.current(Channel.ID_VID)!!.options)
        val next = sessions.start(Channel.ID_VID, start(4))!! // repeated wire ID starts with fresh options
        assertNotSame(old, sessions.current(Channel.ID_VID))
        assertSame(next, sessions.current(Channel.ID_VID))
        assertNull(next.options)
    }

    @Test fun `local sparse update policy preserves raw presence and signed Duration encoding`() {
        val negativeOffset = Media.MediaDuration.newBuilder().setNanos(-1000000).build()
        val options = Media.MediaOptions.newBuilder().setSyncOffsetOverride(negativeOffset)
            .setEnableAvSync(false).setAverageLagWeight(4).build()
        val roundtrip = Media.MediaOptions.parseFrom(options.toByteArray())
        assertTrue(roundtrip.hasEnableAvSync())
        assertFalse(roundtrip.enableAvSync)
        assertEquals(-1000000, roundtrip.syncOffsetOverride.nanos)
        assertTrue(negativeOffset.toByteArray().size > 5) // int32, not ZigZag sint32
        val sessions = galTestSessions()
        sessions.start(Channel.ID_AUD, start(1).toBuilder().setOptions(options).build())
        assertTrue(sessions.current(Channel.ID_AUD)!!.options!!.hasSyncOffsetOverride())
        sessions.update(Channel.ID_AUD, Media.MediaOptions.getDefaultInstance())
        assertTrue(sessions.current(Channel.ID_AUD)!!.options!!.hasSyncOffsetOverride())
        assertFalse(sessions.current(Channel.ID_AUD)!!.options!!.enableAvSync)
    }

    @Test fun `present duration replaces all components while omitted fields survive`() {
        val sessions = galTestSessions()
        val initial = Media.MediaOptions.newBuilder().setEnableMediaStats(true)
            .setMediaStatsReportInterval(Media.MediaDuration.newBuilder().setSeconds(1).setNanos(500_000_000))
            .setAudioBufferLimit(Media.MediaDuration.newBuilder().setNanos(300_000_000)).build()
        sessions.start(Channel.ID_AUD, start(1).toBuilder().setOptions(initial).build())
        val sparse = Media.MediaOptions.newBuilder()
            .setMediaStatsReportInterval(Media.MediaDuration.newBuilder().setNanos(500_000_000)).build()
        assertTrue(sessions.update(Channel.ID_AUD, sparse))
        val current = sessions.current(Channel.ID_AUD)!!
        assertFalse(current.options!!.mediaStatsReportInterval.hasSeconds())
        assertEquals(500L, current.runtime.statisticsIntervalMs)
        assertEquals(300L, current.runtime.audioBufferLimitMs)
        assertTrue(sessions.update(Channel.ID_AUD, Media.MediaOptions.newBuilder().setEnableMediaStats(false).build()))
        assertNull(sessions.current(Channel.ID_AUD)!!.runtime.statisticsIntervalMs)
        assertEquals(300L, sessions.current(Channel.ID_AUD)!!.runtime.audioBufferLimitMs)
        assertNull(sessions.start(Channel.ID_AUD, start(1))!!.options)
    }

    @Test fun `repeated unknown options stay bounded while known sparse presence survives`() {
        val sessions = galTestSessions()
        val initial = Media.MediaOptions.newBuilder().setEnableAvSync(true)
            .setAudioBufferLimit(Media.MediaDuration.newBuilder().setNanos(300_000_000)).build()
        sessions.start(Channel.ID_AUD, start(1).toBuilder().setOptions(initial).build())
        // Unknown field 14, length-delimited 256 bytes: every update is well under 16 KiB.
        val unknown = Media.MediaOptions.parseFrom(
            byteArrayOf(0x72, 0x80.toByte(), 0x02) + ByteArray(256))
        val update = unknown.toBuilder().setEnableAvSync(false).build()
        repeat(64) {
            assertTrue(sessions.update(Channel.ID_AUD, update))
            val current = sessions.current(Channel.ID_AUD)!!
            assertTrue("unknown bytes accumulated at update $it",
                current.options!!.serializedSize <= initial.serializedSize + update.serializedSize)
            assertTrue(current.options.hasEnableAvSync())
            assertFalse(current.options.enableAvSync)
            assertEquals(300L, current.runtime.audioBufferLimitMs)
        }
        sessions.update(Channel.ID_AUD, unknown)
        assertTrue(sessions.current(Channel.ID_AUD)!!.options!!.hasEnableAvSync())
        assertFalse(sessions.current(Channel.ID_AUD)!!.options!!.enableAvSync)
    }

    @Test fun `embedded and standalone options follow requested version independently of ACK mode`() {
        val options = Media.MediaOptions.newBuilder().setEnableAvSync(true).build()
        for (profile in GalProfile.entries) {
            val sessions = galTestSessions(profile)
            for (channel in listOf(Channel.ID_AUD, Channel.ID_VID)) {
                val session = sessions.start(channel, start(2).toBuilder().setOptions(options).build())!!
                assertEquals(profile.version >= GalVersion(5, 0), session.options != null)
                assertFalse(session.ackless)
                val standalone = profile.version >= if (channel == Channel.ID_AUD) GalVersion(5, 1) else GalVersion(6, 0)
                assertEquals(standalone, sessions.update(channel, options))
            }
            assertTrue(sessions.start(Channel.ID_AUD, start(3, Media.Start.FlowControl.ACKLESS))!!.ackless)
        }
    }

    @Test fun `modern UI keeps legacy odd margins and hidden widgets across replacement updates`() {
        val display = GalDisplayContract(GalDisplaySettings(true, false, true), true)
        val initial = display.initial(5, 7)!!
        assertEquals(2, initial.margins.left)
        assertEquals(3, initial.margins.right)
        assertEquals(3, initial.margins.top)
        assertEquals(4, initial.margins.bottom)
        assertEquals(setOf(Media.UiConfig.UiElement.CLOCK, Media.UiConfig.UiElement.PHONE_SIGNAL), initial.uiElementsList.toSet())
        val update = display.margins(1, 2, 3, 4)
        assertEquals(initial.uiElementsList, update.uiElementsList)
        assertFalse(update.hasUiTheme())
        assertEquals(0, GalDisplayContract(GalDisplaySettings(false, false, false), true).initial(0, 0)!!.uiElementsCount)
    }
}
