package com.andrerinas.openheadunit.main

import android.media.AudioManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.main.settings.SettingItem
import com.andrerinas.openheadunit.main.settings.SettingsAdapter
import com.andrerinas.openheadunit.utils.AudioStreamTester
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.ToastUtils
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Which system stream each Android Auto audio channel plays on.
 *
 * Split out of the main Audio section because the separate-streams toggle on its own could only
 * say "the usual three streams or one", and the stream a channel needs is head-unit specific.
 * Each channel gets a picker and a speaker button that auditions the pending choice, so a stream
 * can be tried and rejected without saving, restarting the service or connecting a phone.
 */
class AudioStreamSettingsFragment : Fragment() {
    private lateinit var settings: Settings
    private lateinit var recyclerView: RecyclerView
    private lateinit var settingsAdapter: SettingsAdapter
    private lateinit var toolbar: MaterialToolbar
    private var saveButton: MaterialButton? = null

    // Pending states
    private var pendingSeparateAudioStreams: Boolean? = null
    private var pendingMediaAudioStream: Int? = null
    private var pendingGuidanceAudioStream: Int? = null
    private var pendingSystemAudioStream: Int? = null

    private var hasChanges = false
    private val SAVE_ITEM_ID = 1001

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_audio_stream_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        settings = App.provide(requireContext()).settings

        pendingSeparateAudioStreams = settings.separateAudioStreams
        pendingMediaAudioStream = settings.mediaAudioStream
        pendingGuidanceAudioStream = settings.guidanceAudioStream
        pendingSystemAudioStream = settings.systemAudioStream

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
        // Every setting here is read once when the audio tracks are created, so a running session
        // keeps the old routing until it is restarted.
        saveButton?.text = if (hasChanges) getString(R.string.save_and_restart) else getString(R.string.save)
    }

    private fun saveSettings() {
        pendingSeparateAudioStreams?.let { if (it != settings.separateAudioStreams) settings.separateAudioStreams = it }
        pendingMediaAudioStream?.let { if (it != settings.mediaAudioStream) settings.mediaAudioStream = it }
        pendingGuidanceAudioStream?.let { if (it != settings.guidanceAudioStream) settings.guidanceAudioStream = it }
        pendingSystemAudioStream?.let { if (it != settings.systemAudioStream) settings.systemAudioStream = it }

        settings.commit()
        val manager = App.provide(requireContext()).commManager
        val connected = manager.isConnected
        manager.applyAudioSettings()
        val sessionEnded = connected && !manager.isConnected

        hasChanges = false
        updateSaveButtonState()
        ToastUtils.showToast(context,
            getString(if (sessionEnded) R.string.audio_settings_reconnect else R.string.settings_saved),
            if (sessionEnded) Toast.LENGTH_LONG else Toast.LENGTH_SHORT, force = true)
    }

    private fun checkChanges() {
        hasChanges = pendingSeparateAudioStreams != settings.separateAudioStreams ||
                pendingMediaAudioStream != settings.mediaAudioStream ||
                pendingGuidanceAudioStream != settings.guidanceAudioStream ||
                pendingSystemAudioStream != settings.systemAudioStream

        updateSaveButtonState()
    }

    private fun updateSettingsList() {
        val scrollState = recyclerView.layoutManager?.onSaveInstanceState()
        val items = mutableListOf<SettingItem>()

        items.add(SettingItem.CategoryHeader("audioStreams", R.string.audio_stream_settings))
        items.add(SettingItem.InfoBanner("audioStreamsInfo", R.string.audio_stream_settings_info))

        // Point the vendor streams out rather than leaving them to be noticed. On a head unit that
        // has them they are usually the interesting answer, and their names ("MUSIC_SECOND (10)")
        // mean nothing to a user who has not been told where they came from.
        if (AudioStreamTester.vendorStreamCount(requireContext()) > 0) {
            items.add(SettingItem.InfoBanner(
                stableId = "audioStreamsVendorInfo",
                textResId = R.string.audio_stream_vendor_info
            ))
        }

        val separate = pendingSeparateAudioStreams ?: false
        items.add(SettingItem.ToggleSettingEntry(
            stableId = "separateAudioStreams",
            nameResId = R.string.separate_audio_streams,
            descriptionResId = R.string.separate_audio_streams_description,
            isChecked = separate,
            onCheckedChanged = { isChecked ->
                pendingSeparateAudioStreams = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(streamRow(
            stableId = "mediaAudioStream",
            nameResId = if (separate) R.string.audio_channel_media else R.string.audio_channel_all,
            descriptionResId = if (separate) R.string.audio_channel_media_description
                else R.string.audio_channel_all_description,
            pickerTitle = getString(
                if (separate) R.string.audio_channel_media else R.string.audio_channel_all_short),
            current = { pendingMediaAudioStream ?: AudioManager.STREAM_MUSIC },
            onPicked = { pendingMediaAudioStream = it }
        ))

        // With separate streams off every channel plays on the media stream, so offering the other
        // two pickers would show choices that do nothing.
        if (separate) {
            items.add(streamRow(
                stableId = "guidanceAudioStream",
                nameResId = R.string.audio_channel_guidance,
                descriptionResId = R.string.audio_channel_guidance_description,
                current = { pendingGuidanceAudioStream ?: AudioManager.STREAM_VOICE_CALL },
                onPicked = { pendingGuidanceAudioStream = it }
            ))
            items.add(streamRow(
                stableId = "systemAudioStream",
                nameResId = R.string.audio_channel_system,
                descriptionResId = R.string.audio_channel_system_description,
                current = { pendingSystemAudioStream ?: AudioManager.STREAM_NOTIFICATION },
                onPicked = { pendingSystemAudioStream = it }
            ))
        }

        settingsAdapter.submitList(items) {
            scrollState?.let { recyclerView.layoutManager?.onRestoreInstanceState(it) }
        }
    }

    /** One channel: the stream it plays on, a picker for it, and a tone on the pending choice. */
    private fun streamRow(
        stableId: String,
        nameResId: Int,
        descriptionResId: Int,
        current: () -> Int,
        onPicked: (Int) -> Unit,
        pickerTitle: String = getString(nameResId)
    ): SettingItem.StreamSettingEntry = SettingItem.StreamSettingEntry(
        stableId = stableId,
        nameResId = nameResId,
        descriptionResId = descriptionResId,
        value = AudioStreamTester.label(requireContext(), current()),
        onClick = {
            val labels = AudioStreamTester.labels(requireContext())
            MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                .setTitle(getString(R.string.audio_stream_pick_title, pickerTitle))
                .setSingleChoiceItems(labels, AudioStreamTester.indexOf(requireContext(), current())) { dialog, which ->
                    onPicked(AudioStreamTester.streamAt(requireContext(), which))
                    checkChanges()
                    dialog.dismiss()
                    updateSettingsList()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        },
        onTest = { AudioStreamTester.play(requireContext(), current()) }
    )
}
