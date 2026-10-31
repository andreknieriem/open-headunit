package com.andrerinas.openheadunit.utils

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.os.Looper
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.ViewTreeObserver
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.lang.ref.WeakReference

object SystemUI {

    /**
     * Keeps immersive full screen hidden on API 16 to 18, after [apply]: those versions have no
     * sticky immersive mode, so once a touch brings the navigation bar back it stays over the edge
     * of the window. The bars hide again after [LegacyImmersivePolicy.REHIDE_DELAY_MS]. Does
     * nothing from API 19, where sticky immersive does this itself. The projection screen keeps its
     * own version of this, which also rescales the video.
     */
    fun keepLegacyBarsHidden(window: Window, mode: Settings.FullscreenMode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) return
        val decor = window.decorView
        if (!LegacyImmersivePolicy.applies(Build.VERSION.SDK_INT, mode)) {
            decor.setOnSystemUiVisibilityChangeListener(null)
            return
        }
        val hiddenFlags = decor.systemUiVisibility
        val rehide = Runnable { decor.systemUiVisibility = hiddenFlags }
        decor.setOnSystemUiVisibilityChangeListener { visibility ->
            if (LegacyImmersivePolicy.barsShown(visibility)) {
                decor.removeCallbacks(rehide)
                decor.postDelayed(rehide, LegacyImmersivePolicy.REHIDE_DELAY_MS)
            }
        }
    }

    /**
     * [notesCanvas] false for a window that is not the projection. Its own decor and keyboard
     * must not replace either the projection's canvas or the insets used to measure that canvas.
     */
    fun apply(
        window: Window,
        root: View,
        mode: Settings.FullscreenMode,
        notesCanvas: Boolean = true,
        onInsetsChanged: (() -> Unit)? = null,
    ) {
        applySystemBars(window, mode)
        applyInsets(window, root, mode, notesCanvas, onInsetsChanged)
    }

    /** Configures dialog windows without changing the projection's canvas or manual insets. */
    fun applySystemBars(window: Window, mode: Settings.FullscreenMode) {
        // Always keep screen on for Headunit functionality
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Handle Display Cutout (Notch) for Android P+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val params = window.attributes
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = params
        }

        val controllerCompat = WindowInsetsControllerCompat(window, window.decorView)

        // Modern Edge-to-Edge for Android 11+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Normally force decor to NOT fit only when we are in some kind of immersive mode.
            // NONE fits naturally on older releases, but API 35+ targeting 35+ enforces
            // edge-to-edge: its root must own fitting instead.
            window.setDecorFitsSystemWindows(mode == Settings.FullscreenMode.NONE && !enforcesEdgeToEdge(window))
        }

        // Apply visibility using Compat API
        when (mode) {
            Settings.FullscreenMode.IMMERSIVE, Settings.FullscreenMode.IMMERSIVE_WITH_NOTCH -> {
                controllerCompat.hide(WindowInsetsCompat.Type.systemBars())
                controllerCompat.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            Settings.FullscreenMode.STATUS_ONLY -> {
                controllerCompat.hide(WindowInsetsCompat.Type.statusBars())
                controllerCompat.show(WindowInsetsCompat.Type.navigationBars())
                controllerCompat.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            Settings.FullscreenMode.NONE -> {
                controllerCompat.show(WindowInsetsCompat.Type.systemBars())
            }
        }

        // Legacy Flags (Layout stability & Fallback for hiding bars)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            @Suppress("DEPRECATION")
            var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            
            when (mode) {
                Settings.FullscreenMode.IMMERSIVE, Settings.FullscreenMode.IMMERSIVE_WITH_NOTCH -> {
                    flags = flags or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or 
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_FULLSCREEN or 
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        flags = flags or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    } else {
                        // Older than KitKat: use FLAG_FULLSCREEN for reliability
                        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                        flags = flags or View.SYSTEM_UI_FLAG_LOW_PROFILE
                    }
                }
                Settings.FullscreenMode.STATUS_ONLY -> {
                    flags = flags or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_FULLSCREEN
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                        flags = flags or View.SYSTEM_UI_FLAG_LOW_PROFILE
                    }
                }
                Settings.FullscreenMode.NONE -> {
                    // Reset flags for normal mode
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                    }
                }
            }
            
            window.decorView.systemUiVisibility = flags
        }

        // Visual Polish: Background colors and Light/Dark icon appearance
        if (mode != Settings.FullscreenMode.IMMERSIVE && mode != Settings.FullscreenMode.IMMERSIVE_WITH_NOTCH) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                if (Build.VERSION.SDK_INT < 35) {
                    window.statusBarColor = Color.BLACK
                    if (mode == Settings.FullscreenMode.NONE || mode == Settings.FullscreenMode.STATUS_ONLY) {
                        window.navigationBarColor = Color.BLACK
                    }
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
                if (mode == Settings.FullscreenMode.NONE || mode == Settings.FullscreenMode.STATUS_ONLY) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
                }
            }
            controllerCompat.isAppearanceLightStatusBars = false
            controllerCompat.isAppearanceLightNavigationBars = false
        }
    }

    // Both gates matter: adding system padding while the OS still fits NONE would count it twice.
    private fun enforcesEdgeToEdge(window: Window): Boolean =
        Build.VERSION.SDK_INT >= 35 && window.context.applicationInfo.targetSdkVersion >= 35

    private fun applyInsets(
        window: Window,
        root: View,
        mode: Settings.FullscreenMode,
        notesCanvas: Boolean,
        onInsetsChanged: (() -> Unit)?,
    ) {
        val observer = observers[root]?.get() ?: InsetsObserver(window, root).also {
            observers[root] = WeakReference(it)
        }
        observer.configure(mode, notesCanvas, onInsetsChanged)
    }

    /** A one-shot surface callback may precede the root's completed layout. */
    internal fun measureProjectionSurface(root: View, view: View, measure: () -> Unit) {
        observers[root]?.get()?.measureSurface(view, measure)
    }

    private data class FitPolicy(
        val mode: Settings.FullscreenMode,
        val automatic: Boolean,
        val manual: Insets,
        val orientation: Int,
        val settingsHash: Int,
    )

    private data class AppliedInsets(val policy: FitPolicy, val padding: Insets, val ime: Boolean)
    private data class Layout(val width: Int, val height: Int, val insets: AppliedInsets)
    private data class ViewFrame(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        constructor(view: View) : this(view.left, view.top, view.right, view.bottom)
    }
    private data class SurfaceMeasurement(
        val view: View,
        val frame: ViewFrame,
        val rootWidth: Int,
        val rootHeight: Int,
        val policy: FitPolicy,
        val padding: Insets,
        val measure: () -> Unit,
    )

    private class InsetsObserver(private val window: Window, private val root: View) :
        ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        private val owner = Any()
        private val settings = Settings(root.context)
        private var mode = Settings.FullscreenMode.NONE
        private var notesCanvas = false
        private var callback: (() -> Unit)? = null
        private var applied: AppliedInsets? = null
        private var delivered: Layout? = null
        private var pendingSurface: SurfaceMeasurement? = null
        private var notify = false
        private var invalidationVersion = 0L
        private var observer: ViewTreeObserver? = null
        private var observedPreferences: SharedPreferences? = null
        private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                null, "resolutionId", "dpi-pixel-density", "pixel-aspect-ratio-e4",
                "inset-left", "inset-top", "inset-right", "inset-bottom",
                "view-mode", Settings.KEY_SCREEN_ORIENTATION,
                "fullscreen-mode", "start-in-fullscreen-mode",
                "video-fit-mode", "stretch_to_fill", "forced_scale",
                "dpi-floor-override", "display-size-preset" -> {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        settingsChanged()
                    } else {
                        root.post { settingsChanged() }
                    }
                }
            }
        }

        init {
            root.addOnAttachStateChangeListener(this)
            installPreDraw()
            root.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
                if (l != oldL || t != oldT || r != oldR || b != oldB) invalidate()
            }
            ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets -> apply(insets) }
            if (ViewCompat.isAttachedToWindow(root)) observeSettings()
        }

        private fun observeSettings() {
            if (observedPreferences != null) return
            val prefs = try {
                root.context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
            } catch (_: Exception) {
                null
            } ?: return
            prefs.registerOnSharedPreferenceChangeListener(settingsListener)
            observedPreferences = prefs
        }

        private fun settingsChanged() {
            pendingSurface = null
            applied = null
            invalidate()
            ViewCompat.requestApplyInsets(root)
            root.invalidate()
        }

        private fun policy(): FitPolicy = FitPolicy(
            mode, mode == Settings.FullscreenMode.NONE && !enforcesEdgeToEdge(window),
            Insets.of(settings.insetLeft, settings.insetTop, settings.insetRight, settings.insetBottom),
            root.resources.configuration.orientation,
            HeadUnitScreenConfig.computeSettingsHash(settings, root.resources.configuration.orientation),
        )

        fun configure(mode: Settings.FullscreenMode, notesCanvas: Boolean, callback: (() -> Unit)?) {
            if (ViewCompat.isAttachedToWindow(root)) observeSettings()
            this.mode = mode
            this.notesCanvas = notesCanvas
            this.callback = callback
            if (notesCanvas) {
                activeObserver = WeakReference(this)
                HeadUnitScreenConfig.beginWindowMeasurement(owner, mode)
            } else {
                HeadUnitScreenConfig.releaseWindowMeasurement(owner)
                if (activeObserver?.get() === this) activeObserver = null
                pendingSurface = null
            }
            // A focus reapply keeps the padding that was actually laid out. A changed policy
            // waits for its own inset dispatch instead of pairing old bounds with manual-only bars.
            if (applied?.policy != policy()) {
                applied = null
                pendingSurface = null
            }
            if (root.width == 0 && applied == null) {
                val manual = policy().manual
                root.setPadding(manual.left, manual.top, manual.right, manual.bottom)
            }
            invalidate()
            installPreDraw()
            ViewCompat.requestApplyInsets(root)
            root.requestLayout()
        }

        private fun invalidate() {
            notify = true
            invalidationVersion++
            if (notesCanvas) HeadUnitScreenConfig.invalidateWindowMeasurement(owner)
        }

        private fun apply(insets: WindowInsetsCompat): WindowInsetsCompat {
            val policy = policy()
            val cutout = if (Build.VERSION.SDK_INT >= 28) WindowInsetsCompat.Type.displayCutout() else 0
            val mask = when (policy.mode) {
                Settings.FullscreenMode.IMMERSIVE -> 0
                Settings.FullscreenMode.IMMERSIVE_WITH_NOTCH -> cutout
                Settings.FullscreenMode.STATUS_ONLY -> WindowInsetsCompat.Type.navigationBars() or cutout
                Settings.FullscreenMode.NONE -> WindowInsetsCompat.Type.systemBars() or cutout
            }
            val bars = if (policy.automatic) Insets.NONE
                else insets.getInsets(mask or WindowInsetsCompat.Type.ime())
            val manual = policy.manual
            val padding = Insets.of(
                bars.left + manual.left, bars.top + manual.top,
                bars.right + manual.right, bars.bottom + manual.bottom,
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()) != Insets.NONE
            applied = AppliedInsets(policy, padding, ime)
            if (ime || pendingSurface?.let { it.policy != policy || it.padding != padding } == true) {
                pendingSurface = null
            }
            invalidate()
            root.setPadding(padding.left, padding.top, padding.right, padding.bottom)
            return if (policy.automatic) insets else WindowInsetsCompat.CONSUMED
        }

        override fun onPreDraw(): Boolean {
            if (root.isLayoutRequested || root.width <= 0 || root.height <= 0) return true
            val previous = delivered
            if (observedPreferences != null && !notify && pendingSurface == null && previous != null &&
                previous.width == root.width && previous.height == root.height &&
                previous.insets.padding.left == root.paddingLeft &&
                previous.insets.padding.top == root.paddingTop &&
                previous.insets.padding.right == root.paddingRight &&
                previous.insets.padding.bottom == root.paddingBottom &&
                previous.insets.policy.orientation == root.resources.configuration.orientation &&
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ||
                    previous.insets.ime == (window.currentFocus?.onCheckIsTextEditor() == true))
            ) return true
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                // Legacy fitting belongs to the window; measure its laid-out root with the
                // manual padding. There is no public per-source IME inset before API 21, so
                // conservatively defer persistence while a local text editor owns input.
                val policy = policy()
                val editing = window.currentFocus?.onCheckIsTextEditor() == true
                applied = AppliedInsets(policy, policy.manual, editing)
                if (editing) pendingSurface = null
                if (padding() != policy.manual) {
                    root.setPadding(policy.manual.left, policy.manual.top, policy.manual.right, policy.manual.bottom)
                    return false
                }
            }
            val sample = applied ?: return true
            if (sample.policy != policy() || sample.padding != padding()) {
                invalidate()
                applied = null
                pendingSurface = null
                ViewCompat.requestApplyInsets(root)
                return true
            }
            val layout = Layout(root.width, root.height, sample)
            val currentProjection = notesCanvas && activeObserver?.get() === this
            val changed = notify || delivered != layout
            val version = invalidationVersion
            if (currentProjection && !sample.ime &&
                (changed || !HeadUnitScreenConfig.isWindowMeasurementCurrent(owner))) {
                HeadUnitScreenConfig.publishWindowGeometry(
                    owner, root.context, root.resources.displayMetrics, settings,
                    layout.width, layout.height,
                    sample.padding.left, sample.padding.top, sample.padding.right, sample.padding.bottom,
                    sample.policy.orientation, sample.policy.settingsHash,
                )
            }
            delivered = layout
            if (invalidationVersion == version) notify = false
            if (changed && (!notesCanvas || currentProjection)) callback?.invoke()
            // The scaler may have requested a second layout. Only a sample whose view still
            // occupies the same frame after that layout belongs to the committed root.
            drainSurface()
            return !root.isLayoutRequested
        }

        private fun padding(): Insets = Insets.of(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)

        private fun canMeasureSurface(): Boolean = notesCanvas && activeObserver?.get() === this &&
            !root.isLayoutRequested && applied?.ime == false &&
            delivered == applied?.let { Layout(root.width, root.height, it) } &&
            applied?.policy == policy() && applied?.padding == padding() &&
            HeadUnitScreenConfig.isWindowMeasurementCurrent(owner)

        fun measureSurface(view: View, measure: () -> Unit) {
            if (!notesCanvas || activeObserver?.get() !== this || applied?.ime == true ||
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP &&
                    window.currentFocus?.onCheckIsTextEditor() == true)) return
            if (canMeasureSurface() && !view.isLayoutRequested) {
                pendingSurface = null
                measure()
                return
            }
            pendingSurface = SurfaceMeasurement(
                view, ViewFrame(view), root.width, root.height, policy(), padding(), measure,
            )
            root.invalidate()
        }

        private fun drainSurface() {
            val pending = pendingSurface ?: return
            if (!notesCanvas || activeObserver?.get() !== this || applied?.ime == true ||
                pending.policy != policy() || pending.padding != padding() ||
                pending.rootWidth != root.width || pending.rootHeight != root.height ||
                pending.frame != ViewFrame(pending.view)) {
                pendingSurface = null
                return
            }
            if (!canMeasureSurface() || pending.view.isLayoutRequested) return
            pendingSurface = null
            pending.measure()
            if (notesCanvas && activeObserver?.get() === this && applied?.policy == pending.policy) {
                callback?.invoke()
            }
        }

        private fun installPreDraw() {
            val current = root.viewTreeObserver
            if (observer === current) return
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            current.addOnPreDrawListener(this)
            observer = current
        }

        override fun onViewAttachedToWindow(v: View) {
            observeSettings()
            installPreDraw()
            invalidate()
            ViewCompat.requestApplyInsets(root)
        }

        override fun onViewDetachedFromWindow(v: View) {
            observedPreferences?.unregisterOnSharedPreferenceChangeListener(settingsListener)
            observedPreferences = null
            pendingSurface = null
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            observer = null
            invalidate()
        }
    }

    // Weak values too: an observer owns its view, and a strong value would retain the weak key.
    private val observers = java.util.WeakHashMap<View, WeakReference<InsetsObserver>>()
    private var activeObserver: WeakReference<InsetsObserver>? = null
}
