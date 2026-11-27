package com.andrerinas.openheadunit.connection.carplaybox

import com.andrerinas.openheadunit.aap.protocol.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarPlayBoxAudioFormatTest {

    @Test
    fun `each decode type has a rate and channel count`() {
        val expected = mapOf(
            1 to (44100 to 2), 2 to (44100 to 2), 3 to (8000 to 1), 4 to (48000 to 2),
            5 to (16000 to 1), 6 to (24000 to 1), 7 to (16000 to 2),
        )
        for ((type, rc) in expected) {
            val f = CarPlayBoxAudioFormat.forDecodeType(type)!!
            assertEquals(rc, f.sampleRate to f.channels)
        }
        assertNull(CarPlayBoxAudioFormat.forDecodeType(0))
        assertNull(CarPlayBoxAudioFormat.forDecodeType(8))
    }

    @Test
    fun `media navigation and call route to separate channels`() {
        assertEquals(Channel.ID_AUD, CarPlayBoxAudioRouting.channelFor(4, 1))
        assertEquals(Channel.ID_AU1, CarPlayBoxAudioRouting.channelFor(4, 2))
        assertEquals(Channel.ID_AU2, CarPlayBoxAudioRouting.channelFor(3, 0))
        assertEquals(Channel.ID_AU2, CarPlayBoxAudioRouting.channelFor(5, 0))
        assertEquals(Channel.ID_AUD, CarPlayBoxAudioRouting.channelFor(4, 0))
    }

    @Test
    fun `microphone type is never played`() {
        assertNull(CarPlayBoxAudioRouting.channelFor(5, 3))
    }
}
