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
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real session selection/recovery; platform output is scripted, not the decoder's decisions. */
class VideoCodecSessionTest {
    private class Fixture(preference: CodecType, forceSoftware: Boolean = false) : AutoCloseable {
        val decoder: VideoDecoder
        // An invalid platform target exposes the initialization gate without creating a codec,
        // touching a real Surface, or depending on a packaged FFmpeg library.
        val surface = mock(Surface::class.java)
        val errors = mutableListOf<String>()
        private val software = forceSoftware
        private val softwareCompletion = AtomicReference<CountDownLatch?>()
        private val softwareFailure = AtomicReference<Throwable?>()

        init {
            val context = mock(Context::class.java)
            val preferences = mock(SharedPreferences::class.java)
            `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences)
            `when`(preferences.getInt(anyString(), anyInt())).thenAnswer { it.arguments[1] }
            `when`(preferences.getString(anyString(), anyString())).thenAnswer { it.arguments[1] }
            `when`(preferences.getString(eq("video-codec"), anyString())).thenReturn(preference.settingsValue)
            `when`(preferences.getBoolean(eq("force-software-decoding"), anyBoolean())).thenReturn(forceSoftware)
            `when`(preferences.getInt(eq("software-video-decoder"), anyInt()))
                .thenReturn(Settings.SoftwareVideoDecoder.BUNDLED_FFMPEG.value)
            decoder = VideoDecoder(Settings(context), elapsedRealtime = { 1_000L })
            decoder.onDecoderError = { errors.add(it) }
            decoder.setSurface(surface)
            clearInvocations(surface)
        }

        fun decode(packet: ByteArray, announced: CodecType) {
            val completion = if (decoder.usingBundledSoftwareVideo) CountDownLatch(1) else null
            softwareCompletion.set(completion)
            decoder.decode(packet, 0, packet.size, software, announced)
            // A restart may have retired the software owner instead of queueing this packet.
            if (completion != null && decoder.usingBundledSoftwareVideo) {
                val completed = completion.await(3, TimeUnit.SECONDS)
                softwareFailure.get()?.let { throw AssertionError("software worker failed", it) }
                assertTrue("software worker did not finish frame publication", completed)
            }
        }

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
            `when`(owner.decode(org.mockito.kotlin.any(), anyInt(), anyInt(), anyLong()))
                .thenReturn(results.first(), *results.drop(1).toTypedArray())
            setOwner("softwareVideoDecoder", owner)
            val timing = GalVideoOutputTiming(null, 0)
            // Entering the next poll proves the preceding frame's publication AND event dispatch
            // finished. Native decode completion alone is too early for state/listener assertions.
            val queue = object : ArrayBlockingQueue<Any>(16) {
                private var hadFrame = false
                override fun poll(timeout: Long, unit: TimeUnit): Any? {
                    if (hadFrame) {
                        hadFrame = false
                        softwareCompletion.getAndSet(null)?.countDown()
                    }
                    return super.poll(timeout, unit).also { hadFrame = it != null }
                }
            }
            val generation = VideoDecoder::class.java.getDeclaredField("decoderGeneration")
                .apply { isAccessible = true }.getLong(decoder)
            val loop = VideoDecoder::class.java.getDeclaredMethod(
                "softwareFeedThreadLoop", FfmpegVideoDecoder::class.java, GalVideoOutputTiming::class.java,
                Long::class.javaPrimitiveType, ArrayBlockingQueue::class.java
            ).apply { isAccessible = true }
            val worker = Thread {
                try { loop.invoke(decoder, owner, timing, generation, queue) }
                catch (t: Throwable) {
                    softwareFailure.set(t.cause ?: t)
                    softwareCompletion.getAndSet(null)?.countDown()
                }
            }
            setOwner("outputTiming", timing)
            setOwner("frameQueue", queue)
            setOwner("running", true)
            setOwner("feedThread", worker)
            worker.start()
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
            verify(owner).stop()
            verify(owner).release()
        }

        fun assertSelected(type: CodecType) {
            assertEquals(type, decoder.sessionHealth().finalCodec)
            assertFalse("decoder failure is not evidence of a different encoding", decoder.sessionHealth().codecFallbackUsed)
        }

        override fun close() { decoder.stop("test session ended") }
    }

    private fun slice(type: CodecType): ByteArray = when (type) {
        // AVC non-IDR 0x41 resembles an HEVC VPS; HEVC IDR 0x28 resembles an AVC PPS.
        CodecType.H264 -> byteArrayOf(0, 0, 0, 1, 0x41, 0x9a.toByte(), 0x22)
        CodecType.H265 -> byteArrayOf(0, 0, 0, 1, 0x28, 0x01, 0xaf.toByte())
    }

    private fun other(type: CodecType) = if (type == CodecType.H264) CodecType.H265 else CodecType.H264

    /** Mock only Android enumeration, so the removed AVC -> available HEVC fallback could run. */
    private fun withPlatformCodecs(hevcAvailable: Boolean = true, body: () -> Unit) {
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
            setSdk(21)
            mockConstruction(MediaCodecList::class.java) { list, _ ->
                `when`(list.codecInfos).thenReturn(codecs)
            }.use {
                assertEquals(hevcAvailable, VideoDecoder.isHevcDecoderAvailable(includeSoftware = false))
                body()
            }
        } finally { setSdk(oldSdk) }
    }

    @Test fun `selected dimensions replace initial fallback and survive decoder recovery`() {
        Fixture(preference = CodecType.H264).use { f ->
            assertTrue(f.decoder.selectFormat(1280, 720, 30))
            f.decode(slice(CodecType.H264), CodecType.H264)
            assertEquals(VideoSize(1280, 720), f.decoder.videoSize)
            assertFalse(f.decoder.selectFormat(0, 480, 30))
            assertFalse(f.decoder.selectFormat(800, 480, 0))
            assertEquals(1280, f.decoder.videoSize.width)
            assertTrue(f.decoder.selectFormat(800, 480, 60))
            f.decoder.stop("restart: format retention")
            f.decode(slice(CodecType.H264), CodecType.H264)
            assertEquals(VideoSize(800, 480), f.decoder.videoSize)
        }
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
                            repeat(8) { f.decode(packet, CodecType.H264) }
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
            verify(owner).stop()
            assertFalse(f.decoder.usingBundledSoftwareVideo)
            assertFalse(f.decoder.hasCrediblePicture)
            assertTrue(f.decoder.hasRenderedThisSession)
            assertEquals(3L, f.decoder.sessionHealth().framesRendered)
            f.assertSelected(type)
        }
    }
}
