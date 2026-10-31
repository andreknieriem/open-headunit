package com.andrerinas.openheadunit.utils

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.NarrowBandProfilePolicy
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.connection.wifi.direct.WifiBandCapability
import com.andrerinas.openheadunit.decoder.video.VideoDecoder
import java.lang.ref.WeakReference

object HeadUnitScreenConfig {

    private var screenWidthPx: Int = 0
    private var screenHeightPx: Int = 0
    private var density: Float = 1.0f
    private var scaleFactor: Float = 1.0f
    private var isSmallScreen: Boolean = true
    private var isPortraitScaled: Boolean = false
    private var isInitialized: Boolean = false
    private var lastSettingsHash: Int = 0

    // How the negotiated video is fitted into the panel (FILL/CONTAIN/COVER, see Settings.VideoFitMode).
    private var videoFitMode: Settings.VideoFitMode = Settings.VideoFitMode.FILL

    // Forced scale for older devices (Legacy fix)
    var forcedScale: Boolean = false
        private set

    @Volatile var negotiatedResolutionType: Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType = Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480
        private set
    @Volatile var isResolutionLocked: Boolean = false
        private set

    /** A second size we offered, and the margins that go with it. Swapped whole, never edited. */
    private class Fallback(
        val type: Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType,
        val widthMargin: Int,
        val heightMargin: Int,
    )

    // Written under the object's lock; read by touch, layout and the sender without one.
    @Volatile private var offeredFallback: Fallback? = null
    @Volatile private var adoptedFallback: Fallback? = null

    /** Raised after the phone picks the fallback, so the view can be laid out again. */
    var onFallbackAdopted: (() -> Unit)? = null

    fun hasOfferedFallback(): Boolean = offeredFallback != null

    /**
     * The phone chose the fallback: size and margins change together, then the view is laid out
     * again. The margins on the wire become the fallback's, so no drift is reported.
     */
    fun adoptFallback(): Boolean {
        val adopted = synchronized(this) {
            val offered = offeredFallback ?: return false
            if (adoptedFallback != null) return false
            announcedWidthMargin = offered.widthMargin
            announcedHeightMargin = offered.heightMargin
            adoptedFallback = offered
            offered
        }
        AppLog.i("HeadUnitScreenConfig: Video: the phone chose the ${adopted.type} fallback (index 1), margins ${adopted.widthMargin}x${adopted.heightMargin}")
        onFallbackAdopted?.invoke()
        return true
    }

    private lateinit var currentSettings: Settings // Store settings instance

    /** Application context, so [recalculate] can ask the radio what band it has. Never an Activity. */
    private var appContext: Context? = null

    // System Insets (Bars/Cutouts)
    var systemInsetLeft: Int = 0
        private set
    var systemInsetTop: Int = 0
        private set
    var systemInsetRight: Int = 0
        private set
    var systemInsetBottom: Int = 0
        private set


    // Raw Screen Dimensions (Full Display)
    private var realScreenWidthPx: Int = 0
    private var realScreenHeightPx: Int = 0

    // The panel itself, orientation-normalised. A ROM that counts a decoration on the wrong axis
    // moves this reading by tens of px between two init() calls, so it is a sanity bound on a
    // stored canvas and never an identity the cache is keyed on.
    private var physicalWidthPx: Int = 0
    private var physicalHeightPx: Int = 0

    // Which way up every reading is turned. Held rather than re-derived, so the four call sites
    // that take one cannot disagree about the orientation a canvas was measured in.
    private var normalisation: ScreenOrientationPolicy.Normalisation =
        ScreenOrientationPolicy.Normalisation.AS_READ

    private data class WindowGeometry(
        val canvas: ProjectionCanvasPolicy.Measurement,
        val left: Int, val top: Int, val right: Int, val bottom: Int,
    )

    private data class GeometrySettings(
        val resolutionId: Int,
        val pixelAspectRatioE4: Int,
        val dpiPixelDensity: Int,
        val displaySizePreset: String?,
        val dpiFloorOverride: Boolean,
    )

    private var geometrySettings: GeometrySettings? = null
    private var surfaceCanvas: ProjectionCanvasPolicy.Measurement? = null
    private var windowGeometry: WindowGeometry? = null
    private var windowOwner: Any? = null
    private var windowMeasurementPending = false
    private var windowMode: Settings.FullscreenMode? = null

    data class AnnouncedGeometry(
        val resolution: Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType,
        val width: Int,
        val height: Int,
        val widthMargin: Int,
        val heightMargin: Int,
        val pixelAspectRatioE4: Int,
        val densityDecision: AnnouncedDensityPolicy.Decision,
    )

    private var announcedGeometry: AnnouncedGeometry? = null
    private var announcementOwner: WeakReference<Any>? = null
    private var geometryRevision = 0L

    // Writers commit the entire geometry before calling out. In particular a scale callback must
    // never re-enter init between publishing the window, its padding and its settings signature.
    private inline fun <T> updateGeometry(block: () -> T): T {
        var changed = false
        val result = synchronized(this) {
            val before = geometryRevision
            val value = block()
            changed = geometryRevision != before
            value
        }
        if (changed) notifyMarginsDiverged()
        return result
    }

    // What ServiceDiscoveryResponse actually put on the wire. init() re-reads the display metrics
    // on every scale update, so the live margins can move under a session that already announced
    // its own; this is what the drift is measured against.
    @Volatile private var announcedWidthMargin: Int = MarginAnnouncementPolicy.NOT_ANNOUNCED
    @Volatile private var announcedHeightMargin: Int = MarginAnnouncementPolicy.NOT_ANNOUNCED

    /**
     * Raised when the live margins leave the announced ones. The listener records what it sends;
     * a false keeps the old announcement standing so the next recalculate retries.
     */
    var onMarginsDiverged: (() -> Boolean)? = null

    // The listener redraws, which re-enters init() and can land back here. One notification at a time.
    private var notifyingMarginDivergence: Boolean = false

    @Synchronized
    fun recordAnnouncedMargins(widthMargin: Int, heightMargin: Int) {
        announcedWidthMargin = widthMargin
        announcedHeightMargin = heightMargin
    }

    // The canvas the announced pixel shape was derived from. Only service discovery carries a pixel
    // shape, so this is what a later canvas change can no longer be told to the phone.
    private var announcedCanvasW: Int = 0
    private var announcedCanvasH: Int = 0
    private var loggedCanvasDrift: Boolean = false
    private var canvasDriftLines: Int = 0

    /** True when the live margins are already on the wire, so sending them again would only repeat it. */
    @Synchronized
    fun marginsMatchAnnounced(): Boolean =
        announcedWidthMargin != MarginAnnouncementPolicy.NOT_ANNOUNCED &&
            announcedHeightMargin != MarginAnnouncementPolicy.NOT_ANNOUNCED &&
            !MarginAnnouncementPolicy.shouldReannounce(
                announcedWidthMargin, announcedHeightMargin, getWidthMargin(), getHeightMargin()
            )

    @Synchronized
    fun clearAnnouncedMargins() {
        announcedWidthMargin = MarginAnnouncementPolicy.NOT_ANNOUNCED
        announcedHeightMargin = MarginAnnouncementPolicy.NOT_ANNOUNCED
        announcedCanvasW = 0
        announcedCanvasH = 0
        loggedCanvasDrift = false
        canvasDriftLines = 0
    }


    fun init(context: Context, displayMetrics: DisplayMetrics, settings: Settings) = updateGeometry {
        initGeometry(context, displayMetrics, settings)
    }

    private fun initGeometry(context: Context, displayMetrics: DisplayMetrics, settings: Settings) {
        val screenOrientation = settings.screenOrientation
        val configOrientation = context.resources.configuration.orientation
        val isConfigLandscape = configOrientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val isConfigPortrait = configOrientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        val requestedNormalisation = ScreenOrientationPolicy.normalisation(screenOrientation, isConfigLandscape, isConfigPortrait)
        val currentHash = computeSettingsHash(settings, requestedNormalisation)
        val measured = windowGeometry

        // While a window is being refitted, the last complete frame remains the usable geometry.
        // A service/application context must not relabel it with new settings or orientation.
        if (windowOwner != null && measured != null &&
            (windowMeasurementPending || measured.canvas.hash != currentHash)) return

        normalisation = requestedNormalisation
        val mode = windowMode ?: settings.fullscreenMode
        val immersive = mode == Settings.FullscreenMode.IMMERSIVE || mode == Settings.FullscreenMode.IMMERSIVE_WITH_NOTCH
        val realW: Int
        val realH: Int
        val usableW: Int
        val usableH: Int
        var left = settings.insetLeft
        var top = settings.insetTop
        var right = settings.insetRight
        var bottom = settings.insetBottom

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = context.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics
            realW = displayMetrics.widthPixels
            realH = displayMetrics.heightPixels
            val bounds = metrics.bounds
            // An initial response can precede every View layout. WindowMetrics provides bounds
            // and insets together; never combine that fallback with a previous window's padding.
            val automatic = mode == Settings.FullscreenMode.NONE &&
                !(Build.VERSION.SDK_INT >= 35 && context.applicationInfo.targetSdkVersion >= 35)
            val cutout = android.view.WindowInsets.Type.displayCutout()
            val mask = when (mode) {
                Settings.FullscreenMode.IMMERSIVE -> 0
                Settings.FullscreenMode.IMMERSIVE_WITH_NOTCH -> cutout
                Settings.FullscreenMode.STATUS_ONLY -> android.view.WindowInsets.Type.navigationBars() or cutout
                Settings.FullscreenMode.NONE -> android.view.WindowInsets.Type.systemBars() or cutout
            }
            val bars = if (mask == 0) android.graphics.Insets.NONE else metrics.windowInsets.getInsets(mask)
            if (!automatic) {
                left += bars.left
                top += bars.top
                right += bars.right
                bottom += bars.bottom
            }
            usableW = bounds.width() - (if (automatic) bars.left + bars.right else 0) - left - right
            usableH = bounds.height() - (if (automatic) bars.top + bars.bottom else 0) - top - bottom
        } else {
            @Suppress("DEPRECATION")
            val display = (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
            val size = android.graphics.Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            realW = size.x
            realH = size.y
            @Suppress("DEPRECATION")
            display.getSize(size)
            usableW = (if (immersive) realW else size.x) - left - right
            usableH = (if (immersive) realH else size.y) - top - bottom
        }

        AppLog.i("[UI_DEBUG] HeadUnitScreenConfig: Raw size: ${realW}x${realH}, usable: ${usableW}x${usableH}, orientation setting: $screenOrientation, configOrientation: $configOrientation")
        val real = ScreenOrientationPolicy.normalise(realW, realH, normalisation)
        val usable = ScreenOrientationPolicy.normalise(usableW.coerceAtLeast(1), usableH.coerceAtLeast(1), normalisation)
        AppLog.i("[UI_DEBUG] HeadUnitScreenConfig: Final normalized size: ${real.width}x${real.height}, usable: ${usable.width}x${usable.height}")
        physicalWidthPx = real.width
        physicalHeightPx = real.height
        // Padding is measured in the view's axes. Turn those axes with the drawable rectangle.
        val axesSwapped = (usableW < usableH) != (usable.width < usable.height)
        if (axesSwapped) {
            val oldLeft = left
            val oldRight = right
            left = top
            top = oldLeft
            right = bottom
            bottom = oldRight
        }
        if (measured?.canvas?.hash == currentHash) {
            left = measured.left
            top = measured.top
            right = measured.right
            bottom = measured.bottom
        }
        val choice = ProjectionCanvasPolicy.choose(
            immersive = false, // The fallback above is already the drawable rectangle in every mode.
            realW = real.width, realH = real.height, usableW = usable.width, usableH = usable.height,
            surface = surfaceCanvas, window = measured?.canvas,
            cached = cachedMeasurement(settings, screenOrientation, isConfigLandscape, isConfigPortrait), hash = currentHash,
        )
        val anchor = ProjectionCanvasPolicy.anchor(choice.width, choice.height, left + right, top + bottom)
        val capturedSettings = GeometrySettings(
            settings.resolutionId, settings.pixelAspectRatioE4, settings.dpiPixelDensity,
            settings.displaySizePreset, settings.dpiFloorOverride,
        )
        if (isInitialized && realScreenWidthPx == anchor.width && realScreenHeightPx == anchor.height &&
            lastSettingsHash == currentHash && systemInsetLeft == left && systemInsetTop == top &&
            systemInsetRight == right && systemInsetBottom == bottom && geometrySettings == capturedSettings) return

        if (isInitialized && lastSettingsHash != currentHash && announcedGeometry == null) {
            AppLog.i("[UI_DEBUG] HeadUnitScreenConfig: Settings changed ($lastSettingsHash -> $currentHash). Unlocking resolution.")
            unlockResolution(keepFallback = true)
        }
        isInitialized = true
        lastSettingsHash = currentHash
        currentSettings = settings
        geometrySettings = capturedSettings
        appContext = context.applicationContext
        density = displayMetrics.density
        videoFitMode = settings.videoFitMode
        forcedScale = settings.forcedScale && settings.viewMode == Settings.ViewMode.SURFACE
        systemInsetLeft = left
        systemInsetTop = top
        systemInsetRight = right
        systemInsetBottom = bottom
        realScreenWidthPx = anchor.width
        realScreenHeightPx = anchor.height
        AppLog.i("[UI_DEBUG] HeadUnitScreenConfig: Honest Init | Mode: $mode | Anchor: ${anchor.width}x${anchor.height} (from ${choice.source}) | Insets: L$left T$top R$right B$bottom")
        recalculate()
    }

    /** All geometry fields in service discovery, including input and fallback, share this value. */
    fun announceGeometry(owner: Any, context: Context, metrics: DisplayMetrics, settings: Settings): AnnouncedGeometry = updateGeometry {
        if (announcementOwner?.get() === owner) announcedGeometry?.let { return@updateGeometry it }
        // Remote EOF may reconnect without an Activity teardown. Announcement lifetime belongs
        // to the transport, not to a process-wide unlock callback or a particular screen instance.
        unlockResolution()
        clearAnnouncedMargins()
        initGeometry(context, metrics, settings)
        // Even unchanged local geometry must select a fresh primary after a previous fallback
        // or session lock; init's unchanged-frame fast path deliberately does not recalculate.
        recalculate()
        val (width, height) = primarySize()
        val geometry = AnnouncedGeometry(
            negotiatedResolutionType, width, height, getWidthMargin(), getHeightMargin(),
            getPixelAspectRatioE4(), densityDecision(),
        )
        announcedWidthMargin = geometry.widthMargin
        announcedHeightMargin = geometry.heightMargin
        announcedCanvasW = screenWidthPx
        announcedCanvasH = screenHeightPx
        loggedCanvasDrift = false
        canvasDriftLines = 0
        VideoFallbackPolicy.fallbackFor(geometry.resolution)?.let { type ->
            val margins = VideoFallbackPolicy.scaledMargins(width, height, geometry.widthMargin, geometry.heightMargin, type)
            offeredFallback = Fallback(type, margins.width, margins.height)
        }
        announcedGeometry = geometry
        announcementOwner = WeakReference(owner)
        lockResolution()
        geometry
    }

    /** The canvas an earlier session measured, normalised to the orientation now in force. */
    private fun cachedMeasurement(
        settings: Settings,
        screenOrientation: Settings.ScreenOrientation,
        isConfigLandscape: Boolean,
        isConfigPortrait: Boolean
    ): ProjectionCanvasPolicy.Measurement? {
        val cachedW = settings.cachedSurfaceWidth
        val cachedH = settings.cachedSurfaceHeight
        if (cachedW <= 0 || cachedH <= 0) return null

        val turned = ScreenOrientationPolicy.normalise(
            cachedW,
            cachedH,
            ScreenOrientationPolicy.normalisation(screenOrientation, isConfigLandscape, isConfigPortrait),
        )
        // Another session wrote this, so it can describe another panel. The live readings above are
        // this process's own and outrank the panel, which is why only the cache is bounded by it.
        if (!ProjectionCanvasPolicy.fitsPanel(turned.width, turned.height, physicalWidthPx, physicalHeightPx)) {
            return null
        }
        return ProjectionCanvasPolicy.Measurement(turned.width, turned.height, settings.cachedSurfaceSettingsHash)
    }

    @Synchronized
    internal fun beginWindowMeasurement(owner: Any, mode: Settings.FullscreenMode) {
        windowOwner = owner
        windowMode = mode
        windowMeasurementPending = true
    }

    @Synchronized
    internal fun invalidateWindowMeasurement(owner: Any) {
        if (windowOwner === owner) windowMeasurementPending = true
    }

    @Synchronized
    internal fun releaseWindowMeasurement(owner: Any) {
        if (windowOwner === owner) {
            windowOwner = null
            windowMode = null
            windowMeasurementPending = true
        }
    }

    @Synchronized
    internal fun isWindowMeasurementCurrent(owner: Any): Boolean = windowOwner === owner && !windowMeasurementPending

    internal fun publishWindowGeometry(
        owner: Any, context: Context, metrics: DisplayMetrics, settings: Settings,
        contentW: Int, contentH: Int, left: Int, top: Int, right: Int, bottom: Int,
        configOrientation: Int, settingsHash: Int,
    ) = updateGeometry {
        if (windowOwner !== owner || contentW <= 0 || contentH <= 0 || App.isPiPActive) return@updateGeometry
        val orientation = ScreenOrientationPolicy.normalisation(
            settings.screenOrientation,
            configOrientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE,
            configOrientation == android.content.res.Configuration.ORIENTATION_PORTRAIT,
        )
        if (settingsHash != computeSettingsHash(settings, orientation)) return@updateGeometry
        // Subtract in the measured axes, then normalise the complete drawable rectangle.
        val rawCanvas = ProjectionCanvasPolicy.canvas(contentW, contentH, left + right, top + bottom)
        val canvas = ScreenOrientationPolicy.normalise(rawCanvas.width, rawCanvas.height, orientation)
        val swapped = (rawCanvas.width < rawCanvas.height) != (canvas.width < canvas.height)
        val measurement = ProjectionCanvasPolicy.Measurement(canvas.width, canvas.height, settingsHash)
        if (ProjectionCanvasPolicy.windowChanged(windowGeometry?.canvas, measurement)) surfaceCanvas = null
        windowGeometry = WindowGeometry(
            measurement,
            if (swapped) top else left, if (swapped) left else top,
            if (swapped) bottom else right, if (swapped) right else bottom,
        )
        windowMeasurementPending = false
        val previousWidth = realScreenWidthPx
        val previousHeight = realScreenHeightPx
        initGeometry(context, metrics, settings)
        if (previousWidth != realScreenWidthPx || previousHeight != realScreenHeightPx) {
            AppLog.i("[UI_DEBUG] HeadUnitScreenConfig: anchor ${previousWidth}x${previousHeight} -> ${realScreenWidthPx}x${realScreenHeightPx}, measured on the activity window")
        }
    }

    private fun liveHash(): Int = lastSettingsHash

    /** Move the anchor onto a surface measured within the current committed window. */
    private fun adoptCanvas(canvasW: Int, canvasH: Int, source: String): Boolean {
        val anchor = ProjectionCanvasPolicy.anchor(canvasW, canvasH,
            systemInsetLeft + systemInsetRight, systemInsetTop + systemInsetBottom)
        if (anchor.width == realScreenWidthPx && anchor.height == realScreenHeightPx) return false
        AppLog.i("[UI_DEBUG] HeadUnitScreenConfig: anchor ${realScreenWidthPx}x${realScreenHeightPx} -> ${anchor.width}x${anchor.height}, measured on $source")
        realScreenWidthPx = anchor.width
        realScreenHeightPx = anchor.height
        recalculate()
        return true
    }

    // Native standard resolution for a given panel size, mirroring the AUTO selection so the
    // resolution cap never advertises more than the panel warrants (issue #650).
    /** Map a resolution class to the proto type for the current orientation (shared by the manual
     * selection path and the panel cap, so both agree). */
    private fun protoForResolution(
        res: Settings.Resolution,
        portrait: Boolean
    ): Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType {
        val landscape = res.codec
            ?: Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480
        if (!portrait) return landscape
        return when (res) {
            Settings.Resolution._800x480 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._720x1280
            Settings.Resolution._1280x720 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._720x1280
            Settings.Resolution._1920x1080 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1080x1920
            Settings.Resolution._2560x1440 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1440x2560
            Settings.Resolution._3840x2160 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._2160x3840
            else -> landscape
        }
    }

    /**
     * The largest proto resolution the panel can use, in the display's orientation. Deliberately
     * SystemOptimizer.hardCeiling and not panelCeiling: the latter is the recommendation, and
     * capping to it silently overrode the "Use anyway" the settings dialog offers, costing an
     * ultra-wide panel its native width on a resolution the user had picked on purpose.
     */
    private fun hardCeilingForPanel(
        w: Int,
        h: Int,
        portrait: Boolean,
        canHevc: Boolean
    ): Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType {
        return protoForResolution(SystemOptimizer.hardCeiling(w, h, canHevc), portrait)
    }

    /**
     * The ceiling a 2.4 GHz-only radio puts on the resolution, or null when it puts none.
     *
     * Never throws: this runs on every service discovery, and a band read that fails must leave the
     * user's choice alone rather than cost the session.
     */
    private fun narrowBandCeiling(
        isPortraitDisplay: Boolean
    ): Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType? {
        val context = appContext ?: return null
        return try {
            NarrowBandProfilePolicy.linkCeiling(
                supports5Ghz = WifiBandCapability.supports5Ghz(context),
                wirelessSession = App.provide(context).commManager.isWirelessSession,
                capEnabled = currentSettings.narrowBandProfileCap,
                sessionFrequencyMhz = WifiBandCapability.sessionFrequencyMhz(),
                bandUnreadable = WifiBandCapability.bandUnreadable(),
                linkProvedTooSlow = currentSettings.videoProfileStarvationCap,
            )?.let { protoForResolution(it, isPortraitDisplay) }
        } catch (e: Exception) {
            AppLog.d("HeadUnitScreenConfig: could not evaluate the band ceiling: ${e.message}")
            null
        }
    }

    private fun pixelsOf(type: Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType): Long {
        val s = type.toString().replace("_", "")
        return try {
            val parts = s.split("x")
            parts[0].toLong() * parts[1].toLong()
        } catch (e: Exception) {
            0L
        }
    }

    private fun recalculate() {
        geometryRevision++
        // Calculate USABLE area
        val canvas = ProjectionCanvasPolicy.canvas(
            realScreenWidthPx, realScreenHeightPx,
            systemInsetLeft + systemInsetRight, systemInsetTop + systemInsetBottom,
        )
        screenWidthPx = canvas.width
        screenHeightPx = canvas.height

        val selectedResolution = Settings.Resolution.fromId(geometrySettings!!.resolutionId)
        val isPortraitDisplay = screenHeightPx > screenWidthPx
        val canNegotiateHevc = canNegotiateHevcHighResolution()

        // 1. Determine base negotiated resolution
        if (isResolutionLocked) {
            // Safety Check: If the locked resolution's orientation (Landscape/Portrait)
            // no longer matches the display orientation, the lock is stale and must be dropped.
            val isPortraitRes = getNegotiatedHeight() > getNegotiatedWidth()
            if (isPortraitRes != isPortraitDisplay && announcedGeometry == null) {
                AppLog.i("[UI_DEBUG] CarScreen: Orientation mismatch detected (Res: ${if(isPortraitRes) "P" else "L"}, Display: ${if(isPortraitDisplay) "P" else "L"}). DROPPING LOCK.")
                unlockResolution(keepFallback = true)
            } else {
                AppLog.i("[UI_DEBUG] CarScreen: RESOLUTION LOCKED to $negotiatedResolutionType. Usable area is ${screenWidthPx}x${screenHeightPx}. Skipping re-negotiation.")
            }
        }

        // A locked session keeps what it already negotiated. This used to fall through to the
        // manual branch, where AUTO carries no codec and the fallback landed on 480p.
        NegotiatedResolutionPolicy.select(
            isLocked = isResolutionLocked,
            selected = selectedResolution,
            panelW = screenWidthPx,
            panelH = screenHeightPx,
            fitMode = videoFitMode,
            hevcSupported = VideoDecoder.isHevcSupported(),
            canHevcHighRes = canNegotiateHevc,
            sdkInt = Build.VERSION.SDK_INT
        )?.let { negotiatedResolutionType = protoForResolution(it, isPortraitDisplay) }

        // Cap to the largest buffer the panel can use, so a small panel never decodes a frame it
        // has to downscale every time, which overloads the MediaTek MDP scaler (issue #650). This
        // is the hard ceiling, not the recommendation: a wide panel uses a 1920-wide buffer in full
        // and only hides rows. min(current, ceiling), so a lower choice is never raised.
        val preCapResolution = negotiatedResolutionType
        val hardCeiling = hardCeilingForPanel(realScreenWidthPx, realScreenHeightPx, isPortraitDisplay, canNegotiateHevc)
        if (announcedGeometry == null && pixelsOf(negotiatedResolutionType) > pixelsOf(hardCeiling)) {
            negotiatedResolutionType = hardCeiling
        }
        // And to what the link can carry. Same min(current, ceiling) shape as the panel cap above,
        // so a user already asking for less is never raised to meet it.
        val linkCeiling = narrowBandCeiling(isPortraitDisplay)
        if (announcedGeometry == null && linkCeiling != null && pixelsOf(negotiatedResolutionType) > pixelsOf(linkCeiling)) {
            negotiatedResolutionType = linkCeiling
        }
        AppLog.i(
            "[RES_CAP] resolutionId=${currentSettings.resolutionId} " +
                "realScreen=${realScreenWidthPx}x${realScreenHeightPx} usable=${screenWidthPx}x${screenHeightPx} " +
                "portrait=$isPortraitDisplay locked=$isResolutionLocked chosen=$preCapResolution " +
                "capped=$negotiatedResolutionType changed=${preCapResolution != negotiatedResolutionType} " +
                "linkCapped=${linkCeiling ?: "none"}"
        )

        // 2. Perform scaling calculations (now safe because negotiatedResolutionType is set)
        AppLog.i("[UI_DEBUG] CarScreen: usable area ${screenWidthPx}x${screenHeightPx}, using $negotiatedResolutionType")

        val fit = ProjectionGeometryPolicy.fit(
            screenWidthPx, screenHeightPx, getNegotiatedWidth(), getNegotiatedHeight()
        )
        isSmallScreen = fit.isSmallScreen
        scaleFactor = fit.scaleFactor
        // Null on a small screen, where the previous value deliberately stands.
        fit.isPortraitScaled?.let { isPortraitScaled = it }

        AppLog.i("[UI_DEBUG] CarScreen isSmallScreen: $isSmallScreen, scaleFactor: $scaleFactor, portraitScaled: $isPortraitScaled, shape=${marginStrategy()}, margins: w=${getWidthMargin()}, h=${getHeightMargin()}")

        // Only service discovery carries a pixel shape, so a canvas that moves under a session
        // announced by shape cannot be corrected on the wire. Say so once.
        if (announcedCanvasW > 0 && marginStrategy() == MarginStrategyPolicy.Strategy.PAR &&
            canvasDriftLines <= MAX_CANVAS_DRIFT_LINES
        ) {
            val moved = announcedCanvasW != screenWidthPx || announcedCanvasH != screenHeightPx
            if (moved && !loggedCanvasDrift) {
                loggedCanvasDrift = true
                canvasDriftLines++
                AppLog.i(
                    "[UI_DEBUG] CarScreen: canvas moved from the announced " +
                        "${announcedCanvasW}x${announcedCanvasH} to ${screenWidthPx}x${screenHeightPx}; " +
                        "the pixel shape cannot be re-sent" +
                        if (canvasDriftLines > MAX_CANVAS_DRIFT_LINES) " — and is still moving; no longer reporting" else ""
                )
            } else if (!moved && loggedCanvasDrift) {
                // The move can be a settling transient, and a latch that cannot retract turns one
                // into a permanent claim in somebody's bug report.
                loggedCanvasDrift = false
                canvasDriftLines++
                AppLog.i(
                    "[UI_DEBUG] CarScreen: canvas returned to the announced " +
                        "${announcedCanvasW}x${announcedCanvasH}"
                )
            }
        }
    }

    private fun notifyMarginsDiverged() {
        val listener = synchronized(this) {
            if (notifyingMarginDivergence || !MarginAnnouncementPolicy.shouldReannounce(
                    announcedWidthMargin, announcedHeightMargin, getWidthMargin(), getHeightMargin())) return
            AppLog.i("[UI_DEBUG] CarScreen: margins drifted from the announced " +
                "${announcedWidthMargin}x${announcedHeightMargin} to ${getWidthMargin()}x${getHeightMargin()}")
            val callback = onMarginsDiverged ?: return
            notifyingMarginDivergence = true
            callback
        }
        try {
            listener()
        } finally {
            synchronized(this) { notifyingMarginDivergence = false }
        }
    }

    @Synchronized
    fun getAdjustedHeight(): Int = ProjectionGeometryPolicy.adjustedHeight(getNegotiatedHeight(), scaleFactor)

    @Synchronized
    fun getAdjustedWidth(): Int = ProjectionGeometryPolicy.adjustedWidth(getNegotiatedWidth(), scaleFactor)

    // COVER target size for the legacy forcedScale/SurfaceView path, which sizes the view through
    // LayoutParams rather than a View.scale transform.
    @Synchronized
    fun getCoverWidth(): Int =
        ProjectionGeometryPolicy.coverWidth(screenWidthPx, screenHeightPx, getNegotiatedWidth(), getNegotiatedHeight())

    @Synchronized
    fun getCoverHeight(): Int =
        ProjectionGeometryPolicy.coverHeight(screenWidthPx, screenHeightPx, getNegotiatedWidth(), getNegotiatedHeight())

    private fun primarySize(): Pair<Int, Int> = VideoFallbackPolicy.sizeOf(negotiatedResolutionType)

    /** The size the phone is sending: the fallback's once it has chosen it. */
    private fun activeSize(): Pair<Int, Int> =
        VideoFallbackPolicy.sizeOf(adoptedFallback?.type ?: negotiatedResolutionType)

    @Synchronized
    fun getNegotiatedHeight(): Int = activeSize().second

    private fun canNegotiateHevcHighResolution(): Boolean {
        if (VideoDecoder.isHevcSupported()) return true
        if (currentSettings.videoCodec != VideoDecoder.CodecType.H265.settingsValue || !currentSettings.forceSoftwareDecoding) return false
        return when (currentSettings.softwareVideoDecoder) {
            Settings.SoftwareVideoDecoder.BUNDLED_FFMPEG -> VideoDecoder.isBundledDecoderAvailable(VideoDecoder.CodecType.H265)
            Settings.SoftwareVideoDecoder.DEVICE_MEDIACODEC -> VideoDecoder.isHevcDecoderAvailable(includeSoftware = true)
        }
    }

    @Synchronized
    fun getNegotiatedWidth(): Int = activeSize().first

    // A stored resolution above the panel's rows used to hide a third of the frame behind a margin
    // the touch mapper never saw. In FILL a wider panel describes itself by pixel shape instead.
    private fun marginStrategy(): MarginStrategyPolicy.Strategy = MarginStrategyPolicy.select(
        videoFitMode, screenWidthPx, screenHeightPx, getNegotiatedWidth(), getNegotiatedHeight()
    )

    @Synchronized
    fun getHeightMargin(): Int {
        val fallback = adoptedFallback ?: return if (marginStrategy() == MarginStrategyPolicy.Strategy.PAR) 0
            else ProjectionGeometryPolicy.heightMargin(getNegotiatedHeight(), screenHeightPx, scaleFactor)
        return scaledFallbackMargins(fallback).height
    }

    @Synchronized
    fun getWidthMargin(): Int {
        val fallback = adoptedFallback ?: return if (marginStrategy() == MarginStrategyPolicy.Strategy.PAR) 0
            else ProjectionGeometryPolicy.widthMargin(getNegotiatedWidth(), screenWidthPx, scaleFactor)
        return scaledFallbackMargins(fallback).width
    }

    // The primary's margins for the panel as it is now, scaled to the fallback, so a bar that
    // settles after the announcement is still re-announced.
    private fun scaledFallbackMargins(fallback: Fallback): VideoFallbackPolicy.Margins {
        val (pw, ph) = primarySize()
        val strategy = MarginStrategyPolicy.select(videoFitMode, screenWidthPx, screenHeightPx, pw, ph)
        if (strategy == MarginStrategyPolicy.Strategy.PAR) return VideoFallbackPolicy.Margins(0, 0)
        val primaryScale = ProjectionGeometryPolicy.fit(screenWidthPx, screenHeightPx, pw, ph).scaleFactor
        return VideoFallbackPolicy.scaledMargins(
            pw, ph,
            ProjectionGeometryPolicy.widthMargin(pw, screenWidthPx, primaryScale),
            ProjectionGeometryPolicy.heightMargin(ph, screenHeightPx, primaryScale),
            fallback.type
        )
    }

    @Synchronized
    fun getScaleX(): Float = ProjectionGeometryPolicy.scaleX(
        videoFitMode, forcedScale,
        screenWidthPx, screenHeightPx, getNegotiatedWidth(), getNegotiatedHeight(),
        getWidthMargin(), getHeightMargin()
    )

    @Synchronized
    fun getScaleY(): Float = ProjectionGeometryPolicy.scaleY(
        videoFitMode, forcedScale,
        screenWidthPx, screenHeightPx, getNegotiatedWidth(), getNegotiatedHeight(),
        getWidthMargin(), getHeightMargin()
    )

    /** The density decision for the geometry in force, with what was asked and why it changed. */
    @Synchronized
    fun densityDecision(): AnnouncedDensityPolicy.Decision {
        val captured = geometrySettings
            ?: return AnnouncedDensityPolicy.decide(0, SystemOptimizer.DisplaySizePreset.SMALL_7_8, 0, 0)
        val preset = OnboardingDisplayPolicy.initialPreset(
            captured.displaySizePreset, SystemOptimizer.DisplaySizePreset.SMALL_7_8
        )
        return AnnouncedDensityPolicy.decide(
            captured.dpiPixelDensity, preset,
            getNegotiatedWidth() - getWidthMargin(), getNegotiatedHeight() - getHeightMargin(),
            getPixelAspectRatioE4(), captured.dpiFloorOverride, screenWidthPx, screenHeightPx
        )
    }

    @Synchronized
    fun getPixelAspectRatioE4(): Int {
        // The settings row normalises anything <= 0 to 10000, so 10000 is also "unset" and is what
        // lets the derived value through. An explicit non-square choice always wins.
        val manual = geometrySettings?.pixelAspectRatioE4 ?: 0
        if (manual > 0 && manual != ProjectionGeometryPolicy.SQUARE_PIXELS_E4) return manual
        return ProjectionGeometryPolicy.pixelAspectRatioE4(
            videoFitMode, screenWidthPx, screenHeightPx, getNegotiatedWidth(), getNegotiatedHeight(),
            getWidthMargin(), getHeightMargin()
        )
    }

    @Synchronized
    fun getUsableWidth(): Int = screenWidthPx
    @Synchronized
    fun getUsableHeight(): Int = screenHeightPx


    // These are half the total margin, distributed symmetrically.
    @Synchronized
    fun getLeftMargin(): Int = getWidthMargin() / 2
    @Synchronized
    fun getRightMargin(): Int = getWidthMargin() - getLeftMargin()
    @Synchronized
    fun getTopMargin(): Int = getHeightMargin() / 2
    @Synchronized
    fun getBottomMargin(): Int = getHeightMargin() - getTopMargin()

    /**
     * Called when the actual rendering surface dimensions become known (from onSurfaceChanged).
     * Compares with the current usable area and updates the anchor if they differ.
     * @return true if the dimensions changed and margins need to be re-sent to AA.
     */
    fun updateSurfaceDimensions(surfaceW: Int, surfaceH: Int): Boolean = updateGeometry {
        // A picture-in-picture window is a few hundred px of somebody else's screen, not the canvas.
        if (App.isPiPActive || windowMeasurementPending || !isInitialized || surfaceW <= 0 || surfaceH <= 0) return@updateGeometry false

        val surface = ScreenOrientationPolicy.normalise(surfaceW, surfaceH, normalisation)
        val finalSurfaceW = surface.width
        val finalSurfaceH = surface.height

        val diffW = kotlin.math.abs(finalSurfaceW - screenWidthPx)
        val diffH = kotlin.math.abs(finalSurfaceH - screenHeightPx)

        if (diffW <= SURFACE_MISMATCH_TOLERANCE && diffH <= SURFACE_MISMATCH_TOLERANCE) {
            // Already the canvas in force, but still the reading that outranks the display metrics.
            surfaceCanvas = ProjectionCanvasPolicy.Measurement(screenWidthPx, screenHeightPx, liveHash())
            return@updateGeometry false
        }

        val primary = primarySize()
        val active = activeSize()
        if ((diffW > 0 && (active.first == finalSurfaceW || primary.first == finalSurfaceW)) ||
            (diffH > 0 && (active.second == finalSurfaceH || primary.second == finalSurfaceH))) {
            AppLog.i("[UI_DEBUG_FIX] Surface mismatch detected but matches negotiated resolution. Usable: ${screenWidthPx}x${screenHeightPx}, Actual surface: ${finalSurfaceW}x${finalSurfaceH}. Ignoring.")
            return@updateGeometry false
        }

        AppLog.i("[UI_DEBUG_FIX] Surface mismatch detected! Usable: ${screenWidthPx}x${screenHeightPx}, Actual surface: ${finalSurfaceW}x${finalSurfaceH} (diff: ${diffW}x${diffH})")

        // The surface outranks other readings until the settings or owning window geometry change.
        surfaceCanvas = ProjectionCanvasPolicy.Measurement(finalSurfaceW, finalSurfaceH, liveHash())
        adoptCanvas(finalSurfaceW, finalSurfaceH, "the projection surface")

        AppLog.i("[UI_DEBUG_FIX] Recalculated: usable=${screenWidthPx}x${screenHeightPx}, margins: w=${getWidthMargin()}, h=${getHeightMargin()}, per-side: L=${getLeftMargin()} T=${getTopMargin()} R=${getRightMargin()} B=${getBottomMargin()}")
        return@updateGeometry true
    }

    /**
     * Computes a hash of all settings that affect screen dimensions.
     * Used to invalidate the cached surface dimensions when settings change.
     */
    @Synchronized
    fun computeSettingsHash(settings: Settings): Int = computeSettingsHash(settings, normalisation)

    internal fun computeSettingsHash(settings: Settings, configOrientation: Int): Int = computeSettingsHash(
        settings, ScreenOrientationPolicy.normalisation(
            settings.screenOrientation,
            configOrientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE,
            configOrientation == android.content.res.Configuration.ORIENTATION_PORTRAIT,
        ),
    )

    private fun computeSettingsHash(
        settings: Settings,
        normalisation: ScreenOrientationPolicy.Normalisation,
    ): Int = ScreenSettingsHash.of(
        resolutionId = settings.resolutionId,
        dpiPixelDensity = settings.dpiPixelDensity,
        pixelAspectRatioE4 = settings.pixelAspectRatioE4,
        insetLeft = settings.insetLeft,
        insetTop = settings.insetTop,
        insetRight = settings.insetRight,
        insetBottom = settings.insetBottom,
        viewMode = settings.viewMode.ordinal,
        screenOrientation = settings.screenOrientation.ordinal,
        fullscreenMode = settings.fullscreenMode.value,
        videoFitMode = settings.videoFitMode.value,
        forcedScale = settings.forcedScale,
        // The effective orientation, not the panel. A ROM that counts a decoration on the wrong
        // axis moves the panel reading mid-connect, which discarded a good window measurement and
        // then fell back to that same reading; rotation is what has to invalidate, and this is it.
        normalisation = normalisation,
    )

    @Synchronized
    fun lockResolution() {
        if (!isResolutionLocked) {
            AppLog.i("[UI_DEBUG] HeadUnitScreenConfig: Locking resolution at $negotiatedResolutionType")
            isResolutionLocked = true
        }
    }

    // The phone keeps the fallback until the session ends, so only the session-end callers clear it;
    // a mid-session settings or orientation unlock must not send touch back to the primary size.
    @Synchronized
    fun unlockResolution(keepFallback: Boolean = false) {
        if (!keepFallback) {
            announcedGeometry = null
            announcementOwner = null
            adoptedFallback = null
            offeredFallback = null
        }
        if (isResolutionLocked) {
            AppLog.i("[UI_DEBUG] HeadUnitScreenConfig: Unlocking resolution (was $negotiatedResolutionType)")
            isResolutionLocked = false
        }
    }

    private const val SURFACE_MISMATCH_TOLERANCE = 4

    // A canvas that leaves and returns more than twice is itself the finding, and repeating the
    // pair for every settle would drown the line that matters.
    private const val MAX_CANVAS_DRIFT_LINES = 4
}
