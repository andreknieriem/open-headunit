package com.andrerinas.openheadunit.app

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.andrerinas.openheadunit.utils.AppThemeManager
import com.andrerinas.openheadunit.utils.LocaleHelper
import com.andrerinas.openheadunit.utils.Settings

/**
 * Base Activity that handles app language configuration, HUD mirroring, and live theme switching.
 * All activities should extend this class to properly apply the user's language preference and HUD mode.
 */
open class BaseActivity : AppCompatActivity() {

    private var currentLanguage: String? = null
    private var currentAppTheme: Settings.AppTheme? = null
    private var currentNightMode: Int = 0
    private var currentUseGradientBackground: Boolean = false
    private var currentUseExtremeDarkMode: Boolean = false
    private var currentHudMirroring: Boolean = false

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

        val appliedVersion = AppThemeManager.themeVersion.value
        AppThemeManager.themeVersion.observe(this) { version ->
            if (version != appliedVersion) {
                recreate()
            }
        }
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
            currentHudMirroring != settings.hudMirroring) {
            recreate()
        } else {
            applyHudMirroring()
        }
    }

    protected fun applyHudMirroring() {
        val settings = Settings(this)
        findViewById<View>(android.R.id.content)?.scaleX = if (settings.hudMirroring) -1.0f else 1.0f
    }
}
