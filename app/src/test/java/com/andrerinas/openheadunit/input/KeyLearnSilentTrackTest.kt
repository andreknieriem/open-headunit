package com.andrerinas.openheadunit.input

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class KeyLearnSilentTrackTest {
    private val bytes = 800 * 2

    private fun track(write: Int = bytes, loop: Int = AudioTrack.SUCCESS, playThrows: Boolean = false): AudioTrack {
        val t = mock(AudioTrack::class.java)
        `when`(t.write(any(), eq(0), eq(bytes))).thenReturn(write)
        `when`(t.setLoopPoints(0, 800, -1)).thenReturn(loop)
        if (playThrows) `when`(t.play()).thenThrow(IllegalStateException("play"))
        return t
    }

    private fun any(): ByteArray = org.mockito.ArgumentMatchers.any(ByteArray::class.java) ?: ByteArray(0)
    private fun eq(v: Int): Int = org.mockito.ArgumentMatchers.eq(v)

    // Runs body with the three builders replaced; builds yields the track, or throws when it is null.
    private fun <T> withBuilders(built: AudioTrack?, body: (builds: () -> Int) -> T): T {
        var builds = 0
        mockConstruction(AudioAttributes.Builder::class.java) { b, _ ->
            `when`(b.setUsage(org.mockito.ArgumentMatchers.anyInt())).thenReturn(b)
            `when`(b.setContentType(org.mockito.ArgumentMatchers.anyInt())).thenReturn(b)
        }.use {
            mockConstruction(AudioFormat.Builder::class.java, { b, _ ->
                `when`(b.setEncoding(org.mockito.ArgumentMatchers.anyInt())).thenReturn(b)
                `when`(b.setSampleRate(org.mockito.ArgumentMatchers.anyInt())).thenReturn(b)
                `when`(b.setChannelMask(org.mockito.ArgumentMatchers.anyInt())).thenReturn(b)
            }).use {
                mockConstruction(AudioTrack.Builder::class.java, { b, _ ->
                    `when`(b.setAudioAttributes(org.mockito.ArgumentMatchers.any())).thenReturn(b)
                    `when`(b.setAudioFormat(org.mockito.ArgumentMatchers.any())).thenReturn(b)
                    `when`(b.setTransferMode(org.mockito.ArgumentMatchers.anyInt())).thenReturn(b)
                    `when`(b.setBufferSizeInBytes(org.mockito.ArgumentMatchers.anyInt())).thenReturn(b)
                    if (built == null) `when`(b.build()).thenThrow(UnsupportedOperationException("build"))
                    else `when`(b.build()).thenAnswer { builds++; built }
                }).use { return body { builds } }
            }
        }
    }

    @Test fun successReturnsTrueAndPlays() {
        val t = track()
        withBuilders(t) { _ -> assertTrue(KeyLearnSilentTrack().start()) }
        verify(t).play()
        verify(t, never()).release()
    }

    @Test fun aFailedBuildReturnsFalse() {
        withBuilders(null) { _ -> assertFalse(KeyLearnSilentTrack().start()) }
    }

    @Test fun aShortWriteReturnsFalseAndReleases() {
        val t = track(write = 10)
        withBuilders(t) { _ -> assertFalse(KeyLearnSilentTrack().start()) }
        verify(t).release()
        verify(t, never()).play()
    }

    @Test fun aLoopPointErrorReturnsFalseAndReleases() {
        val t = track(loop = AudioTrack.ERROR_BAD_VALUE)
        withBuilders(t) { _ -> assertFalse(KeyLearnSilentTrack().start()) }
        verify(t).release()
    }

    @Test fun aThrowingPlayReturnsFalseAndReleases() {
        val t = track(playThrows = true)
        withBuilders(t) { _ -> assertFalse(KeyLearnSilentTrack().start()) }
        verify(t).release()
    }

    @Test fun aSecondStartBuildsNothing() {
        val t = track()
        withBuilders(t) { builds ->
            val s = KeyLearnSilentTrack()
            assertTrue(s.start())
            assertTrue(s.start())
            assertEquals(1, builds())
        }
    }

    @Test fun stopAfterAFailedStartDoesNothing() {
        val t = track(write = 10)
        withBuilders(t) { _ ->
            val s = KeyLearnSilentTrack()
            assertFalse(s.start())
            s.stop()
        }
        verify(t, times(1)).release()
        verify(t, never()).stop()
    }
}
