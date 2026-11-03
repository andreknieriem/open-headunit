package com.andrerinas.openheadunit.aap

import android.app.Application
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.media.MediaCodec
import android.content.pm.PackageManager
import android.media.MediaFormat
import android.util.Rational
import android.widget.FrameLayout
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.AppComponent
import com.andrerinas.openheadunit.decoder.video.DecoderStopPolicy
import com.andrerinas.openheadunit.decoder.video.FfmpegVideoDecoder
import com.andrerinas.openheadunit.decoder.video.GalVideoOutputTiming
import com.andrerinas.openheadunit.decoder.video.VideoDecoder
import com.andrerinas.openheadunit.decoder.video.VideoDimensionsListener
import com.andrerinas.openheadunit.decoder.video.VideoSize
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.HeadUnitScreenConfig
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.view.IProjectionView
import com.andrerinas.openheadunit.view.ProjectionView
import com.andrerinas.openheadunit.view.TextureProjectionView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class VideoDimensionsPublicationTest {
    private class Fixture : AutoCloseable {
        private val base = RuntimeEnvironment.getApplication()
        val settings = spy(Settings(base))
        private val previousHudMirroring = settings.hudMirroring
        private val previousForcedScale = HeadUnitScreenConfig.forcedScale
        private val app = mock<App>()
        private val component = mock<AppComponent>()
        private val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = app
        }
        val decoder = VideoDecoder(settings)
        val activity = mock<AapProjectionActivity>()
        private val ui = LinkedBlockingDeque<Runnable>()
        private val softwareWorkers = mutableListOf<Thread>()
        private val softwareFailure = AtomicReference<Throwable>()
        private val formatChanged = VideoDecoder::class.java.getDeclaredMethod(
            "handleOutputFormatChange", MediaCodec::class.java, MediaFormat::class.java
        ).apply { isAccessible = true }
        var view: TextureProjectionView = newView()
            private set

        init {
            settings.hudMirroring = true
            App::class.java.getDeclaredField("component\$delegate")
                .apply { isAccessible = true }.set(app, lazyOf(component))
            whenever(component.settings).thenReturn(settings)
            whenever(component.videoDecoder).thenReturn(decoder)
            AapProjectionActivity::class.java.getDeclaredField("videoDecoder\$delegate")
                .apply { isAccessible = true }.set(activity, lazyOf(decoder))
            installView(view)
            doAnswer { ui.put(it.getArgument(0)); null }.whenever(activity).runOnUiThread(any())
            doCallRealMethod().whenever(activity).onVideoDimensionsChanged(any())
            decoder.dimensionsListener = activity
        }

        private fun newView() = TextureProjectionView(context).apply {
            layoutParams = FrameLayout.LayoutParams(1920, 1080)
            layout(0, 0, 1920, 1080)
        }
        private fun installView(target: IProjectionView) {
            AapProjectionActivity::class.java.getDeclaredField("projectionView")
                .apply { isAccessible = true }.set(activity, target)
        }
        fun replaceView() {
            view = newView()
            installView(view)
        }
        fun installSurfaceView(): ProjectionView {
            HeadUnitScreenConfig::class.java.getDeclaredField("forcedScale")
                .apply { isAccessible = true }.setBoolean(null, true)
            doReturn(Settings.VideoFitMode.FILL).whenever(settings).videoFitMode
            doReturn(true).whenever(settings).forcedScale
            doReturn(Settings.ViewMode.SURFACE).whenever(settings).viewMode
            val target = spy(ProjectionView(context).apply {
                layoutParams = FrameLayout.LayoutParams(1920, 1080)
                layout(0, 0, 1920, 1080)
            })
            // Preserve the real SurfaceHolder implementation while observing the platform call.
            doReturn(spy(target.holder)).whenever(target).holder
            installView(target)
            return target
        }
        fun own(thread: Thread) {
            set("running", true)
            set("outputThread", thread)
        }
        private fun set(name: String, value: Any?) {
            VideoDecoder::class.java.getDeclaredField(name).apply { isAccessible = true }.set(decoder, value)
        }
        fun seedPreStartCodec(): MediaCodec {
            // Run the real SPS/configuration fallback update, but replace only native creation.
            // No Start selection has run yet, so streamFps must read settings.fpsLimit.
            VideoDecoder::class.java.getDeclaredMethod("applyStreamDimensions",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, VideoDecoder.CodecType::class.java)
                .apply { isAccessible = true }.invoke(decoder, 800, 480, VideoDecoder.CodecType.H264)
            set("configuredWidth", 800)
            set("configuredHeight", 480)
            return mock<MediaCodec>().also { set("codec", it) }
        }
        fun publish(width: Int, height: Int) {
            publish(MediaFormat.createVideoFormat("video/avc", width, height))
        }
        fun publish(format: MediaFormat) {
            formatChanged.invoke(decoder, mock<MediaCodec>(), format)
        }
        fun startSoftware(width: Int, height: Int) {
            val backend = mock<FfmpegVideoDecoder>()
            whenever(backend.decode(any(), any(), any(), any())).thenReturn(1)
            whenever(backend.outputWidth).thenReturn(width)
            whenever(backend.outputHeight).thenReturn(height)
            val generation = VideoDecoder::class.java.getDeclaredField("decoderGeneration")
                .apply { isAccessible = true }.getLong(decoder)
            @Suppress("UNCHECKED_CAST")
            val queue = VideoDecoder::class.java.getDeclaredField("frameQueue")
                .apply { isAccessible = true }.get(decoder) as ArrayBlockingQueue<Any>
            val loop = VideoDecoder::class.java.getDeclaredMethod("softwareFeedThreadLoop",
                FfmpegVideoDecoder::class.java, GalVideoOutputTiming::class.java,
                Long::class.javaPrimitiveType, ArrayBlockingQueue::class.java).apply { isAccessible = true }
            val worker = Thread {
                try { loop.invoke(decoder, backend, GalVideoOutputTiming(null, 0), generation, queue) }
                catch (error: Throwable) { softwareFailure.set(error.cause ?: error) }
            }
            set("softwareVideoDecoder", backend)
            set("running", true)
            set("feedThread", worker)
            val bytes = byteArrayOf(0, 0, 1, 0x65, 0x42)
            val frame = VideoDecoder::class.java.getDeclaredMethod("prepareFrame", ByteArray::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
                .invoke(decoder, bytes, 0, bytes.size)
            queue.put(frame)
            softwareWorkers.add(worker)
            worker.start()
        }
        fun drainUi() {
            while (true) (ui.poll() ?: break).run()
        }
        fun runNewestUi() { checkNotNull(ui.pollLast()).run() }
        fun awaitAndRunUi() { checkNotNull(ui.poll(3, TimeUnit.SECONDS)) { "no dimension UI notification" }.run() }
        fun storedSize(target: IProjectionView = view): Pair<Int, Int> {
            val type = if (target is ProjectionView) ProjectionView::class.java else TextureProjectionView::class.java
            fun dimension(name: String) = type.getDeclaredField(name)
                .apply { isAccessible = true }.getInt(target)
            return dimension("videoWidth") to dimension("videoHeight")
        }
        override fun close() {
            settings.hudMirroring = previousHudMirroring
            HeadUnitScreenConfig::class.java.getDeclaredField("forcedScale")
                .apply { isAccessible = true }.setBoolean(null, previousForcedScale)
            decoder.stop("dimensions test cleanup")
            softwareWorkers.forEach { it.join(3_000); assertFalse(it.isAlive) }
            softwareFailure.get()?.let { throw AssertionError("software dimension worker failed", it) }
        }
    }

    private fun lateNotificationCannotReplaceCurrentSize(replaceView: Boolean) {
        Fixture().use { f ->
            val enteredCallback = CountDownLatch(1)
            val resumeCallback = CountDownLatch(1)
            val failure = AtomicReference<Throwable>()
            val previousLogger = AppLog.LOGGER
            AppLog.LOGGER = object : AppLog.Logger {
                override fun println(priority: Int, tag: String, msg: String) {
                    // The actual Activity callback has begun, after OutputEvents' owner check,
                    // but has not posted the UI mutation. Stop must not wait for this callback.
                    if (msg.contains("Received video dimensions: 800x480")) {
                        enteredCallback.countDown()
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                        while (resumeCallback.count != 0L && System.nanoTime() < deadline) {
                            try { resumeCallback.await(100, TimeUnit.MILLISECONDS) }
                            catch (_: InterruptedException) { }
                        }
                        check(resumeCallback.count == 0L) { "dimension callback was never released" }
                    }
                }
            }
            val oldView = f.view
            val old = Thread {
                try { f.publish(800, 480) }
                catch (error: Throwable) { failure.set(error.cause ?: error) }
            }
            f.own(old)
            try {
                old.start()
                assertTrue("Activity callback did not begin", enteredCallback.await(5, TimeUnit.SECONDS))
                f.decoder.stop("replace decoder after callback entry")
                if (replaceView) f.replaceView()
                f.own(Thread.currentThread())
                f.publish(1920, 1080)
                f.drainUi()
                assertEquals(1920 to 1080, f.storedSize())
                assertTrue("real scaler must apply the non-identity HUD mirror", f.view.scaleX < 0f)
                val currentScale = f.view.scaleX to f.view.scaleY
                resumeCallback.countDown()
                old.join(3_000)
                assertFalse("old callback did not finish", old.isAlive)
                failure.get()?.let { throw AssertionError("old dimension callback failed", it) }
                f.drainUi()
                assertEquals("retired callback overwrote the current view's stored dimensions", 1920 to 1080, f.storedSize())
                assertEquals("retired callback changed the current projection scale", currentScale, f.view.scaleX to f.view.scaleY)
                if (replaceView) assertEquals(0 to 0, f.storedSize(oldView))
            } finally {
                resumeCallback.countDown()
                old.join(3_000)
                AppLog.LOGGER = previousLogger
            }
        }
    }

    @Test fun `late callback cannot overwrite replacement decoder on the same view`() {
        lateNotificationCannotReplaceCurrentSize(replaceView = false)
    }

    @Test fun `late callback cannot overwrite replacement projection view`() {
        lateNotificationCannotReplaceCurrentSize(replaceView = true)
    }

    private fun selectionCannotOverwriteCurrentOutput(surfaceView: Boolean) {
        Fixture().use { f ->
            val target = if (surfaceView) f.installSurfaceView() else f.view
            val holder = (target as? ProjectionView)?.holder
            val codec = f.seedPreStartCodec()
            f.drainUi()
            f.own(Thread.currentThread())
            val enteredFps = CountDownLatch(1)
            val resumeFps = CountDownLatch(1)
            val failure = AtomicReference<Throwable>()
            doAnswer {
                enteredFps.countDown()
                check(resumeFps.await(10, TimeUnit.SECONDS)) { "FPS lookup was never released" }
                30
            }.whenever(f.settings).fpsLimit
            val selector = Thread {
                try { assertTrue(f.decoder.selectFormat(800, 480, 30)) }
                catch (error: Throwable) { failure.set(error) }
            }
            try {
                selector.start()
                assertTrue("selection did not reach FPS lookup", enteredFps.await(5, TimeUnit.SECONDS))
                // The same current output owner publishes and the real Activity/view applies it
                // after selectFormat's size comparison but before its selected-fallback update.
                f.publish(1920, 1080)
                f.drainUi()
                assertEquals(1920 to 1080, f.storedSize(target))
                if (holder != null) {
                    verify(holder).setFixedSize(1920, 1080)
                    clearInvocations(holder)
                }
                resumeFps.countDown()
                selector.join(3_000)
                assertFalse("selection did not finish", selector.isAlive)
                failure.get()?.let { throw AssertionError("format selection failed", it) }
                verify(codec, never()).stop()
                verify(codec, never()).release()
                // A new valid token must not make a stale selected fallback authoritative.
                f.decoder.notifyVideoDimensions()
                f.drainUi()
                // FILL's forced-scale SurfaceView path consumes dimensions as buffer geometry,
                // unlike TextureView's negotiated-layout scaler. Reject the actual stale call.
                if (holder != null) verify(holder, never()).setFixedSize(800, 480)
                assertEquals("reannouncement overwrote actual output dimensions", 1920 to 1080, f.storedSize(target))
                assertEquals(VideoSize(1920, 1080), f.decoder.videoSize)
            } finally {
                resumeFps.countDown()
                selector.join(3_000)
            }
        }
    }

    @Test fun `unchanged Start selection cannot overwrite output published during FPS lookup`() {
        selectionCannotOverwriteCurrentOutput(surfaceView = false)
    }

    @Test fun `unchanged Start selection cannot resize the actual Surface buffer to a stale fallback`() {
        selectionCannotOverwriteCurrentOutput(surfaceView = true)
    }

    @Test fun `unchanged Start selection before output preserves later actual dimensions`() {
        Fixture().use { f ->
            val codec = f.seedPreStartCodec()
            doReturn(30).whenever(f.settings).fpsLimit
            f.own(Thread.currentThread())
            assertTrue(f.decoder.selectFormat(800, 480, 30))
            f.publish(1920, 1080)
            f.drainUi()
            f.decoder.notifyVideoDimensions()
            f.drainUi()
            assertEquals(1920 to 1080, f.storedSize())
            assertEquals(VideoSize(1920, 1080), f.decoder.videoSize)
            verify(codec, never()).stop()
            verify(codec, never()).release()
        }
    }

    @Test fun `unchanged Start after output compares configuration rather than actual dimensions`() {
        Fixture().use { f ->
            val codec = f.seedPreStartCodec()
            doReturn(30).whenever(f.settings).fpsLimit
            f.own(Thread.currentThread())
            f.publish(1920, 1080)
            f.drainUi()
            repeat(2) { assertTrue(f.decoder.selectFormat(800, 480, 30)) }
            f.decoder.notifyVideoDimensions()
            f.drainUi()
            assertEquals(1920 to 1080, f.storedSize())
            assertEquals(VideoSize(1920, 1080), f.decoder.videoSize)
            verify(codec, never()).stop()
            verify(codec, never()).release()
        }
    }

    @Test fun `true Start size change matching actual output retires pending old format`() {
        Fixture().use { f ->
            val codec = f.seedPreStartCodec()
            doReturn(30).whenever(f.settings).fpsLimit
            f.own(Thread.currentThread())
            assertTrue(f.decoder.selectFormat(800, 480, 30))
            f.publish(1920, 1080)
            f.drainUi()
            val enteredFormat = CountDownLatch(1)
            val resumeFormat = CountDownLatch(1)
            val failure = AtomicReference<Throwable>()
            val format = mock<MediaFormat>()
            whenever(format.containsKey(MediaFormat.KEY_WIDTH)).thenReturn(true)
            whenever(format.containsKey(MediaFormat.KEY_HEIGHT)).thenReturn(true)
            whenever(format.getInteger(MediaFormat.KEY_WIDTH)).thenAnswer {
                enteredFormat.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (resumeFormat.count != 0L && System.nanoTime() < deadline) {
                    try { resumeFormat.await(100, TimeUnit.MILLISECONDS) }
                    catch (_: InterruptedException) { }
                }
                check(resumeFormat.count == 0L) { "old output format was never released" }
                1280
            }
            whenever(format.getInteger(MediaFormat.KEY_HEIGHT)).thenReturn(720)
            val old = Thread {
                try { f.publish(format) }
                catch (error: Throwable) { failure.set(error.cause ?: error) }
            }
            f.own(old)
            try {
                old.start()
                assertTrue("output did not reach format lookup", enteredFormat.await(5, TimeUnit.SECONDS))
                // The selected size really changes even though it now matches actual output.
                // Stop must retire the blocked worker without holding the publication lock.
                assertTrue(f.decoder.selectFormat(1920, 1080, 30))
                verify(codec).stop()
                verify(codec).release()
                f.decoder.notifyVideoDimensions()
                f.drainUi()
                assertEquals(1920 to 1080, f.storedSize())
                resumeFormat.countDown()
                old.join(3_000)
                assertFalse("retired output did not finish", old.isAlive)
                failure.get()?.let { throw AssertionError("old output failed", it) }
                f.decoder.notifyVideoDimensions()
                f.drainUi()
                assertEquals(1920 to 1080, f.storedSize())
                assertEquals(VideoSize(1920, 1080), f.decoder.videoSize)
                // With the owner absent, a different selection replaces the retained fallback.
                assertTrue(f.decoder.selectFormat(1280, 720, 30))
                f.decoder.notifyVideoDimensions()
                f.drainUi()
                assertEquals(1280 to 720, f.storedSize())
                assertEquals(VideoSize(1280, 720), f.decoder.videoSize)
            } finally {
                resumeFormat.countDown()
                old.join(3_000)
            }
        }
    }

    @Test fun `new selection fallback invalidates a queued earlier fallback notification`() {
        Fixture().use { f ->
            assertTrue(f.decoder.selectFormat(800, 480, 30))
            f.decoder.notifyVideoDimensions()
            assertTrue(f.decoder.selectFormat(1280, 720, 30))
            f.drainUi()
            assertEquals(0 to 0, f.storedSize())
            f.decoder.notifyVideoDimensions()
            f.drainUi()
            assertEquals(1280 to 720, f.storedSize())
        }
    }

    @Test fun `older dimensions of the same output cannot overwrite a newer UI revision`() {
        Fixture().use { f ->
            f.own(Thread.currentThread())
            f.publish(800, 480)
            f.publish(1920, 1080)
            f.runNewestUi()
            assertEquals(1920 to 1080, f.storedSize())
            val currentScale = f.view.scaleX to f.view.scaleY
            f.drainUi()
            assertEquals(1920 to 1080, f.storedSize())
            assertEquals(currentScale, f.view.scaleX to f.view.scaleY)
        }
    }

    @Test fun `Stop invalidates a notification already posted to the UI`() {
        Fixture().use { f ->
            f.own(Thread.currentThread())
            f.publish(800, 480)
            f.decoder.stop("stop before UI application")
            f.drainUi()
            assertEquals(0 to 0, f.storedSize())
        }
    }

    @Test fun `removing and reinstalling the same listener does not revive its queued notification`() {
        Fixture().use { f ->
            f.own(Thread.currentThread())
            f.publish(800, 480)
            f.decoder.dimensionsListener = mock<VideoDimensionsListener>()
            f.decoder.dimensionsListener = f.activity
            f.drainUi()
            assertEquals(0 to 0, f.storedSize())
            f.decoder.notifyVideoDimensions()
            f.drainUi()
            assertEquals(800 to 480, f.storedSize())
        }
    }

    @Test fun `view replacement without decoder retirement rejects the old target and reannounces current size`() {
        Fixture().use { f ->
            f.own(Thread.currentThread())
            f.publish(800, 480)
            val oldView = f.view
            f.replaceView()
            f.drainUi()
            assertEquals(0 to 0, f.storedSize(oldView))
            assertEquals(0 to 0, f.storedSize())
            f.decoder.notifyVideoDimensions()
            f.drainUi()
            assertEquals(800 to 480, f.storedSize())
            assertEquals(0 to 0, f.storedSize(oldView))
        }
    }

    @Test fun `dimensions received before view initialization are applied on surface reannouncement`() {
        Fixture().use { f ->
            AapProjectionActivity::class.java.getDeclaredField("projectionView")
                .apply { isAccessible = true }.set(f.activity, null)
            f.own(Thread.currentThread())
            f.publish(1920, 1080)
            f.drainUi()
            f.replaceView()
            f.decoder.notifyVideoDimensions()
            f.drainUi()
            assertEquals(1920 to 1080, f.storedSize())
        }
    }

    @Test fun `same size hardware replacement applies dimensions whose old notification was retired`() {
        Fixture().use { f ->
            f.own(Thread.currentThread())
            f.publish(800, 480)
            f.decoder.stop(DecoderStopPolicy.REASON_ACTIVITY_STOPPED)
            f.drainUi()
            assertEquals(0 to 0, f.storedSize())
            f.own(Thread.currentThread())
            f.publish(800, 480)
            f.drainUi()
            assertEquals(800 to 480, f.storedSize())
        }
    }

    @Test fun `unchanged selection preserves dimensions published by the current software owner`() {
        Fixture().use { f ->
            assertTrue(f.decoder.selectFormat(800, 480, 30))
            f.startSoftware(1920, 1080)
            f.awaitAndRunUi()
            assertEquals(1920 to 1080, f.storedSize())
            assertTrue(f.decoder.selectFormat(800, 480, 30))
            f.decoder.notifyVideoDimensions()
            f.drainUi()
            assertTrue(f.decoder.usingBundledSoftwareVideo)
            assertEquals(1920 to 1080, f.storedSize())
            assertEquals(VideoSize(1920, 1080), f.decoder.videoSize)
        }
    }

    @Test fun `same size software replacement applies dimensions whose old notification was retired`() {
        Fixture().use { f ->
            f.own(Thread.currentThread())
            f.publish(800, 480)
            f.decoder.stop(DecoderStopPolicy.REASON_ACTIVITY_STOPPED)
            f.drainUi()
            assertEquals(0 to 0, f.storedSize())
            f.startSoftware(800, 480)
            f.awaitAndRunUi()
            assertEquals(800 to 480, f.storedSize())
        }
    }
    private fun pipKeepsOneDimensionPublication(overlap: Boolean) {
        Fixture().use { f ->
            val previousPip = App.isPiPActive
            val entered = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val failure = AtomicReference<Throwable>()
            val submitted = AtomicReference<PictureInPictureParams>()
            f.own(Thread.currentThread())
            f.publish(800, 480)
            f.drainUi()
            if (!overlap) f.publish(1920, 1080)
            val proxy = spy(f.decoder)
            // Pause after the real immutable read; no lock covers the following UI work.
            // The output worker may publish while the platform PiP request is being prepared.
            doAnswer {
                val dimensions = f.decoder.videoSize
                entered.countDown()
                check(resume.await(5, TimeUnit.SECONDS)) { "PiP dimension read was not released" }
                dimensions
            }.whenever(proxy).videoSize
            AapProjectionActivity::class.java.getDeclaredField("videoDecoder\$delegate")
                .apply { isAccessible = true }.set(f.activity, lazyOf(proxy))
            val packageManager = mock<PackageManager>()
            whenever(packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)).thenReturn(true)
            whenever(f.activity.packageManager).thenReturn(packageManager)
            doAnswer { submitted.set(it.getArgument(0)); true }
                .whenever(f.activity).enterPictureInPictureMode(any())
            val enterPip = AapProjectionActivity::class.java.getDeclaredMethod("enterPiP").apply { isAccessible = true }
            val ui = Thread {
                try { enterPip.invoke(f.activity) }
                catch (error: Throwable) { failure.set(error.cause ?: error) }
            }
            try {
                ui.start()
                assertTrue("PiP never read the decoder dimensions", entered.await(5, TimeUnit.SECONDS))
                if (overlap) f.publish(1920, 1080)
                resume.countDown()
                ui.join(3_000)
                assertFalse("PiP request did not finish", ui.isAlive)
                failure.get()?.let { throw AssertionError("PiP request failed", it) }
                assertEquals(if (overlap) Rational(800, 480) else Rational(1920, 1080), submitted.get()?.aspectRatio)
                f.drainUi()
                assertEquals("reading PiP dimensions must not retire a queued current notification",
                    1920 to 1080, f.storedSize())
            } finally {
                resume.countDown()
                ui.join(3_000)
                App.isPiPActive = previousPip
            }
        }
    }

    @Test @Config(sdk = [33])
    fun `PiP cannot combine dimensions from different output publications`() {
        pipKeepsOneDimensionPublication(overlap = true)
    }

    @Test @Config(sdk = [33])
    fun `PiP uses the current complete dimensions without overlapping output`() {
        pipKeepsOneDimensionPublication(overlap = false)
    }
}
