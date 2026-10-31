package com.andrerinas.openheadunit.decoder.video

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.andrerinas.openheadunit.decoder.video.VideoDecoder.CodecType
import com.andrerinas.openheadunit.decoder.video.preflight.AnnexBAccessUnits
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real session selection/recovery; platform output is scripted, not the decoder's decisions. */
class VideoCodecSessionTest {
    private class Fixture(preference: CodecType, forceSoftware: Boolean = false, lowLatency: Boolean = false) : AutoCloseable {
        val decoder: VideoDecoder
        // An invalid platform target exposes the initialization gate without creating a codec,
        // touching a real Surface, or depending on a packaged FFmpeg library.
        val surface = mock(Surface::class.java)
        val errors = mutableListOf<String>()
        private val software = forceSoftware

        init {
            val context = mock(Context::class.java)
            val preferences = mock(SharedPreferences::class.java)
            `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences)
            `when`(preferences.getInt(anyString(), anyInt())).thenAnswer { it.arguments[1] }
            `when`(preferences.getString(anyString(), anyString())).thenAnswer { it.arguments[1] }
            `when`(preferences.getString(eq("video-codec"), anyString())).thenReturn(preference.settingsValue)
            `when`(preferences.getBoolean(eq("force-software-decoding"), anyBoolean())).thenReturn(forceSoftware)
            `when`(preferences.getBoolean(eq("debug-video-low-latency"), anyBoolean())).thenReturn(lowLatency)
            `when`(preferences.getInt(eq("software-video-decoder"), anyInt()))
                .thenReturn(Settings.SoftwareVideoDecoder.BUNDLED_FFMPEG.value)
            // Host JVMs have neither Android scheduling nor FFmpeg JNI. Replace only these
            // platform boundaries; startup, retirement, replay and worker loops remain real.
            decoder = VideoDecoder(
                Settings(context),
                elapsedRealtime = { 1_000L },
                setThreadPriority = {},
                bundledDecoderAvailable = { true },
            )
            decoder.onDecoderError = { errors.add(it) }
            decoder.setSurface(surface)
            clearInvocations(surface)
        }

        fun decode(packet: ByteArray, announced: CodecType) =
            decoder.decode(packet, 0, packet.size, software, announced)

        fun beginPinnedSession(type: CodecType, packet: ByteArray) {
            decode(packet, type)
            assertSelected(type)
            // Stand in only for successful native configuration. Selection was performed by
            // decode(), and failure/render/restart state is exclusively driven by real operations.
            setOwner("codecTypePinned", true)
            clearInvocations(surface)
        }

        private fun setOwner(name: String, value: Any) {
            VideoDecoder::class.java.getDeclaredField(name).apply { isAccessible = true }.set(decoder, value)
        }


        fun bundledOutput(vararg results: Int): FfmpegVideoDecoder {
            // Script only the native decode boundary. Session publication, watchdog evidence and
            // recovery below still run through VideoDecoder.decode(), not private render helpers.
            val owner = mockStatic(android.util.Log::class.java).use { logs ->
                logs.`when`<String> { android.util.Log.getStackTraceString(any(Throwable::class.java)) }
                    .thenReturn("native library unavailable on the host JVM")
                mock(FfmpegVideoDecoder::class.java)
            }
            `when`(owner.decode(org.mockito.kotlin.any(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(results.first(), *results.drop(1).toTypedArray())
            setOwner("softwareVideoDecoder", owner)
            return owner
        }
        fun startInputOwner(owner: MediaCodec) {
            val loop = VideoDecoder::class.java.getDeclaredMethod("feedThreadLoop").apply { isAccessible = true }
            val worker = Thread { loop.invoke(decoder) }
            setOwner("codec", owner)
            setOwner("running", true)
            setOwner("feedThread", worker)
            worker.start()
        }

        fun failOutputOwner(renderFirst: Boolean = false): MediaCodec {
            val codec = mock(MediaCodec::class.java)
            val failure = IllegalStateException("synthetic component dequeue failure")
            if (renderFirst) {
                // One output and an empty catch-up drain publish an actual rendered session,
                // followed by three native errors. Never write renderedThisSession ourselves.
                `when`(codec.dequeueOutputBuffer(any(), anyLong()))
                    .thenReturn(7, MediaCodec.INFO_TRY_AGAIN_LATER).thenThrow(failure)
            } else {
                `when`(codec.dequeueOutputBuffer(any(), anyLong())).thenThrow(failure)
            }
            val thrown = AtomicReference<Throwable?>()
            val loop = VideoDecoder::class.java.getDeclaredMethod("outputThreadLoop").apply { isAccessible = true }
            val worker = Thread {
                try { loop.invoke(decoder) } catch (t: Throwable) { thrown.set(t.cause ?: t) }
            }
            setOwner("codec", codec)
            setOwner("codecBufferInfo", MediaCodec.BufferInfo())
            setOwner("running", true)
            setOwner("outputThread", worker)
            worker.start()
            worker.join(3_000)
            assertFalse("output errors must terminate the current owner", worker.isAlive)
            thrown.get()?.let { throw AssertionError("output worker failed", it) }
            verify(codec, times(if (renderFirst) 5 else 3)).dequeueOutputBuffer(any(), anyLong())
            if (renderFirst) verify(codec).releaseOutputBuffer(7, true)
            return codec
        }

        fun consumeRestart(packet: ByteArray, announced: CodecType, renderFirst: Boolean = false) {
            val owner = failOutputOwner(renderFirst)
            clearInvocations(surface)
            decode(packet, announced)
            decoder.awaitRetirementForTest()
            verify(owner).stop()
            verify(owner).release()
        }

        fun assertSelected(type: CodecType) {
            assertEquals(type, decoder.sessionHealth().finalCodec)
            assertFalse("decoder failure is not evidence of a different encoding", decoder.sessionHealth().codecFallbackUsed)
        }

        override fun close() { decoder.stop("test session ended"); decoder.awaitRetirementForTest() }
    }

    private fun slice(type: CodecType): ByteArray = when (type) {
        // AVC non-IDR 0x41 resembles an HEVC VPS; HEVC IDR 0x28 resembles an AVC PPS.
        CodecType.H264 -> byteArrayOf(0, 0, 0, 1, 0x41, 0x9a.toByte(), 0x22)
        CodecType.H265 -> byteArrayOf(0, 0, 0, 1, 0x28, 0x01, 0xaf.toByte())
    }

    private fun other(type: CodecType) = if (type == CodecType.H264) CodecType.H265 else CodecType.H264

    /** Mock only Android enumeration, so the removed AVC -> available HEVC fallback could run. */
    private fun withPlatformCodecs(hevcAvailable: Boolean = true, sdkInt: Int = 21, body: () -> Unit) {
        val sdk = Build.VERSION::class.java.getDeclaredField("SDK_INT").apply { isAccessible = true }
        val oldSdk = sdk.getInt(null)
        // Same host-stub technique as MicrophoneForegroundClaimTest; always restore the final field.
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val base = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java).invoke(unsafe, sdk)
        val offset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java).invoke(unsafe, sdk)
        val writeInt = unsafeClass.getMethod("putIntVolatile", Any::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        fun setSdk(value: Int) { writeInt.invoke(unsafe, base, offset, value) }
        val codecs = (if (hevcAvailable) CodecType.values().toList() else listOf(CodecType.H264)).map { type ->
            mock(MediaCodecInfo::class.java).also {
                `when`(it.name).thenReturn("OMX.vendor.${type.name}.decoder")
                `when`(it.supportedTypes).thenReturn(arrayOf(type.mimeType))
            }
        }.toTypedArray()
        try {
            setSdk(sdkInt)
            mockConstruction(MediaCodecList::class.java) { list, _ ->
                `when`(list.codecInfos).thenReturn(codecs)
            }.use {
                assertEquals(hevcAvailable, VideoDecoder.isHevcDecoderAvailable(includeSoftware = false))
                body()
            }
        } finally { setSdk(oldSdk) }
    }

    @Test fun `ambiguous slices obey announcement despite opposite preference and hardware inventory`() {
        for (available in listOf(false, true)) withPlatformCodecs(available) {
            for (type in CodecType.values()) for (forceSoftware in listOf(false, true)) {
                Fixture(preference = other(type), forceSoftware = forceSoftware).use { f ->
                    f.decode(slice(type), type)
                    f.assertSelected(type)
                    verify(f.surface).isValid
                }
            }
        }
    }

    @Test fun `without identifiable NALs forced software bundled preference still uses announcement`() {
        withPlatformCodecs {
            for (type in CodecType.values()) {
                Fixture(preference = other(type), forceSoftware = true).use { f ->
                    // No Annex-B start code: unlike the ambiguous-slice case, old selection must
                    // consult settings here. Bundled preference must not turn announced AVC into HEVC.
                    f.decode(byteArrayOf(0x55, 0x66, 0x77), type)
                    f.assertSelected(type)
                    verify(f.surface).isValid
                }
            }
        }
    }

    @Test fun `three cold restarts retain codec and latch initialization even when other codec exists`() {
        withPlatformCodecs {
            for (type in CodecType.values()) Fixture(preference = type).use { f ->
                val packet = slice(type)
                f.beginPinnedSession(type, packet)
                repeat(3) { restart ->
                    // A changed argument cannot override the established session pin either.
                    f.consumeRestart(packet, other(type))
                    f.assertSelected(type)
                    assertEquals(List(restart + 1) { "sync_consecutive_errors" }, f.errors)
                    if (restart < 2) verify(f.surface).isValid else verifyNoInteractions(f.surface)
                }
                repeat(5) { f.decode(packet, other(type)) }
                f.decoder.stop("restart: additional recovery request")
                f.decode(packet, other(type))
                verifyNoInteractions(f.surface)
                assertEquals("latched packets must not manufacture another restart", 3, f.errors.size)
                f.assertSelected(type)
            }
        }
    }

    @Test fun `surface recovery rearms same codec but full session reset accepts new announcement`() {
        withPlatformCodecs {
            for (type in CodecType.values()) Fixture(preference = type).use { f ->
                val packet = slice(type)
                f.beginPinnedSession(type, packet)
                repeat(3) { f.consumeRestart(packet, type) }
                verifyNoInteractions(f.surface)

                f.decoder.stop(DecoderStopPolicy.REASON_SURFACE_DESTROYED)
                f.decode(packet, other(type))
                verify(f.surface).isValid
                f.assertSelected(type)

                f.decoder.stop("projection disconnected")
                f.decoder.beginSession()
                clearInvocations(f.surface)
                f.decode(slice(other(type)), other(type))
                verify(f.surface).isValid
                f.assertSelected(other(type))
                assertFalse(f.decoder.hasRenderedThisSession)
            }
        }
    }

    @Test fun `a session that rendered can restart repeatedly without cold failure latch or codec flip`() {
        withPlatformCodecs {
            for (type in CodecType.values()) Fixture(preference = other(type)).use { f ->
                val packet = slice(type)
                f.beginPinnedSession(type, packet)
                repeat(5) { restart ->
                    f.consumeRestart(packet, other(type), renderFirst = restart == 0)
                    assertTrue("render history must survive instance teardown", f.decoder.hasRenderedThisSession)
                    assertEquals(0L, f.decoder.lastFrameRenderedMs)
                    assertEquals(1L, f.decoder.sessionHealth().framesRendered)
                    verify(f.surface).isValid
                    f.assertSelected(type)
                }
                assertEquals(List(5) { "sync_consecutive_errors" }, f.errors)
                f.decoder.stop("projection disconnected")
                assertFalse(f.decoder.hasRenderedThisSession)
            }
        }
    }

    @Test fun `parsed codec evidence survives an absent surface before any decoder exists`() {
        val vectors = mapOf(
            CodecType.H264 to "67 42 80 1F F4 02 80 2D C8",
            CodecType.H265 to "42 01 01 01 00 00 03 00 00 80 00 00 03 00 00 03 00 5D A0 02 80 80 2D 16 59 38"
        )
        for ((type, hex) in vectors) Fixture(preference = other(type)).use { f ->
            val sps = byteArrayOf(0, 0, 0, 1) + hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()
            f.decoder.setSurface(null)
            f.decode(sps, other(type))
            f.decode(slice(type), other(type))
            assertEquals(type, f.decoder.sessionHealth().finalCodec)
            assertTrue(f.decoder.sessionHealth().codecFallbackUsed)
            f.decoder.setSurface(f.surface)
            f.decode(slice(type), other(type))
            assertEquals(type, f.decoder.sessionHealth().finalCodec)

            f.decoder.stop("projection disconnected")
            f.decoder.beginSession()
            f.decode(slice(other(type)), other(type))
            f.assertSelected(other(type))
        }
    }

    @Test fun `first platform creation failures stop retrying without a successful codec pin`() {
        withPlatformCodecs {
            mockStatic(android.util.Log::class.java).use { logs ->
                logs.`when`<String> { android.util.Log.getStackTraceString(any(Throwable::class.java)) }
                    .thenReturn("synthetic creation failure")
                mockStatic(MediaFormat::class.java).use { formats ->
                    formats.`when`<MediaFormat> { MediaFormat.createVideoFormat(anyString(), anyInt(), anyInt()) }
                        .thenAnswer { mock(MediaFormat::class.java) }
                    mockStatic(MediaCodec::class.java).use { codecs ->
                        var attempts = 0
                        codecs.`when`<MediaCodec> { MediaCodec.createByCodecName(anyString()) }.thenAnswer {
                            attempts++
                            throw IllegalStateException("component unavailable")
                        }
                        Fixture(preference = CodecType.H264).use { f ->
                            `when`(f.surface.isValid).thenReturn(true)
                            val packet = slice(CodecType.H264) // No SPS evidence and no successful start.
                            repeat(8) {
                                f.decode(packet, CodecType.H264)
                                f.decoder.awaitRetirementForTest()
                            }
                            assertEquals(3, f.errors.size)
                            assertTrue(attempts > 0)
                            val latchedAttempts = attempts
                            repeat(8) { f.decode(packet, CodecType.H264) }
                            assertEquals(latchedAttempts, attempts)
                            assertFalse(f.decoder.hasRenderedThisSession)
                            f.assertSelected(CodecType.H264)

                            f.decoder.stop(DecoderStopPolicy.REASON_SURFACE_DESTROYED)
                            f.decode(packet, CodecType.H264)
                            assertTrue("a new surface must allow another real creation attempt", attempts > latchedAttempts)
                        }
                    }
                }
            }
        }
    }

    @Test fun `strong codec correction gets its own cold-start budget at the previous limit`() {
        val hevcSps = byteArrayOf(0, 0, 0, 1) +
            "42 01 01 01 00 00 03 00 00 80 00 00 03 00 00 03 00 5D A0 02 80 80 2D 16 59 38"
                .split(" ").map { it.toInt(16).toByte() }.toByteArray()
        Fixture(preference = CodecType.H264).use { f ->
            f.decode(slice(CodecType.H264), CodecType.H264)
            repeat(2) { f.consumeRestart(slice(CodecType.H264), CodecType.H264) }
            f.consumeRestart(hevcSps, CodecType.H264)
            assertEquals(CodecType.H265, f.decoder.sessionHealth().finalCodec)
            assertTrue(f.decoder.sessionHealth().codecFallbackUsed)
            verify(f.surface).isValid
            repeat(3) { retry ->
                f.consumeRestart(slice(CodecType.H265), CodecType.H264)
                if (retry < 2) verify(f.surface).isValid else verifyNoInteractions(f.surface)
            }
        }
    }

    @Test fun `parameter sets received by a running platform owner survive surface recreation`() {
        fun annex(hex: String) = byteArrayOf(0, 0, 0, 1) +
            hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val sps0 = annex("6742c01fda0320f684000003000400000300f03c60ca80")
        val pps0 = annex("68ce0f2c80")
        val sps1 = annex("6742c01f5680c83da10000030001000003003c8f1832a0")
        val pps1 = annex("6848e0fc80")
        withPlatformCodecs {
            val stored = java.util.IdentityHashMap<MediaFormat, MutableMap<String, ByteArray>>()
            val submitted = LinkedBlockingQueue<ByteArray>()
            mockStatic(MediaFormat::class.java).use { formats ->
                formats.`when`<MediaFormat> { MediaFormat.createVideoFormat(anyString(), anyInt(), anyInt()) }
                    .thenAnswer {
                        val format = mock(MediaFormat::class.java)
                        val data = mutableMapOf<String, ByteArray>()
                        stored[format] = data
                        doAnswer { call ->
                            val bytes = (call.arguments[1] as ByteBuffer).duplicate()
                            data[call.arguments[0] as String] = ByteArray(bytes.remaining()).also { bytes.get(it) }
                            null
                        }.`when`(format).setByteBuffer(anyString(), org.mockito.kotlin.any())
                        format
                    }
                val owner = mock(MediaCodec::class.java)
                val input = ByteBuffer.allocate(4096)
                `when`(owner.dequeueInputBuffer(anyLong())).thenReturn(0)
                `when`(owner.getInputBuffer(anyInt())).thenReturn(input)
                doAnswer { call ->
                    val size = call.arguments[2] as Int
                    submitted.add(ByteArray(size).also { input.duplicate().get(it) })
                    null
                }.`when`(owner).queueInputBuffer(anyInt(), anyInt(), anyInt(), anyLong(), anyInt())
                Fixture(preference = CodecType.H264).use { f ->
                    f.decode(sps0 + pps0, CodecType.H264)
                    // Run the production feed loop without Android's thread-priority wrapper.
                    f.startInputOwner(owner)
                    f.decode(sps1 + pps1, CodecType.H264)
                    assertArrayEquals(sps1 + pps1, submitted.poll(3, TimeUnit.SECONDS))
                    assertTrue(f.decoder.detachSurfaceIfCurrent(f.surface, DecoderStopPolicy.REASON_SURFACE_DESTROYED))
                    f.decoder.setSurface(f.surface)
                    val buildFormat = VideoDecoder::class.java.getDeclaredMethod(
                        "buildFormat", String::class.java, Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType, String::class.java
                    ).apply { isAccessible = true }
                    val format = buildFormat.invoke(f.decoder, CodecType.H264.mimeType, 800, 480, "OMX.vendor.H264.decoder")
                    val replay = stored[format]!!
                    assertArrayEquals(sps0 + sps1, replay["csd-0"])
                    assertArrayEquals(pps0 + pps1, replay["csd-1"])
                }
            }
        }
    }

    @Test fun `a software first frame listener registered during delivery survives for the next picture`() {
        Fixture(preference = CodecType.H264, forceSoftware = true).use { f ->
            val packet = slice(CodecType.H264)
            f.beginPinnedSession(CodecType.H264, packet)
            f.bundledOutput(1, 0, 1, 1)
            val notifications = mutableListOf<String>()
            var registrar: Thread? = null
            f.decoder.onFirstFrameListener = {
                notifications.add("first")
                val registered = CountDownLatch(1)
                registrar = Thread {
                    f.decoder.onFirstFrameListener = { notifications.add("replacement") }
                    registered.countDown()
                }.also { it.start() }
                assertTrue("registration must not wait for an external callback", registered.await(3, TimeUnit.SECONDS))
            }
            try {
                f.decode(packet, CodecType.H264)
                f.decode(packet, CodecType.H264)
                assertEquals(listOf("first"), notifications)
                f.decode(packet, CodecType.H264)
                f.decode(packet, CodecType.H264)
                assertEquals(listOf("first", "replacement"), notifications)
            } finally {
                registrar?.join(3_000)
                assertFalse("registration worker must exit", registrar?.isAlive == true)
            }
        }
    }

    @Test fun `bundled AVC and HEVC publish credible frames and recover without changing the codec`() {
        for (type in CodecType.values()) Fixture(preference = other(type), forceSoftware = true).use { f ->
            val packet = slice(type)
            f.beginPinnedSession(type, packet)
            val owner = f.bundledOutput(0, 1, 2, -7)
            var firstFrames = 0
            f.decoder.onFirstFrameListener = { firstFrames++ }

            assertTrue(f.decoder.usingBundledSoftwareVideo)
            f.decode(packet, type)
            assertFalse(f.decoder.hasCrediblePicture)
            assertFalse(f.decoder.hasRenderedThisSession)
            assertEquals(0, firstFrames)

            f.decode(packet, type)
            assertTrue(f.decoder.hasCrediblePicture)
            assertTrue(f.decoder.hasRenderedThisSession)
            assertEquals(1L, f.decoder.framesRenderedThisSession)
            assertEquals(1_000L, f.decoder.lastFrameRenderedMs)
            f.decode(packet, type)
            assertEquals(3L, f.decoder.framesRenderedThisSession)
            assertEquals(1, firstFrames)

            f.decode(packet, type)
            f.decode(packet, other(type))
            assertEquals(listOf("software_video_error_-7"), f.errors)
            f.decoder.awaitRetirementForTest()
            verify(owner).stop()
            assertFalse(f.decoder.usingBundledSoftwareVideo)
            assertFalse(f.decoder.hasCrediblePicture)
            assertTrue(f.decoder.hasRenderedThisSession)
            assertEquals(3L, f.decoder.sessionHealth().framesRendered)
            f.assertSelected(type)
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
            check(resume.count == 0L) { "native call was not resumed" }
        }
        fun await() = assertTrue("native call was not reached", entered.await(3, TimeUnit.SECONDS))
    }

    private fun renderingCodec(requiredAccessUnit: ByteArray? = null): MediaCodec {
        val codec = mock(MediaCodec::class.java)
        val input = ByteBuffer.allocate(maxOf(4096, requiredAccessUnit?.size ?: 0))
        val outputs = LinkedBlockingQueue<Long>()
        `when`(codec.dequeueInputBuffer(anyLong())).thenReturn(0)
        `when`(codec.getInputBuffer(anyInt())).thenReturn(input)
        doAnswer { call ->
            val size = call.getArgument<Int>(2)
            val accepted = if (requiredAccessUnit == null) size > 0 else {
                val bytes = ByteArray(size)
                input.duplicate().apply { position(call.getArgument(1)); get(bytes) }
                requiredAccessUnit.contentEquals(bytes) &&
                    VideoKeyframeScanner.containsKeyframe(bytes, 0, bytes.size, false)
            }
            if (accepted) outputs.add(call.getArgument(3))
            null
        }.`when`(codec).queueInputBuffer(anyInt(), anyInt(), anyInt(), anyLong(), anyInt())
        `when`(codec.dequeueOutputBuffer(any(), anyLong())).thenAnswer { call ->
            val pts = outputs.poll(10, TimeUnit.MILLISECONDS)
            if (pts == null) MediaCodec.INFO_TRY_AGAIN_LATER else {
                call.getArgument<MediaCodec.BufferInfo>(0).apply {
                    presentationTimeUs = pts
                    size = 1
                }
                7
            }
        }
        return codec
    }

    @Test fun `blocking stop or release gates replacements and decoding recovers after physical retirement`() {
        for (blockStop in listOf(true, false)) withPlatformCodecs {
            mockStatic(MediaFormat::class.java).use { formats ->
                formats.`when`<MediaFormat> { MediaFormat.createVideoFormat(anyString(), anyInt(), anyInt()) }
                    .thenAnswer { mock(MediaFormat::class.java) }
                mockStatic(MediaCodec::class.java).use { factories ->
                    val replacement = renderingCodec()
                    factories.`when`<MediaCodec> { MediaCodec.createByCodecName(anyString()) }.thenReturn(replacement)
                    Fixture(CodecType.H264).use { f ->
                        val packet = slice(CodecType.H264)
                        f.beginPinnedSession(CodecType.H264, packet)
                        val old = f.failOutputOwner()
                        val pause = NativePause()
                        val caller = Thread.currentThread()
                        val pauseNative = org.mockito.stubbing.Answer<Any?> {
                            assertNotSame(caller, Thread.currentThread())
                            assertFalse("native shutdown must not own the UI monitor", Thread.holdsLock(f.decoder))
                            pause.block()
                            null
                        }
                        if (blockStop) doAnswer(pauseNative).`when`(old).stop()
                        else doAnswer(pauseNative).`when`(old).release()
                        try {
                            f.decoder.stop(DecoderStopPolicy.REASON_ACTIVITY_STOPPED)
                            pause.await()
                            `when`(f.surface.isValid).thenReturn(true)
                            repeat(10) {
                                f.decoder.setSurface(null)
                                f.decoder.setSurface(f.surface)
                                f.decode(packet, CodecType.H264)
                            }
                            factories.verifyNoInteractions()
                            assertTrue(f.decoder.hasPendingRetirement)
                            assertTrue("waiting is not a decoder failure", f.errors.isEmpty())
                            // This case exercises an explicit later retry; idle recovery is covered
                            // separately with a real IDR and thread-scoped platform doubles.
                            f.decoder.stop("discard gated test input")
                            pause.resume.countDown()
                            f.decoder.awaitRetirementForTest()
                            val rendered = CountDownLatch(1)
                            f.decoder.onFirstFrameListener = { rendered.countDown() }
                            f.decode(packet, CodecType.H264)
                            assertTrue("subsequent input must restart actual decoding", rendered.await(3, TimeUnit.SECONDS))
                            assertTrue(f.decoder.hasRenderedThisSession)
                            verify(old).stop()
                            verify(old).release()
                            verify(replacement).configure(any(), eq(f.surface), isNull(), eq(0))
                        } finally { pause.resume.countDown() }
                    }
                }
            }
        }
    }

    @Test fun `idle restart picture preserves ownership and does not hide later reference loss`() {
        val clip = File("src/main/res/raw/preflight_h264_480p.h264").readBytes()
        val first = AnnexBAccessUnits.split(clip, CodecType.H264).first()
        val packet = clip.copyOfRange(first.offset, first.offset + first.length)
        assertTrue(VideoKeyframeScanner.containsKeyframe(packet, 0, packet.size, false))
        for (repairResponse in listOf(false, true)) for (gap in listOf(false, true)) withPlatformCodecs {
            Fixture(CodecType.H264).use { f ->
                f.beginPinnedSession(CodecType.H264, packet)
                val old = f.failOutputOwner()
                val pause = NativePause()
                doAnswer { pause.block(); null }.`when`(old).release()
                val replacement = renderingCodec(packet)
                val rendered = CountDownLatch(1)
                val keyframe = CountDownLatch(1)
                val dropped = CountDownLatch(1)
                val retirementThread = AtomicReference<Thread>()
                val callbackFailure = AtomicReference<Throwable?>()
                val scopes = mutableListOf<AutoCloseable>()
                f.decoder.onFirstFrameListener = { rendered.countDown() }
                f.decoder.onKeyframeObserved = { keyframe.countDown() }
                f.decoder.onFrameDropped = { dropped.countDown() }
                // Mockito's static/constructor doubles are thread-local. Install only platform
                // boundaries on the retiring thread; replay and both worker loops remain real.
                doAnswer {
                    scopes.asReversed().forEach { it.close() }
                    scopes.clear()
                    null
                }.`when`(replacement).start()
                try {
                    `when`(f.surface.isValid).thenReturn(true)
                    if (repairResponse) f.decode(slice(CodecType.H264), CodecType.H264)
                    val transport = ByteArray(packet.size + 13)
                    packet.copyInto(transport, 7)
                    f.decoder.decode(transport, 7, packet.size, false, CodecType.H264)
                    transport.fill(0x55)
                    pause.await()
                    // A clean idle replay can acknowledge repair; a picture followed by discarded
                    // references cannot. Both must preserve the owned IDR bytes and render it.
                    if (gap) repeat(5) { f.decode(slice(CodecType.H264), CodecType.H264) }
                    f.decoder.whenRetired {
                        retirementThread.set(Thread.currentThread())
                        try {
                            assertFalse(Thread.holdsLock(f.decoder))
                            val info = mock(MediaCodecInfo::class.java)
                            `when`(info.name).thenReturn("OMX.vendor.H264.decoder")
                            `when`(info.supportedTypes).thenReturn(arrayOf(CodecType.H264.mimeType))
                            scopes += mockConstruction(MediaCodecList::class.java) { list, _ ->
                                `when`(list.codecInfos).thenReturn(arrayOf(info))
                            }
                            val formats = mockStatic(MediaFormat::class.java)
                            scopes += formats
                            formats.`when`<MediaFormat> {
                                MediaFormat.createVideoFormat(anyString(), anyInt(), anyInt())
                            }.thenAnswer { mock(MediaFormat::class.java) }
                            val codecs = mockStatic(MediaCodec::class.java)
                            scopes += codecs
                            codecs.`when`<MediaCodec> {
                                MediaCodec.createByCodecName(anyString())
                            }.thenReturn(replacement)
                        } catch (failure: Throwable) { callbackFailure.set(failure) }
                    }
                    assertEquals(listOf("sync_consecutive_errors"), f.errors)
                    assertEquals(1L, rendered.count)
                    pause.resume.countDown()
                    assertTrue("idle transport must still recover its IDR", rendered.await(3, TimeUnit.SECONDS))
                    if (gap) {
                        assertTrue("lost references must keep transport recovery armed", dropped.await(3, TimeUnit.SECONDS))
                        assertEquals("older IDR must not acknowledge later reference loss", 1L, keyframe.count)
                        assertFalse(f.decoder.hasCrediblePicture)
                        f.decode(slice(CodecType.H264), CodecType.H264)
                        verify(replacement, timeout(3_000)).queueInputBuffer(anyInt(), eq(0),
                            eq(slice(CodecType.H264).size), anyLong(), eq(0))
                        assertEquals(1L, keyframe.count)
                        assertFalse("dependent live input cannot repair missing references", f.decoder.hasCrediblePicture)
                        f.decode(packet, CodecType.H264)
                    }
                    assertTrue("a subsequent complete keyframe repairs the picture", keyframe.await(3, TimeUnit.SECONDS))
                    callbackFailure.get()?.let { throw AssertionError("platform callback failed", it) }
                    assertTrue(f.decoder.hasCrediblePicture)
                    verify(old).release()
                    verify(replacement).configure(any(), eq(f.surface), isNull(), eq(0))
                } finally {
                    pause.resume.countDown()
                    f.decoder.awaitRetirementForTest()
                    retirementThread.get()?.join(3_000)
                    scopes.asReversed().forEach { it.close() }
                }
            }
        }
    }

    @Test fun `bundled replay keeps later reference loss visible until newer keyframe output`() {
        val clip = File("src/main/res/raw/preflight_h264_480p.h264").readBytes()
        val first = AnnexBAccessUnits.split(clip, CodecType.H264).first()
        val packet = clip.copyOfRange(first.offset, first.offset + first.length)
        Fixture(CodecType.H264, forceSoftware = true).use { f ->
            f.beginPinnedSession(CodecType.H264, packet)
            val old = f.bundledOutput(0)
            val pause = NativePause()
            doAnswer { pause.block(); null }.`when`(old).stop()
            val rendered = CountDownLatch(1)
            val repaired = CountDownLatch(1)
            val dropped = CountDownLatch(1)
            val replacement = AtomicReference<FfmpegVideoDecoder>()
            val retirementThread = AtomicReference<Thread>()
            val construction = AtomicReference<org.mockito.MockedConstruction<FfmpegVideoDecoder>>()
            f.decoder.onFirstFrameListener = { rendered.countDown() }
            f.decoder.onKeyframeObserved = { repaired.countDown() }
            f.decoder.onFrameDropped = { dropped.countDown() }
            try {
                f.decoder.stop(DecoderStopPolicy.REASON_ACTIVITY_STOPPED)
                pause.await()
                `when`(f.surface.isValid).thenReturn(true)
                f.decode(packet, CodecType.H264)
                f.decode(slice(CodecType.H264), CodecType.H264)
                f.decoder.whenRetired {
                    retirementThread.set(Thread.currentThread())
                    construction.set(mockConstruction(FfmpegVideoDecoder::class.java) { owner, _ ->
                        replacement.set(owner)
                        `when`(owner.start()).thenReturn(true)
                        val keyframeOutput = java.util.concurrent.atomic.AtomicBoolean()
                        `when`(owner.keyframeRendered).thenAnswer { keyframeOutput.get() }
                        `when`(owner.decode(org.mockito.kotlin.any(), anyInt(), anyInt(), anyBoolean()))
                            .thenAnswer { call ->
                                val data = call.getArgument<ByteArray>(0)
                                val offset = call.getArgument<Int>(1)
                                val size = call.getArgument<Int>(2)
                                val eligible = call.getArgument<Boolean>(3)
                                keyframeOutput.set(eligible &&
                                    VideoKeyframeScanner.containsKeyframe(data, offset, size, false))
                                if (!eligible) construction.getAndSet(null)?.close()
                                if (CodecConfigScanner.classify(data, offset, size, false) ==
                                    CodecConfigScanner.Content.PARAMETER_SETS_ONLY) 0 else 1
                            }
                    })
                }
                pause.resume.countDown()
                assertTrue(rendered.await(3, TimeUnit.SECONDS))
                assertTrue(dropped.await(3, TimeUnit.SECONDS))
                assertEquals("older software picture cannot cancel recovery", 1L, repaired.count)
                assertFalse("software output alone is not continuity proof", f.decoder.hasCrediblePicture)
                f.decode(slice(CodecType.H264), CodecType.H264)
                assertFalse(f.decoder.hasCrediblePicture)
                assertEquals(1L, repaired.count)
                f.decode(packet, CodecType.H264)
                assertTrue(repaired.await(3, TimeUnit.SECONDS))
                assertTrue(f.decoder.hasCrediblePicture)
                verify(replacement.get()).decode(org.mockito.kotlin.any(), anyInt(), eq(packet.size), eq(false))
                verify(old).stop()
            } finally {
                pause.resume.countDown()
                f.decoder.awaitRetirementForTest()
                retirementThread.get()?.join(3_000)
                construction.getAndSet(null)?.close()
            }
        }
    }

    @Test fun `late retirement completion notifies once without reviving a detached picture`() {
        withPlatformCodecs {
            Fixture(CodecType.H264).use { f ->
                val packet = slice(CodecType.H264)
                f.beginPinnedSession(CodecType.H264, packet)
                val old = f.failOutputOwner()
                val pause = NativePause()
                doAnswer { pause.block(); null }.`when`(old).release()
                val completed = CountDownLatch(1)
                val retirementThread = AtomicReference<Thread>()
                val callbackFailure = AtomicReference<Throwable?>()
                val rendered = CountDownLatch(1)
                f.decoder.onFirstFrameListener = { rendered.countDown() }
                try {
                    `when`(f.surface.isValid).thenReturn(true)
                    f.decode(packet, CodecType.H264)
                    pause.await()
                    f.decoder.setSurface(null)
                    f.decoder.stop("preflight")
                    f.decoder.whenRetired {
                        retirementThread.set(Thread.currentThread())
                        try {
                            assertFalse(Thread.holdsLock(f.decoder))
                            val lock = VideoDecoder::class.java.getDeclaredField("nativeWorkLock")
                                .apply { isAccessible = true }.get(f.decoder) as java.util.concurrent.locks.ReentrantLock
                            assertFalse(lock.isHeldByCurrentThread)
                            assertFalse(f.decoder.hasPendingRetirement)
                        } catch (failure: Throwable) { callbackFailure.set(failure) }
                        completed.countDown()
                    }
                    assertFalse("observation deadlines cannot finish native release", completed.await(1_100, TimeUnit.MILLISECONDS))
                    assertTrue(f.decoder.hasPendingRetirement)
                    pause.resume.countDown()
                    assertTrue("late physical completion must still release its observer", completed.await(3, TimeUnit.SECONDS))
                    retirementThread.get().join(3_000)
                    assertFalse(retirementThread.get().isAlive)
                    callbackFailure.get()?.let { throw AssertionError("retirement callback failed", it) }
                    assertEquals("detached pending input must not produce a picture", 1L, rendered.count)
                    assertFalse(f.decoder.hasCrediblePicture)
                    verify(old).release()
                    var immediate = 0
                    f.decoder.whenRetired { immediate++ }
                    f.decoder.stop("already retired")
                    assertEquals("already-complete observers run once without another shutdown", 1, immediate)
                } finally { pause.resume.countDown() }
            }
        }
    }

    @Test fun `software native decode cannot block surface retirement or publish into the next owner`() {
        Fixture(CodecType.H264, forceSoftware = true).use { f ->
            val packet = slice(CodecType.H264)
            f.beginPinnedSession(CodecType.H264, packet)
            val software = f.bundledOutput(1)
            val pause = NativePause()
            `when`(software.decode(org.mockito.kotlin.any(), anyInt(), anyInt(), anyBoolean())).thenAnswer {
                assertFalse(Thread.holdsLock(f.decoder))
                pause.block()
                1
            }
            val failure = AtomicReference<Throwable?>()
            val input = Thread {
                try { f.decode(packet, CodecType.H264) } catch (t: Throwable) { failure.set(t) }
            }
            input.start()
            try {
                pause.await()
                val stopped = CountDownLatch(1)
                val stopper = Thread {
                    f.decoder.setSurface(null)
                    stopped.countDown()
                }
                stopper.start()
                assertTrue("UI teardown must not wait for native decode", stopped.await(250, TimeUnit.MILLISECONDS))
                stopper.join(3000)
                val replacementListener: () -> Unit = { fail("retired software must not notify the next owner") }
                f.decoder.onFirstFrameListener = replacementListener
                verify(software, never()).stop()
                assertTrue(f.decoder.hasPendingRetirement)
                pause.resume.countDown()
                input.join(3000)
                assertFalse(input.isAlive)
                failure.get()?.let { throw AssertionError("software worker failed", it) }
                f.decoder.awaitRetirementForTest()
                verify(software).stop()
                assertSame(replacementListener, f.decoder.onFirstFrameListener)
                assertFalse(f.decoder.hasRenderedThisSession)
                assertEquals(0L, f.decoder.framesRenderedThisSession)
                assertTrue(f.errors.isEmpty())
            } finally {
                pause.resume.countDown()
                input.join(3000)
            }
        }
    }
    @Test fun `retiring during configure or failed rung release cannot publish or allocate another stale candidate`() {
        for (blockConfigure in listOf(true, false)) withPlatformCodecs(sdkInt = 23) {
            mockStatic(android.util.Log::class.java).use { logs ->
                logs.`when`<String> { android.util.Log.getStackTraceString(any(Throwable::class.java)) }
                    .thenReturn("synthetic configuration failure")
                mockStatic(MediaFormat::class.java).use { formats ->
                    formats.`when`<MediaFormat> { MediaFormat.createVideoFormat(anyString(), anyInt(), anyInt()) }
                        .thenAnswer { mock(MediaFormat::class.java) }
                    mockStatic(MediaCodec::class.java).use { factories ->
                        val old = mock(MediaCodec::class.java)
                        val replacement = renderingCodec()
                        factories.`when`<MediaCodec> { MediaCodec.createByCodecName(anyString()) }
                            .thenReturn(old, replacement)
                        Fixture(CodecType.H264, lowLatency = true).use { f ->
                            val packet = slice(CodecType.H264)
                            val pause = NativePause()
                            `when`(f.surface.isValid).thenReturn(true)
                            doAnswer {
                                assertFalse(Thread.holdsLock(f.decoder))
                                if (blockConfigure) pause.block()
                                else throw IllegalArgumentException("optional keys rejected")
                                null
                            }.`when`(old).configure(any(), eq(f.surface), isNull(), eq(0))
                            if (!blockConfigure) doAnswer {
                                assertFalse(Thread.holdsLock(f.decoder))
                                pause.block()
                                null
                            }.`when`(old).release()
                            val failure = AtomicReference<Throwable?>()
                            val teardown = Thread {
                                try {
                                    pause.await()
                                    val start = System.nanoTime()
                                    assertTrue(f.decoder.detachSurfaceIfCurrent(
                                        f.surface, DecoderStopPolicy.REASON_SURFACE_DESTROYED))
                                    f.decoder.setSurface(f.surface)
                                    assertTrue("native initialization must not hold the UI monitor",
                                        System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(250))
                                    assertTrue(f.decoder.hasPendingRetirement)
                                    if (blockConfigure) verify(old, never()).release()
                                } catch (t: Throwable) {
                                    failure.set(t)
                                } finally { pause.resume.countDown() }
                            }
                            teardown.start()
                            try {
                                f.decode(packet, CodecType.H264)
                                teardown.join(3000)
                                assertFalse(teardown.isAlive)
                                failure.get()?.let { throw AssertionError("surface teardown failed", it) }
                                f.decoder.awaitRetirementForTest()
                                verify(old, never()).start()
                                verify(old).release()
                                verifyNoInteractions(replacement)
                                assertTrue(f.errors.isEmpty())
                                val rendered = CountDownLatch(1)
                                f.decoder.onFirstFrameListener = { rendered.countDown() }
                                f.decode(packet, CodecType.H264)
                                assertTrue("fresh input must recover after the old rung exits",
                                    rendered.await(3, TimeUnit.SECONDS))
                            } finally {
                                pause.resume.countDown()
                                teardown.join(3000)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun `same-ID CSD update during retirement invalidates an older retained keyframe`() {
        val type = CodecType.H264
        fun firstPicture(name: String): Pair<ByteArray, ByteArray> {
            val clip = File("src/main/res/raw/$name").readBytes()
            val unit = AnnexBAccessUnits.split(clip, type).first()
            val prefix = AnnexBAccessUnits.parameterSetPrefix(clip, unit, type)
            assertTrue(prefix > 0)
            return clip.copyOfRange(unit.offset, unit.offset + unit.length) to
                clip.copyOfRange(unit.offset, unit.offset + prefix)
        }
        val (oldPicture, _) = firstPicture("preflight_h264_480p.h264")
        val (newPicture, newConfig) = firstPicture("preflight_h264_720p.h264")
        Fixture(type, forceSoftware = true).use { f ->
            val field = VideoDecoder::class.java.getDeclaredField("parameterSets").apply { isAccessible = true }
            val cache = field.get(f.decoder) as ParameterSetCache
            fun cachedWidth(): Int {
                val sps = checkNotNull(cache.latest(7))
                return checkNotNull(ParameterSetInspector.parseH264Sps(sps, 4, sps.size - 4)).width
            }
            f.beginPinnedSession(type, oldPicture)
            assertEquals(800, cachedWidth())
            val old = f.bundledOutput(0)
            val pause = NativePause()
            doAnswer { pause.block(); null }.`when`(old).stop()
            val retirementThread = AtomicReference<Thread>()
            val callbackEntered = CountDownLatch(1)
            val construction = AtomicReference<org.mockito.MockedConstruction<FfmpegVideoDecoder>>()
            val dropped = java.util.concurrent.atomic.AtomicInteger()
            val repaired = java.util.concurrent.atomic.AtomicInteger()
            f.decoder.onFrameDropped = { dropped.incrementAndGet() }
            f.decoder.onKeyframeObserved = { repaired.incrementAndGet() }
            try {
                f.decoder.stop(DecoderStopPolicy.REASON_ACTIVITY_STOPPED)
                pause.await()
                `when`(f.surface.isValid).thenReturn(true)
                f.decode(oldPicture, type)
                f.decode(newConfig, type)
                assertEquals("new same-ID configuration was received", 1280, cachedWidth())
                f.decoder.whenRetired {
                    retirementThread.set(Thread.currentThread())
                    // The valid Surface remains attached. Replace only the JNI endpoint on the
                    // retiring thread; actual retention, replay, initialization and cache run.
                    construction.set(mockConstruction(FfmpegVideoDecoder::class.java) { owner, _ ->
                        `when`(owner.start()).thenReturn(true)
                        `when`(owner.decode(org.mockito.kotlin.any(), anyInt(), anyInt(), anyBoolean())).thenReturn(0)
                    })
                    callbackEntered.countDown()
                }
                pause.resume.countDown()
                assertTrue(callbackEntered.await(3, TimeUnit.SECONDS))
                checkNotNull(retirementThread.get()).join(3_000)
                assertFalse(retirementThread.get().isAlive)
                assertEquals("older replay must not overwrite newer same-ID SPS", 1280, cachedWidth())
                assertEquals("configuration invalidation must request fresh input even on idle stream", 1, dropped.get())
                assertEquals("stale input cannot acknowledge repair", 0, repaired.get())
                assertFalse(f.decoder.hasCrediblePicture)
                assertTrue("stale-config replay must not allocate a decoder", construction.get().constructed().isEmpty())
                construction.getAndSet(null)?.close()
                // A fresh keyframe is admitted normally and repairs only after output.
                mockConstruction(FfmpegVideoDecoder::class.java) { owner, _ ->
                    val keyframe = java.util.concurrent.atomic.AtomicBoolean()
                    `when`(owner.start()).thenReturn(true)
                    `when`(owner.keyframeRendered).thenAnswer { keyframe.get() }
                    `when`(owner.decode(org.mockito.kotlin.any(), anyInt(), anyInt(), anyBoolean())).thenAnswer { call ->
                        val data = call.getArgument<ByteArray>(0)
                        val offset = call.getArgument<Int>(1)
                        val size = call.getArgument<Int>(2)
                        val isPicture = VideoKeyframeScanner.containsKeyframe(data, offset, size, false)
                        keyframe.set(isPicture && call.getArgument<Boolean>(3))
                        if (isPicture) 1 else 0
                    }
                }.use {
                    f.decode(newPicture, type)
                    assertEquals(1, repaired.get())
                    assertTrue(f.decoder.hasCrediblePicture)
                    assertEquals(1280, cachedWidth())
                }
            } finally {
                pause.resume.countDown()
                retirementThread.get()?.join(3_000)
                construction.getAndSet(null)?.close()
            }
        }
    }

    private enum class RetainedConfigControl {
        IDENTICAL, PPS_CHANGE, NEWER_KEYFRAME, DETACHED, INVALID_SURFACE, INVALID_BEFORE_CALLBACK,
        RETIRED_GENERATION,
    }

    @Test fun `byte-identical CSD during retirement still replays the retained picture`() =
        checkRetainedConfigControl(RetainedConfigControl.IDENTICAL)

    @Test fun `same-ID PPS update keeps newest PPS and requests a fresh keyframe`() =
        checkRetainedConfigControl(RetainedConfigControl.PPS_CHANGE)

    @Test fun `newer keyframe replaces stale retained configuration without extra recovery`() =
        checkRetainedConfigControl(RetainedConfigControl.NEWER_KEYFRAME)

    @Test fun `detaching after a CSD update suppresses retained replay and recovery callbacks`() =
        checkRetainedConfigControl(RetainedConfigControl.DETACHED)

    @Test fun `invalidating the retained Surface after a CSD update suppresses replay and recovery callbacks`() =
        checkRetainedConfigControl(RetainedConfigControl.INVALID_SURFACE)

    @Test fun `Surface invalidated after replay validation cannot request repair`() =
        checkRetainedConfigControl(RetainedConfigControl.INVALID_BEFORE_CALLBACK)

    @Test fun `retiring the same Surface generation after a CSD update suppresses replay and recovery callbacks`() =
        checkRetainedConfigControl(RetainedConfigControl.RETIRED_GENERATION)

    private fun checkRetainedConfigControl(control: RetainedConfigControl) {
        val type = CodecType.H264
        fun firstPicture(name: String): Pair<ByteArray, ByteArray> {
            val clip = File("src/main/res/raw/$name").readBytes()
            val unit = AnnexBAccessUnits.split(clip, type).first()
            val prefix = AnnexBAccessUnits.parameterSetPrefix(clip, unit, type)
            assertTrue(prefix > 0)
            return clip.copyOfRange(unit.offset, unit.offset + unit.length) to
                clip.copyOfRange(unit.offset, unit.offset + prefix)
        }
        val (oldPicture, oldConfig) = firstPicture("preflight_h264_480p.h264")
        val (newPicture, newConfig) = firstPicture("preflight_h264_720p.h264")
        // Existing PPS id=0, SPS id=0, with constrained_intra_pred_flag changed from 0 to 1.
        // The payload length and both syntax IDs are unchanged; no resolution change is needed.
        val newPps = byteArrayOf(0, 0, 0, 1, 0x68, 0xce.toByte(), 0x0f, 0x2e, 0x80.toByte())
        assertEquals(0, ParameterSetInspector.parameterSetId(newPps, 4, newPps.size - 4, false))
        Fixture(type, forceSoftware = true).use { f ->
            val cache = VideoDecoder::class.java.getDeclaredField("parameterSets")
                .apply { isAccessible = true }.get(f.decoder) as ParameterSetCache
            fun cachedWidth(): Int {
                val sps = checkNotNull(cache.latest(7))
                return checkNotNull(ParameterSetInspector.parseH264Sps(sps, 4, sps.size - 4)).width
            }
            fun scriptNative(owner: FfmpegVideoDecoder) {
                val keyframe = java.util.concurrent.atomic.AtomicBoolean()
                `when`(owner.start()).thenReturn(true)
                `when`(owner.keyframeRendered).thenAnswer { keyframe.get() }
                `when`(owner.decode(org.mockito.kotlin.any(), anyInt(), anyInt(), anyBoolean())).thenAnswer { call ->
                    val bytes = call.getArgument<ByteArray>(0)
                    val offset = call.getArgument<Int>(1)
                    val size = call.getArgument<Int>(2)
                    val key = VideoKeyframeScanner.containsKeyframe(bytes, offset, size, false)
                    keyframe.set(key && call.getArgument<Boolean>(3))
                    if (CodecConfigScanner.classify(bytes, offset, size, false) ==
                        CodecConfigScanner.Content.PARAMETER_SETS_ONLY) 0 else 1
                }
            }
            f.beginPinnedSession(type, oldPicture)
            assertEquals(800, cachedWidth())
            assertFalse(newPps.contentEquals(cache.latest(8)))
            val old = f.bundledOutput(0)
            val pause = NativePause()
            doAnswer { pause.block(); null }.`when`(old).stop()
            val callbackEntered = CountDownLatch(1)
            val retirementThread = AtomicReference<Thread>()
            val construction = AtomicReference<org.mockito.MockedConstruction<FfmpegVideoDecoder>>()
            val dropped = java.util.concurrent.atomic.AtomicInteger()
            val repaired = java.util.concurrent.atomic.AtomicInteger()
            val rendered = java.util.concurrent.atomic.AtomicInteger()
            f.decoder.onFrameDropped = { dropped.incrementAndGet() }
            f.decoder.onKeyframeObserved = { repaired.incrementAndGet() }
            f.decoder.onFirstFrameListener = { rendered.incrementAndGet() }
            try {
                f.decoder.stop(DecoderStopPolicy.REASON_ACTIVITY_STOPPED)
                pause.await()
                `when`(f.surface.isValid).thenReturn(true)
                f.decode(oldPicture, type)
                when (control) {
                    RetainedConfigControl.IDENTICAL -> f.decode(oldConfig, type)
                    RetainedConfigControl.PPS_CHANGE -> f.decode(newPps, type)
                    RetainedConfigControl.NEWER_KEYFRAME -> {
                        f.decode(newConfig, type)
                        f.decode(newPicture, type)
                    }
                    RetainedConfigControl.DETACHED -> {
                        f.decode(newConfig, type)
                        f.decoder.setSurface(null)
                    }
                    RetainedConfigControl.INVALID_SURFACE -> {
                        f.decode(newConfig, type)
                        `when`(f.surface.isValid).thenReturn(false)
                    }
                    RetainedConfigControl.INVALID_BEFORE_CALLBACK -> {
                        f.decode(newConfig, type)
                        // The replay ownership check passes; callback-time validity must be
                        // rechecked after configuration invalidation, not captured once.
                        `when`(f.surface.isValid).thenReturn(true, false)
                    }
                    RetainedConfigControl.RETIRED_GENERATION -> {
                        f.decode(newConfig, type)
                        f.decoder.stop(DecoderStopPolicy.REASON_ACTIVITY_STOPPED)
                        assertTrue(f.decoder.isCurrentSurface(f.surface))
                    }
                }
                f.decoder.whenRetired {
                    retirementThread.set(Thread.currentThread())
                    try {
                        construction.set(mockConstruction(FfmpegVideoDecoder::class.java) { owner, _ ->
                            scriptNative(owner)
                        })
                    } finally { callbackEntered.countDown() }
                }
                pause.resume.countDown()
                assertTrue(callbackEntered.await(3, TimeUnit.SECONDS))
                checkNotNull(retirementThread.get()).join(3_000)
                assertFalse(retirementThread.get().isAlive)
                when (control) {
                    RetainedConfigControl.IDENTICAL, RetainedConfigControl.NEWER_KEYFRAME -> {
                        assertEquals(1, construction.get().constructed().size)
                        assertEquals(1, rendered.get())
                        assertEquals(1, repaired.get())
                        assertEquals(0, dropped.get())
                        assertTrue(f.decoder.hasCrediblePicture)
                        assertEquals(if (control == RetainedConfigControl.IDENTICAL) 800 else 1280, cachedWidth())
                    }
                    RetainedConfigControl.PPS_CHANGE -> {
                        assertArrayEquals("older IDR must not restore the old PPS", newPps, cache.latest(8))
                        assertEquals("PPS-only changes do not need a resolution change", 800, cachedWidth())
                        assertTrue(construction.get().constructed().isEmpty())
                        assertEquals(0, rendered.get())
                        assertEquals(0, repaired.get())
                        assertEquals(1, dropped.get())
                        assertFalse(f.decoder.hasCrediblePicture)
                    }
                    RetainedConfigControl.DETACHED, RetainedConfigControl.INVALID_SURFACE,
                    RetainedConfigControl.INVALID_BEFORE_CALLBACK, RetainedConfigControl.RETIRED_GENERATION -> {
                        assertTrue(construction.get().constructed().isEmpty())
                        assertEquals(0, rendered.get())
                        assertEquals(0, repaired.get())
                        assertEquals(0, dropped.get())
                        assertEquals(1280, cachedWidth())
                        if (control == RetainedConfigControl.INVALID_BEFORE_CALLBACK) {
                            assertEquals("configuration invalidation happened before callback suppression",
                                1L, f.decoder.sessionHealth().framesDropped)
                        }
                    }
                }
                construction.getAndSet(null)?.close()
                if (control == RetainedConfigControl.PPS_CHANGE) {
                    val pictureOnly = oldPicture.copyOfRange(oldConfig.size, oldPicture.size)
                    assertTrue(VideoKeyframeScanner.containsKeyframe(pictureOnly, 0, pictureOnly.size, false))
                    mockConstruction(FfmpegVideoDecoder::class.java) { owner, _ -> scriptNative(owner) }.use {
                        f.decode(pictureOnly, type)
                        assertArrayEquals(newPps, cache.latest(8))
                        assertEquals(1, rendered.get())
                        assertEquals(1, repaired.get())
                        assertTrue(f.decoder.hasCrediblePicture)
                    }
                }
            } finally {
                pause.resume.countDown()
                retirementThread.get()?.join(3_000)
                construction.getAndSet(null)?.close()
            }
        }
    }
}
