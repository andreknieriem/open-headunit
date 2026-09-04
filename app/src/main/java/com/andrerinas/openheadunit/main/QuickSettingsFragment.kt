package com.andrerinas.openheadunit.main

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.main.settings.SettingItem
import com.andrerinas.openheadunit.main.settings.SettingsAdapter
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.AppLog
import android.widget.Toast
import com.andrerinas.openheadunit.utils.LogExporter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch

class QuickSettingsFragment : DialogFragment() {

    companion object {
        const val ACTION_SETTINGS_CHANGED = "com.andrerinas.openheadunit.SETTINGS_CHANGED"
        const val EXTRA_NEEDS_VIEW_RECREATE = "needs_view_recreate"
        const val EXTRA_NEEDS_AUDIO_RESTART = "needs_audio_restart"
        const val EXTRA_SENSOR_REFRESH = "sensor_refresh"
        const val EXTRA_APPLY_FULLSCREEN = "apply_fullscreen"
    }

    private lateinit var settings: Settings
    private lateinit var settingsRecyclerView: RecyclerView
    private lateinit var settingsAdapter: SettingsAdapter
    private lateinit var toolbar: MaterialToolbar

    private var originalViewMode: Settings.ViewMode? = null
    private var originalStretch: Boolean? = null
    private var originalScale: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.AppTheme_Fullscreen)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_quick_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        settings = App.provide(requireContext()).settings
        originalViewMode = settings.viewMode
        originalStretch = settings.stretchToFill
        originalScale = settings.forcedScale

        toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { dismiss() }

        settingsAdapter = SettingsAdapter()
        settingsRecyclerView = view.findViewById(R.id.settingsRecyclerView)
        settingsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        settingsRecyclerView.adapter = settingsAdapter

        updateSettingsList()
        applyHudMirroring(view)
    }

    override fun onStart() {
        super.onStart()
        view?.let { applyHudMirroring(it) }
    }

    private fun applyHudMirroring(view: View) {
        val mirror = if (settings.hudMirroring) -1.0f else 1.0f
        dialog?.window?.let { win ->
            val root = win.findViewById<View>(android.R.id.content) ?: win.decorView
            root?.scaleX = mirror
        } ?: run {
            view.scaleX = mirror
        }
    }

    private fun updateSettingsList() {
        val items = mutableListOf<SettingItem>()

        // --- Audio Section ---
        items.add(SettingItem.CategoryHeader("audio", R.string.category_audio))

        items.add(SettingItem.SettingEntry(
            stableId = "audioVolumeOffsets",
            nameResId = R.string.audio_volume_offset,
            value = "${(100 + settings.mediaVolumeOffset)}% / ${(100 + settings.guidanceVolumeOffset)}% / ${(100 + settings.systemVolumeOffset)}%",
            onClick = { showAudioOffsetsDialog() }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "audioLatencyMultiplier",
            nameResId = R.string.audio_latency_multiplier,
            value = "${settings.audioLatencyMultiplier}x",
            onClick = { showAudioLatencyDialog() }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "audioQueueCapacity",
            nameResId = R.string.audio_queue_capacity,
            value = if (settings.audioQueueCapacity == 0) "Unbounded (Legacy)" else "${settings.audioQueueCapacity} chunks",
            onClick = { showAudioQueueCapacityDialog() }
        ))

        // --- Display Section ---
        items.add(SettingItem.CategoryHeader("graphic", R.string.category_graphic))

        val nightModeTitles = resources.getStringArray(R.array.night_mode)
        items.add(SettingItem.SettingEntry(
            stableId = "nightMode",
            nameResId = R.string.night_mode,
            value = nightModeTitles[settings.nightMode.value],
            onClick = { showNightModeDialog() }
        ))

        // Screen rotation: a common "pulled over because it came up sideways" fix. Applied live.
        items.add(SettingItem.SettingEntry(
            stableId = "screenOrientation",
            nameResId = R.string.screen_orientation,
            value = resources.getStringArray(R.array.screen_orientation)[settings.screenOrientation.value],
            onClick = { showOrientationDialog() }
        ))

        // System bars handling: fix a nav/status bar covering the projection, without a restart.
        items.add(SettingItem.SettingEntry(
            stableId = "fullscreenMode",
            nameResId = R.string.start_in_fullscreen_mode,
            value = fullscreenModeLabels()[settings.fullscreenMode.value],
            onClick = { showFullscreenDialog() }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "stretchToFill",
            nameResId = R.string.pref_stretch_screen_title,
            descriptionResId = R.string.pref_stretch_screen_summary,
            isChecked = settings.stretchToFill,
            onCheckedChanged = { isChecked ->
                settings.stretchToFill = isChecked
                settings.commit()
                notifyChange(needsViewRecreate = true)
                updateSettingsList()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "hudMirroring",
            nameResId = R.string.hud_mirroring,
            descriptionResId = R.string.hud_mirroring_description,
            isChecked = settings.hudMirroring,
            onCheckedChanged = { isChecked ->
                settings.hudMirroring = isChecked
                settings.commit()
                notifyChange(needsViewRecreate = true)
                updateSettingsList()
                view?.let { applyHudMirroring(it) }
                activity?.recreate()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "viewMode",
            nameResId = R.string.view_mode,
            value = settings.viewMode.name,
            onClick = { showViewModeDialog() }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "showFpsCounter",
            nameResId = R.string.show_fps_counter,
            descriptionResId = R.string.show_fps_counter_description,
            isChecked = settings.showFpsCounter,
            onCheckedChanged = { isChecked ->
                settings.showFpsCounter = isChecked
                settings.commit()
                notifyChange()
                updateSettingsList()
            }
        ))

        // --- System & Safety ---
        items.add(SettingItem.CategoryHeader("system", R.string.category_automation))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "fakeSpeed",
            nameResId = R.string.fake_speed_title,
            descriptionResId = R.string.fake_speed_description,
            isChecked = settings.fakeSpeed,
            onCheckedChanged = { isChecked ->
                settings.fakeSpeed = isChecked
                settings.commit()
                notifyChange(sensorRefresh = true)
                updateSettingsList()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "killOnDisconnect",
            nameResId = R.string.kill_on_disconnect,
            descriptionResId = R.string.kill_on_disconnect_description,
            isChecked = settings.killOnDisconnect,
            onCheckedChanged = { isChecked ->
                settings.killOnDisconnect = isChecked
                settings.commit()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "exportLogs",
            nameResId = R.string.export_logs,
            value = getString(R.string.export_logs_description),
            onClick = { triggerLogExport() }
        ))

        items.add(SettingItem.ActionButton(
            stableId = "dismiss",
            textResId = R.string.close,
            onClick = { dismiss() }
        ))

        settingsAdapter.submitList(items)
    }

    private fun notifyChange(needsViewRecreate: Boolean = false, needsAudioRestart: Boolean = false, sensorRefresh: Boolean = false, applyFullscreen: Boolean = false) {
        val intent = Intent(ACTION_SETTINGS_CHANGED).apply {
            putExtra(EXTRA_NEEDS_VIEW_RECREATE, needsViewRecreate)
            putExtra(EXTRA_NEEDS_AUDIO_RESTART, needsAudioRestart)
            putExtra(EXTRA_SENSOR_REFRESH, sensorRefresh)
            putExtra(EXTRA_APPLY_FULLSCREEN, applyFullscreen)
        }
        LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(intent)
    }

    private fun fullscreenModeLabels() = arrayOf(
        getString(R.string.fullscreen_none),
        getString(R.string.fullscreen_immersive),
        getString(R.string.fullscreen_status_only),
        getString(R.string.fullscreen_immersive_avoid_notch)
    )

    private fun showOrientationDialog() {
        val options = resources.getStringArray(R.array.screen_orientation)
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.change_screen_orientation)
            .setSingleChoiceItems(options, settings.screenOrientation.value) { dialog, which ->
                val newOrientation = Settings.ScreenOrientation.fromInt(which) ?: Settings.ScreenOrientation.SYSTEM
                settings.screenOrientation = newOrientation
                settings.commit()
                // Applied live: the projection activity re-reads the orientation on this broadcast.
                requireContext().sendBroadcast(Intent(AapService.ACTION_ORIENTATION_CHANGED).apply {
                    setPackage(requireContext().packageName)
                })
                dialog.dismiss()
                updateSettingsList()
            }
            .show()
    }

    private fun showFullscreenDialog() {
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.start_in_fullscreen_mode)
            .setSingleChoiceItems(fullscreenModeLabels(), settings.fullscreenMode.value) { dialog, which ->
                val newMode = Settings.FullscreenMode.fromInt(which) ?: Settings.FullscreenMode.NONE
                settings.fullscreenMode = newMode
                settings.commit()
                notifyChange(applyFullscreen = true)
                dialog.dismiss()
                updateSettingsList()
            }
            .show()
    }

    private fun showAudioOffsetsDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_audio_offsets, null)

        val seekMedia = dialogView.findViewById<android.widget.SeekBar>(R.id.seek_media)
        val seekGuidance = dialogView.findViewById<android.widget.SeekBar>(R.id.seek_guidance)
        val seekSystem = dialogView.findViewById<android.widget.SeekBar>(R.id.seek_system)

        val textMedia = dialogView.findViewById<android.widget.TextView>(R.id.text_media_val)
        val textGuidance = dialogView.findViewById<android.widget.TextView>(R.id.text_guidance_val)
        val textSystem = dialogView.findViewById<android.widget.TextView>(R.id.text_system_val)

        seekMedia.progress = (settings.mediaVolumeOffset / 2) + 50
        seekGuidance.progress = (settings.guidanceVolumeOffset / 2) + 50
        seekSystem.progress = (settings.systemVolumeOffset / 2) + 50

        val updateLabels = {
            textMedia.text = "${(seekMedia.progress * 2)}%"
            textGuidance.text = "${(seekGuidance.progress * 2)}%"
            textSystem.text = "${(seekSystem.progress * 2)}%"
        }
        updateLabels()

        val listener = object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                updateLabels()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        }

        seekMedia.setOnSeekBarChangeListener(listener)
        seekGuidance.setOnSeekBarChangeListener(listener)
        seekSystem.setOnSeekBarChangeListener(listener)

        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.audio_volume_offset)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                settings.mediaVolumeOffset = (seekMedia.progress - 50) * 2
                settings.guidanceVolumeOffset = (seekGuidance.progress - 50) * 2
                settings.systemVolumeOffset = (seekSystem.progress - 50) * 2
                settings.commit()
                notifyChange()
                updateSettingsList()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAudioLatencyDialog() {
        val options = arrayOf("1x (Lowest Latency)", "2x (Low Latency)", "4x (High Latency)", "8x (Very High Latency)")
        val values = intArrayOf(1, 2, 4, 8)
        val currentIndex = values.indexOf(settings.audioLatencyMultiplier).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.audio_latency_multiplier)
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                settings.audioLatencyMultiplier = values[which]
                settings.commit()
                notifyChange(needsAudioRestart = true)
                dialog.dismiss()
                updateSettingsList()
            }
            .show()
    }

    private fun showAudioQueueCapacityDialog() {
        val options = arrayOf("10 chunks (Low Latency)", "20 chunks (Balanced)", "50 chunks (High Latency)", "Unbounded (Max)")
        val values = intArrayOf(10, 20, 50, 0)
        val currentIdx = values.indexOf(settings.audioQueueCapacity).coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.audio_queue_capacity)
            .setSingleChoiceItems(options, currentIdx) { dialog, which ->
                settings.audioQueueCapacity = values[which]
                settings.commit()
                notifyChange(needsAudioRestart = true)
                dialog.dismiss()
                updateSettingsList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showNightModeDialog() {
        val nightModeTitles = resources.getStringArray(R.array.night_mode)
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.night_mode)
            .setSingleChoiceItems(nightModeTitles, settings.nightMode.value) { dialog, which ->
                val newMode = Settings.NightMode.fromInt(which) ?: Settings.NightMode.AUTO
                settings.nightMode = newMode
                settings.commit()
                notifyChange(sensorRefresh = true)
                dialog.dismiss()
                updateSettingsList()
            }
            .show()
    }

    private fun showViewModeDialog() {
        val viewModes = arrayOf("SurfaceView", "TextureView", "GLES20")
        val values = arrayOf(Settings.ViewMode.SURFACE, Settings.ViewMode.TEXTURE, Settings.ViewMode.GLES)
        val currentIdx = values.indexOf(settings.viewMode).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.view_mode)
            .setSingleChoiceItems(viewModes, currentIdx) { dialog, which ->
                settings.viewMode = values[which]
                settings.commit()
                notifyChange(needsViewRecreate = true)
                dialog.dismiss()
                updateSettingsList()
            }
            .show()
    }

    private fun triggerLogExport() {
        val context = requireContext()
        val exporterLevel = settings.exporterLogLevel
        if (exporterLevel == LogExporter.LogLevel.SILENT) {
            Toast.makeText(context, getString(R.string.failed_export_in_silent_logs), Toast.LENGTH_LONG).show()
            return
        }

        if (settings.logSource == Settings.LogSource.APPLOG_FILE) {
            if (AppLog.isCapturing) {
                settings.exporterCaptureEnabled = false
                AppLog.init(settings, context.applicationContext)
            }
        } else if (LogExporter.isCapturing) {
            LogExporter.stopCapture()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val logFile = LogExporter.saveLogToPublicFile(context, exporterLevel)
            updateSettingsList()

            if (logFile != null) {
                MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
                    .setTitle(R.string.logs_exported)
                    .setMessage(getString(R.string.log_saved_to, logFile.absolutePath))
                    .setPositiveButton(R.string.share) { _, _ ->
                        LogExporter.shareLogFile(context, logFile)
                    }
                    .setNeutralButton(R.string.copy_to_clipboard) { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            val copied = LogExporter.copyFileToClipboard(context, logFile)
                            if (copied) {
                                val msg = getString(R.string.logs_copied_to_clipboard, logFile.absolutePath)
                                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                                    .setTitle(R.string.success)
                                    .setMessage(msg)
                                    .setPositiveButton(R.string.close, null)
                                    .show()
                            } else {
                                Toast.makeText(requireContext(), getString(R.string.logs_copy_to_clipboard_failed), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    .setNegativeButton(R.string.close) { dialog, _ ->
                        dialog.dismiss()
                    }
                    .show()
            } else {
                Toast.makeText(context, getString(R.string.failed_export_logs), Toast.LENGTH_SHORT).show()
            }
        }
    }
}