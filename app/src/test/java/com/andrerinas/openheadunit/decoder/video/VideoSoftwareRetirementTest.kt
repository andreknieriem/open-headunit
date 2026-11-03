package com.andrerinas.openheadunit.decoder.video

import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class VideoSoftwareRetirementTest {
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }

    @Test fun `Stop detaches blocked software owner and replacement progresses before old native release`() {
        val decoder = VideoDecoder(mock<Settings> { on { fpsLimit } doReturn 30 }, elapsedRealtime = { 1_000L })
        val old = mock<FfmpegVideoDecoder>()
        val replacement = mock<FfmpegVideoDecoder>()
        val borrowing = CountDownLatch(1)
        val finishBorrow = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val replacementPresented = CountDownLatch(1)
        val oldNativeMonitor = Any()
        val oldReleases = AtomicInteger()
        val replacementReleases = AtomicInteger()
        val failure = AtomicReference<Throwable>()
        val workers = mutableListOf<Thread>()
        whenever(old.decode(any(), any(), any(), any())).thenAnswer {
            // Model the real synchronized FFmpeg decode while its GL callback borrows AVFrame.
            synchronized(oldNativeMonitor) {
                borrowing.countDown()
                var interrupted = false
                while (finishBorrow.count != 0L) {
                    try { check(finishBorrow.await(5, TimeUnit.SECONDS)) }
                    catch (_: InterruptedException) { interrupted = true }
                }
                if (interrupted) Thread.currentThread().interrupt()
                1
            }
        }
        doAnswer {
            synchronized(oldNativeMonitor) {
                assertEquals("native planes freed while GL retained its borrow", 0L, finishBorrow.count)
                oldReleases.incrementAndGet()
            }
            null
        }.whenever(old).stop()
        whenever(replacement.decode(any(), any(), any(), any())).thenReturn(1)
        doAnswer { replacementReleases.incrementAndGet(); null }.whenever(replacement).stop()

        fun start(backend: FfmpegVideoDecoder): Thread {
            val timing = GalVideoOutputTiming(null, 0)
            val generation = field(decoder, "decoderGeneration").getLong(decoder)
            @Suppress("UNCHECKED_CAST")
            val queue = field(decoder, "frameQueue").get(decoder) as ArrayBlockingQueue<Any>
            val loop = VideoDecoder::class.java.getDeclaredMethod("softwareFeedThreadLoop",
                FfmpegVideoDecoder::class.java, GalVideoOutputTiming::class.java,
                Long::class.javaPrimitiveType, ArrayBlockingQueue::class.java).apply { isAccessible = true }
            val worker = Thread {
                try { loop.invoke(decoder, backend, timing, generation, queue) }
                catch (error: Throwable) { failure.compareAndSet(null, error.cause ?: error) }
            }
            field(decoder, "softwareVideoDecoder").set(decoder, backend)
            field(decoder, "outputTiming").set(decoder, timing)
            field(decoder, "running").set(decoder, true)
            field(decoder, "feedThread").set(decoder, worker)
            val bytes = byteArrayOf(0, 0, 1, 0x65, 0x42)
            val frame = VideoDecoder::class.java.getDeclaredMethod("prepareFrame", ByteArray::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
                .invoke(decoder, bytes, 0, bytes.size)
            queue.put(frame)
            workers.add(worker)
            worker.start()
            return worker
        }

        val reader = Thread {
            try { decoder.stop("test retired software surface") }
            catch (error: Throwable) { failure.compareAndSet(null, error) }
            finally { stopped.countDown() }
        }
        try {
            val oldWorker = start(old)
            assertTrue(borrowing.await(3, TimeUnit.SECONDS))
            reader.start()
            assertTrue("reader waited for native/GL ownership after its bounded worker join",
                stopped.await(2, TimeUnit.SECONDS))
            assertEquals(0, oldReleases.get())
            decoder.onFirstFrameListener = { replacementPresented.countDown() }
            start(replacement)
            assertTrue("replacement output could not progress while old GL upload remained blocked",
                replacementPresented.await(3, TimeUnit.SECONDS))
            assertEquals(1L, decoder.framesRenderedThisSession)
            finishBorrow.countDown()
            oldWorker.join(3_000)
            assertFalse(oldWorker.isAlive)
            assertEquals(1, oldReleases.get())
            assertEquals("late old decode published into replacement", 1L, decoder.framesRenderedThisSession)
        } finally {
            finishBorrow.countDown()
            reader.join(3_000)
            decoder.stop("test cleanup")
            workers.forEach { it.join(3_000); assertFalse(it.isAlive) }
        }
        failure.get()?.let { throw AssertionError("software worker failed", it) }
        assertEquals(1, oldReleases.get())
        assertEquals(1, replacementReleases.get())
    }

    private fun runDimensionFrame(decoder: VideoDecoder) {
        val backend = mock<FfmpegVideoDecoder>()
        whenever(backend.decode(any(), any(), any(), any())).thenReturn(1)
        whenever(backend.outputWidth).thenReturn(800)
        whenever(backend.outputHeight).thenReturn(480)
        val generation = field(decoder, "decoderGeneration").getLong(decoder)
        @Suppress("UNCHECKED_CAST")
        val queue = field(decoder, "frameQueue").get(decoder) as ArrayBlockingQueue<Any>
        val loop = VideoDecoder::class.java.getDeclaredMethod("softwareFeedThreadLoop",
            FfmpegVideoDecoder::class.java, GalVideoOutputTiming::class.java,
            Long::class.javaPrimitiveType, ArrayBlockingQueue::class.java).apply { isAccessible = true }
        val failure = AtomicReference<Throwable>()
        val worker = Thread {
            try { loop.invoke(decoder, backend, GalVideoOutputTiming(null, 0), generation, queue) }
            catch (error: Throwable) { failure.set(error.cause ?: error) }
        }
        field(decoder, "softwareVideoDecoder").set(decoder, backend)
        field(decoder, "running").set(decoder, true)
        field(decoder, "feedThread").set(decoder, worker)
        val bytes = byteArrayOf(0, 0, 1, 0x65, 0x42)
        val frame = VideoDecoder::class.java.getDeclaredMethod("prepareFrame", ByteArray::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(decoder, bytes, 0, bytes.size)
        queue.put(frame)
        try {
            worker.start()
            worker.join(3_000)
            assertFalse("dimension notification did not complete", worker.isAlive)
        } finally {
            decoder.stop("software dimensions test cleanup")
            worker.join(3_000)
        }
        failure.get()?.let { throw AssertionError("software dimension worker failed", it) }
    }

    @Test fun `software dimensions keep the listener captured before an earlier callback replaces it`() {
        val decoder = VideoDecoder(mock<Settings> { on { fpsLimit } doReturn 30 }, elapsedRealtime = { 1_000L })
        val oldCalls = AtomicInteger()
        val replacementCalls = AtomicInteger()
        val originalStillCurrent = AtomicReference<Boolean>()
        val replacement = object : VideoDimensionsListener {
            override fun onVideoDimensionsChanged(dimensions: VideoDimensions) {
                replacementCalls.incrementAndGet()
                field(decoder, "running").set(decoder, false)
            }
        }
        decoder.dimensionsListener = object : VideoDimensionsListener {
            override fun onVideoDimensionsChanged(dimensions: VideoDimensions) {
                oldCalls.incrementAndGet()
                originalStillCurrent.set(dimensions.isCurrent())
                field(decoder, "running").set(decoder, false)
            }
        }
        // First-frame and dimensions notifications are collected under the same output owner.
        // Dispatching the first may replace the listener before the dimensions callback starts.
        decoder.onFirstFrameListener = { decoder.dimensionsListener = replacement }
        runDimensionFrame(decoder)
        assertEquals(1, oldCalls.get())
        assertEquals(0, replacementCalls.get())
        assertEquals(false, originalStillCurrent.get())
    }
}
