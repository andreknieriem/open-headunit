package com.andrerinas.openheadunit.view

import android.app.Application
import android.opengl.GLES20
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class GlProjectionViewBorrowedFrameTest {
    private class Fixture {
        val view = mock<GlProjectionView>()
        val type = GlProjectionView::class.java.declaredClasses.single { it.simpleName == "VideoRenderer" }
        val renderer = type.getDeclaredConstructor(GlProjectionView::class.java)
            .apply { isAccessible = true }.newInstance(view)
        // Resolve only this method. Reflection enumerates unrelated Renderer signatures and
        // asks the host JVM for GL10, which Robolectric deliberately delegates outside its SDK.
        val render = MethodHandles.privateLookupIn(type, MethodHandles.lookup()).findVirtual(type,
            "renderYuv420FrameDirect", MethodType.methodType(Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, ByteBuffer::class.java,
                Int::class.javaPrimitiveType, ByteBuffer::class.java, Int::class.javaPrimitiveType,
                ByteBuffer::class.java, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)).bindTo(renderer)
        val reserveSequence = MethodHandles.privateLookupIn(type, MethodHandles.lookup()).findVirtual(type,
            "reserveFrameSequence", MethodType.methodType(Long::class.javaPrimitiveType)).bindTo(renderer)

        init {
            type.getDeclaredField("yuvProgram").apply { isAccessible = true }.setInt(renderer, 1)
            (type.getDeclaredField("yuvTextureIds").apply { isAccessible = true }.get(renderer) as IntArray)
                .also { it[0] = 1; it[1] = 2; it[2] = 3 }
        }

        fun submit(): Boolean = render.invokeWithArguments(2, 2, ByteBuffer.allocateDirect(4), 2,
            ByteBuffer.allocateDirect(1), 1, ByteBuffer.allocateDirect(1), 1, reserveSequence.invokeWithArguments()) as Boolean
        fun uploadedWidth(): Int = type.getDeclaredField("yuvWidth")
            .apply { isAccessible = true }.getInt(renderer)
    }

    @Test fun `interrupted pending upload cancels borrowed planes and restores interrupt`() {
        val fixture = Fixture()
        val queued = AtomicReference<Runnable>()
        doAnswer { queued.set(it.getArgument(0)); null }.whenever(fixture.view).queueEvent(any())
        doAnswer { Thread.currentThread().interrupt(); null }.whenever(fixture.view).requestRender()
        try {
            assertFalse(fixture.submit())
            val interrupted = Thread.currentThread().isInterrupted
            Thread.interrupted()
            // Returning to JNI permits av_frame_unref/reuse; a later GL queue drain must not touch it.
            queued.get().run()
            assertEquals("cancelled runnable consumed borrowed planes after JNI ownership ended", 0, fixture.uploadedWidth())
            assertTrue("teardown interrupt must remain observable by the decoder worker", interrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun `interrupted running upload retains borrowed planes until completion and restores interrupt`() {
        val fixture = Fixture()
        val uploadStarted = CountDownLatch(1)
        val allowUpload = CountDownLatch(1)
        val uploadFinished = CountDownLatch(1)
        val callerReturned = CountDownLatch(1)
        val accepted = AtomicBoolean()
        val restored = AtomicBoolean()
        val prematureReturn = AtomicBoolean()
        val failure = AtomicReference<Throwable>()
        var gl: Thread? = null
        doAnswer { invocation ->
            val runnable = invocation.getArgument<Runnable>(0)
            gl = Thread {
                try {
                    mockStatic(GLES20::class.java).use { graphics ->
                        graphics.`when`<Unit> { GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1) }.thenAnswer {
                            uploadStarted.countDown()
                            check(allowUpload.await(5, TimeUnit.SECONDS))
                            null
                        }
                        runnable.run()
                    }
                } catch (error: Throwable) { failure.compareAndSet(null, error) }
                finally { uploadFinished.countDown() }
            }.apply { start() }
            null
        }.whenever(fixture.view).queueEvent(any())
        doAnswer {
            check(uploadStarted.await(5, TimeUnit.SECONDS))
            Thread.currentThread().interrupt()
            null
        }.whenever(fixture.view).requestRender()
        val caller = Thread {
            try {
                accepted.set(fixture.submit())
                restored.set(Thread.currentThread().isInterrupted)
                prematureReturn.set(allowUpload.count != 0L)
            } catch (error: Throwable) { failure.compareAndSet(null, error) }
            finally { callerReturned.countDown() }
        }
        try {
            caller.start()
            assertTrue(uploadStarted.await(5, TimeUnit.SECONDS))
            assertFalse("JNI owner returned while GL still read its native planes",
                callerReturned.await(100, TimeUnit.MILLISECONDS))
        } finally {
            allowUpload.countDown()
            caller.join(5_000)
            gl?.join(5_000)
        }
        failure.get()?.let { throw AssertionError("upload fixture failed", it) }
        assertEquals(0L, callerReturned.count)
        assertEquals(0L, uploadFinished.count)
        assertFalse(prematureReturn.get())
        assertTrue(accepted.get())
        assertTrue(restored.get())
    }
}
