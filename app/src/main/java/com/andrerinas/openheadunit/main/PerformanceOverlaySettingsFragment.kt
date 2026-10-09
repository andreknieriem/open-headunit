package com.andrerinas.openheadunit.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.main.settings.SettingItem
import com.andrerinas.openheadunit.main.settings.SettingsAdapter
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.view.PerformanceOverlayField
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class PerformanceOverlaySettingsFragment : Fragment() {
    private lateinit var settings: Settings
    private lateinit var recyclerView: RecyclerView
    private lateinit var settingsAdapter: SettingsAdapter
    private lateinit var toolbar: MaterialToolbar
    private var saveButton: MaterialButton? = null

    private var pendingShow = false
    private var pendingFields = emptySet<PerformanceOverlayField>()
    private var pendingPosition = Settings.OverlayPosition.LEFT

    private val hasChanges: Boolean
        get() = pendingShow != settings.showPerformanceOverlay ||
            pendingFields != settings.overlayFields ||
            pendingPosition != settings.overlayPosition

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_performance_overlay_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = App.provide(requireContext()).settings
        pendingShow = settings.showPerformanceOverlay
        pendingFields = settings.overlayFields
        pendingPosition = settings.overlayPosition

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

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener { handleBackPress() }

        val saveItem = toolbar.menu.add(0, SAVE_ITEM_ID, 0, getString(R.string.save))
        saveItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        saveItem.setActionView(R.layout.layout_save_button)
        saveButton = saveItem.actionView?.findViewById(R.id.save_button_widget)
        saveButton?.setOnClickListener { saveSettings() }
        updateSaveButtonState()
    }

    private fun handleBackPress() {
        if (hasChanges) {
            MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                .setTitle(R.string.unsaved_changes)
                .setMessage(R.string.unsaved_changes_message)
                .setPositiveButton(R.string.discard) { _, _ -> navigateBack() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            navigateBack()
        }
    }

    private fun navigateBack() {
        try {
            if (!findNavController().navigateUp()) requireActivity().finish()
        } catch (e: Exception) {
            requireActivity().finish()
        }
    }

    private fun updateSaveButtonState() {
        saveButton?.isEnabled = hasChanges
    }

    private fun saveSettings() {
        if (pendingShow != settings.showPerformanceOverlay) settings.showPerformanceOverlay = pendingShow
        if (pendingFields != settings.overlayFields) settings.overlayFields = pendingFields
        if (pendingPosition != settings.overlayPosition) settings.overlayPosition = pendingPosition
        navigateBack()
    }

    private fun fieldRow(field: PerformanceOverlayField, nameResId: Int) = SettingItem.ToggleSettingEntry(
        stableId = "overlayField${field.name}",
        nameResId = nameResId,
        descriptionResId = null,
        isChecked = field in pendingFields,
        isEnabled = pendingShow,
        onCheckedChanged = { isChecked ->
            pendingFields = if (isChecked) pendingFields + field else pendingFields - field
            updateSaveButtonState()
            updateSettingsList()
        }
    )

    private fun updateSettingsList() {
        val items = mutableListOf<SettingItem>()

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "showPerformanceOverlay",
            nameResId = R.string.show_performance_overlay,
            descriptionResId = R.string.show_performance_overlay_description,
            isChecked = pendingShow,
            onCheckedChanged = { isChecked ->
                pendingShow = isChecked
                updateSaveButtonState()
                updateSettingsList()
            }
        ))
        items.add(fieldRow(PerformanceOverlayField.FPS, R.string.overlay_field_fps))
        items.add(fieldRow(PerformanceOverlayField.CPU, R.string.overlay_field_cpu))
        items.add(fieldRow(PerformanceOverlayField.TEMP, R.string.overlay_field_temperature))
        items.add(fieldRow(PerformanceOverlayField.FRAME, R.string.overlay_field_frame_age))

        // The overlay sits in a top corner, and on a panel with an OEM bar that corner is covered.
        val overlayPositions = arrayOf(getString(R.string.margin_left), getString(R.string.margin_right))
        items.add(SettingItem.SettingEntry(
            stableId = "overlayPosition",
            nameResId = R.string.overlay_position,
            value = overlayPositions.getOrElse(pendingPosition.value) { "" },
            isEnabled = pendingShow,
            onClick = { _ ->
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.overlay_position)
                    .setSingleChoiceItems(overlayPositions, pendingPosition.value) { dialog, which ->
                        Settings.OverlayPosition.fromInt(which)?.let { pendingPosition = it }
                        updateSaveButtonState()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        settingsAdapter.submitList(items)
    }

    private companion object {
        const val SAVE_ITEM_ID = 1001
    }
}
