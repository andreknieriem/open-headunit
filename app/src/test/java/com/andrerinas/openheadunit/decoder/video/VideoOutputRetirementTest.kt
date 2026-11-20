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
            decoder.stop("test replacement") // logical retirement must not wait for the native call
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

    @Test fun `three native dequeue failures request a restart while the owner is current`() {
        val f = Fixture()
        `when`(f.codec.dequeueOutputBuffer(any(), anyLong())).thenThrow(IllegalStateException("component failure"))
        f.start()
        try {
            f.join()
            assertEquals(true, f.get("decoderNeedsRestart"))
            assertEquals("sync_consecutive_errors", f.get("decoderRestartReason"))
            verify(f.codec, times(3)).dequeueOutputBuffer(any(), anyLong())
        } finally { f.decoder.stop("test cleanup") }
    }

    @Test fun `a native dequeue failure after retirement cannot restart the replacement`() {
        val f = Fixture()
        val pause = NativePause()
        val attempts = AtomicInteger()
        `when`(f.codec.dequeueOutputBuffer(any(), anyLong())).thenAnswer {
            // The third strike would restart a live decoder. Retire exactly at that boundary,
            // rather than checking a first strike which cannot request a restart anyway.
            if (attempts.incrementAndGet() == 3) pause.block()
            throw IllegalStateException("component failure")
        }
        f.start()
        try {
            pause.await()
            val replacement = f.replace()
            pause.resume.countDown()
            f.join()
            assertEquals(false, f.get("decoderNeedsRestart"))
            verify(f.codec, times(3)).dequeueOutputBuffer(any(), anyLong())
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
            // A naked ownership check followed by state writes would let retirement finish,
            // then this old publication overwrite the reset.
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
    @Test fun `replacement reannounces dimensions stored before the old notification was retired`() {
        val f = Fixture()
        val pause = NativePause()
        val previousLogger = AppLog.LOGGER
        val format = mock(MediaFormat::class.java)
        `when`(format.containsKey(anyString())).thenReturn(true)
        `when`(format.getInteger(anyString())).thenReturn(0)
        `when`(format.getInteger(MediaFormat.KEY_WIDTH)).thenReturn(1280)
        `when`(format.getInteger(MediaFormat.KEY_HEIGHT)).thenReturn(720)
        `when`(f.codec.dequeueOutputBuffer(any(), anyLong())).thenReturn(MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)
        `when`(f.codec.outputFormat).thenReturn(format)
        val calls = AtomicInteger()
        f.decoder.dimensionsListener = object : VideoDimensionsListener {
            override fun onVideoDimensionsChanged(width: Int, height: Int) {
                assertEquals(1280, width)
                assertEquals(720, height)
                calls.incrementAndGet()
                f.decoder.stop("dimensions received")
            }
        }
        AppLog.LOGGER = object : AppLog.Logger {
            override fun println(priority: Int, tag: String, msg: String) {
                if (msg.contains("Video dimensions changed via format")) pause.block()
            }
        }
        f.start()
        val oldWorker = f.worker
        try {
            pause.await()
            f.decoder.stop(DecoderStopPolicy.REASON_ACTIVITY_STOPPED)
            assertEquals(1280, f.get("mWidth"))
            assertEquals(0, calls.get())
            AppLog.LOGGER = previousLogger
            val replacement = mock(MediaCodec::class.java)
            `when`(replacement.dequeueOutputBuffer(any(), anyLong())).thenReturn(MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)
            `when`(replacement.outputFormat).thenReturn(format)
            f.start(replacement)
            f.join()
            pause.resume.countDown()
            oldWorker.join(3000)
            assertFalse(oldWorker.isAlive)
            assertEquals(1, calls.get())
        } finally {
            AppLog.LOGGER = previousLogger
            pause.resume.countDown()
            oldWorker.join(3000)
            f.decoder.stop("test cleanup")
        }
    }

    @Test fun `idle output expires concealment without requiring another frame`() {
        val now = java.util.concurrent.atomic.AtomicLong(10_000L)
        val f = Fixture { now.get() }
        f.set("concealState", CorruptionConcealmentPolicy.State.CONCEALING)
        f.set("concealWindowOpenedMs", now.get())
        f.set("renderedThisSession", true)
        val deadline = now.get() + CorruptionConcealmentPolicy.CONCEAL_MAX_MS
        `when`(f.codec.dequeueOutputBuffer(any(), anyLong())).thenAnswer {
            if (f.get("concealState") != CorruptionConcealmentPolicy.State.CONCEALING) {
                f.set("running", false)
            } else {
                val waitUs = it.getArgument<Long>(1)
                check(waitUs in 1L..100_000L) { "idle output must periodically service recovery" }
                now.addAndGet((waitUs + 999) / 1000)
                check(now.get() <= deadline + 100) { "concealment remained latched without output" }
            }
            MediaCodec.INFO_TRY_AGAIN_LATER
        }
        f.start()
        try {
            f.join()
            assertEquals(CorruptionConcealmentPolicy.State.DISARMED, f.get("concealState"))
            assertTrue(now.get() in deadline..deadline + 100)
            assertEquals(false, f.get("decoderNeedsRestart"))
            verify(f.codec, never()).releaseOutputBuffer(anyInt(), anyBoolean())
        } finally { f.decoder.stop("test cleanup") }
    }

    @Test fun `bounded output waits still restart a stalled codec while input continues`() {
        val now = java.util.concurrent.atomic.AtomicLong(10_000L)
        val f = Fixture { now.get() }
        val tracker = f.get("keyframeRepair") as KeyframeRepairTracker
        val repair = tracker.beginKeyframe(1)
        tracker.commitKeyframe(repair)
        tracker.onOutputReleased(repair, repair, 1, true, countOutput = true)
        assertTrue(tracker.consumeRepair())
        f.set("renderedThisSession", true)
        f.set("lastFrameRenderedMs", now.get())
        `when`(f.codec.dequeueOutputBuffer(any(), anyLong())).thenAnswer {
            val waitUs = it.getArgument<Long>(1)
            check(waitUs in 1L..100_000L)
            now.addAndGet((waitUs + 999) / 1000)
            f.set("lastInputBytesReceivedMs", now.get())
            check(now.get() <= 12_100L) { "output waits starved the stall watchdog" }
            MediaCodec.INFO_TRY_AGAIN_LATER
        }
        f.start()
        try {
            f.join()
            assertEquals(true, f.get("decoderNeedsRestart"))
            assertEquals("sync_stall", f.get("decoderRestartReason"))
            assertTrue(now.get() in 12_001L..12_100L)
        } finally { f.decoder.stop("test cleanup") }
    }

    @Test fun `a catch-up dequeue failure returns held output before retrying`() {
        val f = Fixture()
        val calls = AtomicInteger()
        val returned = java.util.concurrent.atomic.AtomicBoolean()
        `when`(f.codec.dequeueOutputBuffer(any(), anyLong())).thenAnswer {
            when (calls.incrementAndGet()) {
                1 -> 7
                2 -> throw IllegalStateException("catch-up dequeue failed")
                3 -> {
                    if (returned.get()) 8 else {
                        // A one-slot codec cannot produce more output until its held slot returns.
                        f.set("running", false)
                        MediaCodec.INFO_TRY_AGAIN_LATER
                    }
                }
                else -> MediaCodec.INFO_TRY_AGAIN_LATER
            }
        }
        doAnswer { returned.set(true); null }.`when`(f.codec).releaseOutputBuffer(7, false)
        f.decoder.onFirstFrameListener = { f.set("running", false) }
        f.start()
        try {
            f.join()
            assertTrue("the failed pass must not strand a codec output slot", returned.get())
            verify(f.codec).releaseOutputBuffer(8, true)
            assertEquals(1L, f.get("framesRendered"))
            assertEquals(false, f.get("decoderNeedsRestart"))
        } finally { f.decoder.stop("test cleanup") }
    }

    @Test fun `a successful earlier render survives a later release failure and idle output`() {
        for (firstPts in listOf(1_000L, 900L)) {
            val now = java.util.concurrent.atomic.AtomicLong(10_000L)
            val f = Fixture { now.get() }
            val tracker = f.get("keyframeRepair") as KeyframeRepairTracker
            val repair = tracker.beginKeyframe(1_000L)
            tracker.commitKeyframe(repair)
            f.set("lastKeyframeFedMs", now.get())
            val calls = AtomicInteger()
            val repairs = AtomicInteger()
            val firstFrames = AtomicInteger()
            f.decoder.onKeyframeObserved = { repairs.incrementAndGet(); Unit }
            f.decoder.onFirstFrameListener = { firstFrames.incrementAndGet(); Unit }
            `when`(f.codec.dequeueOutputBuffer(any(), anyLong())).thenAnswer {
                val info = it.getArgument<MediaCodec.BufferInfo>(0)
                when (calls.incrementAndGet()) {
                    1 -> { info.presentationTimeUs = firstPts; 7 }
                    2 -> { info.presentationTimeUs = 1_010L; 8 }
                    3 -> MediaCodec.INFO_TRY_AGAIN_LATER
                    4 -> { now.set(13_000L); MediaCodec.INFO_TRY_AGAIN_LATER }
                    else -> { f.set("running", false); MediaCodec.INFO_TRY_AGAIN_LATER }
                }
            }
            doThrow(IllegalStateException("later render failed")).`when`(f.codec).releaseOutputBuffer(8, true)
            f.start()
            try {
                f.join()
                assertEquals("only successfully rendered timestamps prove repair", firstPts >= 1_000L, tracker.keyframeDecoded)
                assertEquals(if (firstPts >= 1_000L) 1 else 0, repairs.get())
                assertEquals(firstPts >= 1_000L, f.decoder.hasCrediblePicture)
                assertEquals(1, firstFrames.get())
                assertEquals(1L, f.get("framesRendered"))
                assertEquals(1L, f.get("framesPresented"))
                assertEquals(1L, f.decoder.framesRenderedThisSession)
                assertEquals(10_000L, f.decoder.lastFrameRenderedMs)
                verify(f.codec).releaseOutputBuffer(7, true)
                verify(f.codec, never()).releaseOutputBuffer(7, false)
                verify(f.codec).releaseOutputBuffer(8, false)
                assertEquals(false, f.get("decoderNeedsRestart"))
            } finally { f.decoder.stop("test cleanup") }
        }
    }

    @Test fun `rearming a first frame listener waits for output after its callback`() {
        val f = Fixture()
        val calls = AtomicInteger()
        val notifications = AtomicInteger()
        lateinit var listener: () -> Unit
        listener = {
            notifications.incrementAndGet()
            f.decoder.onFirstFrameListener = listener
        }
        f.decoder.onFirstFrameListener = listener
        `when`(f.codec.dequeueOutputBuffer(any(), anyLong())).thenAnswer {
            when (calls.incrementAndGet()) {
                1 -> 7
                2 -> 8
                3 -> MediaCodec.INFO_TRY_AGAIN_LATER
                else -> { f.set("running", false); MediaCodec.INFO_TRY_AGAIN_LATER }
            }
        }
        f.start()
        try {
            f.join()
            assertEquals(1, notifications.get())
            assertSame(listener, f.decoder.onFirstFrameListener)
            assertEquals(2L, f.get("framesRendered"))
            assertEquals(1L, f.get("framesPresented"))
        } finally { f.decoder.stop("test cleanup") }
    }

    @Test fun `a later repair candidate does not expose an earlier concealed picture`() {
        for (laterFails in listOf(false, true)) {
            val f = Fixture { 10_000L }
            f.set("renderedThisSession", true)
            f.set("lastFrameRenderedMs", 9_900L)
            f.set("concealState", CorruptionConcealmentPolicy.State.CONCEALING)
            f.set("concealWindowOpenedMs", 10_000L)
            val tracker = f.get("keyframeRepair") as KeyframeRepairTracker
            val token = tracker.beginKeyframe(1_000L)
            tracker.commitKeyframe(token)
            val calls = AtomicInteger()
            `when`(f.codec.dequeueOutputBuffer(any(), anyLong())).thenAnswer {
                val info = it.getArgument<MediaCodec.BufferInfo>(0)
                when (calls.incrementAndGet()) {
                    1 -> { info.presentationTimeUs = 900L; 7 }
                    2 -> { info.presentationTimeUs = 1_000L; 8 }
                    3 -> MediaCodec.INFO_TRY_AGAIN_LATER
                    else -> { f.set("running", false); MediaCodec.INFO_TRY_AGAIN_LATER }
                }
            }
            if (laterFails) {
                doThrow(IllegalStateException("repair render failed")).`when`(f.codec).releaseOutputBuffer(8, true)
            }
            f.start()
            try {
                f.join()
                verify(f.codec).releaseOutputBuffer(7, false)
                verify(f.codec, never()).releaseOutputBuffer(7, true)
                assertEquals(!laterFails, tracker.keyframeDecoded)
                assertEquals(if (laterFails) 0L else 1L, f.get("framesRendered"))
                assertEquals(1L, f.get("framesConcealed"))
                assertEquals(
                    if (laterFails) CorruptionConcealmentPolicy.State.CONCEALING else CorruptionConcealmentPolicy.State.ARMED,
                    f.get("concealState")
                )
                assertEquals(if (laterFails) 9_900L else 10_000L, f.decoder.lastFrameRenderedMs)
            } finally { f.decoder.stop("test cleanup") }
        }
    }

    @Test fun `unreturnable output requests restart instead of retaining a depleted pool`() {
        val f = Fixture()
        val calls = AtomicInteger()
        `when`(f.codec.dequeueOutputBuffer(any(), anyLong())).thenAnswer {
            if (calls.incrementAndGet() == 1) 7 else throw IllegalStateException("drain failed")
        }
        doThrow(IllegalStateException("return failed")).`when`(f.codec).releaseOutputBuffer(7, false)
        f.start()
        try {
            f.join()
            assertEquals(true, f.get("decoderNeedsRestart"))
            assertEquals("output_buffer_return_failed", f.get("decoderRestartReason"))
            assertEquals(2, calls.get())
        } finally { f.decoder.stop("test cleanup") }
    }

    @Test fun `failed output cleanup after retirement cannot restart a replacement`() {
        val f = Fixture()
        val pause = NativePause()
        val calls = AtomicInteger()
        `when`(f.codec.dequeueOutputBuffer(any(), anyLong())).thenAnswer {
            if (calls.incrementAndGet() == 1) 7 else throw IllegalStateException("drain failed")
        }
        doAnswer {
            pause.block()
            throw IllegalStateException("retired during return")
        }.`when`(f.codec).releaseOutputBuffer(7, false)
        f.start()
        try {
            pause.await()
            val replacement = f.replace()
            pause.resume.countDown()
            f.join()
            assertEquals(false, f.get("decoderNeedsRestart"))
            verifyNoInteractions(replacement)
        } finally { pause.resume.countDown(); f.decoder.stop("test cleanup") }
    }

    @Test fun `failed render keeps repair pending until a later successful picture`() {
        val f = Fixture { 1_000L }
        val tracker = f.get("keyframeRepair") as KeyframeRepairTracker
        tracker.commitKeyframe(tracker.beginKeyframe(1_000))
        f.set("concealState", CorruptionConcealmentPolicy.State.CONCEALING)
        f.set("concealWindowOpenedMs", 1_000L)
        f.set("renderedThisSession", true)
        val calls = AtomicInteger()
        val repairs = AtomicInteger()
        `when`(f.codec.dequeueOutputBuffer(any(), anyLong())).thenAnswer { call ->
            when (calls.incrementAndGet()) {
                1 -> { call.getArgument<MediaCodec.BufferInfo>(0).presentationTimeUs = 1_000L; 7 }
                3 -> {
                    assertFalse("failed rendering must not consume repair evidence", tracker.keyframeDecoded)
                    assertEquals(CorruptionConcealmentPolicy.State.CONCEALING, f.get("concealState"))
                    assertEquals(0, repairs.get())
                    call.getArgument<MediaCodec.BufferInfo>(0).presentationTimeUs = 1_010L
                    8
                }
                else -> MediaCodec.INFO_TRY_AGAIN_LATER
            }
        }
        doThrow(IllegalStateException("render failed")).`when`(f.codec).releaseOutputBuffer(7, true)
        f.decoder.onKeyframeObserved = { repairs.incrementAndGet(); f.set("running", false) }
        f.start()
        try {
            f.join()
            assertTrue(tracker.keyframeDecoded)
            assertFalse(tracker.awaitingOutput)
            assertEquals(1, repairs.get())
            verify(f.codec).releaseOutputBuffer(7, false)
            verify(f.codec).releaseOutputBuffer(8, true)
        } finally { f.decoder.stop("test cleanup") }
    }
}
