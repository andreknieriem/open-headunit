package com.andrerinas.openheadunit.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.input.InputAction
import com.andrerinas.openheadunit.input.InputActionCodec
import com.andrerinas.openheadunit.main.settings.SettingItem
import com.andrerinas.openheadunit.main.settings.SettingsAdapter
import com.google.android.material.appbar.MaterialToolbar

/**
 * The one screen for choosing what a key binding or a gesture does. It lists every action in
 * [ActionCatalog] plus "Launch app…", and hands the choice back to the screen that opened it as a
 * fragment result under that screen's request key, with the caller's tag echoed back.
 */
class ActionPickerFragment : Fragment() {

    companion object {
        private const val ARG_REQUEST_KEY = "request_key"
        private const val ARG_TAG = "tag"
        private const val ARG_TITLE = "title"
        private const val ARG_CURRENT = "current"
        private const val ARG_NONE_LABEL = "none_label"

        /** Result bundle: the chosen action, encoded with [InputActionCodec]. */
        const val RESULT_ACTION = "action"
        /** Result bundle: the tag the caller passed in. */
        const val RESULT_TAG = "tag"

        private const val APP_PICK_KEY = "action_picker_app"

        fun args(requestKey: String, tag: String, title: String, current: InputAction, noneLabelResId: Int) = bundleOf(
            ARG_REQUEST_KEY to requestKey,
            ARG_TAG to tag,
            ARG_TITLE to title,
            ARG_CURRENT to InputActionCodec.encode(current),
            ARG_NONE_LABEL to noneLabelResId,
        )
    }

    private lateinit var adapter: SettingsAdapter
    private var pendingClose = false

    private val current: InputAction
        get() = InputActionCodec.decode(requireArguments().getString(ARG_CURRENT))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app drawer answers here; the choice is passed on once this screen is back on top.
        parentFragmentManager.setFragmentResultListener(APP_PICK_KEY, this) { _, result ->
            val target = AppDrawerFragment.pickedTarget(result) ?: return@setFragmentResultListener
            deliver(InputAction.LaunchApp(target), closeNow = false)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_action_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = requireArguments().getString(ARG_TITLE) ?: getString(R.string.action_picker_title)
            subtitle = getString(R.string.action_picker_title)
            setNavigationOnClickListener { findNavController().popBackStack() }
        }
        adapter = SettingsAdapter()
        view.findViewById<RecyclerView>(R.id.recycler_view).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ActionPickerFragment.adapter
        }
        adapter.submitList(buildItems())
    }

    override fun onResume() {
        super.onResume()
        if (pendingClose) {
            pendingClose = false
            view?.post { if (isAdded) findNavController().popBackStack() }
        }
    }

    private fun buildItems(): List<SettingItem> {
        val selected = current
        val items = mutableListOf<SettingItem>()
        items += row("none", getString(requireArguments().getInt(ARG_NONE_LABEL)), InputAction.None, selected)

        items += SettingItem.CategoryHeader("projection", R.string.action_section_projection)
        ActionCatalog.localActions.forEach { items += row(it.action.id(), getString(it.nameResId), it.action, selected) }

        items += SettingItem.CategoryHeader("apps", R.string.action_section_apps)
        if (selected is InputAction.LaunchApp) {
            items += row("app_current", ActionCatalog.label(requireContext(), selected), selected, selected)
        }
        items += SettingItem.SettingEntry(
            stableId = "app_pick",
            nameResId = R.string.action_launch_app,
            value = "",
            onClick = { openAppDrawer() }
        )

        items += SettingItem.CategoryHeader("aa_keys", R.string.action_section_aa_keys)
        ActionCatalog.aaKeys.forEach { items += row(it.action.id(), getString(it.nameResId), it.action, selected) }

        // A key code that was learned before but is not in the catalog still shows as chosen.
        if (selected is InputAction.AaKey && ActionCatalog.aaKeys.none { it.action == selected }) {
            items += row(selected.id(), ActionCatalog.label(requireContext(), selected), selected, selected)
        }
        return items
    }

    private fun InputAction.id() = InputActionCodec.encode(this)

    private fun row(id: String, name: String, action: InputAction, selected: InputAction) = SettingItem.SettingEntry(
        stableId = id,
        nameResId = R.string.action_picker_title,
        nameOverride = name,
        value = if (action == selected) getString(R.string.action_selected) else "",
        onClick = { deliver(action, closeNow = true) }
    )

    private fun openAppDrawer() {
        val controller = findNavController()
        if (controller.currentDestination?.id == R.id.actionPickerFragment) {
            controller.navigate(
                R.id.action_actionPickerFragment_to_appDrawerFragment,
                AppDrawerFragment.pickActionArgs(APP_PICK_KEY)
            )
        }
    }

    private fun deliver(action: InputAction, closeNow: Boolean) {
        val args = requireArguments()
        setFragmentResult(
            args.getString(ARG_REQUEST_KEY)!!,
            bundleOf(RESULT_ACTION to InputActionCodec.encode(action), RESULT_TAG to args.getString(ARG_TAG))
        )
        if (closeNow) findNavController().popBackStack() else pendingClose = true
    }
}
