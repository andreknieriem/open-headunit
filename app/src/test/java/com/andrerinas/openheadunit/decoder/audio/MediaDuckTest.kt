package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaDuckTest {

    @Test
    fun `an idle duck leaves the music at full gain`() {
        assertEquals(1f, MediaDuck.next(1f, active = false))
    }

    @Test
    fun `the gain eases down to the duck and back up, never past either end`() {
        var gain = 1f
        var cycles = 0
        while (gain > MediaDuck.GAIN) {
            gain = MediaDuck.next(gain, active = true)
            cycles++
        }
        assertEquals(MediaDuck.GAIN, gain)
        assertEquals(7, cycles)
        while (gain < 1f) gain = MediaDuck.next(gain, active = false)
        assertEquals(1f, gain)
    }
}
