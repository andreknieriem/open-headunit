package com.andrerinas.openheadunit.decoder.video

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaCodec
import android.media.MediaFormat
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.AppLog
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real output loop; only Android's codec is doubled. Native calls deliberately ignore interrupt. */
class VideoOutputRetirementTest {
    private class Fixture(clock: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) }) {
        val decoder: VideoDecoder
        val codec = mock(MediaCodec::class.java)
        val failure = AtomicReference<Throwable?>()
        lateinit var worker: Thread
        init {
            val context = mock(Context::class.java)
            val preferences = mock(SharedPreferences::class.java)
            `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences)
            `when`(preferences.getInt(anyString(), anyInt())).thenAnswer { it.arguments[1] }
            decoder = VideoDecoder(Settings(context), elapsedRealtime = clock)
        }
        fun set(name: String, value: Any?) {
            VideoDecoder::class.java.getDeclaredField(name).apply { isAccessible = true }.set(decoder, value)
        }
        fun get(name: String): Any? =
            VideoDecoder::class.java.getDeclaredField(name).apply { isAccessible = true }.get(decoder)
        fun start(targetCodec: MediaCodec = codec) {
            val loop = VideoDecoder::class.java.getDeclaredMethod("outputThreadLoop").apply { isAccessible = true }
            worker = Thread {
                try { loop.invoke(decoder) } catch (t: Throwable) { failure.set(t.cause ?: t) }
            }
            set("codec", targetCodec)
            set("codecBufferInfo", MediaCodec.BufferInfo())
            set("running", true)
            set("outputThread", worker)
            worker.start()
        }
        fun replace(): MediaCodec {
            decoder.stop("test replacement") // bounded join must return while the native call is stuck
            val replacement = mock(MediaCodec::class.java)
            set("codec", replacement)
            set("mWidth", 1920)
            set("mHeight", 1080)
            set("decoderNeedsRestart", false)
            set("running", true)
            set("outputThread", Thread("replacement owner"))
            return replacement
        }
        fun join() {
            worker.join(3000)
            assertFalse("retired output worker must exit", worker.isAlive)
            failure.get()?.let { throw AssertionError("output worker failed", it) }
        }
    }

    private class NativePause {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        fun block() {
            entered.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (resume.count != 0L && System.nanoTime() < deadline) {
                try { resume.await(100, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
            }
            check(resume.count == 0L) { "native pause was never released" }
        }
        fun await() = assertTrue("worker did not reach native pause", entered.await(3, TimeUnit.SECONDS))
    }

    @Test fun `late format cannot publish dimensions or scale a replacement codec`() {
        val f = Fixture()
        val pause = NativePause()
        val format = mock(MediaFormat::class.java)
        `when`(format.containsKey(anyString())).thenReturn(true)
        `when`(format.getInteger(anyString())).thenReturn(0)
        `when`(format.getInteger(MediaFormat.KEY_WIDTH)).thenAnswer { pause.block(); 1280 }
        `when`(format.getInteger(MediaFormat.KEY_HEIGHT)).thenReturn(720)
        `when`(f.codec.dequeueOutputBuffer(any(), anyLong())).thenReturn(MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)
        `when`(f.codec.outputFormat).thenReturn(format)
        f.start()
        try {
            pause.await()
            val replacement = f.replace()
            val listener = mock(VideoDimensionsListener::class.java)
            f.decoder.dimensionsListener = listener
            pause.resume.countDown()
            f.join()
            assertEquals(1920, f.get("mWidth"))
            assertEquals(1080, f.get("mHeight"))
            verifyNoInteractions(listener, replacement)
            assertEquals(false, f.get("decoderNeedsRestart"))
        } finally { pause.resume.countDown(); f.decoder.stop("test cleanup") }
    }

    @Test fun `late render completion cannot consume the next first frame listener or counters`() {
        val f = Fixture()
        val pause = NativePause()
        `when`(f.codec.dequeueOutputBuffer(any(), anyLong())).thenReturn(7, MediaCodec.INFO_TRY_AGAIN_LATER)
        doAnswer {
            assertEquals("codec must only receive its own output index", 7, it.arguments[0])
            pause.block(); null
        }.`when`(f.codec).releaseOutputBuffer(anyInt(), anyBoolean())
        f.start()
        try {
            pause.await()
            val replacement = f.replace()
            val calls = AtomicInteger()
            val listener: () -> Unit = { calls.incrementAndGet(); Unit }
            f.decoder.onFirstFrameListener = listener
            pause.resume.countDown()
            f.join()
            assertSame(listener, f.decoder.onFirstFrameListener)
            assertEquals(0, calls.get())
            assertEquals(0L, f.decoder.lastFrameRenderedMs)
            assertEquals(0L, f.get("framesRendered"))
            assertEquals(false, f.get("renderedThisSession"))
            assertEquals(false, f.get("decoderNeedsRestart"))
            verifyNoInteractions(replacement)
        } finally { pause.resume.countDown(); f.decoder.stop("test cleanup") }
    }

    @Test fun `a native dequeue failure after retirement cannot restart the replacement`() {
        val f = Fixture()
        val pause = NativePause()
        `when`(f.codec.dequeueOutputBuffer(any(), anyLong())).thenAnswer {
            pause.block()
            throw IllegalStateException("retired component")
        }
        f.start()
        try {
            pause.await()
            val replacement = f.replace()
            pause.resume.countDown()
            f.join()
            assertEquals(false, f.get("decoderNeedsRestart"))
            verify(f.codec, times(1)).dequeueOutputBuffer(any(), anyLong())
            verifyNoInteractions(replacement)
        } finally { pause.resume.countDown(); f.decoder.stop("test cleanup") }
    }

    @Test fun `retiring before callback dispatch preserves the first frame notification for the next worker`() {
        val f = Fixture()
        val pause = NativePause()
        val originalLogger = AppLog.LOGGER
        val calls = AtomicInteger()
        val listener: () -> Unit = { calls.incrementAndGet(); f.decoder.stop("first frame received") }
        f.decoder.onFirstFrameListener = listener
        `when`(f.codec.dequeueOutputBuffer(any(), anyLong())).thenReturn(7, MediaCodec.INFO_TRY_AGAIN_LATER)
        AppLog.LOGGER = object : AppLog.Logger {
            override fun println(priority: Int, tag: String, msg: String) {
                if (msg.contains("First frame rendered (hardware decode)")) pause.block()
            }
        }
        f.start()
        val oldWorker = f.worker
        try {
            pause.await() // state was published, but the queued first-frame callback has not run
            f.decoder.stop("retired before dispatch")
            assertSame("the next decoder still owes the first-frame notification", listener, f.decoder.onFirstFrameListener)
            AppLog.LOGGER = originalLogger
            val replacement = mock(MediaCodec::class.java)
            `when`(replacement.dequeueOutputBuffer(any(), anyLong())).thenReturn(10, MediaCodec.INFO_TRY_AGAIN_LATER)
            doAnswer {
                assertEquals("the replacement must release only its own index", 10, it.arguments[0]); null
            }.`when`(replacement).releaseOutputBuffer(anyInt(), anyBoolean())
            f.start(replacement)
            f.join()
            pause.resume.countDown()
            oldWorker.join(3000)
            assertFalse(oldWorker.isAlive)
            assertEquals(1, calls.get())
            assertNull(f.decoder.onFirstFrameListener)
        } finally {
            AppLog.LOGGER = originalLogger
            pause.resume.countDown()
            oldWorker.join(3000)
            f.decoder.stop("test cleanup")
        }
    }

    @Test fun `retirement cannot interleave with a render publication already in progress`() {
        val pause = NativePause()
        val released = java.util.concurrent.atomic.AtomicBoolean()
        val f = Fixture {
            if (released.compareAndSet(true, false)) pause.block()
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime())
        }
        `when`(f.codec.dequeueOutputBuffer(any(), anyLong())).thenReturn(7, MediaCodec.INFO_TRY_AGAIN_LATER)
        doAnswer { released.set(true); null }.`when`(f.codec).releaseOutputBuffer(7, true)
        val retired = CountDownLatch(1)
        val stopper = Thread { f.decoder.stop("concurrent retirement"); retired.countDown() }
        f.start()
        try {
            pause.await() // clock read inside the publication, after the ownership check
            stopper.start()
            // Longer than stop's 500ms join budget: a naked check followed by state writes
            // would let retirement finish, then this old publication overwrite the reset.
            assertFalse("retirement must wait for an in-flight publication", retired.await(700, TimeUnit.MILLISECONDS))
            pause.resume.countDown()
            assertTrue(retired.await(3, TimeUnit.SECONDS))
            f.join()
            assertEquals(0L, f.get("framesRendered"))
            assertEquals(0L, f.decoder.lastFrameRenderedMs)
        } finally { pause.resume.countDown(); stopper.join(3000); f.decoder.stop("test cleanup") }
    }

    @Test fun `first frame callback may retire the worker without clearing its replacement listener`() {
        val f = Fixture()
        val replacementCalls = AtomicInteger()
        val retiredDuringCallback = java.util.concurrent.atomic.AtomicBoolean()
        val replacement: () -> Unit = { replacementCalls.incrementAndGet(); Unit }
        `when`(f.codec.dequeueOutputBuffer(any(), anyLong())).thenReturn(7, MediaCodec.INFO_TRY_AGAIN_LATER)
        f.decoder.onFirstFrameListener = {
            val done = CountDownLatch(1)
            Thread {
                f.decoder.stop("callback retirement")
                f.decoder.onFirstFrameListener = replacement
                done.countDown()
            }.start()
            // An external callback must not hold the publication lock while another thread
            // retires the owner. Same-thread stop alone would hide a reentrant-monitor bug.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (done.count != 0L && System.nanoTime() < deadline) {
                try { done.await(100, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
            }
            retiredDuringCallback.set(done.count == 0L)
            check(retiredDuringCallback.get()) { "callback held the retirement lock" }
        }
        f.start()
        f.join()
        assertTrue("retirement must complete before the callback returns", retiredDuringCallback.get())
        assertSame(replacement, f.decoder.onFirstFrameListener)
        assertEquals(0, replacementCalls.get())
        verify(f.codec).releaseOutputBuffer(7, true)
    }
}
