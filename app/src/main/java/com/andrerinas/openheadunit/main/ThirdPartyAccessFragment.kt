package com.andrerinas.openheadunit.main

import android.content.Context
import android.content.pm.ApplicationInfo
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
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.main.settings.SettingItem
import com.andrerinas.openheadunit.main.settings.SettingsAdapter
import com.andrerinas.openheadunit.thirdparty.ThirdPartyAccessStore
import com.andrerinas.openheadunit.thirdparty.ThirdPartyForwardPolicy
import com.andrerinas.openheadunit.utils.ToastUtils
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Settings > Companion apps: which installed apps receive the phone's navigation and media messages (see
 * ThirdPartyFrameForwarder), and whether album art goes with them. No app is ticked by default. Ticking an app pins its
 * signing certificate; an app replaced by one signed with another key gets nothing until it is ticked again.
 * Changes apply on Save, like on the other settings screens.
 */
class ThirdPartyAccessFragment : Fragment() {

    private class AppRow(val pkg: String, val label: String)

    private lateinit var store: ThirdPartyAccessStore
    private lateinit var recyclerView: RecyclerView
    private lateinit var settingsAdapter: SettingsAdapter
    private lateinit var toolbar: MaterialToolbar
    private var saveButton: MaterialButton? = null

    private var apps: List<AppRow> = emptyList()
    /** package -> pinned certificate digest, as Save will write it */
    private var pendingEntries: Map<String, String> = emptyMap()
    private var pendingAlbumArt = false
    /** package -> digest of the certificate installed now (null: unreadable); read once per screen */
    private val installedDigests = mutableMapOf<String, String?>()

    private val savedEntries: Map<String, String> get() = store.entries().associate { it.pkg to it.certDigest }

    private val hasChanges: Boolean
        get() = pendingEntries != savedEntries || pendingAlbumArt != store.includeAlbumArt

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_third_party_access, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = ThirdPartyAccessStore(requireContext())
        apps = installedApps(requireContext())
        pendingEntries = savedEntries
        pendingAlbumArt = store.includeAlbumArt

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
        if (pendingEntries != savedEntries) {
            store.replaceAll(pendingEntries.map { (pkg, digest) -> ThirdPartyForwardPolicy.Entry(pkg, digest) })
        }
        if (pendingAlbumArt != store.includeAlbumArt) store.includeAlbumArt = pendingAlbumArt
        navigateBack()
    }

    private fun changed() {
        updateSaveButtonState()
        updateSettingsList()
    }

    private fun installedDigest(pkg: String): String? =
        installedDigests.getOrPut(pkg) { ThirdPartyAccessStore.installedDigest(requireContext().packageManager, pkg) }

    /** puts a row's switch back where its item says, after a tap that did not change the setting */
    private fun rebind(stableId: String) {
        val index = settingsAdapter.currentList.indexOfFirst { it.stableId == stableId }
        if (index >= 0) settingsAdapter.notifyItemChanged(index)
    }

    private fun approve(app: AppRow, stableId: String) {
        val digest = installedDigest(app.pkg)
        if (digest == null) {
            ToastUtils.showToast(requireContext(), R.string.third_party_no_signature, Toast.LENGTH_LONG, force = true)
            rebind(stableId)
            return
        }
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(app.label)
            .setMessage(getString(R.string.third_party_confirm, app.label, app.pkg))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                pendingEntries = pendingEntries + (app.pkg to digest)
                changed()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> rebind(stableId) }
            .setOnCancelListener { rebind(stableId) }
            .show()
    }

    private fun appRow(app: AppRow): SettingItem {
        val stableId = "thirdPartyApp:${app.pkg}"
        val pinned = pendingEntries[app.pkg]
        val receiving = pinned != null && pinned == installedDigest(app.pkg)
        // Pinned to a certificate the installed app no longer has: it gets nothing until it is ticked again.
        val note = if (pinned != null && !receiving) "${app.pkg} · ${getString(R.string.third_party_state_changed)}" else app.pkg
        return SettingItem.ToggleSettingEntry(
            stableId = stableId,
            nameResId = R.string.third_party_apps_header,
            nameOverride = app.label,
            descriptionResId = null,
            descriptionOverride = note,
            isChecked = receiving,
            onCheckedChanged = { isChecked ->
                if (isChecked) {
                    approve(app, stableId)
                } else {
                    pendingEntries = pendingEntries - app.pkg
                    changed()
                }
            }
        )
    }

    private fun updateSettingsList() {
        val items = mutableListOf<SettingItem>()
        items.add(SettingItem.InfoBanner("thirdPartyAccessBanner", R.string.third_party_access_banner))
        items.add(SettingItem.ToggleSettingEntry(
            stableId = "thirdPartyAlbumArt",
            nameResId = R.string.third_party_album_art,
            descriptionResId = R.string.third_party_album_art_description,
            helpResId = R.string.third_party_album_art_help,
            isChecked = pendingAlbumArt,
            onCheckedChanged = { isChecked ->
                pendingAlbumArt = isChecked
                changed()
            }
        ))
        items.add(SettingItem.SubHeader("thirdPartyApps", R.string.third_party_apps_header))
        apps.forEach { items.add(appRow(it)) }
        settingsAdapter.submitList(items)
    }

    companion object {
        private const val SAVE_ITEM_ID = 1001

        /** launchable apps and user-installed apps, without this app, sorted by name */
        private fun installedApps(context: Context): List<AppRow> {
            val pm = context.packageManager
            return pm.getInstalledApplications(0)
                .filter { it.packageName != context.packageName }
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                .map { AppRow(it.packageName, pm.getApplicationLabel(it).toString()) }
                .sortedBy { it.label.lowercase() }
        }

        /** the value shown on the settings row: the names of the ticked apps, or Off */
        fun summary(context: Context): String {
            val pm = context.packageManager
            val labels = try {
                ThirdPartyAccessStore(context).entries().mapNotNull { entry ->
                    try {
                        pm.getApplicationLabel(pm.getApplicationInfo(entry.pkg, 0)).toString()
                    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                        null
                    }
                }
            } catch (e: RuntimeException) {
                emptyList()
            }
            return if (labels.isEmpty()) context.getString(R.string.appearance_off) else labels.joinToString(", ")
        }
    }
}
