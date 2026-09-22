package com.andrerinas.openheadunit.utils

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.NarrowBandProfilePolicy
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.connection.wifi.direct.WifiBandCapability
import com.andrerinas.openheadunit.decoder.video.VideoDecoder
import kotlin.math.roundToInt

object HeadUnitScreenConfig {

    private var screenWidthPx: Int = 0
    private var screenHeightPx: Int = 0
    private var density: Float = 1.0f
    private var densityDpi: Int = 240
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

    var negotiatedResolutionType: Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType = Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480
    var isResolutionLocked: Boolean = false
        private set

    private lateinit var currentSettings: Settings // Store settings instance

    /** Application context, so [recalculate] can ask the radio what band it has. Never an Activity. */
    private var appContext: Context? = null

    /** The display the live measurement was taken from, so a cached one cannot cross displays. */
    var measuredDisplayId: Int = DisplayTargetPolicy.DEFAULT_DISPLAY_ID
        private set

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

    // The canvas the anchor was derived from, so the anchor can follow the insets when they move
    // under it. A bar subtracted from a rectangle measured before it appeared shrinks it twice.
    private var anchorCanvasW: Int = 0
    private var anchorCanvasH: Int = 0
    private var anchorSource: String = ""

    // Canvases somebody actually measured, each tagged with the settings they were measured under.
    private var surfaceCanvas: ProjectionCanvasPolicy.Measurement? = null
    private var windowCanvas: ProjectionCanvasPolicy.Measurement? = null

    // A window measured before the first init(), which has no hash to tag it with yet.
    private var pendingWindowCanvasW: Int = 0
    private var pendingWindowCanvasH: Int = 0

    // What ServiceDiscoveryResponse actually put on the wire. init() re-reads the display metrics
    // on every scale update, so the live margins can move under a session that already announced
    // its own; this is what the drift is measured against.
    private var announcedWidthMargin: Int = MarginAnnouncementPolicy.NOT_ANNOUNCED
    private var announcedHeightMargin: Int = MarginAnnouncementPolicy.NOT_ANNOUNCED

    /**
     * Raised when the live margins leave the announced ones. The listener records what it sends;
     * a false keeps the old announcement standing so the next recalculate retries.
     */
    var onMarginsDiverged: (() -> Boolean)? = null

    // The listener redraws, which re-enters init() and can land back here. One notification at a time.
    private var notifyingMarginDivergence: Boolean = false

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

    fun recordAnnouncedCanvas() {
        announcedCanvasW = screenWidthPx
        announcedCanvasH = screenHeightPx
        loggedCanvasDrift = false
        canvasDriftLines = 0
    }

    /** True when the live margins are already on the wire, so sending them again would only repeat it. */
    fun marginsMatchAnnounced(): Boolean =
        announcedWidthMargin != MarginAnnouncementPolicy.NOT_ANNOUNCED &&
            announcedHeightMargin != MarginAnnouncementPolicy.NOT_ANNOUNCED &&
            !MarginAnnouncementPolicy.shouldReannounce(
                announcedWidthMargin, announcedHeightMargin, getWidthMargin(), getHeightMargin()
            )

    fun clearAnnouncedMargins() {
        announcedWidthMargin = MarginAnnouncementPolicy.NOT_ANNOUNCED
        announcedHeightMargin = MarginAnnouncementPolicy.NOT_ANNOUNCED
        announcedCanvasW = 0
        announcedCanvasH = 0
        loggedCanvasDrift = false
        canvasDriftLines = 0
    }


    fun init(context: Context, displayMetrics: DisplayMetrics, settings: Settings) {
        measuredDisplayId = DisplayTargets.displayIdOf(context)
        videoFitMode = settings.videoFitMode
        forcedScale = settings.forcedScale && settings.viewMode == Settings.ViewMode.SURFACE

        val realW: Int
        val realH: Int
        val usableW: Int
        val usableH: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // API 30+
            val windowManager = context.getSystemService(android.view.WindowManager::class.java)
            val bounds = windowManager.currentWindowMetrics.bounds
            // On API 30+, bounds on an Activity context often return the usable area.
            // We use the displayMetrics as a fallback for the physical area.
            realW = displayMetrics.widthPixels
            realH = displayMetrics.heightPixels
            usableW = bounds.width()
            usableH = bounds.height()
        } else { // Older APIs
            @Suppress("DEPRECATION")
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val display = windowManager.defaultDisplay
            val size = android.graphics.Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            realW = size.x
            realH = size.y

            @Suppress("DEPRECATION")
            display.getSize(size)
            usableW = size.x
            usableH = size.y
        }

        val screenOrientation = settings.screenOrientation
        val configOrientation = context.resources.configuration.orientation
        val isConfigLandscape = configOrientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val isConfigPortrait = configOrientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

        normalisation = ScreenOrientationPolicy.normalisation(screenOrientation, isConfigLandscape, isConfigPortrait)
        val real = ScreenOrientationPolicy.normalise(realW, realH, normalisation)
        val usable = ScreenOrientationPolicy.normalise(usableW, usableH, normalisation)
        val finalRealW = real.width
        val finalRealH = real.height
        val finalUsableW = usable.width
        val finalUsableH = usable.height

        AppLog.i("[UI_DEBUG] HeadUnitScreenConfig: Raw size: ${realW}x${realH}, usable: ${usableW}x${usableH}, orientation setting: $screenOrientation, configOrientation: $configOrientation")
        AppLog.i("[UI_DEBUG] HeadUnitScreenConfig: Final normalized size: ${finalRealW}x${finalRealH}, usable: ${finalUsableW}x${finalUsableH}")

        physicalWidthPx = finalRealW
        physicalHeightPx = finalRealH

        val currentHash = computeSettingsHash(settings)

        val settingsChanged = !isInitialized || lastSettingsHash != currentHash

        // A window laid out before the first init() was measured under these very settings, but in
        // whatever orientation the view happened to report; this is the first call that knows which.
        if (windowCanvas == null && pendingWindowCanvasW > 0 && pendingWindowCanvasH > 0) {
            val pending = ScreenOrientationPolicy.normalise(pendingWindowCanvasW, pendingWindowCanvasH, normalisation)
            windowCanvas = ProjectionCanvasPolicy.Measurement(pending.width, pending.height, currentHash)
            pendingWindowCanvasW = 0
            pendingWindowCanvasH = 0
        }

        val immersive = settings.fullscreenMode == Settings.FullscreenMode.IMMERSIVE ||
                        settings.fullscreenMode == Settings.FullscreenMode.IMMERSIVE_WITH_NOTCH

        // THE ANCHOR: whatever last measured the canvas the video is drawn into, and the display
        // only while nothing has. Outside immersive the display APIs describe neither a window a
        // ROM sidebar narrowed nor a decoration reported on the wrong axis.
        val choice = ProjectionCanvasPolicy.choose(
            immersive = immersive,
            realW = finalRealW,
            realH = finalRealH,
            usableW = finalUsableW,
            usableH = finalUsableH,
            surface = surfaceCanvas,
            window = windowCanvas,
            cached = cachedMeasurement(settings, screenOrientation, isConfigLandscape, isConfigPortrait),
            hash = currentHash,
        )

        // A measured canvas is the area inside the insets, so the anchor carries them. Only the
        // immersive display reading is the whole panel already; outside it the fallback is the
        // window metrics, which are bar-reduced like any other canvas. The insets in force are the
        // right ones unless the settings moved, in which case the manual ones are re-seeded below.
        val insetW = if (settingsChanged) settings.insetLeft + settings.insetRight else systemInsetLeft + systemInsetRight
        val insetH = if (settingsChanged) settings.insetTop + settings.insetBottom else systemInsetTop + systemInsetBottom
        val panelIsTheAnchor = choice.source == ProjectionCanvasPolicy.Source.DISPLAY && immersive
        val anchor = if (panelIsTheAnchor) {
            ProjectionCanvasPolicy.Rect(choice.width, choice.height)
        } else {
            ProjectionCanvasPolicy.anchor(choice.width, choice.height, insetW, insetH)
        }
        val anchorW = anchor.width
        val anchorH = anchor.height

        // Nothing the anchor depends on has moved, so a repeat call must not undo a measurement
        // taken since. This used to compare the anchor with the raw display, which outside an
        // immersive mode can never match, so every scale update reset it.
        if (isInitialized && realScreenWidthPx == anchorW && realScreenHeightPx == anchorH &&
            lastSettingsHash == currentHash) {
            return
        }

        // If settings changed (e.g. orientation swap), unlock resolution before recalculating
        if (isInitialized && lastSettingsHash != 0 && lastSettingsHash != currentHash) {
            AppLog.i("[UI_DEBUG] HeadUnitScreenConfig: Settings changed ($lastSettingsHash -> $currentHash). Unlocking resolution.")
            unlockResolution()
        }

        isInitialized = true
        lastSettingsHash = currentHash
        currentSettings = settings
        appContext = context.applicationContext

        density = displayMetrics.density
        densityDpi = displayMetrics.densityDpi

        // Manual insets only; the bars are re-read by the insets listener right after this. Left
        // alone otherwise, or a bar already in force would be subtracted from the canvas twice.
        if (settingsChanged) {
            systemInsetLeft = settings.insetLeft
            systemInsetTop = settings.insetTop
            systemInsetRight = settings.insetRight
            systemInsetBottom = settings.insetBottom
        }

        realScreenWidthPx = anchorW
        realScreenHeightPx = anchorH

        // The panel reading is the one rectangle that already contains the bars, so it has no
        // canvas to re-derive from; everything else does, and the insets can still move under it.
        anchorCanvasW = if (panelIsTheAnchor) 0 else choice.width
        anchorCanvasH = if (panelIsTheAnchor) 0 else choice.height
        anchorSource = sourceNoun(choice.source)

        AppLog.i("[UI_DEBUG] HeadUnitScreenConfig: Honest Init | Mode: ${settings.fullscreenMode} | Anchor: ${realScreenWidthPx}x${realScreenHeightPx} (from ${choice.source}) | Seeded Insets: L$systemInsetLeft T$systemInsetTop R$systemInsetRight B$systemInsetBottom")

        recalculate()
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

    /**
     * A laid-out activity window's content area, which is the canvas the projection will get in
     * this screen mode plus whatever insets are in force. Recorded so the first session in a mode
     * announces the shape it will actually draw into, rather than learning it from a surface that
     * only arrives after the answer went out.
     */
    fun noteWindowContent(contentW: Int, contentH: Int) {
        if (contentW <= 0 || contentH <= 0 || App.isPiPActive) return
        val content = ScreenOrientationPolicy.normalise(contentW, contentH, normalisation)
        val canvas = ProjectionCanvasPolicy.canvas(
            content.width, content.height,
            systemInsetLeft + systemInsetRight, systemInsetTop + systemInsetBottom,
        )

        if (!isInitialized) {
            pendingWindowCanvasW = canvas.width
            pendingWindowCanvasH = canvas.height
            return
        }
        val hash = liveHash()
        val known = windowCanvas
        if (known != null && known.width == canvas.width && known.height == canvas.height && known.hash == hash) {
            return
        }
        windowCanvas = ProjectionCanvasPolicy.Measurement(canvas.width, canvas.height, hash)
        adoptCanvas(canvas.width, canvas.height, "the activity window")
    }

    /**
     * The hash a measurement taken right now belongs to. Settings can move between an init() and
     * the layout that answers it, and a reading tagged with the previous hash is thrown away.
     */
    private fun liveHash(): Int =
        if (this::currentSettings.isInitialized) computeSettingsHash(currentSettings) else lastSettingsHash

    /** Move the anchor onto a canvas somebody measured. True when it moved. */
    private fun adoptCanvas(canvasW: Int, canvasH: Int, source: String, note: String = ""): Boolean {
        val anchor = ProjectionCanvasPolicy.anchor(
            canvasW, canvasH,
            systemInsetLeft + systemInsetRight, systemInsetTop + systemInsetBottom,
        )
        // Recorded whether or not the anchor moved, so the provenance can never describe an older
        // rectangle than the numbers beside it.
        anchorCanvasW = canvasW
        anchorCanvasH = canvasH
        anchorSource = source
        if (anchor.width == realScreenWidthPx && anchor.height == realScreenHeightPx) return false

        AppLog.i("[UI_DEBUG] HeadUnitScreenConfig: anchor ${realScreenWidthPx}x${realScreenHeightPx} -> ${anchor.width}x${anchor.height}, measured on $source$note")
        realScreenWidthPx = anchor.width
        realScreenHeightPx = anchor.height
        recalculate()
        return true
    }

    fun updateInsets(left: Int, top: Int, right: Int, bottom: Int) {
        if (systemInsetLeft == left && systemInsetTop == top && systemInsetRight == right && systemInsetBottom == bottom) {
            return
        }

        systemInsetLeft = left
        systemInsetTop = top
        systemInsetRight = right
        systemInsetBottom = bottom

        if (!isInitialized) return

        // A bar arriving after the canvas was measured has to grow the anchor, not shrink the
        // canvas: subtracting it from a rectangle that never contained it counts it twice.
        if (anchorCanvasW > 0 && anchorCanvasH > 0) {
            if (!adoptCanvas(anchorCanvasW, anchorCanvasH, anchorSource, ", against the insets now in force")) {
                recalculate()
            }
        } else {
            recalculate()
        }
    }

    /** What the anchor's rectangle is called in a log line. */
    private fun sourceNoun(source: ProjectionCanvasPolicy.Source): String = when (source) {
        ProjectionCanvasPolicy.Source.SURFACE -> "the projection surface"
        ProjectionCanvasPolicy.Source.WINDOW -> "the activity window"
        ProjectionCanvasPolicy.Source.CACHE -> "a canvas an earlier session measured"
        // Never "the display": outside immersive this reading is the window metrics, and calling it
        // the display is what hid a bar-reduced rectangle being treated as the whole panel.
        ProjectionCanvasPolicy.Source.DISPLAY -> "the window metrics"
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
        // Calculate USABLE area
        val canvas = ProjectionCanvasPolicy.canvas(
            realScreenWidthPx, realScreenHeightPx,
            systemInsetLeft + systemInsetRight, systemInsetTop + systemInsetBottom,
        )
        screenWidthPx = canvas.width
        screenHeightPx = canvas.height

        val selectedResolution = Settings.Resolution.fromId(currentSettings.resolutionId)
        val isPortraitDisplay = screenHeightPx > screenWidthPx
        val canNegotiateHevc = canNegotiateHevcHighResolution()

        // 1. Determine base negotiated resolution
        if (isResolutionLocked) {
            // Safety Check: If the locked resolution's orientation (Landscape/Portrait)
            // no longer matches the display orientation, the lock is stale and must be dropped.
            val isPortraitRes = getNegotiatedHeight() > getNegotiatedWidth()
            if (isPortraitRes != isPortraitDisplay) {
                AppLog.i("[UI_DEBUG] CarScreen: Orientation mismatch detected (Res: ${if(isPortraitRes) "P" else "L"}, Display: ${if(isPortraitDisplay) "P" else "L"}). DROPPING LOCK.")
                unlockResolution()
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
        if (pixelsOf(negotiatedResolutionType) > pixelsOf(hardCeiling)) {
            negotiatedResolutionType = hardCeiling
        }
        // And to what the link can carry. Same min(current, ceiling) shape as the panel cap above,
        // so a user already asking for less is never raised to meet it.
        val linkCeiling = narrowBandCeiling(isPortraitDisplay)
        if (linkCeiling != null && pixelsOf(negotiatedResolutionType) > pixelsOf(linkCeiling)) {
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

        if (!notifyingMarginDivergence &&
            MarginAnnouncementPolicy.shouldReannounce(
                announcedWidthMargin, announcedHeightMargin, getWidthMargin(), getHeightMargin()
            )
        ) {
            AppLog.i(
                "[UI_DEBUG] CarScreen: margins drifted from the announced " +
                    "${announcedWidthMargin}x${announcedHeightMargin} to ${getWidthMargin()}x${getHeightMargin()}"
            )
            notifyingMarginDivergence = true
            try {
                onMarginsDiverged?.invoke()
            } finally {
                notifyingMarginDivergence = false
            }
        }

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

    fun getAdjustedHeight(): Int = ProjectionGeometryPolicy.adjustedHeight(getNegotiatedHeight(), scaleFactor)

    fun getAdjustedWidth(): Int = ProjectionGeometryPolicy.adjustedWidth(getNegotiatedWidth(), scaleFactor)

    // COVER target size for the legacy forcedScale/SurfaceView path, which sizes the view through
    // LayoutParams rather than a View.scale transform.
    fun getCoverWidth(): Int =
        ProjectionGeometryPolicy.coverWidth(screenWidthPx, screenHeightPx, getNegotiatedWidth(), getNegotiatedHeight())

    fun getCoverHeight(): Int =
        ProjectionGeometryPolicy.coverHeight(screenWidthPx, screenHeightPx, getNegotiatedWidth(), getNegotiatedHeight())

    fun getNegotiatedHeight(): Int {
        val resString = negotiatedResolutionType.toString().replace("_", "")
        return try {
            resString.split("x")[1].toInt()
        } catch (e: Exception) {
            480
        }
    }

    private fun canNegotiateHevcHighResolution(): Boolean {
        if (VideoDecoder.isHevcSupported()) return true
        if (currentSettings.videoCodec != VideoDecoder.CodecType.H265.settingsValue || !currentSettings.forceSoftwareDecoding) return false
        return when (currentSettings.softwareVideoDecoder) {
            Settings.SoftwareVideoDecoder.BUNDLED_FFMPEG -> VideoDecoder.isBundledHevcDecoderAvailable()
            Settings.SoftwareVideoDecoder.DEVICE_MEDIACODEC -> VideoDecoder.isHevcDecoderAvailable(includeSoftware = true)
        }
    }

    fun getNegotiatedWidth(): Int {
        val resString = negotiatedResolutionType.toString().replace("_", "")
        return try {
            resString.split("x")[0].toInt()
        } catch (e: Exception) {
            800
        }
    }

    // A stored resolution above the panel's rows used to hide a third of the frame behind a margin
    // the touch mapper never saw. In FILL a wider panel describes itself by pixel shape instead.
    private fun marginStrategy(): MarginStrategyPolicy.Strategy = MarginStrategyPolicy.select(
        videoFitMode, screenWidthPx, screenHeightPx, getNegotiatedWidth(), getNegotiatedHeight()
    )

    fun getHeightMargin(): Int =
        if (marginStrategy() == MarginStrategyPolicy.Strategy.PAR) 0
        else ProjectionGeometryPolicy.heightMargin(getNegotiatedHeight(), screenHeightPx, scaleFactor)

    fun getWidthMargin(): Int =
        if (marginStrategy() == MarginStrategyPolicy.Strategy.PAR) 0
        else ProjectionGeometryPolicy.widthMargin(getNegotiatedWidth(), screenWidthPx, scaleFactor)

    fun getScaleX(): Float = ProjectionGeometryPolicy.scaleX(
        videoFitMode, forcedScale,
        screenWidthPx, screenHeightPx, getNegotiatedWidth(), getNegotiatedHeight(),
        getWidthMargin(), getHeightMargin()
    )

    fun getScaleY(): Float = ProjectionGeometryPolicy.scaleY(
        videoFitMode, forcedScale,
        screenWidthPx, screenHeightPx, getNegotiatedWidth(), getNegotiatedHeight(),
        getWidthMargin(), getHeightMargin()
    )

    fun getDensityDpi(): Int {
        return if (this::currentSettings.isInitialized && currentSettings.dpiPixelDensity != 0) {
            currentSettings.dpiPixelDensity
        } else {
            densityDpi
        }
    }

    fun getPixelAspectRatioE4(): Int {
        // The settings row normalises anything <= 0 to 10000, so 10000 is also "unset" and is what
        // lets the derived value through. An explicit non-square choice always wins.
        val manual = if (this::currentSettings.isInitialized) currentSettings.pixelAspectRatioE4 else 0
        if (manual > 0 && manual != ProjectionGeometryPolicy.SQUARE_PIXELS_E4) return manual
        return ProjectionGeometryPolicy.pixelAspectRatioE4(
            videoFitMode, screenWidthPx, screenHeightPx, getNegotiatedWidth(), getNegotiatedHeight(),
            getWidthMargin(), getHeightMargin()
        )
    }

    fun getUsableWidth(): Int = screenWidthPx
    fun getUsableHeight(): Int = screenHeightPx


    // These are half the total margin, distributed symmetrically.
    fun getLeftMargin(): Int = getWidthMargin() / 2
    fun getRightMargin(): Int = getWidthMargin() - getLeftMargin()
    fun getTopMargin(): Int = getHeightMargin() / 2
    fun getBottomMargin(): Int = getHeightMargin() - getTopMargin()

    /**
     * Called when the actual rendering surface dimensions become known (from onSurfaceChanged).
     * Compares with the current usable area and updates the anchor if they differ.
     * @return true if the dimensions changed and margins need to be re-sent to AA.
     */
    fun updateSurfaceDimensions(surfaceW: Int, surfaceH: Int): Boolean {
        // A picture-in-picture window is a few hundred px of somebody else's screen, not the canvas.
        if (App.isPiPActive) return false

        val surface = ScreenOrientationPolicy.normalise(surfaceW, surfaceH, normalisation)
        val finalSurfaceW = surface.width
        val finalSurfaceH = surface.height

        val diffW = kotlin.math.abs(finalSurfaceW - screenWidthPx)
        val diffH = kotlin.math.abs(finalSurfaceH - screenHeightPx)

        if (diffW <= SURFACE_MISMATCH_TOLERANCE && diffH <= SURFACE_MISMATCH_TOLERANCE) {
            // Already the canvas in force, but still the reading that outranks the display metrics.
            surfaceCanvas = ProjectionCanvasPolicy.Measurement(screenWidthPx, screenHeightPx, liveHash())
            return false
        }

        if( (diffW > 0 && getNegotiatedWidth() == finalSurfaceW) || (diffH > 0 && getNegotiatedHeight() == finalSurfaceH)) {
            AppLog.i("[UI_DEBUG_FIX] Surface mismatch detected but matches negotiated resolution. Usable: ${screenWidthPx}x${screenHeightPx}, Actual surface: ${finalSurfaceW}x${finalSurfaceH}. Ignoring.")
            return false
        }

        AppLog.i("[UI_DEBUG_FIX] Surface mismatch detected! Usable: ${screenWidthPx}x${screenHeightPx}, Actual surface: ${finalSurfaceW}x${finalSurfaceH} (diff: ${diffW}x${diffH})")

        // The surface is the canvas, so it outranks every other reading until the settings change.
        surfaceCanvas = ProjectionCanvasPolicy.Measurement(finalSurfaceW, finalSurfaceH, liveHash())
        adoptCanvas(finalSurfaceW, finalSurfaceH, "the projection surface")

        AppLog.i("[UI_DEBUG_FIX] Recalculated: usable=${screenWidthPx}x${screenHeightPx}, margins: w=${getWidthMargin()}, h=${getHeightMargin()}, per-side: L=${getLeftMargin()} T=${getTopMargin()} R=${getRightMargin()} B=${getBottomMargin()}")
        return true
    }

    /**
     * Computes a hash of all settings that affect screen dimensions.
     * Used to invalidate the cached surface dimensions when settings change.
     */
    fun computeSettingsHash(settings: Settings): Int = ScreenSettingsHash.of(
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
        displayId = measuredDisplayId,
    )

    fun lockResolution() {
        if (!isResolutionLocked) {
            AppLog.i("[UI_DEBUG] HeadUnitScreenConfig: Locking resolution at $negotiatedResolutionType")
            isResolutionLocked = true
        }
    }

    fun unlockResolution() {
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
