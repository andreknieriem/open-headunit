package com.andrerinas.openheadunit.main

import android.os.Bundle
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
import com.andrerinas.openheadunit.main.settings.SettingItem
import com.andrerinas.openheadunit.main.settings.SettingsAdapter
import com.andrerinas.openheadunit.utils.Settings
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class VehicleInfoFragment : Fragment() {
    private lateinit var settings: Settings
    private lateinit var recyclerView: RecyclerView
    private lateinit var settingsAdapter: SettingsAdapter
    private lateinit var toolbar: MaterialToolbar
    private var saveButton: MaterialButton? = null

    private var pendingVehicleDisplayName: String? = null
    private var pendingVehicleMake: String? = null
    private var pendingVehicleModel: String? = null
    private var pendingVehicleYear: String? = null
    private var pendingVehicleId: String? = null
    private var pendingRightHandDrive: Boolean? = null
    private var pendingHeadUnitMake: String? = null
    private var pendingHeadUnitModel: String? = null

    private var hasChanges = false
    private val SAVE_ITEM_ID = 1001

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_vehicle_info, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        settings = App.provide(requireContext()).settings

        pendingVehicleDisplayName = settings.vehicleDisplayName
        pendingVehicleMake = settings.vehicleMake
        pendingVehicleModel = settings.vehicleModel
        pendingVehicleYear = settings.vehicleYear
        pendingVehicleId = settings.vehicleId
        pendingRightHandDrive = settings.rightHandDrive
        pendingHeadUnitMake = settings.headUnitMake
        pendingHeadUnitModel = settings.headUnitModel

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
        saveButton?.text = getString(R.string.save)
    }

    private fun saveSettings() {
        pendingVehicleDisplayName?.let { settings.vehicleDisplayName = it }
        pendingVehicleMake?.let { settings.vehicleMake = it }
        pendingVehicleModel?.let { settings.vehicleModel = it }
        pendingVehicleYear?.let { settings.vehicleYear = it }
        pendingVehicleId?.let { settings.vehicleId = it }
        pendingRightHandDrive?.let { settings.rightHandDrive = it }
        pendingHeadUnitMake?.let { settings.headUnitMake = it }
        pendingHeadUnitModel?.let { settings.headUnitModel = it }

        hasChanges = false
        updateSaveButtonState()

        Toast.makeText(context, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
    }

    private fun checkChanges() {
        hasChanges = pendingVehicleDisplayName != settings.vehicleDisplayName ||
                pendingVehicleMake != settings.vehicleMake ||
                pendingVehicleModel != settings.vehicleModel ||
                pendingVehicleYear != settings.vehicleYear ||
                pendingVehicleId != settings.vehicleId ||
                pendingRightHandDrive != settings.rightHandDrive ||
                pendingHeadUnitMake != settings.headUnitMake ||
                pendingHeadUnitModel != settings.headUnitModel

        updateSaveButtonState()
    }

    private fun updateSettingsList() {
        val scrollState = recyclerView.layoutManager?.onSaveInstanceState()
        val items = mutableListOf<SettingItem>()

        items.add(SettingItem.InfoBanner(
            stableId = "vehicleInfoRestartNote",
            textResId = R.string.vehicle_info_restart_note
        ))

        items.add(SettingItem.CategoryHeader("vehicle", R.string.category_vehicle))

        items.add(SettingItem.SettingEntry(
            stableId = "vehicleDisplayName",
            nameResId = R.string.vehicle_display_name_label,
            value = pendingVehicleDisplayName ?: "",
            onClick = {
                showTextInputDialog(R.string.vehicle_display_name_label, pendingVehicleDisplayName ?: "") { value ->
                    pendingVehicleDisplayName = value
                    checkChanges()
                    updateSettingsList()
                }
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "vehicleMake",
            nameResId = R.string.vehicle_make_label,
            value = pendingVehicleMake ?: "",
            onClick = {
                showTextInputDialogWithMessage(
                    R.string.vehicle_make_label,
                    R.string.vehicle_make_hint,
                    pendingVehicleMake ?: ""
                ) { value ->
                    pendingVehicleMake = value
                    checkChanges()
                    updateSettingsList()
                }
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "vehicleModel",
            nameResId = R.string.vehicle_model_label,
            value = pendingVehicleModel ?: "",
            onClick = {
                showTextInputDialog(R.string.vehicle_model_label, pendingVehicleModel ?: "") { value ->
                    pendingVehicleModel = value
                    checkChanges()
                    updateSettingsList()
                }
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "vehicleYear",
            nameResId = R.string.vehicle_year_label,
            value = pendingVehicleYear ?: "",
            onClick = {
                showYearInputDialog(pendingVehicleYear ?: "") { value ->
                    pendingVehicleYear = value
                    checkChanges()
                    updateSettingsList()
                }
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "vehicleId",
            nameResId = R.string.vehicle_id_label,
            value = pendingVehicleId ?: "",
            onClick = {
                showTextInputDialogWithMessage(
                    R.string.vehicle_id_label,
                    R.string.vehicle_id_description,
                    pendingVehicleId ?: ""
                ) { value ->
                    pendingVehicleId = value
                    checkChanges()
                    updateSettingsList()
                }
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "rightHandDrive",
            nameResId = R.string.right_hand_drive,
            descriptionResId = R.string.right_hand_drive_description,
            isChecked = pendingRightHandDrive!!,
            onCheckedChanged = { isChecked ->
                pendingRightHandDrive = isChecked
                checkChanges()
            }
        ))

        items.add(SettingItem.CategoryHeader("headUnit", R.string.category_head_unit))

        items.add(SettingItem.SettingEntry(
            stableId = "headUnitMake",
            nameResId = R.string.head_unit_make_label,
            value = pendingHeadUnitMake ?: "",
            onClick = {
                showTextInputDialogWithMessage(
                    R.string.head_unit_make_label,
                    R.string.head_unit_make_hint,
                    pendingHeadUnitMake ?: ""
                ) { value ->
                    pendingHeadUnitMake = value
                    checkChanges()
                    updateSettingsList()
                }
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "headUnitModel",
            nameResId = R.string.head_unit_model_label,
            value = pendingHeadUnitModel ?: "",
            onClick = {
                showTextInputDialog(R.string.head_unit_model_label, pendingHeadUnitModel ?: "") { value ->
                    pendingHeadUnitModel = value
                    checkChanges()
                    updateSettingsList()
                }
            }
        ))

        settingsAdapter.submitList(items) {
            scrollState?.let { recyclerView.layoutManager?.onRestoreInstanceState(it) }
        }
    }

    private fun showTextInputDialog(titleResId: Int, currentValue: String, onResult: (String) -> Unit) {
        showTextInputDialogWithMessage(titleResId, null, currentValue, onResult)
    }

    private fun showTextInputDialogWithMessage(titleResId: Int, messageResId: Int?, currentValue: String, onResult: (String) -> Unit) {
        val container = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }

        if (messageResId != null) {
            val messageText = android.widget.TextView(requireContext()).apply {
                setText(messageResId)
                val textColorAttr = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.textColorSecondary, textColorAttr, true)
                // Resources.getColor(int, Theme) is API 23; this app runs down to 16, where the
                // two-argument overload does not exist and the call is a NoSuchMethodError.
                setTextColor(ContextCompat.getColor(context, textColorAttr.resourceId))
                textSize = 13f
                setPadding(0, 0, 0, 24)
            }
            container.addView(messageText)
        }

        val editText = android.widget.EditText(requireContext()).apply {
            setText(currentValue)
            setSelection(text.length)
        }
        container.addView(editText)

        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(titleResId)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = editText.text.toString().trim()
                if (value.isNotEmpty()) {
                    onResult(value)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.window?.clearFlags(
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        )
        dialog.show()
        editText.requestFocus()
    }

    private fun showYearInputDialog(currentValue: String, onResult: (String) -> Unit) {
        val maxYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) + 2

        val editText = android.widget.EditText(requireContext()).apply {
            setText(currentValue)
            setSelection(text.length)
            setPadding(48, 32, 48, 16)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        }

        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.vehicle_year_label)
            .setView(editText)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.window?.clearFlags(
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        )
        dialog.show()
        editText.requestFocus()

        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = editText.text.toString().trim()
            val year = text.toIntOrNull()
            when {
                text.isEmpty() || year == null -> {
                    editText.error = getString(R.string.vehicle_year_error_invalid)
                }
                year > maxYear -> {
                    editText.error = getString(R.string.vehicle_year_error_max, maxYear)
                }
                year < 1900 -> {
                    editText.error = getString(R.string.vehicle_year_error_invalid)
                }
                else -> {
                    onResult(text)
                    dialog.dismiss()
                }
            }
        }
    }
}
