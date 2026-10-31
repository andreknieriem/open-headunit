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
import com.andrerinas.openheadunit.main.settings.SettingHelpDialog
import com.andrerinas.openheadunit.utils.AnnouncedDensityPolicy
import com.andrerinas.openheadunit.utils.OnboardingDisplayPolicy
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.SystemOptimizer
import com.andrerinas.openheadunit.utils.ToastUtils
import com.andrerinas.openheadunit.view.DpiPickerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Sub-screen to choose the Android Auto DPI with a slider, a live graphic and
 * Small/Medium/Large tabs, plus an Automatic switch (0 = the density computed for the codec size).
 * It saves on its own Save button and also hands the value back to SettingsFragment so the
 * main list stays in sync. DPI takes effect on the next Android Auto connection.
 */
class DpiSettingsFragment : Fragment() {

    private val settings by lazy { App.provide(requireContext()).settings }
    private lateinit var toolbar: MaterialToolbar
    private lateinit var picker: DpiPickerView
    private lateinit var autoSwitch: SwitchMaterial
    private lateinit var autoDesc: TextView
    private lateinit var widescreenHint: TextView
    private lateinit var overPanelWarning: TextView
    private lateinit var clampedNote: TextView
    private lateinit var overrideButton: MaterialButton
    private var saveButton: MaterialButton? = null

    private var initialValue = 0
    private var initialOverride = false
    private var pendingOverride = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_dpi_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        toolbar = view.findViewById(R.id.toolbar)
        picker = view.findViewById(R.id.dpi_picker)
        autoSwitch = view.findViewById(R.id.dpi_auto_switch)
        autoDesc = view.findViewById(R.id.dpi_auto_desc)
        widescreenHint = view.findViewById(R.id.dpi_widescreen_hint)
        overPanelWarning = view.findViewById(R.id.dpi_over_panel_warning)
        clampedNote = view.findViewById(R.id.dpi_clamped_note)
        overrideButton = view.findViewById(R.id.dpi_portrait_override_button)
        overrideButton.setOnClickListener {
            pendingOverride = !pendingOverride
            updateSaveButtonState()
            updateClampedNote()
        }

        setupToolbar()

        // Show which DPI "Automatic" actually sends, and the dp canvas it gives.
        val p = previewResolution()
        val (pw, ph, resName) = Triple(p.w, p.h, p.name)
        val auto0 = preview(0)
        val (cw, ch) = AnnouncedDensityPolicy.phoneCanvas(pw, ph, p.par)
        autoDesc.text = getString(
            R.string.dpi_automatic_desc_canvas, auto0.announced, resName,
            AnnouncedDensityPolicy.canvasDp(cw, auto0.announced), AnnouncedDensityPolicy.canvasDp(ch, auto0.announced)
        )

        val wide = AnnouncedDensityPolicy.widescreenHintDpi(cw, ch)
        if (wide > 0) {
            widescreenHint.text = getString(R.string.dpi_widescreen_hint, wide)
            widescreenHint.visibility = View.VISIBLE
        } else {
            widescreenHint.visibility = View.GONE
        }

        // If the chosen resolution exceeds the panel, the DPI is computed for the capped resolution
        // the device will actually project, so flag it (issue #767).
        updateOverPanelWarning()

        initialValue = settings.dpiPixelDensity
        initialOverride = settings.dpiFloorOverride
        pendingOverride = initialOverride
        val auto = initialValue == 0
        autoSwitch.isChecked = auto
        picker.dpi = if (auto) auto0.announced else initialValue
        picker.visibility = if (auto) View.GONE else View.VISIBLE

        autoSwitch.setOnCheckedChangeListener { _, checked ->
            picker.visibility = if (checked) View.GONE else View.VISIBLE
            if (checked) picker.stopDemo() else picker.startDemo()
            updateSaveButtonState()
            updateClampedNote()
        }
        picker.setOnDpiChanged { updateSaveButtonState(); updateClampedNote() }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { handleBackPress() }
        })

        updateSaveButtonState()
        updateClampedNote()

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
        SettingHelpDialog.addToolbarHelp(toolbar, toolbar.title, R.string.dpi_screen_help, "dpi")
        val saveItem = toolbar.menu.add(0, SAVE_ITEM_ID, 1, getString(R.string.save))
        saveItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        saveItem.setActionView(R.layout.layout_save_button)
        saveButton = saveItem.actionView?.findViewById(R.id.save_button_widget)
        saveButton?.setOnClickListener { saveSettings() }
    }

    /** 0 = Automatic (device density), otherwise the chosen DPI. */
    private fun currentValue(): Int = if (autoSwitch.isChecked) 0 else picker.dpi

    private fun hasChanges(): Boolean = currentValue() != initialValue || pendingOverride != initialOverride

    private fun updateSaveButtonState() {
        val changed = hasChanges()
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
        if (pendingOverride != initialOverride) settings.dpiFloorOverride = pendingOverride
        settings.commit()
        initialValue = value
        initialOverride = pendingOverride
        updateSaveButtonState()
        ToastUtils.showToast(requireContext(), R.string.settings_saved, Toast.LENGTH_SHORT, force = true)
    }

    private fun handleBackPress() {
        // So the unsaved-changes prompt weighs the value the user typed, not the one it replaced.
        picker.commitPendingInput()
        if (hasChanges()) {
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

    private data class Preview(val w: Int, val h: Int, val name: String, val par: Int, val panelW: Int, val panelH: Int)

    /** The visible size Android Auto gets at the stored resolution, its name and the pixel aspect ratio. */
    private fun previewResolution(): Preview {
        val m = realMetrics()
        val chosen = Settings.Resolution.fromId(settings.resolutionId)
        val r = if (chosen == null || chosen == Settings.Resolution.AUTO) {
            SystemOptimizer.recommendedResolution(m.widthPixels, m.heightPixels)
        } else chosen
        val (pw, ph) = AnnouncedDensityPolicy.orientPanel(
            m.widthPixels, m.heightPixels, AnnouncedDensityPolicy.projectsPortrait(settings.screenOrientation)
        )
        val (w, h) = AnnouncedDensityPolicy.visibleSize(settings.videoFitMode, pw, ph, r.width, r.height)
        val par = AnnouncedDensityPolicy.pixelAspectE4(
            settings.videoFitMode, pw, ph, r.width, r.height, settings.pixelAspectRatioE4
        )
        return Preview(w, h, r.resName, par, pw, ph)
    }

    private fun preview(value: Int, override: Boolean = pendingOverride): AnnouncedDensityPolicy.Decision {
        val p = previewResolution()
        val preset = OnboardingDisplayPolicy.initialPreset(
            settings.displaySizePreset, SystemOptimizer.DisplaySizePreset.SMALL_7_8
        )
        return AnnouncedDensityPolicy.decide(value, preset, p.w, p.h, p.par, override, p.panelW, p.panelH)
    }

    /** Says so while the picked value is lower on the wire than on the slider, or sent past a floor. */
    private fun updateClampedNote() {
        val picked = picker.dpi
        val d = preview(picked)
        val reason = if (autoSwitch.isChecked) null else d.reason
        val floor = preview(picked, false).announced
        val portrait = previewResolution().let { it.h > it.w }
        val note = when {
            reason == AnnouncedDensityPolicy.Reason.FLOOR_OVERRIDE -> getString(
                if (portrait) R.string.dpi_portrait_override_note else R.string.dpi_floor_override_note, picked, floor
            )
            reason == AnnouncedDensityPolicy.Reason.PORTRAIT_FLOOR ->
                getString(R.string.dpi_clamped_note_portrait, picked, d.announced)
            reason != null && d.announced != picked -> getString(R.string.dpi_clamped_note, picked, d.announced)
            else -> null
        }
        clampedNote.text = note ?: ""
        clampedNote.visibility = if (note != null) View.VISIBLE else View.GONE
        overrideButton.setText(
            if (reason == AnnouncedDensityPolicy.Reason.FLOOR_OVERRIDE) R.string.resolution_use_recommended
            else R.string.resolution_use_anyway
        )
        overrideButton.visibility = if (note != null) View.VISIBLE else View.GONE
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
        private const val SAVE_ITEM_ID = 1001
    }
}
