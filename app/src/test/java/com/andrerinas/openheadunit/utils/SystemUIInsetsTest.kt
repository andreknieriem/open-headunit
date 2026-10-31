package com.andrerinas.openheadunit.utils

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.AppComponent
import com.andrerinas.openheadunit.aap.AapTransport
import com.andrerinas.openheadunit.aap.AudioSessionConfig
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.messages.ServiceDiscoveryResponse
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.decoder.video.VideoDecoder
import com.andrerinas.openheadunit.input.TouchCoordinateMapper
import com.andrerinas.openheadunit.view.ProjectionView
import com.andrerinas.openheadunit.view.ProjectionViewScaler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w1000dp-h600dp-land-mdpi")
class SystemUIInsetsTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var root: FrameLayout
    private lateinit var child: View
    private lateinit var projectionContext: Context
    private var lastTransport: AapTransport? = null
    private var originalTarget = 0

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        originalTarget = app.applicationInfo.targetSdkVersion
        app.applicationInfo.targetSdkVersion = 36
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        root = FrameLayout(activity)
        child = View(activity)
        root.addView(child, FrameLayout.LayoutParams(-1, -1))
        activity.setContentView(root)
        Settings(activity).apply {
            insetLeft = 3
            insetTop = 5
            insetRight = 7
            insetBottom = 11
        }
    }

    @After
    fun tearDown() {
        HeadUnitScreenConfig.onMarginsDiverged = null
        HeadUnitScreenConfig.clearAnnouncedMargins()
        HeadUnitScreenConfig.unlockResolution()
        controller.pause().stop().destroy()
        RuntimeEnvironment.getApplication().applicationInfo.targetSdkVersion = originalTarget
    }

    private fun apply(mode: Settings.FullscreenMode = Settings.FullscreenMode.NONE) {
        // These views stand in for settings/home/projection roots; only projection/home record a canvas.
        SystemUI.apply(controller.get().window, root, mode, notesCanvas = false)
    }

    private fun insets(
        status: Insets = Insets.of(0, 24, 0, 0),
        navigation: Insets = Insets.of(0, 0, 0, 48),
        cutout: Insets = Insets.of(30, 32, 0, 0),
        ime: Insets = Insets.NONE,
    ): WindowInsetsCompat = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.statusBars(), status)
        .setInsets(WindowInsetsCompat.Type.navigationBars(), navigation)
        .setInsets(WindowInsetsCompat.Type.displayCutout(), cutout)
        .setInsets(WindowInsetsCompat.Type.ime(), ime)
        .build()

    private fun dispatch(insets: WindowInsetsCompat): WindowInsetsCompat =
        ViewCompat.dispatchApplyWindowInsets(root, insets)

    private fun assertGeometry(left: Int, top: Int, right: Int, bottom: Int, width: Int = 1000, height: Int = 600) {
        assertEquals(listOf(left, top, right, bottom), listOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom))
        completeFrame(width, height)
        assertEquals(left, child.left)
        assertEquals(top, child.top)
        assertEquals(width - right, child.right)
        assertEquals(height - bottom, child.bottom)
        // HeadUnitScreenConfig subtracts the reported root padding from the content, not again
        // from this already-reduced child. Its canvas must match the view's drawable rectangle.
        val canvas = ProjectionCanvasPolicy.canvas(width, height, left + right, top + bottom)
        assertEquals(child.width, canvas.width)
        assertEquals(child.height, canvas.height)
    }

    @Test
    fun `enforced NONE uses the union of bars cutout and keyboard plus each manual margin once`() {
        apply()
        assertTrue(dispatch(insets()).isConsumed)
        assertGeometry(33, 37, 7, 59)

        assertTrue(dispatch(insets(ime = Insets.of(0, 0, 0, 240))).isConsumed)
        assertGeometry(33, 37, 7, 251)

        dispatch(insets())
        assertGeometry(33, 37, 7, 59)
        // Repeated focus/application and inset dispatch must not accumulate manual or system padding.
        apply()
        dispatch(insets())
        dispatch(insets())
        assertGeometry(33, 37, 7, 59)
    }

    @Test
    fun `NONE follows changing visible bars and cutout edges without changing the root bounds`() {
        apply()
        dispatch(insets())
        assertGeometry(33, 37, 7, 59)
        dispatch(insets(status = Insets.NONE, navigation = Insets.of(0, 0, 42, 0), cutout = Insets.of(0, 0, 50, 0)))
        assertGeometry(3, 5, 57, 11)
        dispatch(insets(status = Insets.NONE, navigation = Insets.NONE, cutout = Insets.NONE))
        assertGeometry(3, 5, 7, 11)
    }

    @Test
    fun `API 35 target 35 also owns NONE fitting`() {
        controller.get().applicationInfo.targetSdkVersion = 35
        apply()
        assertTrue(dispatch(insets()).isConsumed)
        assertGeometry(33, 37, 7, 59)
    }

    @Test
    fun `API 35 with older target leaves NONE automatic fitting intact`() {
        controller.get().applicationInfo.targetSdkVersion = 34
        apply()
        assertFalse(dispatch(insets()).isConsumed)
        // The OS already excluded its bars from this supplied content rectangle.
        assertGeometry(3, 5, 7, 11, width = 970, height = 520)
    }

    @Test
    @Config(sdk = [34])
    fun `older runtime leaves NONE system fitting and keyboard resize intact`() {
        apply()
        assertFalse(dispatch(insets()).isConsumed)
        assertGeometry(3, 5, 7, 11, width = 970, height = 520)
        assertFalse(dispatch(insets(ime = Insets.of(0, 0, 0, 240))).isConsumed)
        assertGeometry(3, 5, 7, 11, width = 970, height = 328)
    }

    @Test
    fun `immersive still overlays bars and cutout but avoids the keyboard`() {
        apply(Settings.FullscreenMode.IMMERSIVE)
        assertTrue(dispatch(insets()).isConsumed)
        assertGeometry(3, 5, 7, 11)
        dispatch(insets(ime = Insets.of(0, 0, 0, 240)))
        assertGeometry(3, 5, 7, 251)
    }

    @Test
    fun `immersive with notch still reserves only cutout and keyboard`() {
        apply(Settings.FullscreenMode.IMMERSIVE_WITH_NOTCH)
        dispatch(insets())
        assertGeometry(33, 37, 7, 11)
        dispatch(insets(ime = Insets.of(0, 0, 0, 240)))
        assertGeometry(33, 37, 7, 251)
    }

    @Test
    fun `status only still reserves navigation cutout and keyboard without status bar`() {
        apply(Settings.FullscreenMode.STATUS_ONLY)
        dispatch(insets(status = Insets.of(0, 80, 0, 0)))
        assertGeometry(33, 37, 7, 59)
        dispatch(insets(status = Insets.of(0, 80, 0, 0), ime = Insets.of(0, 0, 0, 240)))
        assertGeometry(33, 37, 7, 251)
    }

    // Only the app/decoder dependencies are mocked; the SurfaceView, scaler and screen config
    // are the production path that a fixed-size video buffer uses.
    private fun prepareProjection(
        width: Int = 1000,
        height: Int = 600,
        resolution: Settings.Resolution = Settings.Resolution._1280x720,
    ): Settings {
        val activity = controller.get()
        val settings = Settings(activity).apply {
            screenOrientation = Settings.ScreenOrientation.LANDSCAPE
            fullscreenMode = Settings.FullscreenMode.IMMERSIVE
            viewMode = Settings.ViewMode.SURFACE
            forcedScale = true
            videoFitMode = Settings.VideoFitMode.FILL
            resolutionId = resolution.id
            dpiPixelDensity = 160
            pixelAspectRatioE4 = 10000
            narrowBandProfileCap = false
            videoProfileStarvationCap = false
            enableRotary = true
            insetLeft = 0
            insetTop = 0
            insetRight = 0
            insetBottom = 0
        }
        val app = mock<App>()
        val component = mock<AppComponent>()
        whenever(component.settings).thenReturn(settings)
        whenever(component.videoDecoder).thenReturn(mock<VideoDecoder>())
        whenever(component.commManager).thenReturn(mock<CommManager>())
        App::class.java.getDeclaredField("component\$delegate").apply { isAccessible = true }
            .set(app, lazyOf(component))
        projectionContext = object : ContextWrapper(activity) {
            override fun getApplicationContext(): Context = app
        }
        root.removeAllViews()
        child = ProjectionView(projectionContext).apply {
            ownsDecoder = false
            appliesProjectionScale = false
        }
        root.addView(child, FrameLayout.LayoutParams(-1, -1))
        completeFrame(width, height)
        HeadUnitScreenConfig.init(activity, activity.resources.displayMetrics, settings)
        applyProjection(Settings.FullscreenMode.IMMERSIVE)
        dispatch(insets(cutout = Insets.NONE))
        completeFrame(width, height)
        HeadUnitScreenConfig.updateSurfaceDimensions(width, height)
        return settings
    }

    private fun applyProjection(mode: Settings.FullscreenMode) {
        SystemUI.apply(controller.get().window, root, mode) { scaleProjection() }
    }

    private fun scaleProjection() {
        ProjectionViewScaler.updateScale(
            child, HeadUnitScreenConfig.getNegotiatedWidth(), HeadUnitScreenConfig.getNegotiatedHeight(),
        )
    }

    private fun layoutRoot(width: Int, height: Int) {
        controller.get().findViewById<View>(android.R.id.content).layout(0, 0, width, height)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
    }

    /** Finish traversals explicitly; a scaler callback may request one more layout. */
    private fun completeFrame(width: Int = 1000, height: Int = 600) {
        repeat(4) {
            layoutRoot(width, height)
            root.viewTreeObserver.dispatchOnPreDraw()
            if (!root.isLayoutRequested && !child.isLayoutRequested) return
        }
        assertFalse("Projection layout did not settle", root.isLayoutRequested || child.isLayoutRequested)
    }

    private fun announce(
        transport: AapTransport? = null,
        newSession: Boolean = true,
    ): Control.ServiceDiscoveryResponse {
        // Most calls sample a fresh handshake at a particular traversal boundary. Repeated
        // responses within one session explicitly keep its first announcement below.
        if (newSession) {
            HeadUnitScreenConfig.clearAnnouncedMargins()
            HeadUnitScreenConfig.unlockResolution()
        }
        val responseTransport = transport ?: if (newSession) mock<AapTransport>()
            else checkNotNull(lastTransport) { "No prior transport for a repeated response" }
        lastTransport = responseTransport
        val settings = App.provide(projectionContext).settings
        val message = ServiceDiscoveryResponse(projectionContext, AudioSessionConfig.from(settings), responseTransport)
        return message.parse(Control.ServiceDiscoveryResponse.newBuilder()).build()
    }

    private fun primary(response: Control.ServiceDiscoveryResponse) =
        response.servicesList.single { it.id == Channel.ID_VID }.mediaSinkService.getVideoConfigs(0)

    private fun assertWire(
        response: Control.ServiceDiscoveryResponse,
        marginW: Int,
        marginH: Int,
        par: Int,
        resolution: Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType =
            Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1280x720,
        width: Int = 1280,
        height: Int = 720,
    ) {
        val video = primary(response)
        assertEquals(resolution, video.codecResolution)
        assertEquals(marginW, video.marginWidth)
        assertEquals(marginH, video.marginHeight)
        assertEquals(par, video.pixelAspectRatioE4)
        assertEquals(160, video.density)
        val input = response.servicesList.single { it.id == Channel.ID_INP }.inputSourceService
        assertEquals(width, input.touchscreen.width)
        assertEquals(height, input.touchscreen.height)
        assertTrue(input.hasTouchpad())
        assertEquals(width, input.touchpad.width)
        assertEquals(height, input.touchpad.height)
    }

    private fun assertProjection(left: Int, top: Int, right: Int, bottom: Int) {
        assertGeometry(left, top, right, bottom)
        assertEquals(child.width, child.layoutParams.width)
        assertEquals(child.height, child.layoutParams.height)
        assertEquals(child.width, HeadUnitScreenConfig.getUsableWidth())
        assertEquals(child.height, HeadUnitScreenConfig.getUsableHeight())
        val point = TouchCoordinateMapper.map(
            rawX = child.width.toFloat(),
            rawY = child.height.toFloat(),
            inputSurfaceWidth = child.width.toFloat(),
            inputSurfaceHeight = child.height.toFloat(),
            negotiatedWidth = HeadUnitScreenConfig.getNegotiatedWidth(),
            negotiatedHeight = HeadUnitScreenConfig.getNegotiatedHeight(),
            marginWidth = HeadUnitScreenConfig.getWidthMargin().toFloat(),
            marginHeight = HeadUnitScreenConfig.getHeightMargin().toFloat(),
            fitMode = Settings.VideoFitMode.FILL,
            hudMirroring = false,
        )
        assertEquals(HeadUnitScreenConfig.getNegotiatedWidth() - HeadUnitScreenConfig.getWidthMargin(), point.x)
        assertEquals(HeadUnitScreenConfig.getNegotiatedHeight() - HeadUnitScreenConfig.getHeightMargin(), point.y)
    }

    @Test
    fun `forced surface follows local NONE override and immersive restore without renegotiation`() {
        val settings = prepareProjection()
        val hash = HeadUnitScreenConfig.computeSettingsHash(settings)
        HeadUnitScreenConfig.lockResolution()
        val videoSize = HeadUnitScreenConfig.getNegotiatedWidth() to HeadUnitScreenConfig.getNegotiatedHeight()
        applyProjection(Settings.FullscreenMode.NONE)
        // Re-entering init from a margin notification must also see the new canvas, not just the
        // explicit onInsetsChanged callback at the completed layout boundary.
        val callbackHeights = mutableListOf<Int>()
        HeadUnitScreenConfig.recordAnnouncedMargins(1, 1)
        HeadUnitScreenConfig.onMarginsDiverged = {
            scaleProjection()
            callbackHeights += HeadUnitScreenConfig.getUsableHeight()
            false
        }
        dispatch(insets(cutout = Insets.NONE))
        assertProjection(0, 24, 0, 48)
        assertTrue(callbackHeights.isNotEmpty())
        assertTrue(callbackHeights.all { it == 528 })
        HeadUnitScreenConfig.onMarginsDiverged = null
        assertFalse(HeadUnitScreenConfig.updateSurfaceDimensions(videoSize.first, videoSize.second))
        scaleProjection()
        assertProjection(0, 24, 0, 48)
        HeadUnitScreenConfig.updateSurfaceDimensions(1000, 528)

        applyProjection(Settings.FullscreenMode.IMMERSIVE)
        dispatch(insets(cutout = Insets.NONE))
        assertProjection(0, 0, 0, 0)
        assertEquals(hash, HeadUnitScreenConfig.computeSettingsHash(settings))
        assertTrue(HeadUnitScreenConfig.isResolutionLocked)
        assertEquals(videoSize, HeadUnitScreenConfig.getNegotiatedWidth() to HeadUnitScreenConfig.getNegotiatedHeight())
    }

    @Test
    fun `fixed root follows visible inset changes but preserves a surface in an unchanged window`() {
        prepareProjection()
        applyProjection(Settings.FullscreenMode.NONE)
        dispatch(insets(cutout = Insets.NONE))
        assertProjection(0, 24, 0, 48)
        HeadUnitScreenConfig.updateSurfaceDimensions(960, 500)
        dispatch(insets(cutout = Insets.NONE))
        completeFrame()
        assertEquals(960, child.layoutParams.width)
        assertEquals(500, child.layoutParams.height)

        dispatch(insets(navigation = Insets.of(0, 0, 42, 0), cutout = Insets.NONE))
        assertProjection(0, 24, 42, 0)
    }

    @Test
    fun `settings change keeps the bars already reported before scaler init`() {
        val settings = prepareProjection()
        applyProjection(Settings.FullscreenMode.NONE)
        dispatch(insets(cutout = Insets.NONE))
        settings.insetTop = 5
        applyProjection(Settings.FullscreenMode.NONE)
        dispatch(insets(cutout = Insets.NONE))
        scaleProjection()
        assertEquals(600, HeadUnitScreenConfig.getUsableHeight())
        assertWire(announce(), marginW = 280, marginH = 120, par = 10000)
        assertProjection(0, 29, 0, 48)
        scaleProjection()
        assertProjection(0, 29, 0, 48)
        assertWire(announce(), marginW = 0, marginH = 0, par = 10755)
    }

    @Test
    fun `newly resolved orientation preserves bars reported before screen init`() {
        val settings = prepareProjection()
        val activity = controller.get()
        val configuration = activity.resources.configuration
        val orientation = configuration.orientation
        try {
            settings.screenOrientation = Settings.ScreenOrientation.SYSTEM
            configuration.orientation = android.content.res.Configuration.ORIENTATION_UNDEFINED
            HeadUnitScreenConfig.init(activity, activity.resources.displayMetrics, settings)
            configuration.orientation = orientation

            // Insets can arrive before init has resolved the window's effective orientation.
            SystemUI.apply(activity.window, root, Settings.FullscreenMode.NONE)
            dispatch(insets(cutout = Insets.NONE))
            completeFrame()
            HeadUnitScreenConfig.init(activity, activity.resources.displayMetrics, settings)
            scaleProjection()
            assertProjection(0, 24, 0, 48)
        } finally {
            configuration.orientation = orientation
        }
    }

    @Test
    fun `keyboard dispatch does not replace the permanent projection canvas`() {
        prepareProjection()
        applyProjection(Settings.FullscreenMode.NONE)
        dispatch(insets(cutout = Insets.NONE))
        completeFrame()
        HeadUnitScreenConfig.updateSurfaceDimensions(1000, 528)
        for (ime in listOf(Insets.of(0, 0, 0, 240), Insets.of(180, 0, 0, 0))) {
            dispatch(insets(cutout = Insets.NONE, ime = ime))
            completeFrame()
            scaleProjection()
            assertEquals(1000, HeadUnitScreenConfig.getUsableWidth())
            assertEquals(528, HeadUnitScreenConfig.getUsableHeight())
        }
        dispatch(insets(cutout = Insets.NONE))
        assertProjection(0, 24, 0, 48)
    }

    @Test
    fun `settings keyboard cannot change a later projection window measurement`() {
        val other = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val otherRoot = FrameLayout(other.get())
            other.get().setContentView(otherRoot)
            prepareProjection()
            applyProjection(Settings.FullscreenMode.NONE)
            dispatch(insets(cutout = Insets.NONE))
            completeFrame()
            HeadUnitScreenConfig.updateSurfaceDimensions(1000, 528)

            SystemUI.apply(other.get().window, otherRoot, Settings.FullscreenMode.NONE, notesCanvas = false)
            ViewCompat.dispatchApplyWindowInsets(otherRoot, insets(cutout = Insets.NONE, ime = Insets.of(0, 0, 0, 240)))
            assertEquals(240, otherRoot.paddingBottom)

            // The projection's layout callback can arrive while settings owns keyboard focus.
            val content = controller.get().findViewById<View>(android.R.id.content)
            content.layout(0, 0, 1000, 601)
            content.layout(0, 0, 1000, 600)
            completeFrame()
            scaleProjection()
            assertEquals(1000, child.layoutParams.width)
            assertEquals(528, child.layoutParams.height)
            assertEquals(528, HeadUnitScreenConfig.getUsableHeight())
        } finally {
            other.pause().stop().destroy()
        }
    }

    @Test
    fun `non projection window cannot retire or publish the projection canvas`() {
        val other = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val otherRoot = FrameLayout(other.get())
            other.get().setContentView(otherRoot)
            other.get().findViewById<View>(android.R.id.content).layout(0, 0, 400, 300)
            // Keep Activity creation/layout outside the ownership check: both windows exist
            // before the measured projection surface is installed.
            val settings = prepareProjection()
            applyProjection(Settings.FullscreenMode.NONE)
            dispatch(insets(cutout = Insets.NONE))
            completeFrame()
            HeadUnitScreenConfig.updateSurfaceDimensions(960, 500)
            SystemUI.apply(other.get().window, otherRoot, Settings.FullscreenMode.NONE, notesCanvas = false)
            ViewCompat.dispatchApplyWindowInsets(otherRoot, insets(cutout = Insets.NONE))
            otherRoot.layout(0, 0, 400, 300)
            HeadUnitScreenConfig.init(controller.get(), controller.get().resources.displayMetrics, settings)
            assertEquals(960, HeadUnitScreenConfig.getUsableWidth())
            assertEquals(500, HeadUnitScreenConfig.getUsableHeight())
            dispatch(insets(cutout = Insets.NONE))
            completeFrame()
            assertEquals(960, child.layoutParams.width)
            assertEquals(500, child.layoutParams.height)
        } finally {
            other.pause().stop().destroy()
        }
    }

    @Test
    fun `insets before layout cannot announce new padding against the previous canvas`() {
        prepareProjection()
        applyProjection(Settings.FullscreenMode.NONE)
        dispatch(insets(cutout = Insets.NONE))
        assertEquals(24, root.paddingTop)
        assertEquals(48, root.paddingBottom)

        // A phone can ask now, before the requested layout. It must get the last whole frame.
        val whilePending = announce()
        assertWire(whilePending, marginW = 280, marginH = 120, par = 10000)
        assertEquals(1000, HeadUnitScreenConfig.getUsableWidth())
        assertEquals(600, HeadUnitScreenConfig.getUsableHeight())

        completeFrame()
        assertProjection(0, 24, 0, 48)
        assertWire(announce(newSession = false), marginW = 280, marginH = 120, par = 10000)
        assertWire(announce(), marginW = 0, marginH = 0, par = 10653)
        assertWire(whilePending, marginW = 280, marginH = 120, par = 10000)
    }

    @Test
    fun `size before insets stays private until both halves complete the same layout`() {
        prepareProjection()
        applyProjection(Settings.FullscreenMode.NONE)
        // This is the old fitted content size arriving before the new edge-to-edge insets.
        layoutRoot(900, 560)
        root.viewTreeObserver.dispatchOnPreDraw()
        assertWire(announce(), marginW = 280, marginH = 120, par = 10000)

        dispatch(insets(cutout = Insets.NONE))
        assertWire(announce(), marginW = 280, marginH = 120, par = 10000)
        completeFrame()
        assertProjection(0, 24, 0, 48)
        assertWire(announce(), marginW = 0, marginH = 0, par = 10653)
    }

    @Test
    fun `repeated focus apply preserves the completed window and a valid measured surface`() {
        prepareProjection()
        applyProjection(Settings.FullscreenMode.NONE)
        dispatch(insets(cutout = Insets.NONE))
        completeFrame()
        HeadUnitScreenConfig.updateSurfaceDimensions(960, 500)
        scaleProjection()
        completeFrame()
        assertWire(announce(), marginW = 0, marginH = 0, par = 10800)

        repeat(3) {
            applyProjection(Settings.FullscreenMode.NONE)
            assertEquals(24, root.paddingTop)
            assertEquals(48, root.paddingBottom)
            assertWire(announce(), marginW = 0, marginH = 0, par = 10800)
            dispatch(insets(cutout = Insets.NONE))
            assertWire(announce(), marginW = 0, marginH = 0, par = 10800)
            completeFrame()
            assertEquals(960, child.width)
            assertEquals(500, child.height)
            assertWire(announce(), marginW = 0, marginH = 0, par = 10800)
        }
    }

    @Test
    fun `layout with old orientation insets is not a new projection measurement`() {
        val settings = prepareProjection()
        val configuration = controller.get().resources.configuration
        val originalOrientation = configuration.orientation
        try {
            settings.screenOrientation = Settings.ScreenOrientation.SYSTEM
            SystemUI.apply(controller.get().window, root, Settings.FullscreenMode.NONE)
            dispatch(insets(cutout = Insets.NONE))
            completeFrame()
            assertWire(announce(), marginW = 0, marginH = 0, par = 10653)

            configuration.orientation = android.content.res.Configuration.ORIENTATION_PORTRAIT
            layoutRoot(600, 1000)
            root.viewTreeObserver.dispatchOnPreDraw()
            assertEquals(1000, HeadUnitScreenConfig.getUsableWidth())
            assertEquals(528, HeadUnitScreenConfig.getUsableHeight())
            assertWire(announce(), marginW = 0, marginH = 0, par = 10653)

            dispatch(insets(status = Insets.of(0, 24, 0, 0), navigation = Insets.of(0, 0, 0, 48), cutout = Insets.NONE))
            completeFrame(600, 1000)
            assertEquals(600, HeadUnitScreenConfig.getUsableWidth())
            assertEquals(928, HeadUnitScreenConfig.getUsableHeight())
        } finally {
            configuration.orientation = originalOrientation
        }
    }

    @Test
    fun `serialized video and input retain one geometry through a transition while response is built`() {
        val settings = prepareProjection(2400, 1080, Settings.Resolution._1920x1080)
        settings.screenOrientation = Settings.ScreenOrientation.SYSTEM
        applyProjection(Settings.FullscreenMode.IMMERSIVE)
        dispatch(insets(cutout = Insets.NONE))
        completeFrame(2400, 1080)
        val configuration = controller.get().resources.configuration
        val originalOrientation = configuration.orientation
        try {
            val transport = mock<AapTransport>()
            doAnswer {
                // Production calls this after building video and before constructing input.
                configuration.orientation = android.content.res.Configuration.ORIENTATION_PORTRAIT
                applyProjection(Settings.FullscreenMode.NONE)
                dispatch(insets(cutout = Insets.NONE))
                completeFrame(600, 1000)
                null
            }.whenever(transport).recordAnnouncedVideoCodec(any())

            val response = announce(transport)
            assertWire(
                response, marginW = 0, marginH = 0, par = 12500,
                resolution = Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1920x1080,
                width = 1920, height = 1080,
            )
            val fallback = response.servicesList.single { it.id == Channel.ID_VID }.mediaSinkService.getVideoConfigs(1)
            assertEquals(Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1280x720, fallback.codecResolution)
            assertEquals(0, fallback.marginWidth)
            assertEquals(0, fallback.marginHeight)
            assertEquals(12500, fallback.pixelAspectRatioE4)
            assertEquals(107, fallback.density)
            assertEquals(600, HeadUnitScreenConfig.getUsableWidth())
            assertEquals(928, HeadUnitScreenConfig.getUsableHeight())
            assertEquals(1920, HeadUnitScreenConfig.getNegotiatedWidth())
            assertEquals(1080, HeadUnitScreenConfig.getNegotiatedHeight())
            assertTrue(HeadUnitScreenConfig.isResolutionLocked)
            assertWire(
                announce(newSession = false), marginW = 0, marginH = 0, par = 12500,
                resolution = Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1920x1080,
                width = 1920, height = 1080,
            )
        } finally {
            configuration.orientation = originalOrientation
        }
    }

    @Test
    fun `bottom and side keyboards cannot become a serialized projection canvas`() {
        prepareProjection()
        applyProjection(Settings.FullscreenMode.NONE)
        dispatch(insets(cutout = Insets.NONE))
        completeFrame()
        assertWire(announce(), marginW = 0, marginH = 0, par = 10653)
        for ((ime, size) in listOf(
            Insets.of(0, 0, 0, 240) to (1000 to 360),
            Insets.of(180, 0, 0, 0) to (820 to 600),
        )) {
            dispatch(insets(cutout = Insets.NONE, ime = ime))
            completeFrame(size.first, size.second)
            assertFalse(HeadUnitScreenConfig.updateSurfaceDimensions(size.first, size.second))
            assertWire(announce(), marginW = 0, marginH = 0, par = 10653)
            assertEquals(1000, HeadUnitScreenConfig.getUsableWidth())
            assertEquals(528, HeadUnitScreenConfig.getUsableHeight())
        }
        dispatch(insets(cutout = Insets.NONE))
        completeFrame()
        assertProjection(0, 24, 0, 48)
        assertWire(announce(), marginW = 0, marginH = 0, par = 10653)
    }

    @Test
    fun `surface callback cannot publish dimensions while inset layout is pending`() {
        prepareProjection()
        applyProjection(Settings.FullscreenMode.NONE)
        dispatch(insets(cutout = Insets.NONE))
        assertFalse(HeadUnitScreenConfig.updateSurfaceDimensions(900, 488))
        assertWire(announce(), marginW = 280, marginH = 120, par = 10000)
        completeFrame()
        assertProjection(0, 24, 0, 48)
        assertWire(announce(), marginW = 0, marginH = 0, par = 10653)
    }

    @Test
    fun `one surface sample pending an unchanged layout is adopted and rescaled after pre draw`() {
        prepareProjection()
        applyProjection(Settings.FullscreenMode.NONE)
        dispatch(insets(cutout = Insets.NONE))
        completeFrame()
        applyProjection(Settings.FullscreenMode.NONE)
        assertTrue(root.isLayoutRequested)
        SystemUI.measureProjectionSurface(root, child) {
            HeadUnitScreenConfig.updateSurfaceDimensions(960, 500)
        }
        assertEquals(1000, HeadUnitScreenConfig.getUsableWidth())
        assertEquals(528, HeadUnitScreenConfig.getUsableHeight())

        // There is no second surface callback. Completing the requested traversal must deliver
        // the one measurement and apply its forced-scale layout, even though margins stay zero.
        completeFrame()
        assertEquals(960, HeadUnitScreenConfig.getUsableWidth())
        assertEquals(500, HeadUnitScreenConfig.getUsableHeight())
        assertEquals(960, child.width)
        assertEquals(500, child.height)
        assertWire(announce(), marginW = 0, marginH = 0, par = 10800)
    }

    @Test
    fun `queued surface from the old fitted view is discarded after the scaler lays out the new frame`() {
        prepareProjection()
        applyProjection(Settings.FullscreenMode.NONE)
        dispatch(insets(cutout = Insets.NONE))
        layoutRoot(1000, 600)
        assertEquals(600, child.height)
        SystemUI.measureProjectionSurface(root, child) {
            HeadUnitScreenConfig.updateSurfaceDimensions(1000, 600)
        }

        // The root's first completed pass publishes 528 usable rows. Its scaler callback then
        // requests the second layout, where the captured 600-row view no longer exists.
        completeFrame()
        assertProjection(0, 24, 0, 48)
        assertEquals(528, child.height)
        assertWire(announce(), marginW = 0, marginH = 0, par = 10653)
    }

    @Test
    fun `a new fitting policy discards a queued surface even when the root bounds stay equal`() {
        prepareProjection()
        applyProjection(Settings.FullscreenMode.IMMERSIVE)
        SystemUI.measureProjectionSurface(root, child) {
            HeadUnitScreenConfig.updateSurfaceDimensions(960, 500)
        }
        applyProjection(Settings.FullscreenMode.NONE)
        dispatch(insets(cutout = Insets.NONE))
        completeFrame()
        assertProjection(0, 24, 0, 48)
        assertEquals(1000, child.width)
        assertEquals(528, child.height)
        assertWire(announce(), marginW = 0, marginH = 0, par = 10653)
    }

    @Test
    fun `keyboard invalidates pending and newly delivered surface samples without replay on hide`() {
        prepareProjection()
        applyProjection(Settings.FullscreenMode.NONE)
        dispatch(insets(cutout = Insets.NONE))
        completeFrame()
        applyProjection(Settings.FullscreenMode.NONE)
        SystemUI.measureProjectionSurface(root, child) {
            HeadUnitScreenConfig.updateSurfaceDimensions(820, 528)
        }
        dispatch(insets(cutout = Insets.NONE, ime = Insets.of(180, 0, 0, 0)))
        SystemUI.measureProjectionSurface(root, child) {
            HeadUnitScreenConfig.updateSurfaceDimensions(640, 500)
        }
        completeFrame(820, 600)
        assertEquals(1000, HeadUnitScreenConfig.getUsableWidth())
        assertEquals(528, HeadUnitScreenConfig.getUsableHeight())
        assertWire(announce(), marginW = 0, marginH = 0, par = 10653)

        dispatch(insets(cutout = Insets.NONE))
        completeFrame()
        assertProjection(0, 24, 0, 48)
        assertEquals(1000, child.width)
        assertEquals(528, child.height)
        assertWire(announce(), marginW = 0, marginH = 0, par = 10653)
    }

    @Test
    fun `new transport captures the current frame without requiring activity or session cleanup`() {
        prepareProjection()
        val oldResponse = announce()
        assertWire(oldResponse, marginW = 280, marginH = 120, par = 10000)

        applyProjection(Settings.FullscreenMode.NONE)
        dispatch(insets(cutout = Insets.NONE))
        completeFrame()
        assertEquals(528, HeadUnitScreenConfig.getUsableHeight())
        assertWire(announce(newSession = false), marginW = 280, marginH = 120, par = 10000)

        // Remote EOF can recreate the transport while this Activity stays alive. No unlock,
        // clearAnnouncedMargins or lifecycle callback occurs between these handshakes.
        val nextResponse = announce(transport = mock(), newSession = false)
        assertWire(nextResponse, marginW = 0, marginH = 0, par = 10653)
        assertWire(oldResponse, marginW = 280, marginH = 120, par = 10000)
    }

    @Test
    fun `new transport retires the previous phones fallback without explicit cleanup`() {
        prepareProjection(2400, 1080, Settings.Resolution._1920x1080)
        val oldResponse = announce()
        assertTrue(HeadUnitScreenConfig.adoptFallback())
        assertEquals(1280, HeadUnitScreenConfig.getNegotiatedWidth())
        assertEquals(720, HeadUnitScreenConfig.getNegotiatedHeight())
        assertEquals(primary(oldResponse), primary(announce(newSession = false)))
        assertEquals(1280, HeadUnitScreenConfig.getNegotiatedWidth())
        assertEquals(720, HeadUnitScreenConfig.getNegotiatedHeight())

        val nextResponse = announce(transport = mock(), newSession = false)
        assertWire(
            nextResponse, marginW = 0, marginH = 0, par = 12500,
            resolution = Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1920x1080,
            width = 1920, height = 1080,
        )
        assertEquals(primary(oldResponse), primary(nextResponse))
        assertEquals(1920, HeadUnitScreenConfig.getNegotiatedWidth())
        assertEquals(1080, HeadUnitScreenConfig.getNegotiatedHeight())
        assertTrue(HeadUnitScreenConfig.hasOfferedFallback())
        assertTrue(HeadUnitScreenConfig.adoptFallback())
        assertEquals(1280, HeadUnitScreenConfig.getNegotiatedWidth())
        assertEquals(720, HeadUnitScreenConfig.getNegotiatedHeight())
    }

    @Test
    fun `retired activity layout cannot overwrite the new projection window`() {
        val other = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val otherRoot = FrameLayout(other.get())
            other.get().setContentView(otherRoot)
            prepareProjection()
            applyProjection(Settings.FullscreenMode.NONE)
            dispatch(insets(cutout = Insets.NONE))
            completeFrame()

            SystemUI.apply(other.get().window, otherRoot, Settings.FullscreenMode.NONE)
            ViewCompat.dispatchApplyWindowInsets(otherRoot, insets(cutout = Insets.NONE))
            other.get().findViewById<View>(android.R.id.content).layout(0, 0, 900, 600)
            otherRoot.measure(
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            )
            otherRoot.layout(0, 0, 900, 600)
            otherRoot.viewTreeObserver.dispatchOnPreDraw()
            assertEquals(900, HeadUnitScreenConfig.getUsableWidth())
            assertEquals(528, HeadUnitScreenConfig.getUsableHeight())
            val fromNewWindow = announce()
            assertWire(fromNewWindow, marginW = 380, marginH = 192, par = 10000)

            // The outgoing Activity still has its watcher until destruction.
            dispatch(insets(cutout = Insets.NONE))
            completeFrame(1100, 620)
            assertEquals(900, HeadUnitScreenConfig.getUsableWidth())
            assertEquals(528, HeadUnitScreenConfig.getUsableHeight())
            assertWire(announce(), marginW = 380, marginH = 192, par = 10000)
        } finally {
            other.pause().stop().destroy()
        }
    }
}
