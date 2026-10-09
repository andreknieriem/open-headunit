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
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.main.settings.SettingItem
import com.andrerinas.openheadunit.main.settings.SettingsAdapter
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.ToastUtils
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.Intent
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor

class MicSettingsFragment : Fragment() {
    private lateinit var settings: Settings
    private lateinit var recyclerView: RecyclerView
    private lateinit var settingsAdapter: SettingsAdapter
    private lateinit var toolbar: MaterialToolbar
    private var saveButton: MaterialButton? = null

    // Pending states
    private var pendingUseHeadUnitMicrophone: Boolean? = null
    private var pendingMicSampleRate: Int? = null
    private var pendingMicInputSource: Int? = null
    private var pendingMicEchoCanceler: Boolean? = null
    private var pendingMicNoiseSuppressor: Boolean? = null
    private var pendingMicAutoGainControl: Boolean? = null

    private var hasChanges = false
    private var requiresRestart = false
    private val SAVE_ITEM_ID = 1001

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_mic_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        settings = App.provide(requireContext()).settings

        // Initialize pending state
        pendingUseHeadUnitMicrophone = settings.useHeadUnitMicrophone
        pendingMicSampleRate = settings.micSampleRate
        pendingMicInputSource = settings.micInputSource
        pendingMicEchoCanceler = settings.micEchoCanceler
        pendingMicNoiseSuppressor = settings.micNoiseSuppressor
        pendingMicAutoGainControl = settings.micAutoGainControl

        // Intercept back press
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
                    findNavController().navigateUp()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            findNavController().navigateUp()
        }
    }

    private fun updateSaveButtonState() {
        saveButton?.isEnabled = hasChanges
        saveButton?.text = if (requiresRestart) getString(R.string.save_and_restart) else getString(R.string.save)
    }

    private fun saveSettings() {
        pendingUseHeadUnitMicrophone?.let { if (it != settings.useHeadUnitMicrophone) settings.useHeadUnitMicrophone = it }
        pendingMicSampleRate?.let { if (it != settings.micSampleRate) settings.micSampleRate = it }
        pendingMicInputSource?.let { if (it != settings.micInputSource) settings.micInputSource = it }
        pendingMicEchoCanceler?.let { if (it != settings.micEchoCanceler) settings.micEchoCanceler = it }
        pendingMicNoiseSuppressor?.let { if (it != settings.micNoiseSuppressor) settings.micNoiseSuppressor = it }
        pendingMicAutoGainControl?.let { if (it != settings.micAutoGainControl) settings.micAutoGainControl = it }

        if (requiresRestart) {
            if (App.provide(requireContext()).commManager.isConnected) {
                ToastUtils.showToast(context, getString(R.string.stopping_service), Toast.LENGTH_SHORT, force = true)
                val stopServiceIntent = Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_STOP_SERVICE
                }
                ContextCompat.startForegroundService(requireContext(), stopServiceIntent)
            }
        }

        hasChanges = false
        requiresRestart = false
        updateSaveButtonState()
        ToastUtils.showToast(context, getString(R.string.settings_saved), Toast.LENGTH_SHORT, force = true)
    }

    private fun checkChanges() {
        val anyChange = pendingUseHeadUnitMicrophone != settings.useHeadUnitMicrophone ||
                pendingMicSampleRate != settings.micSampleRate ||
                pendingMicInputSource != settings.micInputSource ||
                pendingMicEchoCanceler != settings.micEchoCanceler ||
                pendingMicNoiseSuppressor != settings.micNoiseSuppressor ||
                pendingMicAutoGainControl != settings.micAutoGainControl

        hasChanges = anyChange
        // All mic settings currently require a restart to apply to the MicRecorder session
        requiresRestart = anyChange

        updateSaveButtonState()
    }

    private fun updateSettingsList() {
        val scrollState = recyclerView.layoutManager?.onSaveInstanceState()
        val items = mutableListOf<SettingItem>()

        items.add(SettingItem.CategoryHeader("micSettings", R.string.microphone_settings))

        val micEnabled = pendingUseHeadUnitMicrophone != false
        items.add(SettingItem.ToggleSettingEntry(
            stableId = "useHeadUnitMicrophone",
            nameResId = R.string.use_head_unit_microphone,
            descriptionResId = R.string.use_head_unit_microphone_description,
            isChecked = micEnabled,
            onCheckedChanged = { isChecked ->
                pendingUseHeadUnitMicrophone = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        if (micEnabled) {
            addMicDetailRows(items)
        } else {
            items.add(SettingItem.InfoBanner("micOff", R.string.head_unit_microphone_off_info))
        }

        settingsAdapter.submitList(items) {
            scrollState?.let { recyclerView.layoutManager?.onRestoreInstanceState(it) }
        }
    }

    /** Everything that only means something while this head unit is the one recording. */
    private fun addMicDetailRows(items: MutableList<SettingItem>) {
        items.add(SettingItem.InfoBanner("micWireFormat", R.string.mic_wire_format_info))

        // Mic capture rate. A compatibility control, not a quality one: reporters raised it hoping
        // for better audio and got a stream the phone could not understand.
        items.add(SettingItem.SettingEntry(
            stableId = "micSampleRate",
            nameResId = R.string.mic_sample_rate,
            value = "${pendingMicSampleRate} Hz",
            onClick = { _ ->
                val nextRate = Settings.getNextMicSampleRate(pendingMicSampleRate!!)
                pendingMicSampleRate = nextRate
                checkChanges()
                updateSettingsList()
            }
        ))

        // Mic Input Source
        val inputSourceTitles = resources.getStringArray(R.array.mic_input_sources)
        items.add(SettingItem.SettingEntry(
            stableId = "micInputSource",
            nameResId = R.string.mic_input_source,
            value = inputSourceTitles.getOrElse(pendingMicInputSource!!) { getString(R.string.not_set) },
            onClick = { _ ->
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.mic_input_source)
                    .setSingleChoiceItems(inputSourceTitles, pendingMicInputSource!!) { dialog, which ->
                        pendingMicInputSource = which
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        // Echo Canceler
        items.add(SettingItem.ToggleSettingEntry(
            stableId = "micEchoCanceler",
            nameResId = R.string.mic_echo_canceler,
            descriptionResId = if (AcousticEchoCanceler.isAvailable())
                R.string.mic_echo_canceler_description else R.string.mic_feature_unsupported,
            isChecked = pendingMicEchoCanceler ?:false,
            isEnabled = AcousticEchoCanceler.isAvailable(),
            onCheckedChanged = { isChecked ->
                pendingMicEchoCanceler = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        // Noise Suppressor
        items.add(SettingItem.ToggleSettingEntry(
            stableId = "micNoiseSuppressor",
            nameResId = R.string.mic_noise_suppressor,
            descriptionResId = if (NoiseSuppressor.isAvailable())
                R.string.mic_noise_suppressor_description else R.string.mic_feature_unsupported,
            isChecked = pendingMicNoiseSuppressor ?:false,
            isEnabled = NoiseSuppressor.isAvailable(),
            onCheckedChanged = { isChecked ->
                pendingMicNoiseSuppressor = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        // Auto Gain Control
        items.add(SettingItem.ToggleSettingEntry(
            stableId = "micAutoGainControl",
            nameResId = R.string.mic_auto_gain_control,
            descriptionResId = if (AutomaticGainControl.isAvailable())
                R.string.mic_auto_gain_control_description else R.string.mic_feature_unsupported,
            isChecked = pendingMicAutoGainControl ?:false,
            isEnabled = AutomaticGainControl.isAvailable(),
            onCheckedChanged = { isChecked ->
                pendingMicAutoGainControl = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

    }
}
