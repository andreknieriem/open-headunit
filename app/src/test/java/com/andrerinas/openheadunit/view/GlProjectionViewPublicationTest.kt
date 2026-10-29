package com.andrerinas.openheadunit.view

import android.app.Application
import android.opengl.GLES20
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class GlProjectionViewPublicationTest {
    private data class Texture(val width: Int, val height: Int, val pixels: ByteArray)
    private data class DrawnFrame(val width: Int, val height: Int, val pixels: ByteArray, val scales: List<Float>)

    private class Fixture : AutoCloseable {
        val view = mock<GlProjectionView>()
        private val type = GlProjectionView::class.java.declaredClasses.single { it.simpleName == "VideoRenderer" }
        private val renderer = type.getDeclaredConstructor(GlProjectionView::class.java)
            .apply { isAccessible = true }.newInstance(view)
        private val draw = MethodHandles.privateLookupIn(type, MethodHandles.lookup()).findVirtual(type,
            "drawYuvFrame", MethodType.methodType(Void.TYPE)).bindTo(renderer)
        private val events = LinkedBlockingQueue<Runnable>()
        private val glReady = CountDownLatch(1)
        private val stop = Runnable { }
        private val failure = AtomicReference<Throwable>()
        private val textures = mutableMapOf<Int, Texture>()
        private val boundTextures = IntArray(3)
        private val scales = FloatArray(3)
        private var activeTexture = 0
        private var drawn: DrawnFrame? = null
        @Volatile var beforeUpload: (ByteBuffer) -> Unit = { }
        private val gl = Thread {
            try {
                mockStatic(GLES20::class.java) { invocation ->
                    when (invocation.method.name) {
                        "glActiveTexture" -> activeTexture = invocation.getArgument<Int>(0) - GLES20.GL_TEXTURE0
                        "glBindTexture" -> boundTextures[activeTexture] = invocation.getArgument(1)
                        "glTexImage2D", "glTexSubImage2D" -> {
                            val pixels = invocation.getArgument<ByteBuffer>(8)
                            beforeUpload(pixels)
                            val image = invocation.method.name == "glTexImage2D"
                            val width = invocation.getArgument<Int>(if (image) 3 else 4)
                            val height = invocation.getArgument<Int>(if (image) 4 else 5)
                            val bytes = ByteArray(width * height)
                            pixels.duplicate().get(bytes)
                            textures[boundTextures[activeTexture]] = Texture(width, height, bytes)
                        }
                        "glUniform1f" -> {
                            val handle = invocation.getArgument<Int>(0)
                            if (handle in 101..103) scales[handle - 101] = invocation.getArgument(1)
                        }
                        "glDrawArrays" -> {
                            // Model the shader's luminance sampling at the GLES boundary: the
                            // assertion sees the texture actually drawn, including stride cropping.
                            val texture = checkNotNull(textures[1])
                            val width = (texture.width * scales[0]).roundToInt()
                            val pixels = ByteArray(width * texture.height) { index ->
                                texture.pixels[(index / width) * texture.width + index % width]
                            }
                            drawn = DrawnFrame(width, texture.height, pixels, scales.toList())
                        }
                    }
                    Answers.RETURNS_DEFAULTS.answer(invocation)
                }.use {
                    glReady.countDown()
                    while (true) {
                        val event = events.take()
                        if (event === stop) break
                        event.run()
                    }
                }
            } catch (error: Throwable) { failure.compareAndSet(null, error) }
        }

        init {
            GlProjectionView::class.java.getDeclaredField("renderer").apply { isAccessible = true }.set(view, renderer)
            type.getDeclaredField("yuvProgram").apply { isAccessible = true }.setInt(renderer, 1)
            (type.getDeclaredField("yuvTextureIds").apply { isAccessible = true }.get(renderer) as IntArray)
                .also { it[0] = 1; it[1] = 2; it[2] = 3 }
            listOf("yTextureScaleHandle", "uTextureScaleHandle", "vTextureScaleHandle").forEachIndexed { index, name ->
                type.getDeclaredField(name).apply { isAccessible = true }.setInt(renderer, 101 + index)
            }
            doAnswer { enqueue(it.getArgument(0)); null }.whenever(view).queueEvent(any())
            doCallRealMethod().whenever(view).renderYuv420Frame(any(), any(), any(), any(), any(), any(), any(), any())
            gl.start()
            assertTrue("GL fixture initialization did not complete", glReady.await(10, TimeUnit.SECONDS))
        }

        fun enqueue(event: Runnable) { events.put(event) }

        fun submit(value: Int, width: Int = 2, height: Int = 2, stride: Int = width): Boolean {
            fun plane(width: Int, height: Int, stride: Int, value: Int) = ByteBuffer.allocateDirect(stride * height).apply {
                repeat(height) { row -> repeat(width) { column -> put(row * stride + column, value.toByte()) } }
            }
            return view.renderYuv420Frame(width, height, plane(width, height, stride, value), stride,
                plane(width / 2, height / 2, stride / 2, 128), stride / 2,
                plane(width / 2, height / 2, stride / 2, 128), stride / 2)
        }

        fun drawFrame(): DrawnFrame {
            val done = CountDownLatch(1)
            enqueue(Runnable {
                try { draw.invokeWithArguments() }
                catch (error: Throwable) { failure.compareAndSet(null, error) }
                finally { done.countDown() }
            })
            assertTrue("GL draw did not complete", done.await(5, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("GL fixture failed", it) }
            return checkNotNull(drawn)
        }

        override fun close() {
            enqueue(stop)
            gl.join(5_000)
            assertFalse("GL fixture did not stop", gl.isAlive)
            failure.get()?.let { throw AssertionError("GL fixture failed", it) }
        }
    }

    @Test fun `old running direct upload cannot erase replacement staged pixels`() {
        Fixture().use { fixture ->
            val uploadStarted = CountDownLatch(1)
            val releaseUpload = CountDownLatch(1)
            val oldReturned = CountDownLatch(1)
            val oldFailure = AtomicReference<Throwable>()
            fixture.beforeUpload = { pixels ->
                if (pixels.get(0).toInt() == 17) {
                    uploadStarted.countDown()
                    check(releaseUpload.await(5, TimeUnit.SECONDS))
                }
            }
            val oldOwner = Thread {
                try { assertTrue(fixture.submit(17, width = 4, height = 4, stride = 6)) }
                catch (error: Throwable) { oldFailure.set(error) }
                finally { oldReturned.countDown() }
            }
            doAnswer {
                if (Thread.currentThread() === oldOwner) {
                    check(uploadStarted.await(5, TimeUnit.SECONDS))
                }
                null
            }.whenever(fixture.view).requestRender()
            try {
                oldOwner.start()
                assertTrue(uploadStarted.await(5, TimeUnit.SECONDS))
                // The old decoder has been detached, but its borrowed planes stay alive until
                // this native upload returns. The replacement times out pending and stages B.
                oldOwner.interrupt()
                assertTrue(fixture.submit(85, stride = 4))
                assertEquals("old owner's native borrow ended before upload completed", 1L, oldReturned.count)
            } finally {
                releaseUpload.countDown()
                oldOwner.join(5_000)
            }
            oldFailure.get()?.let { throw AssertionError("old owner failed", it) }
            assertEquals(0L, oldReturned.count)
            val displayed = fixture.drawFrame()
            assertArrayEquals("new owner was accepted, but the draw sampled old owner's pixels",
                ByteArray(4) { 85 }, displayed.pixels)
            assertEquals(2, displayed.width)
            assertEquals(2, displayed.height)
            assertEquals(listOf(1f, 1f, 1f), displayed.scales)
        }
    }

    @Test fun `late direct fallback cannot become newer than replacement direct pixels`() {
        assertDelayedSubmissionDoesNotReplaceNewerFrame(failOldDirect = true)
    }

    @Test fun `late queued direct cannot overwrite replacement direct pixels`() {
        assertDelayedSubmissionDoesNotReplaceNewerFrame(failOldDirect = false)
    }

    private fun assertDelayedSubmissionDoesNotReplaceNewerFrame(failOldDirect: Boolean) {
        Fixture().use { fixture ->
            val oldQueueEntered = CountDownLatch(1)
            val releaseOldQueue = CountDownLatch(1)
            val oldFailure = AtomicReference<Throwable>()
            lateinit var oldOwner: Thread
            doAnswer { invocation ->
                if (Thread.currentThread() === oldOwner) {
                    oldQueueEntered.countDown()
                    check(releaseOldQueue.await(5, TimeUnit.SECONDS))
                    if (failOldDirect) throw IllegalStateException("old direct queue became unavailable")
                }
                val event = invocation.getArgument<Runnable>(0)
                val uploaded = CountDownLatch(1)
                fixture.enqueue(Runnable {
                    try { event.run() }
                    finally { uploaded.countDown() }
                })
                // This scenario needs B to use the direct path, not to hit the pending timeout
                // while the test JVM initializes GLES mocking or schedules the GL worker.
                check(uploaded.await(5, TimeUnit.SECONDS))
                null
            }.whenever(fixture.view).queueEvent(any())
            oldOwner = Thread {
                try { assertTrue(fixture.submit(17, width = 4, height = 4, stride = 6)) }
                catch (error: Throwable) { oldFailure.set(error) }
            }
            try {
                oldOwner.start()
                assertTrue(oldQueueEntered.await(5, TimeUnit.SECONDS))
                assertTrue(fixture.submit(85, stride = 4))
                assertEquals("replacement must be direct before old work resumes",
                    listOf(0.5f, 0.5f, 0.5f), fixture.drawFrame().scales)
            } finally {
                releaseOldQueue.countDown()
                oldOwner.join(5_000)
            }
            assertFalse(oldOwner.isAlive)
            oldFailure.get()?.let { throw AssertionError("old owner failed", it) }
            val displayed = fixture.drawFrame()
            assertArrayEquals("delayed work must retain its original submission order", ByteArray(4) { 85 }, displayed.pixels)
            assertEquals(2, displayed.width)
            assertEquals(2, displayed.height)
            assertEquals(listOf(0.5f, 0.5f, 0.5f), displayed.scales)
        }
    }
}
