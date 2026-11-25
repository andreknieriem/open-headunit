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
import com.andrerinas.openheadunit.utils.DisplayLayoutPolicy
import com.andrerinas.openheadunit.utils.OnboardingDisplayPolicy
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.SystemOptimizer
import com.andrerinas.openheadunit.utils.ToastUtils
import com.andrerinas.openheadunit.view.DpiLayoutPickerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Sub-screen to choose the Android Auto layout, then its text size: a size step, a slider or Auto
 * (0 = the top density of the picked layout). It saves on its own Save button.
 * DPI takes effect on the next Android Auto connection.
 */
class DpiSettingsFragment : Fragment() {

    private val settings by lazy { App.provide(requireContext()).settings }
    private lateinit var toolbar: MaterialToolbar
    private lateinit var picker: DpiLayoutPickerView
    private lateinit var overPanelWarning: TextView
    private lateinit var clampedNote: TextView
    private lateinit var overrideButton: MaterialButton
    private var saveButton: MaterialButton? = null

    private var initialValue = 0
    private var initialLayout = ""
    private var initialOverride = false
    private var pendingOverride = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_dpi_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        toolbar = view.findViewById(R.id.toolbar)
        picker = view.findViewById(R.id.dpi_picker)
        overPanelWarning = view.findViewById(R.id.dpi_over_panel_warning)
        clampedNote = view.findViewById(R.id.dpi_clamped_note)
        overrideButton = view.findViewById(R.id.dpi_portrait_override_button)
        overrideButton.setOnClickListener {
            pendingOverride = !pendingOverride
            updateSaveButtonState()
            updateClampedNote()
        }

        setupToolbar()

        // If the chosen resolution exceeds the panel, the DPI is computed for the capped resolution
        // the device will actually project, so flag it (issue #767).
        updateOverPanelWarning()

        initialValue = settings.dpiPixelDensity
        initialLayout = settings.dpiAutoLayout
        initialOverride = settings.dpiFloorOverride
        pendingOverride = initialOverride
        configurePicker()
        picker.setOnPickChanged { updateSaveButtonState(); updateClampedNote() }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { handleBackPress() }
        })

        updateSaveButtonState()
        updateClampedNote()
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

    /** Opens on Auto in the stored layout; with no reachable one stored, on what the wire sends today, untouched. */
    private fun configurePicker() {
        val p = previewResolution()
        val (cw, ch) = AnnouncedDensityPolicy.phoneCanvas(p.w, p.h, p.par)
        val clamp = AnnouncedDensityPolicy.clampDpi(p.w, p.h, p.par)
        val choices = DisplayLayoutPolicy.choices(cw, ch, clamp)
        val stored = settings.dpiAutoLayoutPane?.takeIf { pane -> choices.any { it.pane == pane } }
        val storedDpi = settings.dpiPixelDensity
        val openDpi = if (storedDpi == 0 && stored == null) preview(0, false, null).announced else storedDpi
        picker.configure(cw, ch, clamp, p.h > p.w, openDpi, stored, fresh = true)
    }

    private fun writes(): DisplayLayoutPolicy.Writes =
        DisplayLayoutPolicy.writes(
            picker.pick, picker.hasUserInteracted, false, initialValue, initialLayout, picker.reachablePanes
        )

    private fun hasChanges(): Boolean =
        writes().let { it.dpi != null || it.layout != null } || pendingOverride != initialOverride

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
        val w = writes()
        w.dpi?.let { settings.dpiPixelDensity = it; initialValue = it }
        w.layout?.let { settings.dpiAutoLayout = it; initialLayout = it }
        if (pendingOverride != initialOverride) settings.dpiFloorOverride = pendingOverride
        settings.commit()
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

    private fun preview(
        value: Int, override: Boolean = pendingOverride, layout: DisplayLayoutPolicy.Pane? = settings.dpiAutoLayoutPane
    ): AnnouncedDensityPolicy.Decision {
        val p = previewResolution()
        val preset = OnboardingDisplayPolicy.initialPreset(
            settings.displaySizePreset, SystemOptimizer.DisplaySizePreset.SMALL_7_8
        )
        return AnnouncedDensityPolicy.decide(
            value, preset, p.w, p.h, p.par, override, p.panelW, p.panelH, layout
        )
    }

    /** Landscape only: says so while the picked value is lower on the wire than on the slider, or sent past the floor. */
    private fun updateClampedNote() {
        val pick = picker.pick
        val picked = (pick as? DisplayLayoutPolicy.Pick.Manual)?.dpi
        val portrait = previewResolution().let { it.h > it.w }
        if (picked == null || portrait) {
            clampedNote.visibility = View.GONE
            overrideButton.visibility = View.GONE
            return
        }
        val d = preview(picked)
        val floor = preview(picked, false).announced
        val note = when {
            d.reason == AnnouncedDensityPolicy.Reason.FLOOR_OVERRIDE -> getString(R.string.dpi_floor_override_note, picked, floor)
            d.announced != picked -> getString(R.string.dpi_clamped_note, picked, d.announced)
            else -> null
        }
        clampedNote.text = note ?: ""
        clampedNote.visibility = if (note != null) View.VISIBLE else View.GONE
        overrideButton.setText(
            if (d.reason == AnnouncedDensityPolicy.Reason.FLOOR_OVERRIDE) R.string.resolution_use_recommended
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
