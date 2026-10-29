package com.andrerinas.openheadunit.decoder.audio

import android.app.Application
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AudioTrackPcmOutputTest {
    @Test fun `ordinary pause flush resets positions without reporting a device replacement`() {
        val output = AudioTrackPcmOutput(AudioManager.STREAM_MUSIC, attachHwDsp = false)
        try {
            val epoch = output.outputEpoch
            val position = AudioTrackPcmOutput::class.java.getDeclaredField("position")
                .apply { isAccessible = true }.get(output) as PcmPlaybackPosition
            position.written(480)
            assertEquals(480L, output.writtenFrames)
            output.pause()
            assertEquals(0L, output.writtenFrames)
            assertEquals(epoch, output.outputEpoch)
        } finally {
            output.close()
        }
    }
}
