package com.andrerinas.openheadunit.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.main.settings.SettingItem
import com.andrerinas.openheadunit.main.settings.SettingsAdapter
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.ToastUtils
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class GesturesFragment : Fragment() {

    private lateinit var settings: Settings
    private lateinit var adapter: SettingsAdapter
    private lateinit var toolbar: MaterialToolbar
    private val resetItemId = 1004

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_gestures, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = Settings(requireContext())
        toolbar = view.findViewById(R.id.toolbar)
        adapter = SettingsAdapter()
        view.findViewById<RecyclerView>(R.id.recycler_view).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@GesturesFragment.adapter
        }
        setupToolbar()
        updateItems()
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        val resetItem = toolbar.menu.add(0, resetItemId, 0, getString(R.string.reset))
        resetItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        resetItem.setActionView(R.layout.layout_reset_button)
        resetItem.actionView?.findViewById<MaterialButton>(R.id.reset_button_widget)?.setOnClickListener {
            settings.leftTwoFingerGesture = Settings.GestureAction.EXIT_DIALOG
            settings.rightTwoFingerGesture = Settings.GestureAction.TOGGLE_FULLSCREEN
            settings.downTwoFingerGesture = Settings.GestureAction.DISABLED
            settings.longPressTwoFingerGesture = Settings.GestureAction.DISABLED
            updateItems()
            ToastUtils.showToast(requireContext(), getString(R.string.gestures_reset), Toast.LENGTH_SHORT, force = true)
        }
    }

    private fun updateItems() {
        adapter.submitList(
            listOf(
                gestureSetting(
                    id = "left",
                    titleResId = R.string.gesture_two_finger_left,
                    action = settings.leftTwoFingerGesture
                ),
                gestureSetting(
                    id = "right",
                    titleResId = R.string.gesture_two_finger_right,
                    action = settings.rightTwoFingerGesture
                ),
                gestureSetting(
                    id = "down",
                    titleResId = R.string.gesture_two_finger_down,
                    action = settings.downTwoFingerGesture
                ),
                gestureSetting(
                    id = "longPress",
                    titleResId = R.string.gesture_two_finger_long_press,
                    action = settings.longPressTwoFingerGesture
                )
            )
        )
    }

    private fun gestureSetting(
        id: String,
        titleResId: Int,
        action: Settings.GestureAction
    ) = SettingItem.SettingEntry(
        stableId = id,
        nameResId = titleResId,
        value = getString(action.labelResId),
        onClick = { showActionPicker(id, titleResId, action) }
    )

    private fun showActionPicker(id: String, titleResId: Int, selected: Settings.GestureAction) {
        val actions = Settings.GestureAction.values()
        val options = actions.map { getString(it.labelResId) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(titleResId)
            .setSingleChoiceItems(options, selected.value) { dialog, which ->
                val action = actions.firstOrNull { it.value == which } ?: return@setSingleChoiceItems
                when (id) {
                    "left" -> settings.leftTwoFingerGesture = action
                    "right" -> settings.rightTwoFingerGesture = action
                    "down" -> settings.downTwoFingerGesture = action
                    "longPress" -> settings.longPressTwoFingerGesture = action
                }
                updateItems()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private val Settings.GestureAction.labelResId: Int
        get() = when (this) {
            Settings.GestureAction.DISABLED -> R.string.gesture_action_disabled
            Settings.GestureAction.EXIT_DIALOG -> R.string.gesture_action_exit_dialog
            Settings.GestureAction.TOGGLE_FULLSCREEN -> R.string.gesture_action_toggle_fullscreen
            Settings.GestureAction.NIGHT_OVERLAY -> R.string.gesture_action_night_overlay
        }
}
