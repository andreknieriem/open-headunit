package com.andrerinas.openheadunit.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.device.DeviceProfiles
import com.andrerinas.openheadunit.device.DeviceSpecificHeadUnitSettings
import com.andrerinas.openheadunit.main.settings.SettingItem
import com.andrerinas.openheadunit.main.settings.SettingsAdapter
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DeviceSpecificSettingsFragment : Fragment() {
    private lateinit var deviceSettings: DeviceSpecificHeadUnitSettings
    private lateinit var settingsAdapter: SettingsAdapter
    private val availability = mutableMapOf<String, Boolean>()
    private val checking = mutableSetOf<String>()
    private var checkGeneration = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_device_specific_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        deviceSettings = DeviceSpecificHeadUnitSettings(requireContext())
        settingsAdapter = SettingsAdapter()
        view.findViewById<RecyclerView>(R.id.recycler_view).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = settingsAdapter
        }
        view.findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            if (!findNavController().navigateUp()) requireActivity().finish()
        }
        updateSettingsList()
        checkAvailability()
    }

    private fun updateSettingsList() {
        if (!::settingsAdapter.isInitialized || !::deviceSettings.isInitialized) return
        val items = mutableListOf<SettingItem>()

        val profiles = DeviceProfiles.all
        val currentProfile = DeviceProfiles.selected(deviceSettings)
        items.add(SettingItem.SettingEntry(
            stableId = "deviceSpecificHeadUnitModel",
            nameResId = R.string.device_specific_head_unit_model,
            helpResId = R.string.device_specific_head_unit_model_help,
            value = getString(currentProfile.titleResId),
            isEnabled = true,
            onClick = {
                val labels = profiles.map { getString(it.titleResId) }.toTypedArray()
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.device_specific_head_unit_model)
                    .setSingleChoiceItems(labels, profiles.indexOf(currentProfile)) { dialog, which ->
                        deviceSettings.modelKey = profiles[which].id
                        checkGeneration++
                        availability.clear()
                        checking.clear()
                        dialog.dismiss()
                        updateSettingsList()
                        checkAvailability()
                    }
                    .show()
            }
        ))

        currentProfile.hacks.forEach { hack ->
            items.add(SettingItem.ToggleSettingEntry(
                stableId = hack.id,
                nameResId = hack.titleResId,
                descriptionResId = hack.descriptionResId,
                helpResId = hack.helpResId,
                isChecked = deviceSettings.isEnabled(hack),
                isEnabled = availability[hack.id] == true || deviceSettings.isEnabled(hack),
                descriptionOverride = if (hack.id in checking) {
                    getString(hack.checkingResId)
                } else {
                    null
                },
                onCheckedChanged = { enabled ->
                    deviceSettings.setEnabled(hack, enabled)
                }
            ))
            if (availability[hack.id] == false) {
                items.add(SettingItem.InfoBanner(
                    stableId = "${hack.id}.unavailable",
                    textResId = hack.unavailableResId
                ))
            }
        }
        settingsAdapter.submitList(items)
    }

    private fun checkAvailability() {
        val profile = DeviceProfiles.selected(deviceSettings)
        val generation = checkGeneration
        profile.hacks.forEach { hack ->
            if (hack.id in availability || !checking.add(hack.id)) return@forEach
            viewLifecycleOwner.lifecycleScope.launch {
                val available = withContext(Dispatchers.IO) { hack.isAvailable() }
                if (generation != checkGeneration ||
                    DeviceProfiles.selected(deviceSettings).id != profile.id
                ) return@launch
                checking.remove(hack.id)
                availability[hack.id] = available
                updateSettingsList()
            }
        }
        updateSettingsList()
    }

    override fun onDestroyView() {
        checkGeneration++
        checking.clear()
        super.onDestroyView()
    }
}
