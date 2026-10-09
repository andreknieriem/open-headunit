package com.andrerinas.openheadunit.main

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.main.settings.SettingItem
import com.andrerinas.openheadunit.main.settings.SettingsAdapter
import com.andrerinas.openheadunit.utils.AppThemeManager
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.ToastUtils
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class DarkModeFragment : Fragment(), SensorEventListener {
    private lateinit var settings: Settings
    private lateinit var recyclerView: RecyclerView
    private lateinit var settingsAdapter: SettingsAdapter
    private lateinit var toolbar: MaterialToolbar
    private var saveButton: MaterialButton? = null

    // Pending dark mode settings
    private var pendingAppTheme: Settings.AppTheme? = null
    private var pendingAppThemeThresholdLux: Int? = null
    private var pendingAppThemeThresholdBrightness: Int? = null
    private var pendingAppThemeManualStart: Int? = null
    private var pendingAppThemeManualEnd: Int? = null
    private var pendingUseExtremeDarkMode: Boolean? = null

    // Shared "Location" mode + sunrise reference settings
    private var pendingUseFixedSunriseLocation: Boolean? = null
    private var pendingLocationOutsideNight: Boolean? = null

    // Last non-Location mode of each selector, restored when the Location scope removes it.
    private var restoreAppTheme: Settings.AppTheme = Settings.AppTheme.AUTOMATIC
    private var restoreNightMode: Settings.NightMode = Settings.NightMode.AUTO

    // Pending night mode settings (Android Auto)
    private var pendingNightMode: Settings.NightMode? = null
    private var pendingThresholdLux: Int? = null
    private var pendingThresholdBrightness: Int? = null
    private var pendingManualStart: Int? = null
    private var pendingManualEnd: Int? = null


    // Pending AA monochrome settings
    private var pendingAaMonochromeEnabled: Boolean? = null
    private var pendingAaDesaturationLevel: Int? = null

    // View mode (needed for GLES dialog)
    private var pendingViewMode: Settings.ViewMode? = null

    // Sensor for live lux reading
    private var cachedLux: Float = -1f
    private var sensorManager: SensorManager? = null

    // Live screen brightness reading (mirrors how the manager reads it)
    private var cachedBrightness: Int = -1
    private val brightnessObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            val newBrightness = readBrightness()
            if (newBrightness != cachedBrightness) {
                cachedBrightness = newBrightness
                scheduleListRefresh()
            }
        }
    }

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable {
        if (isAdded && ::settingsAdapter.isInitialized) {
            updateSettingsList()
        }
    }

    private var requiresRestart = false
    private var hasChanges = false
    private val SAVE_ITEM_ID = 1001

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_dark_mode, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        settings = App.provide(requireContext()).settings

        // Initialize pending state from current values
        pendingAppTheme = settings.appTheme
        pendingAppThemeThresholdLux = settings.appThemeThresholdLux
        pendingAppThemeThresholdBrightness = settings.appThemeThresholdBrightness
        pendingAppThemeManualStart = settings.appThemeManualStart
        pendingAppThemeManualEnd = settings.appThemeManualEnd
        pendingUseExtremeDarkMode = settings.useExtremeDarkMode

        pendingNightMode = settings.nightMode
        pendingUseFixedSunriseLocation = settings.useFixedSunriseLocation
        pendingLocationOutsideNight = settings.locationOutsideNight
        restoreAppTheme = if (settings.appTheme == Settings.AppTheme.LOCATION)
            Settings.AppTheme.AUTOMATIC else settings.appTheme
        restoreNightMode = if (settings.nightMode == Settings.NightMode.LOCATION)
            Settings.NightMode.AUTO else settings.nightMode
        pendingThresholdLux = settings.nightModeThresholdLux
        pendingThresholdBrightness = settings.nightModeThresholdBrightness
        pendingManualStart = settings.nightModeManualStart
        pendingManualEnd = settings.nightModeManualEnd

        pendingAaMonochromeEnabled = settings.aaMonochromeEnabled
        pendingAaDesaturationLevel = settings.aaDesaturationLevel

        pendingViewMode = settings.viewMode

        // Intercept system back button
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPress()
            }
        })

        toolbar = view.findViewById(R.id.toolbar)
        settingsAdapter = SettingsAdapter()
        recyclerView = view.findViewById(R.id.recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = settingsAdapter

        updateSettingsList()
        setupToolbar()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_LIGHT) {
            val newLux = event.values[0]
            if (kotlin.math.abs(newLux - cachedLux) >= 1.0f || cachedLux < 0f) {
                cachedLux = newLux
                scheduleListRefresh()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            handleBackPress()
        }

        val saveItem = toolbar.menu.add(0, SAVE_ITEM_ID, 0, getString(R.string.save))
        saveItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        saveItem.setActionView(R.layout.layout_save_button)

        saveButton = saveItem.actionView?.findViewById(R.id.save_button_widget)
        saveButton?.setOnClickListener {
            saveSettings()
        }

        updateSaveButtonState()
    }

    private fun handleBackPress() {
        if (hasChanges) {
            MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                .setTitle(R.string.unsaved_changes)
                .setMessage(R.string.unsaved_changes_message)
                .setPositiveButton(R.string.discard) { _, _ ->
                    navigateBack()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            navigateBack()
        }
    }

    private fun navigateBack() {
        try {
            val navController = findNavController()
            if (!navController.navigateUp()) {
                requireActivity().finish()
            }
        } catch (e: Exception) {
            requireActivity().finish()
        }
    }

    private fun updateSaveButtonState() {
        saveButton?.isEnabled = hasChanges
        saveButton?.text = if (requiresRestart) getString(R.string.save_and_restart) else getString(R.string.save)
    }

    private fun saveSettings() {
        // Detect changes BEFORE saving values to SharedPreferences
        val themeChanged = pendingAppTheme != settings.appTheme
        val appThemeThresholdChanged = pendingAppThemeThresholdLux != settings.appThemeThresholdLux ||
                pendingAppThemeThresholdBrightness != settings.appThemeThresholdBrightness ||
                pendingAppThemeManualStart != settings.appThemeManualStart ||
                pendingAppThemeManualEnd != settings.appThemeManualEnd ||
                // Shared sunrise source / outside-places default affect the dynamic app theme too.
                pendingUseFixedSunriseLocation != settings.useFixedSunriseLocation ||
                pendingLocationOutsideNight != settings.locationOutsideNight
        val extremeDarkChanged = pendingUseExtremeDarkMode != settings.useExtremeDarkMode
        val viewModeChanged = pendingViewMode != settings.viewMode

        // Save night mode settings
        pendingNightMode?.let { if (it != settings.nightMode) settings.nightMode = it }
        pendingUseFixedSunriseLocation?.let { if (it != settings.useFixedSunriseLocation) settings.useFixedSunriseLocation = it }
        pendingLocationOutsideNight?.let { if (it != settings.locationOutsideNight) settings.locationOutsideNight = it }
        pendingThresholdLux?.let { if (it != settings.nightModeThresholdLux) settings.nightModeThresholdLux = it }
        pendingThresholdBrightness?.let { if (it != settings.nightModeThresholdBrightness) settings.nightModeThresholdBrightness = it }
        pendingManualStart?.let { if (it != settings.nightModeManualStart) settings.nightModeManualStart = it }
        pendingManualEnd?.let { if (it != settings.nightModeManualEnd) settings.nightModeManualEnd = it }

        // Save app theme settings
        pendingAppThemeThresholdLux?.let { if (it != settings.appThemeThresholdLux) settings.appThemeThresholdLux = it }
        pendingAppThemeThresholdBrightness?.let { if (it != settings.appThemeThresholdBrightness) settings.appThemeThresholdBrightness = it }
        pendingAppThemeManualStart?.let { if (it != settings.appThemeManualStart) settings.appThemeManualStart = it }
        pendingAppThemeManualEnd?.let { if (it != settings.appThemeManualEnd) settings.appThemeManualEnd = it }
        pendingUseExtremeDarkMode?.let { if (it != settings.useExtremeDarkMode) settings.useExtremeDarkMode = it }

        // Save AA monochrome settings
        pendingAaMonochromeEnabled?.let { if (it != settings.aaMonochromeEnabled) settings.aaMonochromeEnabled = it }
        pendingAaDesaturationLevel?.let { if (it != settings.aaDesaturationLevel) settings.aaDesaturationLevel = it }

        // Save view mode if changed (from GLES dialog)
        if (viewModeChanged) {
            pendingViewMode?.let { if (it != settings.viewMode) settings.viewMode = it }
        }

        pendingAppTheme?.let { newTheme ->
            if (newTheme != settings.appTheme) settings.appTheme = newTheme
            if (themeChanged || appThemeThresholdChanged) {
                // Centralized: runs the live manager for dynamic themes or when a saved
                // place can force the app theme, else applies the static theme.
                AppThemeManager.reapply(requireContext(), settings)
            }
        }

        // Notify Service about Night Mode changes immediately
        val nightModeUpdateIntent = Intent(AapService.ACTION_REQUEST_NIGHT_MODE_UPDATE)
        nightModeUpdateIntent.setPackage(requireContext().packageName)
        requireContext().sendBroadcast(nightModeUpdateIntent)

        if (requiresRestart) {
            if (App.provide(requireContext()).commManager.isConnected) {
                ToastUtils.showToast(context, getString(R.string.stopping_service), Toast.LENGTH_SHORT, force = true)
                val stopServiceIntent = Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_STOP_SERVICE
                }
                ContextCompat.startForegroundService(requireContext(), stopServiceIntent)
            }
        }

        // Reset change tracking
        hasChanges = false
        requiresRestart = false
        updateSaveButtonState()

        ToastUtils.showToast(context, getString(R.string.settings_saved), Toast.LENGTH_SHORT, force = true)

        // Signal visual change so all activities (including MainActivity) pick up changes
        if (extremeDarkChanged || themeChanged ||
            (appThemeThresholdChanged && pendingAppTheme?.let { !AppThemeManager.isStaticMode(it) } == true)) {
            AppThemeManager.signalVisualChange()
        }
    }

    private fun checkChanges() {
        val anyChange = pendingAppTheme != settings.appTheme ||
                pendingAppThemeThresholdLux != settings.appThemeThresholdLux ||
                pendingAppThemeThresholdBrightness != settings.appThemeThresholdBrightness ||
                pendingAppThemeManualStart != settings.appThemeManualStart ||
                pendingAppThemeManualEnd != settings.appThemeManualEnd ||
                pendingUseExtremeDarkMode != settings.useExtremeDarkMode ||
                pendingNightMode != settings.nightMode ||
                pendingUseFixedSunriseLocation != settings.useFixedSunriseLocation ||
                pendingLocationOutsideNight != settings.locationOutsideNight ||
                pendingThresholdLux != settings.nightModeThresholdLux ||
                pendingThresholdBrightness != settings.nightModeThresholdBrightness ||
                pendingManualStart != settings.nightModeManualStart ||
                pendingManualEnd != settings.nightModeManualEnd ||
                pendingAaMonochromeEnabled != settings.aaMonochromeEnabled ||
                pendingAaDesaturationLevel != settings.aaDesaturationLevel ||
                pendingViewMode != settings.viewMode

        hasChanges = anyChange

        // View mode change requires restart
        requiresRestart = pendingViewMode != settings.viewMode

        updateSaveButtonState()
    }

    /**
     * Single entry that opens the self-contained Location screen (sunrise reference point
     * plus saved places). Keeping it here keeps the theme selectors clean.
     */
    /**
     * Sunrise/sunset reference source (GPS or a fixed point), shown as a sub-option under
     * an Auto/sunrise mode. Fixed point can be typed (works with no GPS and no internet)
     * or picked on the map. This is the simple option from issue #647. [idSuffix] keeps
     * stable IDs unique if shown under both the app theme and Android Auto sections.
     */
    private fun addSunriseReference(items: MutableList<SettingItem>, idSuffix: String) {
        val useFixed = pendingUseFixedSunriseLocation == true
        items.add(SettingItem.InfoBanner("sunriseHelp_$idSuffix", R.string.sunrise_reference_help))
        items.add(SettingItem.SettingEntry(
            stableId = "sunriseSource_$idSuffix",
            nameResId = R.string.sunrise_location_title,
            value = if (useFixed)
                getString(R.string.sunrise_location_fixed) +
                    " (%.3f, %.3f)".format(settings.fixedSunriseLatitude, settings.fixedSunriseLongitude)
            else getString(R.string.sunrise_location_gps),
            onClick = { _ ->
                val options = arrayOf(
                    getString(R.string.sunrise_location_gps),
                    getString(R.string.sunrise_location_fixed)
                )
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.sunrise_location_title)
                    .setSingleChoiceItems(options, if (useFixed) 1 else 0) { dialog, which ->
                        pendingUseFixedSunriseLocation = which == 1
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))
        if (useFixed) {
            items.add(SettingItem.SettingEntry(
                stableId = "sunriseCoords_$idSuffix",
                nameResId = R.string.coordinates_enter,
                value = "",
                onClick = { _ -> showCoordinatesDialog() }
            ))
        }
    }

    /**
     * Persists the Auto/sunrise mode(s) and the fixed-point source immediately, so the mode
     * is not deselected when the fragment is recreated on returning from the map (issue #647).
     * [applyAppTheme] should be false right before navigating away, to avoid an activity
     * recreate in the middle of the navigation.
     */
    private fun commitSunriseMode(applyAppTheme: Boolean) {
        pendingUseFixedSunriseLocation = true
        settings.useFixedSunriseLocation = true
        pendingAppTheme?.let { if (it != settings.appTheme) settings.appTheme = it }
        pendingNightMode?.let { if (it != settings.nightMode) settings.nightMode = it }
        // Ask the service to re-send the Android Auto night mode (safe, no activity recreate).
        requireContext().sendBroadcast(
            Intent(AapService.ACTION_REQUEST_NIGHT_MODE_UPDATE).setPackage(requireContext().packageName)
        )
        if (applyAppTheme) App.appThemeManager?.forceRefresh()
    }

    /**
     * Persists the Location-based dark mode selection right away, so it is not lost when the
     * fragment is recreated on returning from the places screen or the map. Every control in the
     * Location group calls this, so setting up a location mode never needs the Save button (the
     * rest of Dark Mode still does). [applyTheme] should be false right before navigating away,
     * to avoid an activity recreate in the middle of the navigation.
     */
    private fun commitLocationMode(applyTheme: Boolean) {
        pendingAppTheme?.let { if (it != settings.appTheme) settings.appTheme = it }
        pendingNightMode?.let { if (it != settings.nightMode) settings.nightMode = it }
        pendingLocationOutsideNight?.let { if (it != settings.locationOutsideNight) settings.locationOutsideNight = it }
        // Re-send the Android Auto night mode (safe, no activity recreate).
        requireContext().sendBroadcast(
            Intent(AapService.ACTION_REQUEST_NIGHT_MODE_UPDATE).setPackage(requireContext().packageName)
        )
        if (applyTheme) App.appThemeManager?.forceRefresh()
        // The selection is saved now, so it no longer counts as a pending change.
        checkChanges()
    }

    /** Sub-options for "Location (by area)" mode: outside-places appearance + manage places. */
    /**
     * Dedicated "Location" group, shown whenever either selector is set to Location. Its
     * scope segmented reflects and drives which selector(s) use Location, so choosing here
     * updates the selectors above immediately. Also holds the outside-places appearance
     * and the saved places.
     */
    private fun addLocationGroup(items: MutableList<SettingItem>) {
        items.add(SettingItem.CategoryHeader("locationGroup", R.string.location_section))

        val scopeIndex = when {
            pendingAppTheme == Settings.AppTheme.LOCATION &&
                pendingNightMode == Settings.NightMode.LOCATION -> 2
            pendingAppTheme == Settings.AppTheme.LOCATION -> 0
            else -> 1
        }
        items.add(SettingItem.SegmentedButtonSettingEntry(
            stableId = "locationScope",
            nameResId = R.string.geofence_scope_label,
            options = listOf(
                getString(R.string.geofence_scope_app),
                getString(R.string.geofence_scope_aa),
                getString(R.string.geofence_scope_both)
            ),
            selectedIndex = scopeIndex,
            onOptionSelected = { idx -> setLocationScope(idx) }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "outsidePlaces",
            nameResId = R.string.location_outside_label,
            value = getString(
                if (pendingLocationOutsideNight == true) R.string.geofence_mode_dark
                else R.string.geofence_mode_light
            ),
            onClick = { _ ->
                val options = arrayOf(
                    getString(R.string.geofence_mode_light),
                    getString(R.string.geofence_mode_dark)
                )
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.location_outside_label)
                    .setSingleChoiceItems(options, if (pendingLocationOutsideNight == true) 1 else 0) { dialog, which ->
                        pendingLocationOutsideNight = which == 1
                        commitLocationMode(applyTheme = true)
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "managePlaces",
            nameResId = R.string.location_manage_places,
            value = run {
                val n = settings.geofenceLocations.size
                if (n == 0) getString(R.string.geofence_none) else getString(R.string.geofence_count_summary, n)
            },
            onClick = { _ ->
                // Persist the location mode before leaving, so it survives the fragment being
                // recreated on return from the places screen.
                commitLocationMode(applyTheme = false)
                findNavController().navigate(R.id.action_darkModeFragment_to_locationsFragment)
            }
        ))
    }

    /**
     * Applies the scope chosen in the Location group to the two selectors, restoring each
     * selector's previous (non-Location) mode when Location is removed from it.
     */
    private fun setLocationScope(index: Int) {
        when (index) {
            0 -> { // App only
                pendingAppTheme = Settings.AppTheme.LOCATION
                if (pendingNightMode == Settings.NightMode.LOCATION) pendingNightMode = restoreNightMode
            }
            1 -> { // Android Auto only
                pendingNightMode = Settings.NightMode.LOCATION
                if (pendingAppTheme == Settings.AppTheme.LOCATION) pendingAppTheme = restoreAppTheme
            }
            else -> { // Both
                pendingAppTheme = Settings.AppTheme.LOCATION
                pendingNightMode = Settings.NightMode.LOCATION
            }
        }
        // Persist the scope right away so it is not lost when opening the places screen or map.
        commitLocationMode(applyTheme = true)
        updateSettingsList()
    }

    /** Dialog to type a fixed latitude/longitude, with a shortcut to pick it on the map. */
    private fun showCoordinatesDialog() {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        fun label(text: String) = android.widget.TextView(ctx).apply {
            this.text = text
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (8 * density).toInt()
            layoutParams = lp
        }

        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val help = android.widget.TextView(ctx).apply {
            text = getString(R.string.coordinates_format_help)
            alpha = 0.7f
        }
        // Prefill with a dot decimal separator regardless of device locale.
        val latInput = android.widget.EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            hint = getString(R.string.coordinate_latitude)
            setText(String.format(java.util.Locale.US, "%.5f", settings.fixedSunriseLatitude))
        }
        val lonInput = android.widget.EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            hint = getString(R.string.coordinate_longitude)
            setText(String.format(java.util.Locale.US, "%.5f", settings.fixedSunriseLongitude))
        }
        container.addView(help)
        container.addView(label(getString(R.string.coordinate_latitude)))
        container.addView(latInput)
        container.addView(label(getString(R.string.coordinate_longitude)))
        container.addView(lonInput)

        // Accept a comma or a dot as the decimal separator (locale-proof).
        fun parse(field: android.widget.EditText): Double? =
            field.text.toString().trim().replace(',', '.').toDoubleOrNull()

        MaterialAlertDialogBuilder(ctx, R.style.DarkAlertDialog)
            .setTitle(R.string.coordinates_enter)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val lat = parse(latInput)
                val lon = parse(lonInput)
                if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                    settings.fixedSunriseLatitude = lat
                    settings.fixedSunriseLongitude = lon
                    // Entering coordinates commits the Auto/sunrise mode and the fixed source
                    // so they are not lost if the settings screen is left without saving.
                    commitSunriseMode(applyAppTheme = true)
                    checkChanges()
                    updateSettingsList()
                } else {
                    ToastUtils.showToast(ctx, R.string.coordinates_invalid, Toast.LENGTH_SHORT, force = true)
                }
            }
            .setNeutralButton(R.string.geofence_pick_on_map) { _, _ ->
                // Commit the Auto/sunrise mode and the fixed source before leaving, so the mode
                // is not deselected when the fragment is recreated on return (issue #647).
                commitSunriseMode(applyAppTheme = false)
                findNavController().navigate(
                    R.id.action_darkModeFragment_to_mapPickerFragment,
                    androidx.core.os.bundleOf(MapPickerFragment.ARG_MODE to MapPickerFragment.MODE_POINT)
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener {
                val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(latInput.windowToken, 0)
            }
            .show()
    }

    private fun updateSettingsList() {
        val scrollState = recyclerView.layoutManager?.onSaveInstanceState()
        val items = mutableListOf<SettingItem>()

        // --- App Theme ---
        items.add(SettingItem.CategoryHeader("appTheme", R.string.app_theme))

        val appThemeTitles = resources.getStringArray(R.array.app_theme)
        items.add(SettingItem.SettingEntry(
            stableId = "appTheme",
            nameResId = R.string.app_theme,
            value = appThemeTitles[pendingAppTheme!!.value],
            onClick = { _ ->
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.change_app_theme)
                    .setSingleChoiceItems(appThemeTitles, pendingAppTheme!!.value) { dialog, which ->
                        pendingAppTheme = Settings.AppTheme.fromInt(which)
                        if (pendingAppTheme != Settings.AppTheme.LOCATION) restoreAppTheme = pendingAppTheme!!
                        // Reset useExtremeDarkMode for static modes
                        if (pendingAppTheme == Settings.AppTheme.CLEAR ||
                            pendingAppTheme == Settings.AppTheme.DARK ||
                            pendingAppTheme == Settings.AppTheme.EXTREME_DARK) {
                            pendingUseExtremeDarkMode = false
                        }
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        // "Use Extreme Dark" toggle for auto modes
        if (pendingAppTheme != Settings.AppTheme.CLEAR &&
            pendingAppTheme != Settings.AppTheme.DARK &&
            pendingAppTheme != Settings.AppTheme.EXTREME_DARK) {
            items.add(SettingItem.ToggleSettingEntry(
                stableId = "useExtremeDarkMode",
                nameResId = R.string.use_extreme_dark,
                descriptionResId = R.string.use_extreme_dark_description,
                isChecked = pendingUseExtremeDarkMode!!,
                onCheckedChanged = { isChecked ->
                    pendingUseExtremeDarkMode = isChecked
                    checkChanges()
                    updateSettingsList()
                }
            ))
        }

        // App theme sub-options: threshold slider for Light Sensor / Screen Brightness
        if (pendingAppTheme == Settings.AppTheme.LIGHT_SENSOR || pendingAppTheme == Settings.AppTheme.SCREEN_BRIGHTNESS) {
            val isSensor = pendingAppTheme == Settings.AppTheme.LIGHT_SENSOR
            val currentValue = if (isSensor) pendingAppThemeThresholdLux else pendingAppThemeThresholdBrightness
            val title = getString(if (isSensor) R.string.threshold_light_title else R.string.threshold_brightness_title)
            val hint = getString(if (isSensor) R.string.threshold_light_hint else R.string.threshold_brightness_hint)
            val currentReading = if (isSensor) {
                if (cachedLux >= 0) getString(R.string.current_light_reading, cachedLux.toInt()) else ""
            } else {
                if (cachedBrightness >= 0) getString(R.string.current_brightness_reading, cachedBrightness) else ""
            }
            val displayValue = if (isSensor) {
                val base = "${currentValue ?: 0} Lux"
                if (currentReading.isNotEmpty()) "$base ($currentReading)" else base
            } else {
                val base = "${currentValue ?: 0} / 255"
                if (currentReading.isNotEmpty()) "$base ($currentReading)" else base
            }

            items.add(SettingItem.SettingEntry(
                stableId = "appThemeThreshold",
                nameResId = if (isSensor) R.string.threshold_light_title else R.string.threshold_brightness_title,
                value = displayValue,
                onClick = { _ ->
                    if (isSensor) {
                        showLuxSliderDialog(
                            title = title,
                            message = hint,
                            initialLux = currentValue ?: 0,
                            currentReading = currentReading,
                            onConfirm = { newLux ->
                                pendingAppThemeThresholdLux = newLux
                                checkChanges()
                                updateSettingsList()
                            }
                        )
                    } else {
                        showSliderDialog(
                            title = title,
                            message = hint,
                            initialPercentage = currentValue ?: 100,
                            minLabel = "0",
                            maxLabel = "255",
                            formatValue = { v -> "$v" },
                            currentReading = currentReading,
                            sliderMax = 255,
                            onConfirm = { newVal ->
                                pendingAppThemeThresholdBrightness = newVal
                                checkChanges()
                                updateSettingsList()
                            }
                        )
                    }
                }
            ))

        }

        // App theme sub-options: time pickers for Manual Time
        if (pendingAppTheme == Settings.AppTheme.MANUAL_TIME) {
            val formatTime = { minutes: Int -> "%02d:%02d".format(minutes / 60, minutes % 60) }

            items.add(SettingItem.SettingEntry(
                stableId = "appThemeStart",
                nameResId = R.string.night_mode_start,
                value = formatTime(pendingAppThemeManualStart!!),
                onClick = { _ ->
                    TimePickerDialog(requireContext(), { _, hour, minute ->
                        pendingAppThemeManualStart = hour * 60 + minute
                        checkChanges()
                        updateSettingsList()
                    }, pendingAppThemeManualStart!! / 60, pendingAppThemeManualStart!! % 60, true).show()
                }
            ))

            items.add(SettingItem.SettingEntry(
                stableId = "appThemeEnd",
                nameResId = R.string.night_mode_end,
                value = formatTime(pendingAppThemeManualEnd!!),
                onClick = { _ ->
                    TimePickerDialog(requireContext(), { _, hour, minute ->
                        pendingAppThemeManualEnd = hour * 60 + minute
                        checkChanges()
                        updateSettingsList()
                    }, pendingAppThemeManualEnd!! / 60, pendingAppThemeManualEnd!! % 60, true).show()
                }
            ))
        }

        // App theme contextual sub-options (Location has its own group below)
        if (pendingAppTheme == Settings.AppTheme.AUTO_SUNRISE) addSunriseReference(items, "app")

        // --- Android Auto Night Mode ---
        items.add(SettingItem.CategoryHeader("aaNightMode", R.string.night_mode))

        // Night Mode (Android Auto)
        items.add(SettingItem.SettingEntry(
            stableId = "nightMode",
            nameResId = R.string.night_mode,
            value = run {
                val base = resources.getStringArray(R.array.night_mode)[pendingNightMode!!.value]
                if (pendingNightMode == Settings.NightMode.AUTO) {
                    val info = com.andrerinas.openheadunit.utils.NightMode(settings, true).getCalculationInfo()
                    "$base ($info)"
                } else {
                    base
                }
            },
            onClick = { _ ->
                val nightModeTitles = resources.getStringArray(R.array.night_mode)

                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.night_mode)
                    .setSingleChoiceItems(nightModeTitles, pendingNightMode!!.value) { dialog, which ->
                        pendingNightMode = Settings.NightMode.fromInt(which)!!
                        if (pendingNightMode != Settings.NightMode.LOCATION) restoreNightMode = pendingNightMode!!
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        // Night mode sub-options: threshold slider for Light Sensor / Screen Brightness
        if (pendingNightMode == Settings.NightMode.LIGHT_SENSOR || pendingNightMode == Settings.NightMode.SCREEN_BRIGHTNESS) {
            val isSensor = pendingNightMode == Settings.NightMode.LIGHT_SENSOR
            val currentValue = if (isSensor) pendingThresholdLux else pendingThresholdBrightness
            val title = getString(if (isSensor) R.string.threshold_light_title else R.string.threshold_brightness_title)
            val hint = getString(if (isSensor) R.string.threshold_light_hint else R.string.threshold_brightness_hint)
            val nmCurrentReading = if (isSensor) {
                if (cachedLux >= 0) getString(R.string.current_light_reading, cachedLux.toInt()) else ""
            } else {
                if (cachedBrightness >= 0) getString(R.string.current_brightness_reading, cachedBrightness) else ""
            }
            val displayValue = if (isSensor) {
                val base = "${currentValue ?: 0} Lux"
                if (nmCurrentReading.isNotEmpty()) "$base ($nmCurrentReading)" else base
            } else {
                val base = "${currentValue ?: 0} / 255"
                if (nmCurrentReading.isNotEmpty()) "$base ($nmCurrentReading)" else base
            }

            items.add(SettingItem.SettingEntry(
                stableId = "nightModeThreshold",
                nameResId = if (isSensor) R.string.threshold_light_title else R.string.threshold_brightness_title,
                value = displayValue,
                onClick = { _ ->
                    if (isSensor) {
                        showLuxSliderDialog(
                            title = title,
                            message = hint,
                            initialLux = currentValue ?: 0,
                            currentReading = nmCurrentReading,
                            onConfirm = { newLux ->
                                pendingThresholdLux = newLux
                                checkChanges()
                                updateSettingsList()
                            }
                        )
                    } else {
                        showSliderDialog(
                            title = title,
                            message = hint,
                            initialPercentage = currentValue ?: 100,
                            minLabel = "0",
                            maxLabel = "255",
                            formatValue = { v -> "$v" },
                            currentReading = nmCurrentReading,
                            sliderMax = 255,
                            onConfirm = { newVal ->
                                pendingThresholdBrightness = newVal
                                checkChanges()
                                updateSettingsList()
                            }
                        )
                    }
                }
            ))

        }

        // Night mode sub-options: time pickers for Manual Time
        if (pendingNightMode == Settings.NightMode.MANUAL_TIME) {
            val formatTime = { minutes: Int -> "%02d:%02d".format(minutes / 60, minutes % 60) }

            items.add(SettingItem.SettingEntry(
                stableId = "nightModeStart",
                nameResId = R.string.night_mode_start,
                value = formatTime(pendingManualStart!!),
                onClick = { _ ->
                    TimePickerDialog(requireContext(), { _, hour, minute ->
                        pendingManualStart = hour * 60 + minute
                        checkChanges()
                        updateSettingsList()
                    }, pendingManualStart!! / 60, pendingManualStart!! % 60, true).show()
                }
            ))

            items.add(SettingItem.SettingEntry(
                stableId = "nightModeEnd",
                nameResId = R.string.night_mode_end,
                value = formatTime(pendingManualEnd!!),
                onClick = { _ ->
                    TimePickerDialog(requireContext(), { _, hour, minute ->
                        pendingManualEnd = hour * 60 + minute
                        checkChanges()
                        updateSettingsList()
                    }, pendingManualEnd!! / 60, pendingManualEnd!! % 60, true).show()
                }
            ))
        }

        // Android Auto sunrise reference (avoid duplicating when app theme already shows it).
        if (pendingNightMode == Settings.NightMode.AUTO && pendingAppTheme != Settings.AppTheme.AUTO_SUNRISE) {
            addSunriseReference(items, "aa")
        }

        // AA Monochrome toggle — hidden when Night Mode is DAY
        if (pendingNightMode != Settings.NightMode.DAY) {
            items.add(SettingItem.ToggleSettingEntry(
                stableId = "aaMonochrome",
                nameResId = R.string.aa_monochrome,
                descriptionResId = R.string.aa_monochrome_description,
                isChecked = pendingAaMonochromeEnabled!!,
                onCheckedChanged = { isChecked ->
                    if (isChecked && pendingViewMode != Settings.ViewMode.GLES) {
                        // Set to true so the submitted list matches the visual switch state.
                        // This way DiffUtil can detect the revert to false on cancel.
                        pendingAaMonochromeEnabled = true
                        updateSettingsList()
                        // Show GLES required dialog
                        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                            .setTitle(R.string.gles_required_title)
                            .setMessage(R.string.gles_required_message)
                            .setPositiveButton(R.string.enable_gles) { _, _ ->
                                pendingViewMode = Settings.ViewMode.GLES
                                pendingAaMonochromeEnabled = true
                                checkChanges()
                                updateSettingsList()
                            }
                            .setNegativeButton(R.string.cancel) { _, _ ->
                                pendingAaMonochromeEnabled = false
                                checkChanges()
                                updateSettingsList()
                            }
                            .setOnCancelListener {
                                pendingAaMonochromeEnabled = false
                                checkChanges()
                                updateSettingsList()
                            }
                            .show()
                    } else {
                        pendingAaMonochromeEnabled = isChecked
                        checkChanges()
                        updateSettingsList()
                    }
                }
            ))

            // Desaturation slider — only visible when AA monochrome is ON
            if (pendingAaMonochromeEnabled == true) {
                items.add(SettingItem.SliderSettingEntry(
                    stableId = "aaDesaturation",
                    nameResId = R.string.aa_desaturation,
                    value = "${pendingAaDesaturationLevel}%",
                    sliderValue = pendingAaDesaturationLevel!!.toFloat(),
                    valueFrom = 0f,
                    valueTo = 100f,
                    stepSize = 0f,
                    onValueChanged = { value ->
                        pendingAaDesaturationLevel = value.toInt()
                        checkChanges()
                        updateSettingsList()
                    }
                ))
            }
        }


        // --- Location group (shown when either selector uses Location) ---
        if (pendingAppTheme == Settings.AppTheme.LOCATION || pendingNightMode == Settings.NightMode.LOCATION) {
            addLocationGroup(items)
        }

        settingsAdapter.submitList(items) {
            scrollState?.let { recyclerView.layoutManager?.onRestoreInstanceState(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (lightSensor != null) {
            sensorManager?.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }

        // Observe screen brightness changes so the threshold dialog/list show a live value
        cachedBrightness = readBrightness()
        val cr = requireContext().contentResolver
        cr.registerContentObserver(
            SystemSettings.System.getUriFor(SystemSettings.System.SCREEN_BRIGHTNESS),
            false, brightnessObserver
        )
        if (Build.VERSION.SDK_INT >= 28) {
            cr.registerContentObserver(
                SystemSettings.System.getUriFor("screen_brightness_float"),
                false, brightnessObserver
            )
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
        try { context?.contentResolver?.unregisterContentObserver(brightnessObserver) } catch (_: Exception) {}
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    private fun readBrightness(): Int {
        val ctx = context ?: return -1
        val cr = ctx.contentResolver
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                val f = SystemSettings.System.getFloat(cr, "screen_brightness_float")
                if (!f.isNaN()) return (f * 255f).toInt().coerceIn(0, 255)
            } catch (_: Exception) { /* fall through */ }
        }
        return try {
            SystemSettings.System.getInt(cr, SystemSettings.System.SCREEN_BRIGHTNESS)
                .coerceIn(0, 255)
        } catch (_: Exception) { -1 }
    }


    private fun scheduleListRefresh() {
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshHandler.postDelayed(refreshRunnable, 500)
    }

    private fun showSliderDialog(
        title: String,
        message: String,
        initialPercentage: Int,
        minLabel: String,
        maxLabel: String,
        formatValue: (Int) -> String,
        currentReading: String = "",
        sliderMax: Int = 100,
        onConfirm: (Int) -> Unit
    ) {
        val context = requireContext()
        val density = context.resources.displayMetrics.density
        val padding = (24 * density).toInt()

        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(padding, (8 * density).toInt(), padding, 0)
        }

        val hint = android.widget.TextView(context).apply {
            text = message
            textSize = 14f
                setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
        }
        layout.addView(hint)

        val label = android.widget.TextView(context).apply {
            text = formatValue(initialPercentage.coerceIn(0, sliderMax))
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            val topMargin = (16 * density).toInt()
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = topMargin
            layoutParams = lp
        }
        layout.addView(label)

        val seekBar = android.widget.SeekBar(context).apply {
            max = sliderMax
            progress = initialPercentage.coerceIn(0, sliderMax)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    label.text = formatValue(progress)
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })
        }
        layout.addView(seekBar)

        val rangeRow = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = lp
        }
        val minText = android.widget.TextView(context).apply {
            text = minLabel
            textSize = 12f
                setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            val lp = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }
        val maxText = android.widget.TextView(context).apply {
            text = maxLabel
            textSize = 12f
            gravity = android.view.Gravity.END
                setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            val lp = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }
        rangeRow.addView(minText)
        rangeRow.addView(maxText)
        layout.addView(rangeRow)

        if (currentReading.isNotEmpty()) {
            val readingLabel = android.widget.TextView(context).apply {
                text = currentReading
                textSize = 16f
                gravity = android.view.Gravity.CENTER
                    setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = (16 * density).toInt()
                layoutParams = lp
            }
            layout.addView(readingLabel)
        }

        MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                onConfirm(seekBar.progress)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                dialog.cancel()
            }
            .show()
    }

    private fun showLuxSliderDialog(
        title: String,
        message: String,
        initialLux: Int,
        currentReading: String = "",
        onConfirm: (Int) -> Unit
    ) {
        val context = requireContext()
        val density = context.resources.displayMetrics.density
        val padding = (24 * density).toInt()

        var currentMax = if (initialLux <= LUX_MAX_FINE) LUX_MAX_FINE else LUX_MAX

        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(padding, (8 * density).toInt(), padding, 0)
        }

        val hint = android.widget.TextView(context).apply {
            text = message
            textSize = 14f
                setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
        }
        layout.addView(hint)

        val label = android.widget.TextView(context).apply {
            text = "$initialLux Lux"
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (16 * density).toInt()
            layoutParams = lp
        }
        layout.addView(label)

        val seekBar = android.widget.SeekBar(context).apply {
            max = currentMax
            progress = initialLux.coerceIn(0, currentMax)
        }

        val minText = android.widget.TextView(context).apply {
            text = "0 Lux"
            textSize = 12f
                setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val maxText = android.widget.TextView(context).apply {
            text = "${currentMax} Lux"
            textSize = 12f
            gravity = android.view.Gravity.END
                setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                label.text = "$progress Lux"
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
        layout.addView(seekBar)

        val rangeRow = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        rangeRow.addView(minText)
        rangeRow.addView(maxText)
        layout.addView(rangeRow)

        val checkBox = android.widget.CheckBox(context).apply {
            text = getString(R.string.enable_fine_lux_control)
            isChecked = currentMax == LUX_MAX_FINE
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (12 * density).toInt()
            layoutParams = lp
        }
        checkBox.setOnCheckedChangeListener { _, isChecked ->
            val oldProgress = seekBar.progress
            currentMax = if (isChecked) LUX_MAX_FINE else LUX_MAX
            seekBar.max = currentMax
            seekBar.progress = oldProgress.coerceIn(0, currentMax)
            maxText.text = "${currentMax} Lux"
            label.text = "${seekBar.progress} Lux"
        }
        layout.addView(checkBox)

        if (currentReading.isNotEmpty()) {
            val readingLabel = android.widget.TextView(context).apply {
                text = currentReading
                textSize = 16f
                gravity = android.view.Gravity.CENTER
                    setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = (16 * density).toInt()
                layoutParams = lp
            }
            layout.addView(readingLabel)
        }

        MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                onConfirm(seekBar.progress)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                dialog.cancel()
            }
            .show()
    }

    companion object {
        private const val LUX_MAX = 10000
        private const val LUX_MAX_FINE = 100
    }
}
