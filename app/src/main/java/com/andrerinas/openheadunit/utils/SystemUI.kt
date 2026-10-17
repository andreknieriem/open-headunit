package com.andrerinas.openheadunit.utils

import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.andrerinas.openheadunit.utils.AppLog

object SystemUI {

    /**
     * [notesCanvas] false for a window that is not the projection, so it cannot describe a canvas
     * the video will never be drawn into.
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
            // Only force decor to NOT fit if we are in some kind of immersive mode.
            // If mode is NONE, we want the system to fit the window naturally.
            window.setDecorFitsSystemWindows(mode == Settings.FullscreenMode.NONE)
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

    private fun applyInsets(
        window: Window,
        root: View,
        mode: Settings.FullscreenMode,
        notesCanvas: Boolean,
        onInsetsChanged: (() -> Unit)?,
    ) {
        val settings = Settings(root.context)

        // IMMEDIATE APPLICATION OF MANUAL INSETS
        val manualL = settings.insetLeft
        val manualT = settings.insetTop
        val manualR = settings.insetRight
        val manualB = settings.insetBottom

        // Read before the line below re-seeds the insets: until it runs, the content area and the
        // insets in force describe the same layout, and afterwards one half of the pair is stale.
        val canvasView = if (notesCanvas) (window.decorView.findViewById<View>(android.R.id.content) ?: root) else null
        canvasView?.let { noteContentSize(it) }

        root.setPadding(manualL, manualT, manualR, manualB)
        HeadUnitScreenConfig.updateInsets(manualL, manualT, manualR, manualB)

        // Android 4.x Legacy Fallback
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            root.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    val rect = android.graphics.Rect()
                    window.decorView.getWindowVisibleDisplayFrame(rect)

                    @Suppress("DEPRECATION")
                    val display = window.windowManager.defaultDisplay
                    val size = android.graphics.Point()
                    display.getSize(size)

                    val insetT = rect.top
                    val insetB = size.y - rect.bottom
                    val insetL = rect.left
                    val insetR = size.x - rect.right

                    AppLog.d("[UI_DEBUG] Legacy SystemUI: Detected Insets L$insetL T$insetT R$insetR B$insetB")
                    HeadUnitScreenConfig.updateInsets(manualL + insetL, manualT + insetT, manualR + insetR, manualB + insetB)
                    onInsetsChanged?.invoke()
                    root.viewTreeObserver.removeGlobalOnLayoutListener(this)
                }
            })
        }

        // Set up listener for dynamic system bars (API 21+)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insetsCompat ->
            if (mode == Settings.FullscreenMode.NONE) {
                // In standard mode, let the system handle fitting and don't apply manual padding.
                // We still update HeadUnitScreenConfig with 0 insets because the window is already resized.
                HeadUnitScreenConfig.updateInsets(manualL, manualT, manualR, manualB)
                onInsetsChanged?.invoke()
                return@setOnApplyWindowInsetsListener insetsCompat
            }

            var typeMask = 0
            when (mode) {
                Settings.FullscreenMode.IMMERSIVE -> {
                    typeMask = 0 // Standard Immersive: Overlay everything (Notch included)
                }
                Settings.FullscreenMode.IMMERSIVE_WITH_NOTCH -> {
                    if (Build.VERSION.SDK_INT >= 28) {
                        typeMask = WindowInsetsCompat.Type.displayCutout()
                    }
                }
                Settings.FullscreenMode.STATUS_ONLY -> {
                    typeMask = WindowInsetsCompat.Type.navigationBars()
                    if (Build.VERSION.SDK_INT >= 28) {
                        typeMask = typeMask or WindowInsetsCompat.Type.displayCutout()
                    }
                }
                else -> {
                    typeMask = WindowInsetsCompat.Type.systemBars()
                    if (Build.VERSION.SDK_INT >= 28) {
                        typeMask = typeMask or WindowInsetsCompat.Type.displayCutout()
                    }
                }
            }

            // Always pad for the soft keyboard so text fields are not covered and the
            // content shrinks while the IME is up (0 when the keyboard is hidden).
            typeMask = typeMask or WindowInsetsCompat.Type.ime()

            val bars = if (typeMask != 0) {
                insetsCompat.getInsets(typeMask)
            } else {
                androidx.core.graphics.Insets.NONE
            }

            v.setPadding(bars.left + manualL, bars.top + manualT, bars.right + manualR, bars.bottom + manualB)
            HeadUnitScreenConfig.updateInsets(bars.left + manualL, bars.top + manualT, bars.right + manualR, bars.bottom + manualB)

            onInsetsChanged?.invoke()
            WindowInsetsCompat.CONSUMED
        }

        ViewCompat.requestApplyInsets(root)
        root.requestLayout()

        canvasView?.let { watchCanvas(it) }
    }

    /**
     * Report this window's content area as the canvas for the current screen mode. Service
     * discovery answers before any projection surface exists, so without this the first session in
     * a mode announces a pixel shape measured from the display instead of the window.
     */
    private fun watchCanvas(content: View) {
        // apply() runs again on every focus change, so the watch is installed once per view and
        // left in place: a one-shot would latch whatever the insets happened to be that frame.
        if (canvasWatched.put(content, true) != null) return
        content.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> noteContentSize(v) }
    }

    /** A window shrunk by the soft keyboard is not the canvas the projection will get. */
    private fun noteContentSize(content: View) {
        if (content.width <= 0 || content.height <= 0) return
        val ime = ViewCompat.getRootWindowInsets(content)?.getInsets(WindowInsetsCompat.Type.ime())
        if (ime != null && (ime.bottom > 0 || ime.top > 0)) return
        HeadUnitScreenConfig.noteWindowContent(content.width, content.height)
    }

    private val canvasWatched = java.util.WeakHashMap<View, Boolean>()
}
