package com.andrerinas.openheadunit.main

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.SystemOptimizer
import com.andrerinas.openheadunit.view.DpiPickerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Sub-screen to choose the Android Auto DPI with a slider, a live graphic and
 * Small/Medium/Large tabs, plus an Automatic switch (0 = use the device density).
 * It saves on its own Save button and also hands the value back to SettingsFragment so the
 * main list stays in sync. DPI takes effect on the next Android Auto connection.
 */
class DpiSettingsFragment : Fragment() {

    private val settings by lazy { App.provide(requireContext()).settings }
    private lateinit var toolbar: MaterialToolbar
    private lateinit var picker: DpiPickerView
    private lateinit var autoSwitch: SwitchMaterial
    private lateinit var autoDesc: TextView
    private lateinit var overPanelWarning: TextView
    private var saveButton: MaterialButton? = null

    private var initialValue = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_dpi_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        toolbar = view.findViewById(R.id.toolbar)
        picker = view.findViewById(R.id.dpi_picker)
        autoSwitch = view.findViewById(R.id.dpi_auto_switch)
        autoDesc = view.findViewById(R.id.dpi_auto_desc)
        overPanelWarning = view.findViewById(R.id.dpi_over_panel_warning)

        setupToolbar()

        // Show which DPI "Automatic" actually uses (the device's own density).
        val detected = detectedDensityDpi()
        autoDesc.text = getString(R.string.dpi_automatic_desc_format, detected)

        // If the chosen resolution exceeds the panel, the DPI is computed for the capped resolution
        // the device will actually project, so flag it (issue #767).
        updateOverPanelWarning()

        initialValue = settings.dpiPixelDensity
        val auto = initialValue == 0
        autoSwitch.isChecked = auto
        picker.dpi = if (auto) detected else initialValue
        picker.visibility = if (auto) View.GONE else View.VISIBLE

        autoSwitch.setOnCheckedChangeListener { _, checked ->
            picker.visibility = if (checked) View.GONE else View.VISIBLE
            if (checked) picker.stopDemo() else picker.startDemo()
            updateSaveButtonState()
        }
        picker.setOnDpiChanged { updateSaveButtonState() }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { handleBackPress() }
        })

        updateSaveButtonState()

        // Show the animated demo right away when the picker is visible (Automatic off).
        if (!auto) picker.startDemo()
    }

    override fun onPause() {
        super.onPause()
        if (::picker.isInitialized) picker.stopDemo()
    }

    override fun onResume() {
        super.onResume()
        if (::picker.isInitialized && !autoSwitch.isChecked) picker.startDemo()
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener { handleBackPress() }
        val saveItem = toolbar.menu.add(0, SAVE_ITEM_ID, 0, getString(R.string.save))
        saveItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        saveItem.setActionView(R.layout.layout_save_button)
        saveButton = saveItem.actionView?.findViewById(R.id.save_button_widget)
        saveButton?.setOnClickListener { saveSettings() }
    }

    /** 0 = Automatic (device density), otherwise the chosen DPI. */
    private fun currentValue(): Int = if (autoSwitch.isChecked) 0 else picker.dpi

    private fun updateSaveButtonState() {
        val changed = currentValue() != initialValue
        saveButton?.isEnabled = changed
        // DPI only applies on the next Android Auto connection.
        saveButton?.text = getString(if (changed) R.string.save_and_restart else R.string.save)
    }

    private fun saveSettings() {
        // Save lives in the toolbar and takes no focus, so a value typed into the picker is still
        // sitting in the text field at this point.
        picker.commitPendingInput()
        val value = currentValue()
        settings.dpiPixelDensity = value
        settings.commit()
        // Keep SettingsFragment's pending value in sync so it does not overwrite this on save.
        findNavController().previousBackStackEntry?.savedStateHandle?.set(KEY_DPI_RESULT, value)
        initialValue = value
        updateSaveButtonState()
        Toast.makeText(requireContext(), R.string.settings_saved, Toast.LENGTH_SHORT).show()
    }

    private fun handleBackPress() {
        // So the unsaved-changes prompt weighs the value the user typed, not the one it replaced.
        picker.commitPendingInput()
        if (currentValue() != initialValue) {
            MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                .setTitle(R.string.unsaved_changes)
                .setMessage(R.string.unsaved_changes_message)
                .setPositiveButton(R.string.discard) { _, _ -> findNavController().navigateUp() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            findNavController().navigateUp()
        }
    }

    private fun detectedDensityDpi(): Int {
        val m = realMetrics()
        return if (m.densityDpi > 0) m.densityDpi else 160
    }

    private fun realMetrics(): DisplayMetrics {
        val m = DisplayMetrics()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                requireContext().display?.getRealMetrics(m)
            } else {
                @Suppress("DEPRECATION")
                (requireContext().getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(m)
            }
        } catch (_: Exception) {
        }
        return m
    }

    /** Warn (in red) when the chosen resolution is higher than the panel: the DPI is then computed
     * for the capped resolution the device actually projects, not the one the user picked. */
    private fun updateOverPanelWarning() {
        val m = realMetrics()
        val ceiling = SystemOptimizer.recommendedResolution(m.widthPixels, m.heightPixels)
        val chosen = Settings.Resolution.fromId(settings.resolutionId)
        val over = m.widthPixels > 0 && chosen != null && chosen != Settings.Resolution.AUTO &&
            (chosen.width > ceiling.width || chosen.height > ceiling.height)
        if (over) {
            overPanelWarning.text =
                getString(R.string.dpi_resolution_over_panel_warning, chosen!!.resName, ceiling.resName)
            overPanelWarning.visibility = View.VISIBLE
        } else {
            overPanelWarning.visibility = View.GONE
        }
    }

    companion object {
        const val KEY_DPI_RESULT = "dpi_result"
        private const val SAVE_ITEM_ID = 1001
    }
}
