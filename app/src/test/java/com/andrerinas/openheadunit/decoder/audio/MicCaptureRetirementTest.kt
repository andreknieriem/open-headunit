package com.andrerinas.openheadunit.decoder.audio

import android.media.AudioRecord
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MicCaptureRetirementTest {
    private val runClass = Class.forName("com.andrerinas.openheadunit.decoder.audio.MicRecorder\$CaptureRun")
    private fun pcm(vararg samples: Int) = samples.flatMap { listOf(it.toByte(), (it shr 8).toByte()) }.toByteArray()
    private fun run(samples: ByteArray, received: MutableList<ByteArray>): Any {
        val record = mock<AudioRecord>()
        whenever(record.read(any<ByteArray>(), any(), any())).thenAnswer {
            samples.copyInto(it.arguments[0] as ByteArray); samples.size
        }
        val listener = object : MicRecorder.Listener {
            override fun onMicDataAvailable(mic_buf: ByteArray, mic_audio_len: Int, peak: Int) {
                received.add(mic_buf.copyOf(mic_audio_len))
            }
        }
        return runClass.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(record, 1, 64, 3, listener)
    }
    private fun field(run: Any, name: String) = runClass.getDeclaredField(name).apply { isAccessible = true }
    private fun activate(recorder: MicRecorder, run: Any) = MicRecorder::class.java
        .getDeclaredField("activeCapture").apply { isAccessible = true }.set(recorder, run)
    private fun read(recorder: MicRecorder, run: Any) = MicRecorder::class.java
        .getDeclaredMethod("micAudioRead", runClass).apply { isAccessible = true }.invoke(recorder, run)

    @Test fun `retired worker paused after ownership check cannot leave samples in the new converter`() =
        Mockito.mockStatic(SystemClock::class.java).use {
            val recorder = mock<MicRecorder>(defaultAnswer = Mockito.CALLS_REAL_METHODS)
            val a = run(pcm(900, 900, 900, 900), mutableListOf())
            val fresh = mutableListOf<ByteArray>()
            val b = run(pcm(300, 300), fresh)
            val converter = spy(field(a, "converter").get(a) as MicPcmDecimator)
            field(a, "converter").set(a, converter)
            val entered = CountDownLatch(1)
            val resume = CountDownLatch(1)
            doAnswer { call ->
                entered.countDown(); check(resume.await(3, TimeUnit.SECONDS)); call.callRealMethod()
            }.whenever(converter).decimate(any(), any(), any())
            activate(recorder, a)
            val failure = AtomicReference<Throwable?>()
            val worker = Thread { try { read(recorder, a) } catch (t: Throwable) { failure.set(t) } }.apply { start() }
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                activate(recorder, b)
                resume.countDown(); worker.join(3000)
                assertFalse(worker.isAlive)
                failure.get()?.let { throw AssertionError(it) }
                read(recorder, b)
                assertTrue("B has only two samples, insufficient for a factor-three output", fresh.isEmpty())
                assertNotSame(field(a, "wireBuffer").get(a), field(b, "wireBuffer").get(b))
            } finally { resume.countDown(); worker.join(3000) }
        }

    @Test fun `converted old bytes remain unchanged while a new run converts before callback copy`() =
        Mockito.mockStatic(SystemClock::class.java).use {
            val recorder = mock<MicRecorder>(defaultAnswer = Mockito.CALLS_REAL_METHODS)
            val oldBytes = mutableListOf<ByteArray>()
            val newBytes = mutableListOf<ByteArray>()
            val a = run(pcm(900, 900, 900), oldBytes)
            val b = run(pcm(300, 300, 300), newBytes)
            val original = field(a, "listener").get(a) as MicRecorder.Listener
            val entered = CountDownLatch(1)
            val resume = CountDownLatch(1)
            field(a, "listener").set(a, object : MicRecorder.Listener {
                override fun onMicDataAvailable(mic_buf: ByteArray, mic_audio_len: Int, peak: Int) {
                    entered.countDown(); check(resume.await(3, TimeUnit.SECONDS))
                    original.onMicDataAvailable(mic_buf, mic_audio_len, peak)
                }
            })
            activate(recorder, a)
            val failure = AtomicReference<Throwable?>()
            val worker = Thread { try { read(recorder, a) } catch (t: Throwable) { failure.set(t) } }.apply { start() }
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                activate(recorder, b); read(recorder, b)
                resume.countDown(); worker.join(3000)
                assertFalse(worker.isAlive)
                failure.get()?.let { throw AssertionError(it) }
                assertArrayEquals(pcm(900), oldBytes.single())
                assertArrayEquals(pcm(300), newBytes.single())
            } finally { resume.countDown(); worker.join(3000) }
        }
    @Test fun `failed native stop still releases recorder effects and the original foreground claim`() {
        val recorder = mock<MicRecorder>(defaultAnswer = Mockito.CALLS_REAL_METHODS)
        val record = mock<AudioRecord>()
        val aec = mock<android.media.audiofx.AcousticEchoCanceler>()
        val ns = mock<android.media.audiofx.NoiseSuppressor>()
        val claim = mock<MicRecorder.ForegroundMicrophoneClaim>()
        fun set(name: String, value: Any) = MicRecorder::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(recorder, value)
        set("audioRecord", record); set("aec", aec); set("ns", ns); set("claimedForeground", claim)
        doThrow(IllegalStateException("native stop failure")).whenever(record).stop()
        doThrow(IllegalStateException("effect release failure")).whenever(aec).release()
        val level = com.andrerinas.openheadunit.utils.AppLog::class.java
            .getDeclaredField("cachedLogLevel").apply { isAccessible = true }
        val oldLevel = level.getInt(null)
        level.setInt(null, Int.MAX_VALUE)
        try {
            recorder.stop()
            verify(record).release()
            verify(ns).release()
            verify(claim).release()
            recorder.stop()
            verify(claim, times(1)).release()
        } finally { level.setInt(null, oldLevel) }
    }

}
