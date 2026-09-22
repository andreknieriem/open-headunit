package com.andrerinas.openheadunit.aap.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelTest {

    @Test
    fun `both projected displays answer isVideo`() {
        assertTrue(Channel.isVideo(Channel.ID_VID))
        assertTrue(Channel.isVideo(Channel.ID_VID2))
    }

    @Test
    fun `nothing else answers isVideo`() {
        for (channel in listOf(
            Channel.ID_CTR, Channel.ID_SEN, Channel.ID_INP, Channel.ID_AUD, Channel.ID_AU1,
            Channel.ID_AU2, Channel.ID_MIC, Channel.ID_BTH, Channel.ID_MPB, Channel.ID_NAV,
            Channel.ID_NOTI, Channel.ID_PHONE, Channel.ID_WIFI,
        )) {
            assertFalse("channel $channel", Channel.isVideo(channel))
        }
    }

    @Test
    fun `video and audio never claim the same channel`() {
        for (channel in 0..15) {
            assertFalse("channel $channel", Channel.isVideo(channel) && Channel.isAudio(channel))
        }
    }

    @Test
    fun `the auxiliary display has a channel of its own`() {
        assertNotEquals(Channel.ID_VID, Channel.ID_VID2)
        for (channel in listOf(
            Channel.ID_CTR, Channel.ID_SEN, Channel.ID_VID, Channel.ID_INP, Channel.ID_AUD,
            Channel.ID_AU1, Channel.ID_AU2, Channel.ID_MIC, Channel.ID_BTH, Channel.ID_MPB,
            Channel.ID_NAV, Channel.ID_NOTI, Channel.ID_PHONE, Channel.ID_WIFI,
        )) {
            assertNotEquals(channel, Channel.ID_VID2)
        }
    }

    @Test
    fun `the auxiliary channel is named, so a log line does not read UNK`() {
        assertEquals("VIDEO_AUX", Channel.name(Channel.ID_VID2))
        assertEquals("VIDEO", Channel.name(Channel.ID_VID))
    }

    @Test
    fun `a service id has to be one the protocol allows`() {
        assertTrue(Channel.ID_VID2 in 1..254)
    }
}
