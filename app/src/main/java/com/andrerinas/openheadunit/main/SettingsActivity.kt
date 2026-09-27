package com.andrerinas.openheadunit.main

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.navigation.fragment.NavHostFragment
import android.content.res.Configuration
import android.os.Build
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.app.BaseActivity
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.SystemUI

class SettingsActivity : BaseActivity() {

    override fun attachBaseContext(newBase: Context) {
        val settings  = Settings(newBase)
        val scale = settings.uiScaleSettingsPercent / 100.0f
        if (scale != 1.0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            val cfg = Configuration(newBase.resources.configuration)
            val metrics = newBase.resources.displayMetrics
            cfg.densityDpi = (metrics.densityDpi * scale).toInt()
            val ctx = newBase.createConfigurationContext(cfg)
            super.attachBaseContext(ctx)
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = Settings(this).screenOrientation.androidOrientation
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val appSettings = Settings(this)
        val isNightActive = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (appSettings.appTheme == Settings.AppTheme.EXTREME_DARK ||
            (appSettings.useExtremeDarkMode && isNightActive)) {
            theme.applyStyle(R.style.ThemeOverlay_ExtremeDark, true)
        } else if (appSettings.useGradientBackground) {
            theme.applyStyle(R.style.ThemeOverlay_GradientBackground, true)
        }
        requestedOrientation = appSettings.screenOrientation.androidOrientation

        setContentView(R.layout.activity_settings)

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.settings_nav_host) as NavHostFragment
        val navController = navHostFragment.navController

        // Set the start destination to settingsFragment instead of homeFragment
        val navGraph = navController.navInflater.inflate(R.navigation.nav_graph)
        navGraph.startDestination = R.id.settingsFragment
        navController.graph = navGraph

        // Open a specific sub-screen when requested (e.g. from the onboarding wizard),
        // otherwise restore the sub-screen after recreate() (e.g. theme change from DarkModeFragment)
        val requestedDestination = intent?.getIntExtra(EXTRA_DESTINATION, 0) ?: 0
        val restoredDestination = if (requestedDestination != 0) requestedDestination
            else savedInstanceState?.getInt(KEY_CURRENT_DESTINATION, 0) ?: 0
        if (restoredDestination != 0 && restoredDestination != R.id.settingsFragment) {
            try {
                navController.navigate(restoredDestination)
            } catch (_: Exception) {}
        }

        val root = findViewById<View>(R.id.settings_nav_host)
        // Never the projection: this window has a search box and its own decor, so its content area
        // is not the canvas the video is drawn into.
        SystemUI.apply(window, root, appSettings.fullscreenMode, notesCanvas = false)
    }

    override fun onStart() {
        super.onStart()
        isVisible = true
        AapService.instance?.onSettingsScreenVisibility(visible = true)
    }

    override fun onStop() {
        super.onStop()
        isVisible = false
        AapService.instance?.onSettingsScreenVisibility(visible = false)
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        AapService.instance?.onSettingsScreenChanged(inForeground = true)
    }

    override fun onPause() {
        super.onPause()
        isForeground = false
        // After the flag, never before: launchAapProjectionActivity() reads it and would otherwise
        // swallow the raise this asks for.
        AapService.instance?.onSettingsScreenChanged(inForeground = false)
        if (App.provide(this).commManager.isConnected) {
            // A broadcast, not a service start: AapService receives this one in a receiver.
            sendBroadcast(Intent(AapService.ACTION_RAISE_PROJECTION).apply { setPackage(packageName) })
        }
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        currentFocus?.let { v ->
            imm?.hideSoftInputFromWindow(v.windowToken, 0)
        } ?: window.peekDecorView()?.let { v ->
            imm?.hideSoftInputFromWindow(v.windowToken, 0)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.settings_nav_host) as? NavHostFragment
        val currentDest = navHostFragment?.navController?.currentDestination?.id ?: 0
        outState.putInt(KEY_CURRENT_DESTINATION, currentDest)
    }

    companion object {
        /**
         * Whether this screen is in front. Read by the Native AA wake poke, which would otherwise
         * wake the phone and let it take the screen while the user is still changing settings.
         * A static flag rather than a message to the service: nothing else needs to know.
         */
        @Volatile var isForeground = false

        /**
         * Whether this screen is on show, which a translucent activity such as the USB attach
         * trampoline does not end. Holds every automatic USB and Self Mode start.
         */
        @Volatile var isVisible = false

        private const val KEY_CURRENT_DESTINATION = "current_nav_destination"
        // Optional destination id to open directly on launch (e.g. R.id.darkModeFragment).
        const val EXTRA_DESTINATION = "extra_destination"

        /**
         * Text to put in the settings search box on open, so a caller can land the user on one
         * row rather than one screen.
         *
         * Search is used rather than a scroll because it is the only thing that overrides the
         * Basic/Advanced filter: some rows worth pointing at are Advanced-only, and a Basic-mode
         * user would otherwise arrive at a list that does not contain the row they were sent for.
         */
        const val EXTRA_SEARCH_QUERY = "extra_search_query"
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val appSettings = Settings(this)
            val root = findViewById<View>(R.id.settings_nav_host)
            SystemUI.apply(window, root, appSettings.fullscreenMode, notesCanvas = false)
        }
    }
}
