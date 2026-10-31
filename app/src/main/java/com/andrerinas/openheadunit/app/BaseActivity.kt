package com.andrerinas.openheadunit.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.ActivityState
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.AppThemeManager
import com.andrerinas.openheadunit.utils.LocaleHelper
import com.andrerinas.openheadunit.utils.Settings
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.bumptech.glide.signature.ObjectKey
import java.io.File

/**
 * Base Activity that handles app language configuration, HUD mirroring, custom background, and live theme switching.
 * All activities should extend this class to properly apply the user's language preference and HUD mode.
 */
open class BaseActivity : AppCompatActivity() {

    private var currentLanguage: String? = null
    private var currentAppTheme: Settings.AppTheme? = null
    private var currentNightMode: Int = 0
    private var currentUseGradientBackground: Boolean = false
    private var currentUseExtremeDarkMode: Boolean = false
    private var currentHudMirroring: Boolean = false
    private var currentHomeBackgroundImagePath: String? = null
    private var backgroundTarget: CustomTarget<Bitmap>? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = Settings(this)
        currentLanguage = settings.appLanguage
        currentAppTheme = settings.appTheme
        currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        currentUseGradientBackground = settings.useGradientBackground
        currentUseExtremeDarkMode = settings.useExtremeDarkMode
        currentHudMirroring = settings.hudMirroring
        currentHomeBackgroundImagePath = settings.homeBackgroundImagePath

        val appliedVersion = AppThemeManager.themeVersion.value
        AppThemeManager.themeVersion.observe(this) { version ->
            if (version != appliedVersion) {
                recreate()
            }
        }

        applyWindowBackground()
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        applyHudMirroring()
    }

    override fun setContentView(view: View?) {
        super.setContentView(view)
        applyHudMirroring()
    }

    override fun setContentView(view: View?, params: ViewGroup.LayoutParams?) {
        super.setContentView(view, params)
        applyHudMirroring()
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        applyHudMirroring()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyHudMirroring()
    }

    override fun onResume() {
        super.onResume()
        val settings = Settings(this)
        val actualNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (currentLanguage != settings.appLanguage ||
            currentAppTheme != settings.appTheme ||
            currentNightMode != actualNightMode ||
            currentUseGradientBackground != settings.useGradientBackground ||
            currentUseExtremeDarkMode != settings.useExtremeDarkMode ||
            currentHudMirroring != settings.hudMirroring ||
            currentHomeBackgroundImagePath != settings.homeBackgroundImagePath) {
            recreate()
        } else {
            applyHudMirroring()
            applyWindowBackground()
        }
    }

    protected fun applyHudMirroring() {
        val settings = Settings(this)
        findViewById<View>(android.R.id.content)?.scaleX = if (settings.hudMirroring) -1.0f else 1.0f
    }

    override fun onDestroy() {
        backgroundTarget?.let { Glide.with(applicationContext).clear(it) }
        backgroundTarget = null
        super.onDestroy()
    }

    fun applyWindowBackground() {
        val settings = Settings(this)
        currentHomeBackgroundImagePath = settings.homeBackgroundImagePath
        val path = settings.homeBackgroundImagePath
        val file = if (path.isNotEmpty()) File(path) else null
        val isNightActive = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        if (file != null && file.exists() && file.length() > 0) {
            // When dark mode is active and user wants OLED pure black at night:
            if (isNightActive && settings.homeBackgroundNightMode == Settings.BackgroundNightMode.PURE_BLACK) {
                window.setBackgroundDrawable(ColorDrawable(ContextCompat.getColor(this, R.color.extreme_dark_background)))
                return
            }

            try {
                backgroundTarget = ActivityState.glide(this)
                    .asBitmap()
                    .load(file)
                    .signature(ObjectKey(file.lastModified()))
                    .centerCrop()
                    .into(object : CustomTarget<Bitmap>() {
                        override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                            if (!ActivityState.isGone(this@BaseActivity)) {
                                val drawable = BitmapDrawable(resources, resource)
                                if (isNightActive && settings.homeBackgroundNightMode == Settings.BackgroundNightMode.DIM) {
                                    drawable.colorFilter = PorterDuffColorFilter(Color.argb(135, 0, 0, 0), PorterDuff.Mode.SRC_ATOP)
                                }
                                window.setBackgroundDrawable(drawable)
                            }
                        }

                        override fun onLoadCleared(placeholder: Drawable?) {}
                    })
            } catch (e: Exception) {
                AppLog.e("Failed to load custom background: ${e.message}")
                resetWindowBackgroundToTheme()
            }
        } else {
            resetWindowBackgroundToTheme()
        }
    }

    protected fun resetWindowBackgroundToTheme() {
        val settings = Settings(this)
        val isNightActive = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (settings.appTheme == Settings.AppTheme.EXTREME_DARK ||
            (isNightActive && (settings.useExtremeDarkMode || settings.homeBackgroundNightMode == Settings.BackgroundNightMode.PURE_BLACK))) {
            window.setBackgroundDrawable(ColorDrawable(ContextCompat.getColor(this, R.color.extreme_dark_background)))
        } else if (settings.useGradientBackground) {
            val drawable = ContextCompat.getDrawable(this, R.drawable.bg_gradient)?.mutate()
            if (isNightActive && settings.homeBackgroundNightMode == Settings.BackgroundNightMode.DIM) {
                drawable?.colorFilter = PorterDuffColorFilter(Color.argb(135, 0, 0, 0), PorterDuff.Mode.SRC_ATOP)
            }
            window.setBackgroundDrawable(drawable)
        } else {
            val drawable = ContextCompat.getDrawable(this, R.drawable.bg)?.mutate()
            if (isNightActive && settings.homeBackgroundNightMode == Settings.BackgroundNightMode.DIM) {
                drawable?.colorFilter = PorterDuffColorFilter(Color.argb(135, 0, 0, 0), PorterDuff.Mode.SRC_ATOP)
            }
            window.setBackgroundDrawable(drawable)
        }
    }
}
