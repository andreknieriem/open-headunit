package com.andrerinas.openheadunit.main

import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.core.content.ContextCompat
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

    // A system setup activity is part of this visit. onPause/onStop describe window visibility,
    // not completion: releasing the connection hold there can raise AA over a permission prompt.
    private var externalSetupPending = false
    private var externalSetupGeneration = 0L
    private val visibilityOwner = Any()
    // The external activity does not deliver our onUserLeaveHint when Home is pressed.
    // Listen for system Home/task-switcher/screen-off while this visit exists, including while stopped.
    // CLOSE_SYSTEM_DIALOGS is received only; Android 12 forbids ordinary apps sending it.
    private val setupExitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            onExternalSetupExit(intent?.action, intent?.getStringExtra("reason"))
        }
    }

    internal fun onExternalSetupExit(action: String?, reason: String?) {
        if (action != Intent.ACTION_SCREEN_OFF &&
            !(action == Intent.ACTION_CLOSE_SYSTEM_DIALOGS && reason in setOf("homekey", "recentapps"))) return
        if (externalSetupOwner?.get() !== this || currentVisibilityOwner !== visibilityOwner) return
        releaseExternalSetup()
    }

    private fun releaseExternalSetup() {
        externalSetupPending = false
        externalSetupOwner = null
        isForeground = false
        isVisible = false
        AapService.instance?.onSettingsScreenChanged(inForeground = false)
        AapService.instance?.onSettingsScreenVisibility(visible = false)
    }

    /** Keep both wireless and USB auto-connect held while an explicitly opened setup UI runs. */
    fun openSetup(block: () -> Boolean): Boolean {
        val previous = externalSetupPending
        val previousGeneration = externalSetupGeneration
        val previousOwner = externalSetupOwner
        externalSetupGeneration = ++nextSetupGeneration
        externalSetupPending = true
        externalSetupOwner = java.lang.ref.WeakReference(this)
        return try {
            block().also { if (!it) {
                externalSetupPending = previous
                externalSetupGeneration = previousGeneration
                externalSetupOwner = previousOwner
            } }
        } catch (error: Throwable) {
            externalSetupPending = previous
            externalSetupGeneration = previousGeneration
            externalSetupOwner = previousOwner
            throw error
        }
    }

    /** Permission replies may arrive without any activity pause/resume or window focus change. */
    fun setupRequestCompletion(): () -> Unit {
        val generation = externalSetupGeneration
        return {
            // A configuration replacement owns the same request. Resolve its current instance
            // here, while the process-wide generation excludes an unrelated later setup visit.
            externalSetupOwner?.get()?.let {
                if (it.externalSetupGeneration == generation) it.externalSetupPending = false
            }
        }
    }

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
        ContextCompat.registerReceiver(this, setupExitReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
            addAction(Intent.ACTION_SCREEN_OFF)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        externalSetupPending = savedInstanceState?.getBoolean(KEY_EXTERNAL_SETUP, false) ?: false
        externalSetupGeneration = savedInstanceState?.getLong(KEY_EXTERNAL_SETUP_GENERATION, 0L) ?: 0L
        nextSetupGeneration = maxOf(nextSetupGeneration, externalSetupGeneration)
        if (externalSetupPending) externalSetupOwner = java.lang.ref.WeakReference(this)

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
        currentVisibilityOwner = visibilityOwner
        isVisible = true
        AapService.instance?.onSettingsScreenVisibility(visible = true)
    }

    override fun onStop() {
        super.onStop()
        if ((!externalSetupPending || isFinishing) && currentVisibilityOwner === visibilityOwner) {
            isVisible = false
            AapService.instance?.onSettingsScreenVisibility(visible = false)
        }
    }

    override fun onResume() {
        super.onResume()
        externalSetupPending = false
        if (externalSetupOwner?.get() === this) externalSetupOwner = null
        isForeground = true
        AapService.instance?.onSettingsScreenChanged(inForeground = true)
    }

    override fun onPause() {
        super.onPause()
        if ((!externalSetupPending || isFinishing) && currentVisibilityOwner === visibilityOwner) {
            externalSetupPending = false
            isForeground = false
            // After the flag: launchAapProjectionActivity() reads it before raising projection.
            AapService.instance?.onSettingsScreenChanged(inForeground = false)
            if (App.provide(this).commManager.isConnected) {
                sendBroadcast(Intent(AapService.ACTION_RAISE_PROJECTION).apply { setPackage(packageName) })
            }
        }
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        currentFocus?.let { v ->
            imm?.hideSoftInputFromWindow(v.windowToken, 0)
        } ?: window.peekDecorView()?.let { v ->
            imm?.hideSoftInputFromWindow(v.windowToken, 0)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(setupExitReceiver)
        // A task removed during external setup must not leave a process-wide pause behind.
        // A configuration replacement inherits the hold; an older instance cannot release a
        // newer SettingsActivity's hold when more than one setup entry point was opened.
        if (!isChangingConfigurations && currentVisibilityOwner === visibilityOwner) {
            currentVisibilityOwner = null
            if (isForeground) {
                isForeground = false
                AapService.instance?.onSettingsScreenChanged(inForeground = false)
            }
            if (isVisible) {
                isVisible = false
                AapService.instance?.onSettingsScreenVisibility(visible = false)
            }
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_EXTERNAL_SETUP, externalSetupPending)
        outState.putLong(KEY_EXTERNAL_SETUP_GENERATION, externalSetupGeneration)
        super.onSaveInstanceState(outState)
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.settings_nav_host) as? NavHostFragment
        val currentDest = navHostFragment?.navController?.currentDestination?.id ?: 0
        outState.putInt(KEY_CURRENT_DESTINATION, currentDest)
    }

    companion object {
        /**
         * Whether settings owns the user's interaction, including its explicit external setup UI.
         * Read by the Native AA wake poke, which would otherwise
         * wake the phone and let it take the screen while the user is still changing settings.
         * A static flag rather than a message to the service: nothing else needs to know.
         */
        @Volatile var isForeground = false

        /**
         * Whether settings is on show or temporarily covered by its external setup UI. A
         * translucent USB attach trampoline also keeps this hold on USB and Self Mode starts.
         */
        @Volatile var isVisible = false

        private var currentVisibilityOwner: Any? = null
        private var nextSetupGeneration = 0L
        private var externalSetupOwner: java.lang.ref.WeakReference<SettingsActivity>? = null

        /** Returning to the home screen explicitly ends an abandoned external setup visit. */
        fun onMainScreenResumed() {
            val owner = externalSetupOwner?.get() ?: return
            if (owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
            // A permission reply may have cleared pending after onStop already kept the flags.
            // Ownership and lifecycle, not that request bit, determine whether to release them.
            if (currentVisibilityOwner !== owner.visibilityOwner) return
            owner.releaseExternalSetup()
        }
        private const val KEY_EXTERNAL_SETUP = "external_setup_pending"
        private const val KEY_EXTERNAL_SETUP_GENERATION = "external_setup_generation"
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
            // A dialog can return focus just before its button opens another activity. Focus
            // alone must not release the setup hold; resume or the permission result ends it.
            val appSettings = Settings(this)
            val root = findViewById<View>(R.id.settings_nav_host)
            SystemUI.apply(window, root, appSettings.fullscreenMode, notesCanvas = false)
        }
    }
}
