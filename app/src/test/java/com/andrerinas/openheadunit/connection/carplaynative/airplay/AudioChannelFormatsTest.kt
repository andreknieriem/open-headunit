package com.andrerinas.openheadunit.connection.carplaynative.airplay

import com.andrerinas.openheadunit.connection.carplaybox.CarPlayBoxAudioFormat.Format
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioChannelFormatsTest {

    @Test fun aChannelIsConfiguredOnceForOneFormat() {
        val formats = AudioChannelFormats()
        assertTrue(formats.needsConfigure(6, Format(48_000, 2)))
        assertFalse(formats.needsConfigure(6, Format(48_000, 2)))
    }

    @Test fun anotherFormatOnTheSameChannelReconfiguresIt() {
        val formats = AudioChannelFormats()
        formats.needsConfigure(6, Format(48_000, 2))
        assertTrue(formats.needsConfigure(6, Format(16_000, 1)))
        assertTrue(formats.needsConfigure(6, Format(48_000, 2)))
    }

    @Test fun anotherChannelHasItsOwnFormat() {
        val formats = AudioChannelFormats()
        formats.needsConfigure(6, Format(48_000, 2))
        assertTrue(formats.needsConfigure(4, Format(48_000, 2)))
    }
}
