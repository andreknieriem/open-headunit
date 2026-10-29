package com.andrerinas.openheadunit.decoder.video

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaCodec
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class VideoFeedRetirementTest {
    private class Fixture : AutoCloseable {
        val decoder: VideoDecoder
        val failure = AtomicReference<Throwable?>()
        val workers = mutableListOf<Thread>()
        init {
            val context = mock(Context::class.java)
            val preferences = mock(SharedPreferences::class.java)
            `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences)
            `when`(preferences.getInt(anyString(), anyInt())).thenAnswer { it.arguments[1] }
            decoder = VideoDecoder(Settings(context), elapsedRealtime = {
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime())
            })
        }
        fun set(name: String, value: Any?) = VideoDecoder::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(decoder, value)
        fun get(name: String): Any? = VideoDecoder::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(decoder)
        fun prepare(bytes: ByteArray): Any = VideoDecoder::class.java.getDeclaredMethod(
            "prepareFrame", ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        ).apply { isAccessible = true }.invoke(decoder, bytes, 0, bytes.size)
        fun offer(frame: Any) {
            VideoDecoder::class.java.getDeclaredMethod("offerWithBackpressure", frame.javaClass)
                .apply { isAccessible = true }.invoke(decoder, frame)
        }
        fun worker(method: String, field: String): Thread {
            val loop = VideoDecoder::class.java.getDeclaredMethod(method).apply { isAccessible = true }
            return Thread {
                mockStatic(android.util.Log::class.java).use { logs ->
                    logs.`when`<String> { android.util.Log.getStackTraceString(any(Throwable::class.java)) }
                        .thenAnswer { it.getArgument<Throwable>(0).toString() }
                    try { loop.invoke(decoder) } catch (t: Throwable) { failure.set(t.cause ?: t) }
                }
            }.also { set(field, it); workers.add(it); it.start() }
        }
        fun start(codec: MediaCodec, output: Boolean = false, beforeWorkers: () -> Unit = {}): Thread {
            val input = ByteBuffer.allocate(4096)
            `when`(codec.dequeueInputBuffer(anyLong())).thenReturn(0)
            `when`(codec.getInputBuffer(anyInt())).thenReturn(input)
            beforeWorkers()
            set("codec", codec)
            set("inputBuffers", arrayOf(input)) // API16 and host-stub branch
            set("codecBufferInfo", MediaCodec.BufferInfo())
            set("startTime", System.nanoTime())
            set("running", true)
            if (output) worker("outputThreadLoop", "outputThread")
            return worker("feedThreadLoop", "feedThread")
        }
        fun join(worker: Thread) {
            worker.join(3000)
            assertFalse("retired worker must exit", worker.isAlive)
            failure.get()?.let { throw AssertionError("worker failed", it) }
        }
        override fun close() {
            decoder.stop("test cleanup")
            for (worker in workers) join(worker)
            decoder.awaitRetirementForTest()
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
            check(resume.count == 0L)
        }
        fun await() = assertTrue(entered.await(3, TimeUnit.SECONDS))
    }
    private val idr = byteArrayOf(0, 0, 1, 0x65, 0x42)

    @Test fun `decode owns offset frame bytes before transport storage is reused`() {
        Fixture().use { f ->
            val codec = mock(MediaCodec::class.java)
            val pause = NativePause()
            val submitted = LinkedBlockingQueue<ByteArray>()
            f.start(codec) {
                `when`(codec.dequeueInputBuffer(anyLong())).thenAnswer { pause.block(); 0 }
                doAnswer { call ->
                    val input = (f.get("inputBuffers") as Array<*>)[0] as ByteBuffer
                    submitted.add(ByteArray(call.getArgument(2)).also { input.duplicate().get(it) })
                    null
                }.`when`(codec).queueInputBuffer(anyInt(), anyInt(), anyInt(), anyLong(), anyInt())
            }
            val transport = ByteArray(64) { 0x7f }
            val next = byteArrayOf(0, 0, 0, 1, 0x41, 0x33, 0x44)
            try {
                idr.copyInto(transport, 10)
                f.decoder.decode(transport, 10, idr.size, false, VideoDecoder.CodecType.H264)
                pause.await()
                transport.fill(0x55)
                next.copyInto(transport, 2)
                f.decoder.decode(transport, 2, next.size, false, VideoDecoder.CodecType.H264)
                transport.fill(0x66)
                f.decoder.decode(transport, transport.size, 0, false, VideoDecoder.CodecType.H264)
                pause.resume.countDown()
                assertArrayEquals(idr, submitted.poll(3, TimeUnit.SECONDS))
                assertArrayEquals(next, submitted.poll(3, TimeUnit.SECONDS))
                assertNull("empty input must not submit the old backing bytes", submitted.poll(100, TimeUnit.MILLISECONDS))
            } finally { pause.resume.countDown() }
        }
    }

    @Test fun `late queue completion cannot publish a keyframe into the replacement`() {
        Fixture().use { f ->
            val old = mock(MediaCodec::class.java)
            val pause = NativePause()
            doAnswer { pause.block(); null }.`when`(old)
                .queueInputBuffer(anyInt(), anyInt(), anyInt(), anyLong(), anyInt())
            val oldWorker = f.start(old)
            try {
                f.offer(f.prepare(idr))
                pause.await()
                f.decoder.stop("test replacement")
                val replacement = mock(MediaCodec::class.java)
                f.start(replacement)
                pause.resume.countDown()
                f.join(oldWorker)
                val tracker = f.get("keyframeRepair") as KeyframeRepairTracker
                assertFalse("retired input must not register a replacement keyframe", tracker.awaitingOutput)
                assertFalse(tracker.keyframeDecoded)
                assertEquals(0L, f.get("lastKeyframeFedMs"))
                assertEquals(0L, f.get("framesFed"))
                verify(replacement, never()).queueInputBuffer(anyInt(), anyInt(), anyInt(), anyLong(), anyInt())
            } finally { pause.resume.countDown() }
        }
    }

    @Test fun `an old prepared frame cannot enter the replacement codec`() {
        Fixture().use { f ->
            f.start(mock(MediaCodec::class.java))
            val oldFrame = f.prepare(idr)
            f.decoder.stop("test replacement")
            val replacement = mock(MediaCodec::class.java)
            val submitted = LinkedBlockingQueue<ByteArray>()
            doAnswer { call ->
                val input = (f.get("inputBuffers") as Array<*>)[0] as ByteBuffer
                submitted.add(ByteArray(call.getArgument(2)).also { input.duplicate().get(it) })
                null
            }.`when`(replacement).queueInputBuffer(anyInt(), anyInt(), anyInt(), anyLong(), anyInt())
            f.start(replacement)
            f.offer(oldFrame)
            val current = byteArrayOf(0, 0, 1, 0x41, 0x55)
            f.offer(f.prepare(current))
            assertArrayEquals(current, submitted.poll(3, TimeUnit.SECONDS))
            assertNull("the old frame must not follow the new one either", submitted.poll(100, TimeUnit.MILLISECONDS))
        }
    }

    @Test fun `output before queue returns still reports repair when the stream goes idle`() {
        Fixture().use { f ->
            val codec = mock(MediaCodec::class.java)
            val outputs = LinkedBlockingQueue<Long>()
            val released = CountDownLatch(1)
            val returned = NativePause()
            val repaired = CountDownLatch(1)
            f.decoder.onKeyframeObserved = { repaired.countDown() }
            doAnswer { call ->
                if (call.getArgument<Int>(2) > 0) {
                    outputs.add(call.getArgument(3))
                    returned.block()
                }
                null
            }.`when`(codec).queueInputBuffer(anyInt(), anyInt(), anyInt(), anyLong(), anyInt())
            `when`(codec.dequeueOutputBuffer(any(), anyLong())).thenAnswer { call ->
                val pts = outputs.poll(call.getArgument(1), TimeUnit.MICROSECONDS)
                if (pts == null) MediaCodec.INFO_TRY_AGAIN_LATER else {
                    val info = call.getArgument<MediaCodec.BufferInfo>(0)
                    info.presentationTimeUs = pts
                    info.size = 1
                    7
                }
            }
            doAnswer { released.countDown(); null }.`when`(codec).releaseOutputBuffer(7, true)
            f.start(codec, output = true)
            try {
                f.offer(f.prepare(idr))
                returned.await()
                assertTrue("the output must overtake queue completion", released.await(3, TimeUnit.SECONDS))
                assertEquals("queue acceptance is still unresolved", 1L, repaired.count)
                returned.resume.countDown()
                assertTrue("idle output ticks must finish the repair", repaired.await(2, TimeUnit.SECONDS))
                val tracker = f.get("keyframeRepair") as KeyframeRepairTracker
                assertFalse(tracker.awaitingOutput)
                assertTrue(tracker.keyframeDecoded)
            } finally { returned.resume.countDown() }
        }
    }
    @Test fun `a retired poll cannot consume a replacement frame`() {
        Fixture().use { f ->
            @Suppress("UNCHECKED_CAST")
            val oldQueue = spy(f.get("frameQueue") as java.util.concurrent.ArrayBlockingQueue<Any>)
            val oldPoll = NativePause()
            val replacementPoll = NativePause()
            val polls = java.util.concurrent.atomic.AtomicInteger()
            doAnswer { call ->
                if (polls.incrementAndGet() == 1) oldPoll.block() else replacementPoll.block()
                call.callRealMethod()
            }.`when`(oldQueue).poll(anyLong(), any(TimeUnit::class.java))
            f.set("frameQueue", oldQueue)
            val oldWorker = f.start(mock(MediaCodec::class.java))
            try {
                oldPoll.await()
                f.decoder.stop("test replacement")
                val replacement = mock(MediaCodec::class.java)
                val submitted = LinkedBlockingQueue<Int>()
                doAnswer { call -> submitted.add(call.getArgument(2)); null }.`when`(replacement)
                    .queueInputBuffer(anyInt(), anyInt(), anyInt(), anyLong(), anyInt())
                f.start(replacement)
                f.offer(f.prepare(idr))
                oldPoll.resume.countDown()
                f.join(oldWorker)
                replacementPoll.resume.countDown()
                assertEquals("only the replacement worker may consume its input", idr.size,
                    submitted.poll(3, TimeUnit.SECONDS))
            } finally {
                oldPoll.resume.countDown()
                replacementPoll.resume.countDown()
            }
        }
    }

    @Test fun `an older rendered keyframe survives a failed newer submission without more output`() {
        Fixture().use { f ->
            val codec = mock(MediaCodec::class.java)
            val firstPts = java.util.concurrent.atomic.AtomicLong()
            val inputs = java.util.concurrent.atomic.AtomicInteger()
            val newer = NativePause()
            val outputs = LinkedBlockingQueue<Long>()
            val released = CountDownLatch(1)
            val repaired = CountDownLatch(1)
            f.decoder.onKeyframeObserved = { repaired.countDown() }
            doAnswer { call ->
                if (call.getArgument<Int>(2) > 0) {
                    if (inputs.incrementAndGet() == 1) firstPts.set(call.getArgument(3))
                    else {
                        newer.block()
                        throw IllegalStateException("newer input rejected")
                    }
                }
                null
            }.`when`(codec).queueInputBuffer(anyInt(), anyInt(), anyInt(), anyLong(), anyInt())
            `when`(codec.dequeueOutputBuffer(any(), anyLong())).thenAnswer { call ->
                val pts = outputs.poll(call.getArgument(1), TimeUnit.MICROSECONDS)
                if (pts == null) MediaCodec.INFO_TRY_AGAIN_LATER else {
                    call.getArgument<MediaCodec.BufferInfo>(0).apply {
                        presentationTimeUs = pts
                        size = 1
                    }
                    7
                }
            }
            doAnswer { released.countDown(); null }.`when`(codec).releaseOutputBuffer(7, true)
            f.start(codec, output = true)
            try {
                f.offer(f.prepare(idr))
                f.offer(f.prepare(idr))
                newer.await()
                f.set("concealWindowOpenedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime()))
                f.set("renderedThisSession", true)
                f.set("concealState", CorruptionConcealmentPolicy.State.CONCEALING)
                outputs.add(firstPts.get())
                assertTrue(released.await(3, TimeUnit.SECONDS))
                assertEquals(1L, repaired.count)
                newer.resume.countDown()
                assertTrue("accepted older picture must finish repair on an idle tick",
                    repaired.await(2, TimeUnit.SECONDS))
                assertTrue((f.get("keyframeRepair") as KeyframeRepairTracker).keyframeDecoded)
                assertEquals(CorruptionConcealmentPolicy.State.ARMED, f.get("concealState"))
            } finally { newer.resume.countDown() }
        }
    }

    @Test fun `lost input slot requests restart while a successful return does not`() {
        for (returnFails in listOf(false, true)) Fixture().use { f ->
            val codec = mock(MediaCodec::class.java)
            val dropped = CountDownLatch(1)
            val restartAtDrop = AtomicReference<Any?>()
            f.set("lastFrameRenderedMs", 1L)
            f.decoder.onFrameDropped = {
                restartAtDrop.set(f.get("decoderRestartReason"))
                dropped.countDown()
            }
            doAnswer { call ->
                if (call.getArgument<Int>(2) > 0 || returnFails) {
                    throw IllegalStateException("input ownership not transferred")
                }
                null
            }.`when`(codec).queueInputBuffer(anyInt(), anyInt(), anyInt(), anyLong(), anyInt())
            f.start(codec)
            f.offer(f.prepare(idr))
            assertTrue(dropped.await(3, TimeUnit.SECONDS))
            assertEquals(if (returnFails) "input_buffer_return_failed" else null, restartAtDrop.get())
            assertEquals(returnFails, f.get("decoderNeedsRestart"))
            assertFalse((f.get("keyframeRepair") as KeyframeRepairTracker).awaitingOutput)
            if (returnFails) {
                val errors = mutableListOf<String>()
                f.decoder.onDecoderError = { errors.add(it) }
                f.decoder.decode(byteArrayOf(), 0, 0, false, VideoDecoder.CodecType.H264)
                assertEquals(listOf("input_buffer_return_failed"), errors)
                assertEquals(false, f.get("decoderNeedsRestart"))
                f.decoder.awaitRetirementForTest()
                verify(codec).stop()
                verify(codec).release()
            }
        }
    }

    @Test fun `a retired input return failure cannot restart the replacement`() {
        Fixture().use { f ->
            val old = mock(MediaCodec::class.java)
            val returning = NativePause()
            doAnswer { call ->
                if (call.getArgument<Int>(2) == 0) returning.block()
                throw IllegalStateException("input ownership not transferred")
            }.`when`(old).queueInputBuffer(anyInt(), anyInt(), anyInt(), anyLong(), anyInt())
            val worker = f.start(old)
            try {
                f.offer(f.prepare(idr))
                returning.await()
                f.decoder.stop("test replacement")
                f.start(mock(MediaCodec::class.java))
                returning.resume.countDown()
                f.join(worker)
                assertEquals(false, f.get("decoderNeedsRestart"))
                assertNull(f.get("decoderRestartReason"))
                assertEquals(0L, f.get("framesFed"))
            } finally { returning.resume.countDown() }
        }
    }
    @Test fun `retirement waits for both native workers without blocking surface callers or allocating replacements`() {
        for (pauseQueue in listOf(false, true)) Fixture().use { f ->
            val codec = mock(MediaCodec::class.java)
            val input = NativePause()
            val output = NativePause()
            val disposed = CountDownLatch(1)
            val caller = Thread.currentThread()
            val surface = mock(android.view.Surface::class.java)
            `when`(surface.isValid).thenReturn(true)
            f.decoder.setSurface(surface)
            f.set("mWidth", 800)
            f.set("mHeight", 480)
            `when`(codec.dequeueOutputBuffer(any(), anyLong())).thenAnswer {
                output.block()
                MediaCodec.INFO_TRY_AGAIN_LATER
            }
            doAnswer {
                assertNotSame("native disposal must not run on the surface caller", caller, Thread.currentThread())
                assertFalse(Thread.holdsLock(f.decoder))
                assertFalse(Thread.holdsLock(f.get("outputPublicationLock")))
                assertEquals(0L, input.resume.count)
                assertTrue("both actual loops must have exited before release", f.workers.none { it.isAlive })
                assertEquals(0L, output.resume.count)
                disposed.countDown()
                null
            }.`when`(codec).release()
            // Complete all stubbing before either worker can invoke this same mock. Mockito's
            // when(...).thenAnswer(...) can bind the answer to a concurrent output invocation.
            val worker = f.start(codec, output = true) {
                if (pauseQueue) {
                    doAnswer { input.block(); null }.`when`(codec)
                        .queueInputBuffer(anyInt(), anyInt(), anyInt(), anyLong(), anyInt())
                } else {
                    `when`(codec.dequeueInputBuffer(anyLong())).thenAnswer { input.block(); 0 }
                }
            }
            try {
                f.offer(f.prepare(idr))
                input.await()
                output.await()
                val stopped = CountDownLatch(1)
                val stopper = Thread {
                    f.decoder.detachSurfaceIfCurrent(surface, DecoderStopPolicy.REASON_SURFACE_DESTROYED)
                    stopped.countDown()
                }
                stopper.start()
                assertTrue("surface retirement must not join native workers", stopped.await(250, TimeUnit.MILLISECONDS))
                stopper.join(3000)
                mockStatic(MediaCodec::class.java).use { factories ->
                    repeat(20) {
                        val replacement = mock(android.view.Surface::class.java)
                        `when`(replacement.isValid).thenReturn(true)
                        f.decoder.setSurface(replacement)
                        f.decoder.decode(idr, 0, idr.size, false, VideoDecoder.CodecType.H264)
                        f.decoder.stop("repeated replacement")
                    }
                    factories.verifyNoInteractions()
                }
                // Exceed the old pair of 500ms joins while both native calls ignore interruption.
                assertFalse(disposed.await(1100, TimeUnit.MILLISECONDS))
                verify(codec, never()).stop()
                verify(codec, never()).release()
                input.resume.countDown()
                f.join(worker)
                assertFalse("output still owns the component", disposed.await(100, TimeUnit.MILLISECONDS))
                output.resume.countDown()
                f.decoder.awaitRetirementForTest()
                assertEquals(0L, disposed.count)
                verify(codec).stop()
                verify(codec).release()
                assertEquals(false, f.get("decoderPermanentlyFailed"))
            } finally {
                input.resume.countDown()
                output.resume.countDown()
            }
        }
    }
}
