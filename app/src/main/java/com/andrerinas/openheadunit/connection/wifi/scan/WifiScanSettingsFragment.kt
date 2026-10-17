package com.andrerinas.openheadunit.connection.wifi.scan

import android.content.res.ColorStateList
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import androidx.core.view.isVisible
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.AppLog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.launch

/** Permission and ADB setup stay on one screen; returning from Settings resumes the next step. */
class WifiScanSettingsFragment : Fragment() {
    private lateinit var status: TextView
    private lateinit var permission: MaterialButton
    private lateinit var retry: MaterialButton
    private lateinit var toggle: SwitchMaterial
    private lateinit var progress: TextView
    private lateinit var fyt: MaterialButton
    private lateinit var restoreAdb: MaterialButton
    private lateinit var permissionHelp: TextView
    private lateinit var details: TextView
    private lateinit var fytDetails: TextView
    private lateinit var manager: MaterialButton
    private lateinit var developerSettings: MaterialButton
    private lateinit var help: MaterialButton
    private lateinit var wifiSettings: MaterialButton
    private lateinit var working: LinearProgressIndicator
    private lateinit var systemRestore: MaterialButton
    private var busy = false
    private var rendering = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        if (Build.VERSION.SDK_INT < 26) return View(context)
        WifiScanControl.initialize(context)
        val surface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, javaClass.simpleName)
        val themeText = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, javaClass.simpleName)
        fun legibleText(background: Int, preferred: Int): Int {
            if (ColorUtils.calculateContrast(preferred, background) >= 4.5) return preferred
            return if (ColorUtils.calculateContrast(Color.WHITE, background) >
                ColorUtils.calculateContrast(Color.BLACK, background)) Color.WHITE else Color.BLACK
        }
        val foreground = legibleText(surface, themeText)
        val primary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, javaClass.simpleName)
        val disabledSurface = ColorUtils.blendARGB(surface, foreground, 0.08f)
        val disabledText = legibleText(disabledSurface, ColorUtils.blendARGB(surface, foreground, 0.65f))
        fun colors(enabled: Int, disabled: Int) = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(disabled, enabled))
        // This destination owns an opaque surface. Match every foreground to it instead of
        // mixing the activity's optional wallpaper with default widget/card backgrounds.
        // Limit reading width on tablets while allowing narrow displays to use all available
        // space. Keep the content centered and vertically scrollable at large font sizes.
        val layout = object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = minOf(View.MeasureSpec.getSize(widthMeasureSpec), dp(720))
                super.onMeasure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), heightMeasureSpec)
            }
        }.apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER_HORIZONTAL)

            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        fun text(res: Int, parent: LinearLayout = layout) = TextView(context).apply {
            setText(res)
            setTextColor(foreground)
            setLineSpacing(dp(3).toFloat(), 1.08f)
            setPadding(0, dp(8), 0, dp(8))
            parent.addView(this)
        }
        fun section(res: Int) = text(res).apply {
            textSize = 20f
            setPadding(0, dp(24), 0, dp(8))
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        }
        text(R.string.wifi_scan_title).textSize = 24f
        text(R.string.wifi_scan_explanation)
        toggle = SwitchMaterial(context).apply {
            setText(R.string.wifi_scan_enable)
            setTextColor(colors(foreground, legibleText(surface, disabledText)))
            isChecked = WifiScanControl.enabled()
            setOnCheckedChangeListener { _, value ->
                if (!rendering) {
                    WifiScanControl.setEnabled(value)
                    // Show the reason and approval explanation before requesting system access.
                    // The explicit approval button owns permission requests.
                    render()
                }
            }
        }
        layout.addView(toggle)
        section(R.string.wifi_scan_section_common)
        text(R.string.wifi_scan_common_help)
        // Keep prerequisites visible even when enabled: a saved preference does not prove
        // that the Shizuku service is running or that this app has access.
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        layout.addView(MaterialCardView(context).apply {
            radius = dp(12).toFloat()
            setCardBackgroundColor(surface)
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = ColorUtils.blendARGB(surface, foreground, 0.2f)
            addView(panel)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(12) }
        })
        status = text(R.string.wifi_scan_off, panel).apply {
            textSize = 18f
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        working = LinearProgressIndicator(context).apply { isIndeterminate = true; panel.addView(this) }
        details = text(R.string.wifi_scan_prepare, panel)
        fun button(res: Int, parent: LinearLayout = layout, outlined: Boolean = false, onClick: () -> Unit) =
            MaterialButton(context, null, if (outlined) com.google.android.material.R.attr.materialButtonOutlinedStyle
                else com.google.android.material.R.attr.materialButtonStyle).apply {
                setText(res); setOnClickListener { onClick() }
                // Disabled actions remain readable, but lose the filled accent treatment.
                // Keep MaterialButton's drawable/ripple and supply state-aware colors only.
                backgroundTintList = colors(if (outlined) surface else primary, disabledSurface)
                setTextColor(colors(if (outlined) foreground else legibleText(primary, foreground), disabledText))
                if (outlined) {
                    strokeColor = colors(ColorUtils.blendARGB(surface, foreground, 0.55f),
                        ColorUtils.blendARGB(surface, foreground, 0.2f))
                }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
                minHeight = dp(48)
                parent.addView(this)
            }
        manager = button(R.string.wifi_scan_open_shizuku, panel, outlined = true) { openShizuku() }
        permission = button(R.string.wifi_scan_authorize, panel) {
            if (WifiScanControl.permissionDeniedPermanently()) openShizuku()
            else requestShizukuPermission()
        }
        retry = button(R.string.wifi_scan_retry, panel, outlined = true) { WifiScanControl.retry() }
        systemRestore = button(R.string.wifi_scan_restore_system, panel) {
            open(Intent(WifiManager.ACTION_REQUEST_SCAN_ALWAYS_AVAILABLE))
        }
        permissionHelp = text(R.string.wifi_scan_permission_explanation, panel)
        // Keep the switch, prerequisites and actions ahead of longer version-specific help.
        if (Build.VERSION.SDK_INT < 33) {
            text(R.string.wifi_scan_legacy)
            if (Build.VERSION.SDK_INT < 30) text(R.string.wifi_scan_legacy_limit)
        }
        // Installation and app approval are shared. Device-specific startup routes stay in
        // their own sections so the user does not mistake FYT startup for a universal step.
        section(R.string.wifi_scan_section_fyt)
        text(R.string.wifi_scan_fyt_help)
        fytDetails = text(R.string.wifi_scan_detail_fyt_unavailable)
        fyt = button(R.string.wifi_scan_fyt_start, outlined = true) {
            MaterialAlertDialogBuilder(context).setTitle(R.string.wifi_scan_fyt_start)
                .setMessage(R.string.wifi_scan_fyt_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.wifi_scan_continue) { _, _ -> startFyt() }.show()
        }
        progress = text(R.string.wifi_scan_fyt_authorize)
        progress.visibility = View.GONE
        restoreAdb = button(R.string.wifi_scan_fyt_restore) {
            if (!busy) viewLifecycleOwner.lifecycleScope.launch {
                busy = true
                render()
                try {
                    val restored = WifiScanControl.closeOwnedAdb()
                    progress.visibility = View.VISIBLE
                    progress.setText(when (restored) {
                        ScanControlAdbClose.Result.CLOSED -> R.string.wifi_scan_fyt_restored
                        ScanControlAdbClose.Result.SCAN_RECOVERY -> R.string.wifi_scan_fyt_restore_scans
                        ScanControlAdbClose.Result.ADB_RECOVERY -> R.string.wifi_scan_fyt_recovery
                        ScanControlAdbClose.Result.BUSY -> R.string.wifi_scan_detail_fyt_busy
                    })
                } finally { busy = false; WifiScanControl.refresh(); render() }
            }
        }
        text(R.string.wifi_scan_fyt_close_help)
        section(R.string.wifi_scan_section_other)
        text(R.string.wifi_scan_other_help)
        text(if (Build.VERSION.SDK_INT >= 30) R.string.wifi_scan_adb_wireless else R.string.wifi_scan_adb_usb)
        help = button(R.string.wifi_scan_help, outlined = true) { showHelp() }
        developerSettings = button(R.string.wifi_scan_developer_settings, outlined = true) {
            open(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }
        // Only Android 8–12 Hotspot needs regular Wi-Fi turned off; newer versions never need this.
        wifiSettings = button(R.string.wifi_scan_wifi_settings, outlined = true) { open(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        button(R.string.wifi_scan_done, outlined = true) { findNavController().navigateUp() }
        return ScrollView(context).apply { setBackgroundColor(surface); addView(layout) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { WifiScanControl.state.collect { render() } }
                // Shizuku can be installed/started in another window without recreating this view.
                launch { while (isActive) {
                    // Android may apply the confirmation after onResume has already run.
                    WifiScanControl.confirmSystemRestore()
                    render()
                    delay(1_000)
                } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) { WifiScanControl.confirmSystemRestore(); WifiScanControl.refresh(); render() }
    }
    private fun installed() = WifiScanControl.providerInstalled()
    private fun inSetup(block: () -> Boolean): Boolean {
        val settings = activity as? com.andrerinas.openheadunit.main.SettingsActivity
        return if (settings != null) settings.openSetup(block) else block()
    }

    private fun requestShizukuPermission(): Boolean = inSetup {
        val owner = activity as? com.andrerinas.openheadunit.main.SettingsActivity
        WifiScanControl.requestPermission(owner?.setupRequestCompletion() ?: {})
    }
    private fun open(intent: Intent): Boolean = runCatching {
        inSetup { startActivity(intent); true }
    }.getOrDefault(false)
    private fun openShizuku() {
        val intent = requireContext().packageManager.getLaunchIntentForPackage(FytShizukuStarter.SHIZUKU)
        if (intent != null) open(intent)
        else open(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
    }
    private fun render() {
        if (!isAdded || view == null || !::status.isInitialized) return
        rendering = true
        toggle.isChecked = WifiScanControl.enabled()
        rendering = false
        val settings = com.andrerinas.openheadunit.App.provide(requireContext()).settings
        val summary = WifiScanControl.summaryRes(
            settings.wifiConnectionMode == com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode.NATIVE,
            settings.nativeApStrategy == com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeStrategy.HOTSPOT)
        val statusText = getString(summary)
        if (status.text.toString() != statusText) status.text = statusText
        val unsupported = summary == R.string.wifi_scan_summary_native ||
            summary == R.string.wifi_scan_summary_unsupported
        val pending = FytShizukuStarter.needsRecovery(requireContext())
        if (pending && !busy) {
            progress.visibility = View.VISIBLE
            progress.setText(R.string.wifi_scan_fyt_recovery)
        }
        val ownedAdb = FytShizukuStarter.canCloseAdb(requireContext())
        val running = WifiScanControl.hasBinder()
        val allowed = WifiScanControl.granted()
        val legacy = WifiScanControl.legacyBinder()
        val state = WifiScanControl.state.value
        val changing = busy || state == WifiScanControl.State.WORKING
        working.isVisible = changing
        toggle.isEnabled = !busy
        val providerInstalled = installed()
        val fytSupported = Build.VERSION.SDK_INT < 30 && FytShizukuStarter.available(requireContext())
        val actions = ScanControlSetup.actions(providerInstalled, running, allowed, legacy,
            fytSupported, pending, busy, state, scanSupported = !unsupported)
        val usbDebugging = Settings.Global.getInt(requireContext().contentResolver, Settings.Global.ADB_ENABLED, 0) != 0
        // Without a live Binder, approval cannot be queried reliably. Do not describe an
        // unavailable permission check as a denial (approval may survive a service restart).
        val detailText = listOf(
            getString(if (providerInstalled) R.string.wifi_scan_detail_installed else R.string.wifi_scan_detail_not_installed),
            getString(if (running) R.string.wifi_scan_detail_running else R.string.wifi_scan_detail_stopped),
            getString(when {
                !running -> R.string.wifi_scan_detail_permission_unknown
                legacy -> R.string.wifi_scan_summary_update
                allowed -> R.string.wifi_scan_detail_allowed
                else -> R.string.wifi_scan_detail_not_allowed
            }),
            getString(if (usbDebugging) R.string.wifi_scan_detail_debugging_on else R.string.wifi_scan_detail_debugging_off)
        ).joinToString("\n")
        if (details.text.toString() != detailText) details.text = detailText
        val fytText = listOf(getString(actions.fytDetail),
            getString(if (ownedAdb) R.string.wifi_scan_fyt_adb_owned else R.string.wifi_scan_fyt_adb_unowned)).joinToString("\n")
        if (fytDetails.text.toString() != fytText) fytDetails.text = fytText
        permissionHelp.isVisible = true
        // Stable button positions make returning from external setup predictable. Disable
        // actions whose prerequisites are missing, while keeping setup help accessible.
        help.isEnabled = !changing
        developerSettings.isEnabled = !changing
        manager.setText(actions.managerLabel)
        manager.isEnabled = actions.managerEnabled
        permission.isEnabled = actions.permissionEnabled
        retry.isEnabled = actions.retryEnabled
        wifiSettings.isEnabled = !changing
        restoreAdb.isEnabled = !changing && ownedAdb
        systemRestore.isEnabled = !changing && state == WifiScanControl.State.RECOVERY &&
            WifiScanControl.canRestoreWithoutShizuku()
        fyt.isEnabled = actions.fytEnabled
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun showHelp() {
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.wifi_scan_help)
            .setMessage(if (Build.VERSION.SDK_INT >= 30) R.string.wifi_scan_adb_wireless else R.string.wifi_scan_adb_usb)
            .setPositiveButton(R.string.wifi_scan_open_shizuku) { _, _ -> openShizuku() }
            .setNeutralButton(R.string.wifi_scan_developer_settings) { _, _ ->
                val target = if (Build.VERSION.SDK_INT >= 30) Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")
                    else Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                if (!open(target)) open(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun startFyt() {
        if (busy) return
        busy = true
        render()
        progress.visibility = View.VISIBLE
        progress.setText(R.string.wifi_scan_fyt_authorize)
        inSetup {
            // The local ADB exchange can open Android's RSA approval window too. Keep the
            // same hold as a Shizuku permission request until startup completes or is cancelled.
            val owner = activity as? com.andrerinas.openheadunit.main.SettingsActivity
            val complete = owner?.setupRequestCompletion() ?: {}
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val context = requireContext().applicationContext
                    FytShizukuStarter.start(context)
                    progress.setText(R.string.wifi_scan_fyt_finished)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) {
                    AppLog.e("FYT Shizuku: setup failed; running=${WifiScanControl.hasBinder()} " +
                        "pendingAdbRecovery=${FytShizukuStarter.needsRecovery(requireContext())}", e)
                    val failureText = if (e is FytSetupStep.Failure) {
                        val stage = when (e.stage) {
                            FytSetupStep.Stage.PREPARE -> R.string.wifi_scan_fyt_stage_prepare
                            FytSetupStep.Stage.VENDOR_SERVICE -> R.string.wifi_scan_fyt_stage_vendor
                            FytSetupStep.Stage.OPEN_ADB -> R.string.wifi_scan_fyt_stage_open
                            FytSetupStep.Stage.CONNECT_ADB -> R.string.wifi_scan_fyt_stage_connect
                            FytSetupStep.Stage.START_SHIZUKU -> R.string.wifi_scan_fyt_stage_start
                            FytSetupStep.Stage.WAIT_SHIZUKU -> R.string.wifi_scan_fyt_stage_wait
                            FytSetupStep.Stage.RESTORE_ADB -> R.string.wifi_scan_fyt_stage_restore
                        }
                        getString(R.string.wifi_scan_fyt_failed_at, getString(stage))
                    } else getString(R.string.wifi_scan_fyt_failed)
                    progress.text = failureText
                    // An interrupted close must finish before another startup. Otherwise the
                    // retained listener can be reused on retry, or closed with the button.
                    if (isAdded && !FytShizukuStarter.needsRecovery(requireContext())) {
                        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.wifi_scan_fyt_start)
                            .setMessage(failureText + "\n\n" + getString(R.string.wifi_scan_fyt_manual_setup))
                            .setPositiveButton(R.string.wifi_scan_developer_settings) { _, _ ->
                                open(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                            }.setNegativeButton(android.R.string.cancel, null).show()
                    }
                }
                finally { complete(); busy = false; WifiScanControl.refresh(); if (isAdded) render() }
            }
            true
        }
    }
}
