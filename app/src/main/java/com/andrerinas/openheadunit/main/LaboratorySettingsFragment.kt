package com.andrerinas.openheadunit.main

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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
import com.andrerinas.openheadunit.aap.GalLaboratoryOptions
import com.andrerinas.openheadunit.aap.GalLaboratoryOptions.Mode
import com.andrerinas.openheadunit.aap.GalProfile
import com.andrerinas.openheadunit.main.settings.SettingItem
import com.andrerinas.openheadunit.main.settings.SettingsAdapter
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.ToastUtils
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class LaboratorySettingsFragment : Fragment() {
    private lateinit var settings: Settings
    private lateinit var pending: LaboratoryPendingCopy
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: SettingsAdapter
    private var saveButton: MaterialButton? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = App.provide(requireContext()).settings
        @Suppress("DEPRECATION")
        val restored = savedInstanceState?.getSerializable(PENDING_KEY) as? LaboratoryPendingCopy
        pending = restored ?: LaboratoryPendingCopy.from(settings)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_laboratory_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recycler = view.findViewById(R.id.recycler_view)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        // RecyclerView's change fade resets row alpha to 1, hiding the disabled appearance
        // after a profile switch. Version-dependent controls must keep their disabled styling.
        recycler.itemAnimator = null
        adapter = SettingsAdapter()
        recycler.adapter = adapter
        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { handleBack() }
        val saveItem = toolbar.menu.add(0, 1001, 0, getString(R.string.save))
        saveItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        saveItem.setActionView(R.layout.layout_save_button)
        saveButton = saveItem.actionView?.findViewById(R.id.save_button_widget)
        saveButton?.setOnClickListener { saveSettings() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })
        updateRows()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putSerializable(PENDING_KEY, pending)
    }

    override fun onDestroyView() {
        saveButton = null
        recycler.adapter = null
        super.onDestroyView()
    }

    private fun handleBack() {
        if (!pending.differsFrom(settings)) {
            findNavController().navigateUp()
            return
        }
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.unsaved_changes)
            .setMessage(R.string.unsaved_changes_message)
            .setPositiveButton(R.string.discard) { _, _ -> findNavController().navigateUp() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun saveSettings() {
        if (!pending.differsFrom(settings)) return
        pending.saveTo(settings)
        // Offers and session runtime options are immutable until the next negotiation. This is
        // the same service-stop path used by the main page for protocol/display changes.
        if (App.provide(requireContext()).commManager.isConnected) {
            ToastUtils.showToast(context, getString(R.string.stopping_service), Toast.LENGTH_SHORT, force = true)
            ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_STOP_SERVICE
            })
        }
        updateRows()
        ToastUtils.showToast(context, getString(R.string.settings_saved), Toast.LENGTH_SHORT, force = true)
    }

    private fun profileLabel(profile: GalProfile): String = when (profile) {
        GalProfile.COMPATIBILITY -> getString(R.string.gal_protocol_version_default)
        GalProfile.GAL_1_6 -> profile.version.toString()
        else -> getString(R.string.gal_protocol_version_experimental, profile.version.toString())
    }

    private fun updateRows() {
        val scroll = recycler.layoutManager?.onSaveInstanceState()
        val caps = pending.capabilities
        val options = pending.options
        val items = mutableListOf<SettingItem>(
            SettingItem.InfoBanner("labInfo", R.string.laboratory_description),
            SettingItem.SettingEntry("galProtocolVersion", R.string.gal_protocol_version, profileLabel(pending.profile)) {
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.gal_protocol_version)
                    .setSingleChoiceItems(GalProfile.entries.map(::profileLabel).toTypedArray(), GalProfile.entries.indexOf(pending.profile)) { dialog, which ->
                        pending.selectProfile(which)
                        dialog.dismiss()
                        updateRows()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            },
            SettingItem.InfoBanner("galInfo", R.string.gal_protocol_version_description)
        )
        fun toggle(id: String, title: Int, description: Int, checked: Boolean, enabled: Boolean,
                   change: (GalLaboratoryOptions, Boolean) -> GalLaboratoryOptions) {
            items.add(SettingItem.ToggleSettingEntry(id, title, description, checked, isEnabled = enabled) { value ->
                if (enabled) {
                    pending.options = change(pending.options, value)
                    updateRows()
                }
            })
        }
        toggle("guidance48Khz", R.string.lab_guidance, R.string.lab_guidance_description, options.guidance48Khz, caps.guidance48Khz) { o, v -> o.copy(guidance48Khz = v) }
        toggle("modernDisplayConfig", R.string.lab_display, R.string.lab_display_description, options.modernDisplayConfig, caps.modernDisplayConfig) { o, v -> o.copy(modernDisplayConfig = v) }
        toggle("audioUpdates", R.string.lab_audio_updates, R.string.lab_audio_updates_description, options.audioUpdates, caps.audioUpdates) { o, v -> o.copy(audioUpdates = v) }
        toggle("videoUpdates", R.string.lab_video_updates, R.string.lab_video_updates_description, options.videoUpdates, caps.videoUpdates) { o, v -> o.copy(videoUpdates = v) }
        items.add(SettingItem.InfoBanner("labModes", R.string.lab_modes_description))
        fun mode(id: String, title: Int, current: Mode, change: (GalLaboratoryOptions, Mode) -> GalLaboratoryOptions) {
            val labels = arrayOf(getString(R.string.auto), getString(R.string.lab_off), getString(R.string.lab_on))
            items.add(SettingItem.SettingEntry(id, title, labels[current.ordinal], isEnabled = caps.mediaOptions) {
                if (caps.mediaOptions) MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(title)
                    .setSingleChoiceItems(labels, current.ordinal) { dialog, which ->
                        pending.options = change(pending.options, Mode.entries[which])
                        dialog.dismiss()
                        updateRows()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            })
        }
        fun numbers(vararg fields: LaboratoryNumber) {
            fields.forEach { field ->
                val value = field.value(options)
                val label = when {
                    value == null -> getString(R.string.lab_auto_default)
                    field.milliseconds -> getString(R.string.lab_milliseconds, value)
                    else -> value.toString()
                }
                items.add(SettingItem.SettingEntry("labNumber${field.name}", field.title, label,
                    isEnabled = pending.numericEnabled(field)) { if (pending.numericEnabled(field)) showNumberDialog(field) })
            }
        }
        mode("avSync", R.string.lab_av_sync, options.avSync) { o, v -> o.copy(avSync = v) }
        numbers(LaboratoryNumber.MAX_LAG, LaboratoryNumber.SYNC_INTERVAL, LaboratoryNumber.AUDIO_DELAY,
            LaboratoryNumber.VIDEO_DELAY, LaboratoryNumber.LAG_WEIGHT, LaboratoryNumber.SYNC_OFFSET)
        mode("videoTiming", R.string.lab_video_timing, options.videoTiming) { o, v -> o.copy(videoTiming = v) }
        numbers(LaboratoryNumber.VIDEO_MIN, LaboratoryNumber.VIDEO_MAX)
        mode("audioBuffer", R.string.lab_audio_buffer, options.audioBuffer) { o, v -> o.copy(audioBuffer = v) }
        numbers(LaboratoryNumber.BUFFER_LIMIT)
        mode("mediaStats", R.string.lab_media_stats, options.mediaStats) { o, v -> o.copy(mediaStats = v) }
        numbers(LaboratoryNumber.STATS_INTERVAL)
        items.add(SettingItem.ActionButton("labReset", R.string.lab_reset) {
            pending.resetOptions()
            updateRows()
        })
        saveButton?.isEnabled = pending.differsFrom(settings)
        saveButton?.setText(if (pending.differsFrom(settings)) R.string.save_and_restart else R.string.save)
        adapter.submitList(items) { scroll?.let { recycler.layoutManager?.onRestoreInstanceState(it) } }
    }

    private fun showNumberDialog(field: LaboratoryNumber) {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or
                if (field.range.first < 0) InputType.TYPE_NUMBER_FLAG_SIGNED else 0
            setSingleLine()
            hint = getString(R.string.lab_auto_default)
            setText(field.value(pending.options)?.toString().orEmpty())
            selectAll()
        }
        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(field.title)
            .setMessage(getString(R.string.lab_number_help, field.range.first, field.range.last,
                if (field.milliseconds) getString(R.string.lab_ms_unit) else getString(R.string.lab_weight_unit)))
            .setView(input)
            .setPositiveButton(R.string.save, null)
            .setNeutralButton(R.string.auto) { _, _ ->
                pending.setNumber(field, "")
                updateRows()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (pending.setNumber(field, input.text.toString())) {
                    dialog.dismiss()
                    updateRows()
                } else {
                    input.error = getString(R.string.lab_number_invalid, field.range.first, field.range.last)
                }
            }
        }
        dialog.show()
    }

    private companion object {
        const val PENDING_KEY = "laboratory_pending"
    }
}
