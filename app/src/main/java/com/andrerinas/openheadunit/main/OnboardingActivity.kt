package com.andrerinas.openheadunit.main

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ViewFlipper
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.app.BaseActivity
import com.andrerinas.openheadunit.decoder.VideoDecoder
import com.andrerinas.openheadunit.utils.AppPermissions
import com.andrerinas.openheadunit.utils.AppThemeManager
import com.andrerinas.openheadunit.utils.LocaleHelper
import com.andrerinas.openheadunit.utils.PermissionRowBinder
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.SystemOptimizer
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import kotlin.math.sqrt

/**
 * Intelligent, device-aware first-run wizard. Replaces the old
 * SafetyDisclaimerDialog + SetupWizard dialog chain with a single guided flow.
 *
 * Shown once to everyone (including upgraders) when the stored onboardingVersion
 * is older than CURRENT_ONBOARDING_VERSION, and re-launchable from Settings.
 *
 * Steps: Welcome(+language) / Safety(mandatory) / Connection / Display scan /
 * Appearance / Automation / Location / Ready.
 *
 * Every step reuses the real settings and their strings, and deep-links into the
 * full settings sub-screens for advanced configuration.
 */
class OnboardingActivity : BaseActivity() {

    private val settings by lazy { App.provide(this).settings }
    private lateinit var flipper: ViewFlipper
    private lateinit var backBtn: MaterialButton
    private lateinit var nextBtn: MaterialButton
    private lateinit var skipBtn: MaterialButton
    private lateinit var stepper: LinearLayout

    private var step = 0
    private var isBinding = false

    private var selectedSize = SystemOptimizer.DisplaySizePreset.STANDARD_9_10
    private var selectedPortrait = false
    private var dpiPicker: com.andrerinas.openheadunit.view.DpiPickerView? = null

    // Permissions step (registered here, before the activity is RESUMED).
    private var permissionBinder: PermissionRowBinder? = null
    private val permNormalLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionBinder?.rebind() }
    private val permSpecialLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { permissionBinder?.rebind() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Match the app theme overlays used by MainActivity/SettingsActivity so the wizard
        // honours Extreme Dark (pure black) and the gradient background.
        val isNightActive = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (settings.appTheme == Settings.AppTheme.EXTREME_DARK ||
            (settings.useExtremeDarkMode && isNightActive)) {
            theme.applyStyle(R.style.ThemeOverlay_ExtremeDark, true)
        } else if (settings.useGradientBackground) {
            theme.applyStyle(R.style.ThemeOverlay_GradientBackground, true)
        }

        setContentView(R.layout.activity_onboarding)

        step = savedInstanceState?.getInt(KEY_STEP, 0) ?: 0

        flipper = findViewById(R.id.onb_flipper)
        backBtn = findViewById(R.id.onb_back)
        nextBtn = findViewById(R.id.onb_next)
        skipBtn = findViewById(R.id.onb_skip)
        stepper = findViewById(R.id.onb_stepper)

        selectedPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        selectedSize = estimateSizePreset()

        buildStepperDots()
        bindSteps()
        bindPermissionsStep()

        // Ready step: optional-extras card. Each row finishes the wizard and jumps straight into
        // that Settings sub-screen (things the wizard does not already walk you through).
        findViewById<View>(R.id.onb_extra_loading).setOnClickListener {
            finishOnboardingInto(R.id.loadingScreenFragment)
        }
        findViewById<View>(R.id.onb_extra_keymap).setOnClickListener {
            finishOnboardingInto(R.id.keymapFragment)
        }
        findViewById<View>(R.id.onb_extra_mic).setOnClickListener {
            finishOnboardingInto(R.id.micSettingsFragment)
        }

        backBtn.setOnClickListener { if (step > 0) goBack() }
        nextBtn.setOnClickListener { onNext() }
        skipBtn.setOnClickListener { onDoItLater() }

        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_STEP, step)
    }

    override fun onResume() {
        super.onResume()
        // Reflect any changes made in deep-linked settings screens (theme, automation) or after
        // returning from a special-permission settings screen (overlay / modify system settings).
        updateThemeButtonText()
        updateNightButtonText()
        permissionBinder?.rebind()
        if (step == STEP_DPI) dpiPicker?.startDemo()
    }

    override fun onPause() {
        super.onPause()
        dpiPicker?.stopDemo()
    }

    /**
     * A wizard step that does not apply to the chosen setup and is skipped entirely. The GPS source
     * choice (this device vs the connected phone) makes no sense in Self Mode, where there is no
     * separate phone, so it is hidden there.
     */
    private fun isStepHidden(s: Int): Boolean =
        s == STEP_GPS && !settings.showsExternalGps()

    /** The steps actually shown, in order, after removing the ones that do not apply. */
    private fun visibleSteps(): List<Int> = (0 until STEP_COUNT).filter { !isStepHidden(it) }

    /** Move forward to the next visible step, or finish on the last one. */
    private fun goForward() {
        var s = step + 1
        while (s < STEP_COUNT && isStepHidden(s)) s++
        if (s >= STEP_COUNT) { finishOnboarding(); return }
        step = s
        render()
    }

    /** Move back to the previous visible step. */
    private fun goBack() {
        var s = step - 1
        while (s > 0 && isStepHidden(s)) s--
        step = s.coerceAtLeast(0)
        render()
    }

    private fun buildStepperDots() {
        stepper.removeAllViews()
        repeat(visibleSteps().size) {
            val dot = View(this)
            val h = (5 * resources.displayMetrics.density).toInt()
            val lp = LinearLayout.LayoutParams(h, h)
            lp.marginEnd = (6 * resources.displayMetrics.density).toInt()
            dot.layoutParams = lp
            stepper.addView(dot)
        }
    }

    private fun updateStepperDots() {
        val steps = visibleSteps()
        // Rebuild if the visible-step count changed (e.g. Self Mode just hid the GPS step).
        if (stepper.childCount != steps.size) buildStepperDots()
        val density = resources.displayMetrics.density
        val active = resolveAttrColor(com.google.android.material.R.attr.colorPrimary)
        val inactive = 0x33808080
        val currentPos = steps.indexOf(step).coerceAtLeast(0)
        for (i in steps.indices) {
            val dot = stepper.getChildAt(i) ?: continue
            val lp = dot.layoutParams as LinearLayout.LayoutParams
            lp.width = ((if (i == currentPos) 26 else 8) * density).toInt()
            dot.layoutParams = lp
            val bg = android.graphics.drawable.GradientDrawable()
            bg.cornerRadius = 5 * density
            bg.setColor(if (i <= currentPos) active else inactive)
            dot.background = bg
        }
    }

    private fun bindSteps() {
        // --- Welcome: language ---
        findViewById<MaterialButton>(R.id.onb_language_button).apply {
            text = currentLanguageLabel()
            setOnClickListener { showLanguageDialog() }
        }

        // --- Safety ---
        findViewById<TextView>(R.id.onb_safety_text).text =
            Html.fromHtml(getString(R.string.disclaimer_text))
        findViewById<MaterialCheckBox>(R.id.onb_safety_accept).apply {
            isChecked = settings.hasAcceptedDisclaimer
            setOnCheckedChangeListener { _, checked ->
                // Persist immediately so "Do it later" works once the terms are accepted.
                settings.hasAcceptedDisclaimer = checked
                if (step == STEP_SAFETY) nextBtn.isEnabled = checked
            }
        }

        // --- Connection: multi-select of USB / WiFi / Self Mode ---
        val connGroup = findViewById<MaterialButtonToggleGroup>(R.id.onb_conn_group)
        isBinding = true
        val modes = settings.connectionModes
        if (Settings.ConnectionMode.USB in modes) connGroup.check(R.id.onb_conn_usb)
        if (Settings.ConnectionMode.WIFI in modes) connGroup.check(R.id.onb_conn_wifi)
        if (Settings.ConnectionMode.SELF in modes) connGroup.check(R.id.onb_conn_self)
        isBinding = false
        updateConnectionDetail()
        connGroup.addOnButtonCheckedListener { group, _, _ ->
            if (isBinding) return@addOnButtonCheckedListener
            val checked = group.checkedButtonIds
            val selected = buildSet {
                if (R.id.onb_conn_usb in checked) add(Settings.ConnectionMode.USB)
                if (R.id.onb_conn_wifi in checked) add(Settings.ConnectionMode.WIFI)
                if (R.id.onb_conn_self in checked) add(Settings.ConnectionMode.SELF)
            }
            settings.connectionModes = selected
            updateConnectionDetail()
            // At least one is required; keep Next in sync while on this step.
            if (step == STEP_CONNECTION) nextBtn.isEnabled = selected.isNotEmpty()
        }

        // --- Display: detected panel + size/orientation, pre-selected from detection ---
        findViewById<TextView>(R.id.onb_display_detected).text = detectedDisplayText()
        val sizeGroup = findViewById<MaterialButtonToggleGroup>(R.id.onb_size_group)
        sizeGroup.check(sizeButtonId(selectedSize))
        sizeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            selectedSize = when (id) {
                R.id.onb_size_phone -> SystemOptimizer.DisplaySizePreset.PHONE_4_6
                R.id.onb_size_small -> SystemOptimizer.DisplaySizePreset.SMALL_7_8
                R.id.onb_size_large -> SystemOptimizer.DisplaySizePreset.LARGE_11_PLUS
                else -> SystemOptimizer.DisplaySizePreset.STANDARD_9_10
            }
        }
        val orientGroup = findViewById<MaterialButtonToggleGroup>(R.id.onb_orient_group)
        orientGroup.check(if (selectedPortrait) R.id.onb_orient_port else R.id.onb_orient_land)
        orientGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            selectedPortrait = id == R.id.onb_orient_port
        }

        // Graphical DPI picker (slider + preview + Small/Medium/Large tabs, all synced).
        // Seed from the user's saved DPI when they already set one, so re-running the wizard
        // does not overwrite their choice; the recommended value is shown as a hint instead.
        dpiPicker = findViewById<com.andrerinas.openheadunit.view.DpiPickerView>(R.id.onb_dpi_picker).apply {
            val m = realMetrics()
            setPanelResolution(m.widthPixels, m.heightPixels)
            dpi = settings.dpiPixelDensity.takeIf { it != 0 } ?: recommendedDpi()
        }
        updateDpiOverPanelWarning()

        // --- Appearance: full theme + night-mode pickers; more options live in Settings ---
        updateThemeButtonText()
        findViewById<MaterialButton>(R.id.onb_theme_button).setOnClickListener { showThemeDialog() }
        updateNightButtonText()
        findViewById<MaterialButton>(R.id.onb_night_button).setOnClickListener { showNightModeDialog() }

        // --- Automation: simple toggles inline, priority + advanced via the real screens ---
        findViewById<SwitchMaterial>(R.id.onb_autoconnect_last_switch).apply {
            isChecked = settings.autoConnectLastSession
            setOnCheckedChangeListener { _, v -> settings.autoConnectLastSession = v }
        }
        findViewById<SwitchMaterial>(R.id.onb_autoconnect_single_switch).apply {
            isChecked = settings.autoConnectSingleUsbDevice
            setOnCheckedChangeListener { _, v -> settings.autoConnectSingleUsbDevice = v }
        }
        // USB-only auto-start toggles (also written to device storage, like AutoStartFragment).
        findViewById<SwitchMaterial>(R.id.onb_autostart_usb_switch).apply {
            isChecked = settings.autoStartOnUsb
            setOnCheckedChangeListener { _, v ->
                settings.autoStartOnUsb = v
                Settings.syncAutoStartOnUsbToDeviceStorage(this@OnboardingActivity, v)
            }
        }
        findViewById<SwitchMaterial>(R.id.onb_reopen_switch).apply {
            isChecked = settings.reopenOnReconnection
            setOnCheckedChangeListener { _, v -> settings.reopenOnReconnection = v }
        }
        // WiFi-only auto-start toggle.
        findViewById<SwitchMaterial>(R.id.onb_autostart_wifi_switch).apply {
            isChecked = settings.autoStartOnWifi
            setOnCheckedChangeListener { _, v ->
                settings.autoStartOnWifi = v
                Settings.syncAutoStartOnWifiToDeviceStorage(this@OnboardingActivity, v)
            }
        }
        // --- Location: this device's GPS vs the connected phone's GPS ---
        val gpsGroup = findViewById<MaterialButtonToggleGroup>(R.id.onb_gps_group)
        isBinding = true
        gpsGroup.check(if (settings.useGpsForNavigation) R.id.onb_gps_device else R.id.onb_gps_phone)
        isBinding = false
        gpsGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || isBinding) return@addOnButtonCheckedListener
            settings.useGpsForNavigation = checkedId == R.id.onb_gps_device
        }

        // --- Car brand: the display name sent to Android Auto, and the manufacturer with it ---
        bindVehicleStep()
    }

    /**
     * Binds the car-brand step: a live head-unit screen, an editable field and a horizontal
     * strip of brand chips. The current stored value is shown first and pre-selected, and the
     * field is the source of truth (chips are shortcuts).
     */
    private fun bindVehicleStep() {
        val input = findViewById<TextInputEditText>(R.id.onb_vehicle_input)
        val screen = findViewById<TextView>(R.id.onb_vehicle_screen_name)
        val chips = findViewById<ChipGroup>(R.id.onb_vehicle_chips)

        // Steering wheel side: reuse the existing right-hand-drive setting and mirror the
        // dashboard illustration live (wheel moves to the right when right-hand drive is on).
        val dashboard = findViewById<ImageView>(R.id.onb_vehicle_dashboard)
        val rhdSwitch = findViewById<SwitchMaterial>(R.id.onb_vehicle_rhd_switch)
        fun applyWheelSide(rhd: Boolean) { dashboard.scaleX = if (rhd) -1f else 1f }
        rhdSwitch.isChecked = settings.rightHandDrive
        applyWheelSide(settings.rightHandDrive)
        rhdSwitch.setOnCheckedChangeListener { _, checked ->
            settings.rightHandDrive = checked
            applyWheelSide(checked)
        }

        val current = settings.vehicleDisplayName.trim()
        // Build the brand list with the current value first ("option 1"), then the presets.
        val brands = resources.getStringArray(R.array.vehicle_brands).toMutableList()
        if (current.isNotEmpty()) {
            brands.removeAll { it.equals(current, ignoreCase = true) }
            brands.add(0, current)
        }
        chips.removeAllViews()
        brands.forEach { brand ->
            val chip = layoutInflater.inflate(R.layout.item_vehicle_chip, chips, false) as Chip
            chip.text = brand
            chip.id = ViewCompat.generateViewId()
            chips.addView(chip)
        }

        isBinding = true
        input.setText(current)
        screen.text = current
        for (i in 0 until chips.childCount) {
            val chip = chips.getChildAt(i) as Chip
            if (chip.text.toString().equals(current, ignoreCase = true)) {
                chip.isChecked = true
                break
            }
        }
        isBinding = false

        chips.setOnCheckedStateChangeListener { group, checkedIds ->
            if (isBinding) return@setOnCheckedStateChangeListener
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val chip = group.findViewById<Chip>(id) ?: return@setOnCheckedStateChangeListener
            isBinding = true
            input.setText(chip.text)
            input.setSelection(input.text?.length ?: 0)
            screen.text = chip.text
            isBinding = false
        }

        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val text = s?.toString().orEmpty()
                screen.text = text
                if (isBinding) return
                // Clear a chip selection that no longer matches the typed text.
                val checkedId = chips.checkedChipId
                if (checkedId != View.NO_ID) {
                    val chip = chips.findViewById<Chip>(checkedId)
                    if (chip != null && !chip.text.toString().equals(text.trim(), ignoreCase = true)) {
                        isBinding = true
                        chips.clearCheck()
                        isBinding = false
                    }
                }
            }
        })
    }

    private fun render() {
        flipper.displayedChild = step
        backBtn.visibility = if (step == 0) View.INVISIBLE else View.VISIBLE
        // GONE (not INVISIBLE) so its dedicated row collapses on the final step.
        skipBtn.visibility = if (step == STEP_COUNT - 1) View.GONE else View.VISIBLE
        nextBtn.text = getString(if (step == STEP_COUNT - 1) R.string.onb_ready_finish else R.string.onb_next)
        nextBtn.isEnabled = when (step) {
            STEP_SAFETY -> findViewById<MaterialCheckBox>(R.id.onb_safety_accept).isChecked
            STEP_CONNECTION -> settings.connectionModes.isNotEmpty()
            else -> true
        }
        if (step == STEP_READY) findViewById<TextView>(R.id.onb_ready_summary).text = summaryText()
        if (step == STEP_AUTOMATION) applyAutomationVisibility()
        if (step == STEP_PERMISSIONS) permissionBinder?.rebind()
        if (step == STEP_DPI) {
            findViewById<TextView>(R.id.onb_dpi_recommended).text =
                getString(R.string.onb_dpi_recommended, recommendedDpi())
            dpiPicker?.startDemo()
        } else {
            dpiPicker?.stopDemo()
        }
        updateStepperDots()
    }

    /** Wire the permissions checklist. Rows are rendered from the AppPermissions registry, so a
     * future permission only needs a registry entry, not changes here. */
    private fun bindPermissionsStep() {
        val container = findViewById<LinearLayout>(R.id.onb_perms_container)
        permissionBinder = PermissionRowBinder(this, container, permNormalLauncher, permSpecialLauncher)
        findViewById<MaterialButton>(R.id.onb_perms_enable_all).setOnClickListener {
            permissionBinder?.requestAllMissing()
        }
        permissionBinder?.rebind()
    }

    /** Show only the auto-start toggles that match the chosen connection type. Self Mode
     * connects without USB or WiFi, so both groups are hidden. */
    private fun applyAutomationVisibility() {
        val usbVis = if (settings.showsUsb()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.onb_ac_single_row).visibility = usbVis
        findViewById<View>(R.id.onb_as_usb_row).visibility = usbVis
        findViewById<View>(R.id.onb_reopen_row).visibility = usbVis
        // Auto-start on WiFi is a legacy path that only applies up to Android 12L (API 32).
        findViewById<View>(R.id.onb_as_wifi_row).visibility =
            if (settings.showsWifi() && Build.VERSION.SDK_INT <= 32) View.VISIBLE else View.GONE
    }

    private fun onNext() {
        if (step == STEP_SAFETY) settings.hasAcceptedDisclaimer = true
        // Apply the display recommendation + chosen DPI on Next, so the user does not
        // have to press "Apply recommended settings" for it to take effect.
        if (step == STEP_DISPLAY || step == STEP_DPI) applyDisplaySettings()
        // [BUG_FIX] The chosen name always becomes the display name, but only a real car brand
        // may become the reported manufacturer. Reporting make "Google" alongside model
        // "Desktop Head Unit" is what makes Android Auto list apps installed from outside the
        // Play Store; sending our own app name as a manufacturer breaks that for no benefit,
        // since the manufacturer is only read for the brand logo on Android Auto's exit button.
        if (step == STEP_VEHICLE) {
            val brand = findViewById<TextInputEditText>(R.id.onb_vehicle_input)
                .text?.toString()?.trim().orEmpty()
            if (brand.isNotEmpty()) {
                settings.vehicleDisplayName = brand
                val make = if (brand.lowercase() in NOT_A_MANUFACTURER) DHU_MAKE else brand
                settings.vehicleMake = make
                settings.headUnitMake = make
            }
        }
        if (step == STEP_COUNT - 1) finishOnboarding() else goForward()
    }

    /**
     * "Do it later": close the wizard for now WITHOUT marking it complete, so it appears
     * again on the next app launch. The safety disclaimer is still mandatory, so if it has
     * not been accepted yet we route to that step first instead of leaving.
     */
    private fun onDoItLater() {
        if (!settings.hasAcceptedDisclaimer) {
            step = STEP_SAFETY
            render()
            return
        }
        deferredThisSession = true
        settings.onboardingVersion = CURRENT_ONBOARDING_VERSION
        settings.commit()
        finish()
    }

    override fun onBackPressed() {
        // Back walks the steps; from the first step it behaves like "Do it later".
        if (step > 0) goBack() else onDoItLater()
    }

    private fun finishOnboarding() {
        settings.hasAcceptedDisclaimer = true
        settings.hasCompletedSetupWizard = true
        settings.onboardingVersion = CURRENT_ONBOARDING_VERSION
        settings.commit()
        finish()
    }

    /** Complete the wizard and open a specific Settings sub-screen (e.g. the loading screen setup). */
    private fun finishOnboardingInto(destinationId: Int) {
        settings.hasAcceptedDisclaimer = true
        settings.hasCompletedSetupWizard = true
        settings.onboardingVersion = CURRENT_ONBOARDING_VERSION
        settings.commit()
        startActivity(
            Intent(this, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_DESTINATION, destinationId)
        )
        finish()
    }

    // --- Language ---

    private fun currentLanguageLabel(): String {
        val locale = LocaleHelper.stringToLocale(settings.appLanguage)
        return if (locale == null) getString(R.string.system_default)
        else LocaleHelper.getDisplayName(locale)
    }

    private fun showLanguageDialog() {
        val locales = LocaleHelper.getAvailableLocales(this)
        val labels = ArrayList<String>()
        labels.add(getString(R.string.system_default))
        locales.forEach { labels.add(LocaleHelper.getDisplayName(it)) }
        MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
            .setTitle(R.string.app_language)
            .setItems(labels.toTypedArray()) { _, which ->
                val newLang = if (which == 0) "" else LocaleHelper.localeToString(locales[which - 1])
                if (newLang != settings.appLanguage) {
                    settings.appLanguage = newLang
                    recreate()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- Connection ---

    private fun updateConnectionDetail() {
        val detail = findViewById<TextView>(R.id.onb_conn_detail)
        detail.text = if (settings.connectionModes.isEmpty()) "" else connectionModesLabel()
    }

    /** The chosen connection types as a readable label, e.g. "USB, WiFi". */
    private fun connectionModesLabel(): String {
        val modes = settings.connectionModes
        if (modes.isEmpty()) return getString(R.string.connection_kind_unset)
        val parts = mutableListOf<String>()
        if (Settings.ConnectionMode.USB in modes) parts.add(getString(R.string.connection_kind_usb))
        if (Settings.ConnectionMode.WIFI in modes) parts.add(getString(R.string.connection_kind_wifi))
        if (Settings.ConnectionMode.SELF in modes) parts.add(getString(R.string.self_mode))
        return parts.joinToString(", ")
    }

    // --- Display scan ---

    private fun realMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> display?.getRealMetrics(metrics)
            // getRealMetrics is API 17; on API 16 it does not exist, so fall back to getMetrics.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 ->
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            else ->
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getMetrics(metrics)
        }
        return metrics
    }

    /** Estimate the physical diagonal from real pixels and physical DPI, pick the closest preset. */
    private fun estimateSizePreset(): SystemOptimizer.DisplaySizePreset {
        val m = realMetrics()
        val xdpi = if (m.xdpi > 40f) m.xdpi else m.densityDpi.toFloat()
        val ydpi = if (m.ydpi > 40f) m.ydpi else m.densityDpi.toFloat()
        val wIn = m.widthPixels / xdpi
        val hIn = m.heightPixels / ydpi
        val diagonal = sqrt(wIn * wIn + hIn * hIn)
        return SystemOptimizer.DisplaySizePreset.values().minByOrNull {
            kotlin.math.abs(it.diagonalInch - diagonal)
        } ?: SystemOptimizer.DisplaySizePreset.STANDARD_9_10
    }

    private fun sizeButtonId(preset: SystemOptimizer.DisplaySizePreset): Int = when (preset) {
        SystemOptimizer.DisplaySizePreset.PHONE_4_6 -> R.id.onb_size_phone
        SystemOptimizer.DisplaySizePreset.SMALL_7_8 -> R.id.onb_size_small
        SystemOptimizer.DisplaySizePreset.LARGE_11_PLUS -> R.id.onb_size_large
        else -> R.id.onb_size_standard
    }

    private fun detectedDisplayText(): String {
        val m = realMetrics()
        val hevc = VideoDecoder.isHevcSupported()
        val codec = if (hevc) "H.265" else "H.264"
        return getString(R.string.onb_display_detected, m.widthPixels, m.heightPixels, m.densityDpi, codec)
    }

    private fun recommendedDpi(): Int =
        SystemOptimizer(this).calculateOptimalSettings(selectedSize, selectedPortrait).recommendedDpi

    /** Warn (in red) when the current resolution is higher than the panel: the DPI is computed for
     * the capped resolution the device actually projects (issue #767). Relevant mainly for
     * upgraders who forced a resolution above their panel; fresh installs get a fitted value. */
    private fun updateDpiOverPanelWarning() {
        val warn = findViewById<TextView>(R.id.onb_dpi_over_panel_warning) ?: return
        val m = realMetrics()
        val ceiling = SystemOptimizer.recommendedResolution(m.widthPixels, m.heightPixels)
        val chosen = Settings.Resolution.fromId(settings.resolutionId)
        val over = m.widthPixels > 0 && chosen != null && chosen != Settings.Resolution.AUTO &&
            (chosen.width > ceiling.width || chosen.height > ceiling.height)
        if (over) {
            warn.text = getString(R.string.dpi_resolution_over_panel_warning, chosen!!.resName, ceiling.resName)
            warn.visibility = View.VISIBLE
        } else {
            warn.visibility = View.GONE
        }
    }

    /**
     * True for users who already ran the app before this wizard version (finished the old wizard,
     * or an earlier onboarding). Their display settings are a working config we must not overwrite.
     */
    private fun isUpgrader(): Boolean =
        settings.hasCompletedSetupWizard ||
            settings.onboardingVersion in 1 until CURRENT_ONBOARDING_VERSION

    /** Writes the recommended display settings plus the DPI chosen in the picker. Called when
     * leaving the Display and Android Auto size steps, so no separate Apply tap is needed. */
    private fun applyDisplaySettings() {
        val result = SystemOptimizer(this).calculateOptimalSettings(selectedSize, selectedPortrait)
        val panel = realMetrics()
        // Only auto-apply the video setup on a genuinely fresh install. Upgraders already have a
        // working config (view mode, codec, resolution, orientation), and silently overwriting it
        // has stranded users on a black screen (issue #767), so we leave those untouched and only
        // apply the DPI the user set in the picker.
        if (!isUpgrader()) {
            val previousViewMode = settings.viewMode
            // Set the resolution the panel warrants (the shared panel ceiling; mild downscaling of a
            // slightly-larger standard resolution is allowed, heavy downscaling is not — see panelCeiling).
            settings.resolutionId = SystemOptimizer.recommendedResolution(panel.widthPixels, panel.heightPixels).id
            settings.videoCodec = result.recommendedVideoCodec
            settings.viewMode = result.recommendedViewMode
            settings.screenOrientation = result.suggestedOrientation
            // If we changed the renderer, confirm the picture on the first projection.
            if (settings.viewMode != previousViewMode) settings.pendingRendererConfirm = true
        }
        // Next is a button, so it never takes focus off the DPI field; without this a value the
        // user typed and did not confirm would be dropped in favour of the previous one.
        dpiPicker?.commitPendingInput()
        settings.dpiPixelDensity = dpiPicker?.dpi ?: result.recommendedDpi
        settings.commit()
        updateDpiOverPanelWarning()
    }

    // --- Appearance (reuse the same option arrays as the settings screen) ---

    private fun updateThemeButtonText() {
        val labels = resources.getStringArray(R.array.app_theme)
        val idx = settings.appTheme.value.coerceIn(0, labels.size - 1)
        findViewById<MaterialButton>(R.id.onb_theme_button)?.text = labels[idx]
    }

    private fun updateNightButtonText() {
        val labels = resources.getStringArray(R.array.night_mode)
        val idx = settings.nightMode.value.coerceIn(0, labels.size - 1)
        findViewById<MaterialButton>(R.id.onb_night_button)?.text = labels[idx]
    }

    private fun showThemeDialog() {
        val labels = resources.getStringArray(R.array.app_theme)
        MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
            .setTitle(R.string.onb_appearance_theme_label)
            .setSingleChoiceItems(labels, settings.appTheme.value) { dialog, which ->
                val newTheme = Settings.AppTheme.values().firstOrNull { it.value == which }
                    ?: Settings.AppTheme.AUTOMATIC
                settings.appTheme = newTheme
                updateThemeButtonText()
                dialog.dismiss()
                AppThemeManager.applyStaticTheme(settings)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showNightModeDialog() {
        val labels = resources.getStringArray(R.array.night_mode)
        MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
            .setTitle(R.string.onb_appearance_night_label)
            .setSingleChoiceItems(labels, settings.nightMode.value) { dialog, which ->
                settings.nightMode = Settings.NightMode.fromInt(which) ?: Settings.NightMode.AUTO
                updateNightButtonText()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- Ready ---

    private fun summaryText(): String {
        val conn = connectionModesLabel()
        val res = getString(
            R.string.resolution_recommended_format,
            Settings.Resolution.fromId(settings.resolutionId)?.resName ?: getString(R.string.auto)
        )
        val base = getString(R.string.onb_ready_summary, conn, res, settings.videoCodec)
        val perms = getString(
            R.string.onb_summary_permissions_format,
            AppPermissions.grantedCount(this), AppPermissions.visible().size
        )
        val vehicle = settings.vehicleDisplayName.trim()
        val head = if (vehicle.isNotEmpty())
            getString(R.string.onb_summary_vehicle_format, vehicle) + "\n" + base
        else base
        return head + "\n" + perms
    }

    private fun resolveAttrColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    companion object {
        const val CURRENT_ONBOARDING_VERSION = 2
        // Set when the user chooses "Do it later" so MainActivity does not immediately
        // relaunch the wizard this session. Resets on process restart (next app open).
        @Volatile
        var deferredThisSession = false
        private const val KEY_STEP = "onb_step"
        private const val STEP_COUNT = 11
        private const val STEP_SAFETY = 1
        private const val STEP_CONNECTION = 2
        private const val STEP_DISPLAY = 3
        private const val STEP_DPI = 4
        private const val STEP_AUTOMATION = 6
        private const val STEP_GPS = 7
        private const val STEP_VEHICLE = 8
        private const val STEP_PERMISSIONS = 9
        private const val STEP_READY = 10

        // The manufacturer half of the Desktop Head Unit identity we report by default.
        private const val DHU_MAKE = "Google"
        // Names the car step offers that are not car brands: the head unit make itself and
        // the app's own name. Lowercase, compared against a lowercased input.
        private val NOT_A_MANUFACTURER = setOf("google", "open headunit")
    }
}
