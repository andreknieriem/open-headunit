package com.andrerinas.openheadunit.main

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.ViewFlipper
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.VehicleTypePolicy
import com.andrerinas.openheadunit.app.BaseActivity
import com.andrerinas.openheadunit.app.AppUiEntry
import com.andrerinas.openheadunit.decoder.video.VideoDecoder
import com.andrerinas.openheadunit.decoder.video.preflight.VideoPreflightClipOutcome
import com.andrerinas.openheadunit.decoder.video.preflight.VideoPreflightFailure
import com.andrerinas.openheadunit.decoder.video.preflight.VideoPreflightNote
import com.andrerinas.openheadunit.decoder.video.preflight.VideoPreflightProgress
import com.andrerinas.openheadunit.decoder.video.preflight.VideoPreflightRecommendation
import com.andrerinas.openheadunit.decoder.video.preflight.VideoPreflightRun
import com.andrerinas.openheadunit.decoder.video.preflight.VideoPreflightRunner
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.AppPermissions
import com.andrerinas.openheadunit.utils.AppThemeManager
import com.andrerinas.openheadunit.utils.CarLauncherManager
import com.andrerinas.openheadunit.utils.LocaleHelper
import com.andrerinas.openheadunit.utils.PermissionRowBinder
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.AnnouncedDensityPolicy
import com.andrerinas.openheadunit.utils.OnboardingDisplayPolicy
import com.andrerinas.openheadunit.utils.OnboardingLauncherPolicy
import com.andrerinas.openheadunit.utils.SystemOptimizer
import com.andrerinas.openheadunit.view.VideoPreviewLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.widget.TextViewCompat
import androidx.core.view.MarginLayoutParamsCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Intelligent, device-aware first-run wizard. Replaces the old
 * SafetyDisclaimerDialog + SetupWizard dialog chain with a single guided flow.
 *
 * Shown once to everyone (including upgraders) when the stored onboardingVersion
 * is older than CURRENT_ONBOARDING_VERSION, and re-launchable from Settings.
 *
 * Steps: Welcome(+language) / Safety(mandatory) / Connection / Display scan /
 * Appearance / Audio streams / Automation / Car launcher / Location / Ready.
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
    // The launcher switch asked the system for the home role and the answer is not in yet, so
    // enableCarLauncher is not written until the role is confirmed held (see resolveLauncherRequest).
    private var launcherRequestPending = false

    private var selectedSize = SystemOptimizer.DisplaySizePreset.STANDARD_9_10
    private var selectedPortrait = false
    private var dpiPicker: com.andrerinas.openheadunit.view.DpiPickerView? = null

    private var videoPreview: VideoPreviewLayout? = null
    private var videoJob: Job? = null
    private var videoRun: VideoPreflightRun? = null
    private var videoApply = false
    private var videoAttempted = false
    private var audioStep: OnboardingAudioStep? = null

    // Permissions step (registered here, before the activity is RESUMED).
    private var permissionBinder: PermissionRowBinder? = null
    private val permNormalLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionBinder?.rebind() }
    private val permSpecialLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { permissionBinder?.rebind() }
    // onResume settles launcherRequestPending once this has run.
    private val homeRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { CarLauncherManager.onRoleRequestResult(this) }

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
        launcherRequestPending = savedInstanceState?.getBoolean(KEY_LAUNCHER_PENDING, false) ?: false

        flipper = findViewById(R.id.onb_flipper)
        backBtn = findViewById(R.id.onb_back)
        nextBtn = findViewById(R.id.onb_next)
        skipBtn = findViewById(R.id.onb_skip)
        stepper = findViewById(R.id.onb_stepper)
        compactFooterOnShortScreens()

        applyCompactChrome()

        selectedPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        selectedSize = OnboardingDisplayPolicy.initialPreset(settings.displaySizePreset, estimateSizePreset())

        buildStepperDots()
        bindSteps()
        audioStep?.restore(savedInstanceState)
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
        audioStep?.save(outState)
        outState.putBoolean(KEY_LAUNCHER_PENDING, launcherRequestPending)
    }

    /**
     * Reclaim the wizard chrome's space on short panels.
     *
     * A 624x288 dp unit is a real target (a 720p panel at 400 dpi), and the standard 24 dp
     * padding plus the tall footer leave less than a hundred dp for a step. Eight dp of padding
     * and a tighter indicator keep the content usable without hiding any action.
     */
    private fun applyCompactChrome() {
        val height = resources.configuration.screenHeightDp
        if (height >= 400) return
        val pad = dp(if (height < 300) 4 else 8)
        flipper.setPadding(pad, pad, pad, pad)
        if (height < 300) {
            // A 240 dp panel has no room for a step indicator; the content is the step.
            stepper.visibility = View.GONE
        } else {
            val indicator = stepper.layoutParams as LinearLayout.LayoutParams
            indicator.topMargin = dp(4)
            indicator.bottomMargin = dp(2)
            stepper.layoutParams = indicator
        }
        findViewById<LinearLayout>(R.id.onb_footer).setPadding(dp(8), dp(2), dp(8), dp(4))
        // "Do it later" is already on the Back/Next row: compactFooterOnShortScreens moves it on
        // anything under 500 dp, and this only runs under 400.
    }

    override fun onResume() {
        super.onResume()
        // Reflect any changes made in deep-linked settings screens (theme, automation) or after
        // returning from a special-permission settings screen (overlay / modify system settings).
        updateThemeButtonText()
        updateNightButtonText()
        // Coming back from the home-role request (or the system home settings) is where its answer is.
        resolveLauncherRequest()
        updateLauncherStep()
        permissionBinder?.rebind()
        if (step == STEP_DPI) dpiPicker?.startDemo()
        if (step == STEP_VIDEO) showVideoStep()
    }

    override fun onPause() {
        super.onPause()
        dpiPicker?.stopDemo()
        audioStep?.onPause()
        // A codec and a surface must not outlive the visible wizard. The result is lost, which is
        // honest: the user can run the five second test again when they come back.
        if (step == STEP_VIDEO && videoJob?.isActive == true) {
            stopVideoTest()
            videoAttempted = true
            showVideoIdle()
        }
    }

    override fun onDestroy() {
        videoJob?.cancel()
        videoJob = null
        audioStep?.release()
        super.onDestroy()
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
        resolveLauncherRequest()
        var s = step + 1
        while (s < STEP_COUNT && isStepHidden(s)) s++
        if (s >= STEP_COUNT) { finishOnboarding(); return }
        step = s
        render()
    }

    /** Move back to the previous visible step. */
    private fun goBack() {
        resolveLauncherRequest()
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
            androidx.core.view.MarginLayoutParamsCompat.setMarginEnd(
                lp,
                (6 * resources.displayMetrics.density).toInt()
            )
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
            followSizeWithPicker()
        }
        val orientGroup = findViewById<MaterialButtonToggleGroup>(R.id.onb_orient_group)
        orientGroup.check(if (selectedPortrait) R.id.onb_orient_port else R.id.onb_orient_land)
        orientGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            selectedPortrait = id == R.id.onb_orient_port
            followSizeWithPicker()
        }

        // Graphical DPI picker (slider + preview + Small/Medium/Large tabs, all synced).
        // Seed from the user's saved DPI when they already set one. A size tap re-seeds it
        // unless the picker was moved by hand (followSizeWithPicker).
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
        // --- Audio: a guided sound check, with the technical switches one level down ---
        audioStep = OnboardingAudioStep(this, settings, findViewById(R.id.onb_audio_root)).also {
            it.bind()
        }

        // --- Car launcher: the unit's home screen, with the price of replacing the stock one ---
        // A wizard reopened after the user picked this app as home must not show launcher mode off.
        val launcherOn = OnboardingLauncherPolicy.launcherModeOnOpen(
            settings.enableCarLauncher, CarLauncherManager.isDefaultLauncher(this))
        if (launcherOn != settings.enableCarLauncher) {
            settings.enableCarLauncher = true
            AppLog.i("OnboardingActivity: the app already holds the home role, storing car launcher mode")
        }
        findViewById<SwitchMaterial>(R.id.onb_launcher_switch).apply {
            isChecked = settings.enableCarLauncher || launcherRequestPending
            setOnCheckedChangeListener { _, checked ->
                settings.enableCarLauncher = checked
                CarLauncherManager.setLauncherEnabled(this@OnboardingActivity, checked)
                if (checked) {
                    if (!CarLauncherManager.isDefaultLauncher(this@OnboardingActivity)) {
                        launcherRequestPending = true
                        CarLauncherManager.promptSetDefaultLauncher(this@OnboardingActivity, homeRoleLauncher)
                    }
                } else {
                    launcherRequestPending = false
                }
                updateLauncherStep()
            }
        }
        findViewById<MaterialButton>(R.id.onb_launcher_default_button).setOnClickListener {
            // Only shown with the switch on, where a request is already pending or launcher mode
            // is already stored, so asking again needs no extra bookkeeping.
            CarLauncherManager.promptSetDefaultLauncher(this@OnboardingActivity, homeRoleLauncher)
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

        // --- Video test: the clips are played through the decoder and the renderer ---
        videoPreview = findViewById(R.id.onb_video_preview)
        findViewById<MaterialButton>(R.id.onb_video_run).setOnClickListener { startVideoTest() }
        findViewById<MaterialButton>(R.id.onb_video_skip).setOnClickListener { skipVideoTest() }
        findViewById<MaterialCheckBox>(R.id.onb_video_apply).setOnCheckedChangeListener { _, checked ->
            videoApply = checked
        }
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

        findViewById<MaterialButtonToggleGroup>(R.id.onb_vehicle_type_group).apply {
            check(vehicleTypeButtonId(settings.vehicleType))
            addOnButtonCheckedListener { _, _, checked -> if (checked) applyVehicleTypeNote() }
        }
        applyVehicleTypeNote()

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

    /** Shown only when the microphone setting replaces the picked type with a motorcycle. */
    private fun applyVehicleTypeNote() {
        val picked = vehicleTypeOf(findViewById<MaterialButtonToggleGroup>(R.id.onb_vehicle_type_group).checkedButtonId)
        findViewById<View>(R.id.onb_vehicle_type_forced).visibility =
            if (VehicleTypePolicy.isOverriddenByMicrophone(picked, settings.useHeadUnitMicrophone)) View.VISIBLE else View.GONE
    }

    private fun vehicleTypeButtonId(type: Int): Int = when (VehicleTypePolicy.sanitised(type)) {
        VehicleTypePolicy.TRUCK -> R.id.onb_vehicle_type_truck
        VehicleTypePolicy.MOTORCYCLE -> R.id.onb_vehicle_type_motorcycle
        else -> R.id.onb_vehicle_type_car
    }

    private fun vehicleTypeOf(buttonId: Int): Int = when (buttonId) {
        R.id.onb_vehicle_type_truck -> VehicleTypePolicy.TRUCK
        R.id.onb_vehicle_type_motorcycle -> VehicleTypePolicy.MOTORCYCLE
        else -> VehicleTypePolicy.CAR
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
            STEP_VIDEO -> videoJob?.isActive != true
            else -> true
        }
        if (step == STEP_READY) findViewById<TextView>(R.id.onb_ready_summary).text = summaryText()
        if (step == STEP_AUDIO) audioStep?.onShown() else audioStep?.onHidden()
        if (step == STEP_AUTOMATION) applyAutomationVisibility()
        if (step == STEP_LAUNCHER) updateLauncherStep()
        if (step == STEP_PERMISSIONS) permissionBinder?.rebind()
        if (step == STEP_VEHICLE) applyVehicleTypeNote()
        if (step == STEP_VIDEO) showVideoStep() else stopVideoTest()
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

    /**
     * On short screens (800x480 head units) the "Do it later" row costs a whole row of step
     * content, enough to push the last switch of the longer steps below the fold. There it moves
     * into the navigation row, just before Next. Taller screens keep it on its own row.
     */
    private fun compactFooterOnShortScreens() {
        if (resources.configuration.screenHeightDp >= SHORT_SCREEN_MAX_HEIGHT_DP) return
        val navRow = findViewById<LinearLayout>(R.id.onb_nav_row)
        (skipBtn.parent as? ViewGroup)?.removeView(skipBtn)
        val gap = (8 * resources.displayMetrics.density).toInt()
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            rightMargin = gap
            MarginLayoutParamsCompat.setMarginEnd(this, gap)
        }
        navRow.addView(skipBtn, navRow.indexOfChild(nextBtn), params)
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

    // --- Car launcher ---

    /**
     * The switch state and the default-home button. The button only makes sense with the launcher
     * registered, and its label flips once the system grants the home role, which can happen while
     * the wizard is in the background, so this also runs from onResume.
     */
    private fun updateLauncherStep() {
        val button = findViewById<MaterialButton>(R.id.onb_launcher_default_button) ?: return
        val switchOn = findViewById<SwitchMaterial>(R.id.onb_launcher_switch).isChecked
        button.visibility = if (switchOn) View.VISIBLE else View.GONE
        button.setText(
            if (CarLauncherManager.isDefaultLauncher(this)) R.string.onb_launcher_default_home_active
            else R.string.onb_launcher_default_home
        )
        // Reopen on reconnection is ignored while the app is the home app (Settings.reopenOnReconnection),
        // and the user may have just turned it on in the automation step, so say so here.
        val showNote = OnboardingLauncherPolicy.showsReopenNote(
            launcherSwitchOn = switchOn,
            reopenOn = findViewById<SwitchMaterial>(R.id.onb_reopen_switch).isChecked,
            reopenOffered = settings.showsUsb()
        )
        findViewById<View>(R.id.onb_launcher_reopen_note).visibility = if (showNote) View.VISIBLE else View.GONE
    }

    /**
     * Settles a pending home-role request: launcher mode is stored only if the role is now held,
     * otherwise the switch goes back off. Runs on resume and when leaving the step, so a request
     * whose answer never arrives (the dialog did not show, or was dismissed) cannot leave the
     * setting claiming launcher mode.
     */
    private fun resolveLauncherRequest() {
        val held = CarLauncherManager.isDefaultLauncher(this)
        when (OnboardingLauncherPolicy.resolve(launcherRequestPending, held)) {
            OnboardingLauncherPolicy.Outcome.NONE -> return
            OnboardingLauncherPolicy.Outcome.CONFIRMED -> {
                launcherRequestPending = false
                AppLog.i("OnboardingActivity: home role granted, OpenHU is default home app")
            }
            OnboardingLauncherPolicy.Outcome.UNCONFIRMED -> {
                launcherRequestPending = false
                AppLog.i("OnboardingActivity: home role not held yet, car launcher mode remains enabled")
            }
        }
        updateLauncherStep()
    }

    /** What the wizard ended up doing about the launcher, or null while the switch is off. */
    private fun launcherSummaryLine(): String? = when {
        CarLauncherManager.isDefaultLauncher(this) -> getString(R.string.onb_summary_launcher_home)
        settings.enableCarLauncher -> getString(R.string.onb_summary_launcher_registered)
        else -> null
    }

    private fun onNext() {
        if (step == STEP_SAFETY) settings.hasAcceptedDisclaimer = true
        if (step == STEP_VIDEO) {
            val run = videoRun
            videoApply = run != null &&
                VideoPreflightNote.NOT_TESTED !in run.recommendation.notes &&
                findViewById<MaterialCheckBox>(R.id.onb_video_apply).isChecked
            stopVideoTest()
        }
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
            val type = vehicleTypeOf(findViewById<MaterialButtonToggleGroup>(R.id.onb_vehicle_type_group).checkedButtonId)
            if (type != settings.vehicleType) settings.vehicleType = type
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
        resolveLauncherRequest()
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
        completeOnboarding()
        finish()
    }

    /** Complete the wizard and open a specific Settings sub-screen (e.g. the loading screen setup). */
    private fun finishOnboardingInto(destinationId: Int) {
        completeOnboarding()
        // NEW_TASK puts Settings in the app's own task, over the main screen, instead of in the
        // wizard's task, which would linger out of Recents once the wizard is gone.
        startActivity(AppUiEntry.continuation(
            Intent(this, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_DESTINATION, destinationId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ))
        finish()
    }

    /**
     * Marks the wizard as seen and applies the settings that only take effect when it ends. The
     * launcher switch already enabled or disabled the home alias as it was flipped; syncing here
     * also covers a value stored by an older build, which never touched the alias.
     */
    private fun completeOnboarding() {
        resolveLauncherRequest()
        settings.hasAcceptedDisclaimer = true
        settings.hasCompletedSetupWizard = true
        settings.onboardingVersion = CURRENT_ONBOARDING_VERSION
        if (OnboardingLauncherPolicy.syncsAlias(
                settings.enableCarLauncher, CarLauncherManager.isDefaultLauncher(this))
        ) {
            CarLauncherManager.syncWithSettings(this, settings.enableCarLauncher)
        } else {
            AppLog.i("OnboardingActivity: the app holds the home role, keeping the launcher alias")
        }
        settings.commit()
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

    // --- Video test ---

    private fun showVideoStep() {
        // The preview needs a real window and a laid out surface. onCreate can restore the video
        // step before the activity is resumed, and starting there spent the whole surface timeout
        // on a view nobody could see; onResume calls this again once the window exists.
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (videoJob?.isActive == true) return
        when {
            videoRun != null -> renderVideoResult()
            videoAttempted -> showVideoIdle()
            else -> startVideoTest()
        }
    }

    private fun startVideoTest() {
        if (videoJob?.isActive == true) return
        val preview = videoPreview ?: return
        preview.visibility = View.VISIBLE
        preview.removeAllViews()
        videoRun = null
        videoAttempted = true
        // Ticked for everyone, upgraders included: running the test is asking for its answer, and
        // the box is right there to keep the current settings instead.
        videoApply = true
        setVideoRunning(true)
        findViewById<TextView>(R.id.onb_video_status).text = getString(R.string.onb_video_preparing)
        val runner = VideoPreflightRunner(
            context = this,
            settings = settings,
            sizePreset = selectedSize,
            portrait = selectedPortrait,
            container = preview,
            onProgress = { progress -> runOnUiThread { showVideoProgress(progress) } },
        )
        videoJob = lifecycleScope.launch {
            try {
                videoRun = runner.run()
                videoApply = true
                setVideoRunning(false)
                renderVideoResult()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setVideoRunning(false)
                showVideoIdle()
                showVideoSummary(
                    passed = false,
                    title = getString(R.string.onb_video_error, e.message ?: e.javaClass.simpleName),
                    detail = null,
                )
            } finally {
                videoJob = null
            }
        }
    }

    private fun skipVideoTest() {
        videoAttempted = true
        videoRun = null
        videoApply = false
        stopVideoTest()
        showVideoIdle()
        nextBtn.isEnabled = true
    }

    private fun stopVideoTest() {
        videoJob?.cancel()
        videoJob = null
        videoPreview?.removeAllViews()
    }

    private fun showVideoIdle() {
        videoPreview?.let {
            it.visibility = View.GONE
            it.removeAllViews()
        }
        findViewById<ProgressBar>(R.id.onb_video_progress).visibility = View.GONE
        findViewById<TextView>(R.id.onb_video_status).apply {
            visibility = View.VISIBLE
            text = getString(R.string.onb_video_idle)
        }
        clearVideoResult()
        findViewById<MaterialCheckBox>(R.id.onb_video_apply).visibility = View.GONE
        findViewById<MaterialButton>(R.id.onb_video_run).apply {
            visibility = View.VISIBLE
            text = getString(R.string.onb_video_run)
        }
        findViewById<MaterialButton>(R.id.onb_video_skip).visibility = View.GONE
        nextBtn.isEnabled = true
    }

    private fun setVideoRunning(running: Boolean) {
        findViewById<ProgressBar>(R.id.onb_video_progress).visibility =
            if (running) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.onb_video_status).visibility =
            if (running) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.onb_video_run).visibility =
            if (running) View.GONE else View.VISIBLE
        findViewById<MaterialButton>(R.id.onb_video_skip).visibility =
            if (running) View.VISIBLE else View.GONE
        if (running) {
            findViewById<MaterialCheckBox>(R.id.onb_video_apply).visibility = View.GONE
            clearVideoResult()
        }
        nextBtn.isEnabled = !running
    }

    private fun showVideoProgress(progress: VideoPreflightProgress) {
        findViewById<TextView>(R.id.onb_video_status).text = getString(
            R.string.onb_video_running,
            progress.clip.label,
            viewModeLabel(progress.backend),
        )
    }

    private fun renderVideoResult() {
        val run = videoRun ?: return
        // The clips have already played; leaving a SurfaceView inside the scroll view would make
        // the result harder to read on older units, and the last frame adds nothing to it.
        videoPreview?.let {
            it.visibility = View.GONE
            it.removeAllViews()
        }
        val recommendation = run.recommendation
        val passed = VideoPreflightNote.NOT_TESTED !in recommendation.notes
        // The verdict leads. A recommendation is only named when something passed: saying one next
        // to "nothing passed" read as a contradiction.
        val notes = recommendation.notes
            .filter { it != VideoPreflightNote.NOT_TESTED }
            .sortedBy { it.ordinal }
            .map { noteText(it, recommendation, run) }
        if (passed) {
            showVideoSummary(
                passed = true,
                title = getString(
                    R.string.onb_video_summary_pass,
                    recommendation.codec.settingsValue,
                    recommendation.resolution.resName,
                ),
                detail = (listOf(getString(R.string.onb_video_summary_view_mode, viewModeLabel(recommendation.viewMode))) + notes)
                    .joinToString("\n"),
            )
        } else {
            showVideoSummary(
                passed = false,
                title = getString(R.string.onb_video_summary_none),
                detail = (listOf(getString(R.string.onb_video_note_not_tested)) + notes).joinToString("\n"),
            )
        }
        val rows = findViewById<LinearLayout>(R.id.onb_video_rows)
        rows.removeAllViews()
        val tried = run.backends.filter { it.clips.isNotEmpty() }
        for (backend in tried) {
            if (tried.size > 1) rows.addView(videoGroupHeader(viewModeLabel(backend.viewMode)))
            for (outcome in backend.clips) rows.addView(videoRow(outcome))
        }
        findViewById<TextView>(R.id.onb_video_status).visibility = View.GONE
        findViewById<ProgressBar>(R.id.onb_video_progress).visibility = View.GONE
        findViewById<MaterialButton>(R.id.onb_video_skip).visibility = View.GONE
        val apply = findViewById<MaterialCheckBox>(R.id.onb_video_apply)
        val canApply = VideoPreflightNote.NOT_TESTED !in recommendation.notes
        apply.visibility = if (canApply) View.VISIBLE else View.GONE
        apply.isChecked = videoApply
        findViewById<MaterialButton>(R.id.onb_video_run).apply {
            visibility = View.VISIBLE
            text = getString(R.string.onb_video_again)
        }
        nextBtn.isEnabled = true
    }

    /** The same names View mode shows in Settings, so a recommendation can be found there. */
    private fun viewModeLabel(mode: Settings.ViewMode): String = getString(
        when (mode) {
            Settings.ViewMode.SURFACE -> R.string.surface_view
            Settings.ViewMode.TEXTURE -> R.string.texture_view
            Settings.ViewMode.GLES -> R.string.gles_view
        }
    )

    private fun clearVideoResult() {
        findViewById<View>(R.id.onb_video_summary).visibility = View.GONE
        findViewById<LinearLayout>(R.id.onb_video_rows).removeAllViews()
    }

    /** The verdict box: a check on a green wash when something passed, a cross on red otherwise. */
    private fun showVideoSummary(passed: Boolean, title: String, detail: String?) {
        val accent = ContextCompat.getColor(this, if (passed) R.color.material_green_700 else R.color.material_red_700)
        findViewById<View>(R.id.onb_video_summary).apply {
            visibility = View.VISIBLE
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(withAlpha(accent, SUMMARY_WASH_ALPHA))
            }
        }
        findViewById<ImageView>(R.id.onb_video_summary_icon).setImageDrawable(resultIcon(passed, accent))
        findViewById<TextView>(R.id.onb_video_summary_title).text = title
        findViewById<TextView>(R.id.onb_video_summary_detail).apply {
            text = detail
            visibility = if (detail.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun videoGroupHeader(label: String): View = TextView(this).apply {
        TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_MaterialComponents_Overline)
        text = label
        setPadding(0, dp(10), 0, dp(2))
    }

    /** One clip: whether it passed, what it was, what went wrong in plain words, and the count. */
    private fun videoRow(outcome: VideoPreflightClipOutcome): View {
        val passed = outcome.passed
        // Played, but this Android cannot read the picture back: not a failure, so not a red cross.
        val unchecked = outcome.failure == VideoPreflightFailure.UNREADABLE
        val accent = ContextCompat.getColor(
            this,
            when {
                passed -> R.color.material_green_700
                unchecked -> R.color.material_orange_700
                else -> R.color.material_red_700
            }
        )
        val frames = if (outcome.drawnFrames >= 0) outcome.drawnFrames else outcome.renderedFrames
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(ImageView(context).apply {
                setImageDrawable(
                    if (unchecked) tinted(R.drawable.ic_warning_white, accent) else resultIcon(passed, accent)
                )
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(20), dp(20)))
            addView(TextView(context).apply {
                TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle2)
                text = outcome.clip.label
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(12)
            })
            addView(TextView(context).apply {
                TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2)
                text = getString(failureLabel(outcome.failure))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(12)
            })
            addView(TextView(context).apply {
                TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_MaterialComponents_Caption)
                text = getString(R.string.onb_video_frames, frames, outcome.clip.frameCount)
            })
        }
    }

    private fun failureLabel(failure: VideoPreflightFailure): Int = when (failure) {
        VideoPreflightFailure.NONE -> R.string.onb_video_row_ok
        VideoPreflightFailure.NO_SURFACE,
        VideoPreflightFailure.NO_FIRST_FRAME,
        VideoPreflightFailure.NO_DRAWN_FRAMES -> R.string.onb_video_row_no_picture
        VideoPreflightFailure.DECODER_ERROR -> R.string.onb_video_row_decoder
        VideoPreflightFailure.FEW_FRAMES -> R.string.onb_video_row_few_frames
        VideoPreflightFailure.BAD_MARKER -> R.string.onb_video_row_bad_picture
        VideoPreflightFailure.UNREADABLE -> R.string.onb_video_row_unverified
    }

    /** Vectors go through AppCompatResources so they also load on Android 4. */
    private fun resultIcon(passed: Boolean, color: Int) =
        tinted(if (passed) R.drawable.ic_check else R.drawable.ic_close_white, color)

    private fun tinted(drawableRes: Int, color: Int) =
        AppCompatResources.getDrawable(this, drawableRes)
            ?.mutate()
            ?.let { DrawableCompat.wrap(it).apply { DrawableCompat.setTint(this, color) } }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun noteText(
        note: VideoPreflightNote,
        recommendation: VideoPreflightRecommendation,
        run: VideoPreflightRun,
    ): String = when (note) {
        VideoPreflightNote.H265_FAILED -> getString(R.string.onb_video_note_h265_failed)
        VideoPreflightNote.RESOLUTION_CAPPED ->
            getString(R.string.onb_video_note_resolution_capped, recommendation.resolution.resName)
        VideoPreflightNote.RENDERER_CHANGED -> getString(
            R.string.onb_video_note_renderer_changed,
            viewModeLabel(run.staticViewMode),
            viewModeLabel(recommendation.viewMode),
        )
        VideoPreflightNote.HIGH_RES_UNVERIFIED -> getString(R.string.onb_video_note_high_res_unverified)
        VideoPreflightNote.PICTURE_UNVERIFIED -> getString(R.string.onb_video_note_unverified)
        VideoPreflightNote.NOT_TESTED -> getString(R.string.onb_video_note_not_tested)
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

    private fun estimateSizePreset(): SystemOptimizer.DisplaySizePreset {
        val m = realMetrics()
        return OnboardingDisplayPolicy.estimatePreset(m.widthPixels, m.heightPixels, m.xdpi, m.ydpi, m.densityDpi)
    }

    /** The size and orientation only reach the saved DPI through the picker, so move it with them. */
    private fun followSizeWithPicker() {
        val picker = dpiPicker ?: return
        picker.dpi = OnboardingDisplayPolicy.pickerDpiAfterSizeChange(
            recommendedDpi(), picker.dpi, picker.hasUserInteracted
        )
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

    /** What Automatic will send for the stored resolution, after the margins, as connect decides it. */
    private fun recommendedDpi(): Int {
        val m = realMetrics()
        val chosen = preflightToApply()?.resolution ?: Settings.Resolution.fromId(settings.resolutionId)
        val r = if (chosen == null || chosen == Settings.Resolution.AUTO) {
            SystemOptimizer.recommendedResolution(m.widthPixels, m.heightPixels)
        } else chosen
        val (pw, ph) = AnnouncedDensityPolicy.orientPanel(m.widthPixels, m.heightPixels, selectedPortrait)
        val (w, h) = AnnouncedDensityPolicy.visibleSize(settings.videoFitMode, pw, ph, r.width, r.height)
        val par = AnnouncedDensityPolicy.pixelAspectE4(
            settings.videoFitMode, pw, ph, r.width, r.height, settings.pixelAspectRatioE4
        )
        return AnnouncedDensityPolicy.decide(0, selectedSize, w, h, par, panelW = pw, panelH = ph).announced
    }

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

    /** The recommendation the test produced, but only when the user chose to apply it. */
    private fun preflightToApply(): VideoPreflightRecommendation? =
        videoRun?.recommendation?.takeIf {
            videoApply && VideoPreflightNote.NOT_TESTED !in it.notes
        }

    /** Writes the recommended display settings plus the DPI chosen in the picker. Called when
     * leaving the Display and Android Auto size steps, so no separate Apply tap is needed. */
    private fun applyDisplaySettings() {
        val result = SystemOptimizer(this).calculateOptimalSettings(selectedSize, selectedPortrait)
        val panel = realMetrics()
        val preflight = preflightToApply()
        // Only auto-apply the video setup on a genuinely fresh install. Upgraders already have a
        // working config (view mode, codec, resolution, orientation), and silently overwriting it
        // has stranded users on a black screen (issue #767), so we leave those untouched and only
        // apply the DPI the user set in the picker. A test they chose to apply is the exception.
        if (!isUpgrader() || preflight != null) {
            val previousViewMode = settings.viewMode
            // Set the resolution the panel warrants (the shared panel ceiling; mild downscaling of a
            // slightly-larger standard resolution is allowed, heavy downscaling is not — see panelCeiling).
            settings.resolutionId = preflight?.resolution?.id
                ?: SystemOptimizer.recommendedResolution(panel.widthPixels, panel.heightPixels).id
            settings.videoCodec = preflight?.codec?.settingsValue ?: result.recommendedVideoCodec
            settings.viewMode = preflight?.viewMode ?: result.recommendedViewMode
            settings.screenOrientation = result.suggestedOrientation
            // If we changed the renderer, confirm the picture on the first projection.
            if (settings.viewMode != previousViewMode) settings.pendingRendererConfirm = true
        }
        // Next is a button, so it never takes focus off the DPI field; without this a value the
        // user typed and did not confirm would be dropped in favour of the previous one.
        dpiPicker?.commitPendingInput()
        val stored = settings.dpiPixelDensity
        val dpi = dpiPicker?.let { OnboardingDisplayPolicy.dpiToSave(it.dpi, it.hasUserInteracted, stored) } ?: stored
        if (dpi != stored) settings.dpiPixelDensity = dpi
        if (settings.displaySizePreset != selectedSize.name) settings.displaySizePreset = selectedSize.name
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
        val launcher = launcherSummaryLine()
        return head + "\n" + perms + if (launcher == null) "" else "\n" + launcher
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
        private const val KEY_LAUNCHER_PENDING = "onb_launcher_pending"
        private const val STEP_COUNT = 14
        /** Below this available height (dp) the footer goes to a single row. 800x480 lands
         * well under it; 1024x600 stays above, so its footer is unchanged. */
        private const val SHORT_SCREEN_MAX_HEIGHT_DP = 500
        /** The verdict box's wash: enough to tell pass from fail, light enough for its text. */
        private const val SUMMARY_WASH_ALPHA = 0x24
        private const val STEP_SAFETY = 1
        private const val STEP_CONNECTION = 2
        private const val STEP_VIDEO = 3
        private const val STEP_DISPLAY = 4
        private const val STEP_DPI = 5
        private const val STEP_AUDIO = 7
        private const val STEP_AUTOMATION = 8
        private const val STEP_LAUNCHER = 9
        private const val STEP_GPS = 10
        private const val STEP_VEHICLE = 11
        private const val STEP_PERMISSIONS = 12
        private const val STEP_READY = 13

        // The manufacturer half of the Desktop Head Unit identity we report by default.
        private const val DHU_MAKE = "Google"
        // Names for the two output backends, as product names rather than translated words.
        private const val AUDIO_BACKEND_AAUDIO = "AAudio"
        private const val AUDIO_BACKEND_AUDIOTRACK = "AudioTrack"
        // Names the car step offers that are not car brands: the head unit make itself and
        // the app's own name. Lowercase, compared against a lowercased input.
        private val NOT_A_MANUFACTURER = setOf("google", "open headunit")
    }
}
