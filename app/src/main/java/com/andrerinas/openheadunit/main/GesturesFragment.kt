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
            settings.resetGestureSettings()
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
                    id = "leftTap",
                    titleResId = R.string.gesture_two_finger_tap_left,
                    action = settings.leftTapTwoFingerGesture
                ),
                gestureSetting(
                    id = "rightTap",
                    titleResId = R.string.gesture_two_finger_tap_right,
                    action = settings.rightTapTwoFingerGesture
                ),
                gestureSetting(
                    id = "longPress",
                    titleResId = R.string.gesture_two_finger_long_press,
                    action = settings.longPressTwoFingerGesture
                ),
                SettingItem.SegmentedButtonSettingEntry(
                    stableId = "edgeZone",
                    nameResId = R.string.gesture_edge_zone,
                    options = listOf(
                        getString(R.string.gesture_edge_zone_narrow),
                        getString(R.string.gesture_edge_zone_normal),
                        getString(R.string.gesture_edge_zone_wide)
                    ),
                    selectedIndex = settings.gestureEdgeZone.value,
                    onOptionSelected = { index ->
                        val zone = Settings.GestureEdgeZone.fromInt(index) ?: return@SegmentedButtonSettingEntry
                        if (settings.gestureEdgeZone != zone) settings.gestureEdgeZone = zone
                    }
                ),
                SettingItem.InfoBanner("gesturesHelp", R.string.gestures_help),
                SettingItem.CategoryHeader("nightDimmerGroup", R.string.gestures_night_dimmer_group),
                SettingItem.SliderSettingEntry(
                    stableId = "dimOpacity",
                    nameResId = R.string.dim_opacity,
                    value = "${settings.dimOpacityPercent}%",
                    sliderValue = settings.dimOpacityPercent.toFloat(),
                    valueFrom = 10f,
                    valueTo = 90f,
                    stepSize = 10f,
                    onValueChanged = { v ->
                        val percent = v.toInt()
                        if (settings.dimOpacityPercent != percent) settings.dimOpacityPercent = percent
                        updateItems()
                    }
                ),
                SettingItem.SegmentedButtonSettingEntry(
                    stableId = "nightTextSize",
                    nameResId = R.string.night_overlay_text_size,
                    options = listOf(
                        getString(R.string.night_overlay_text_small),
                        getString(R.string.night_overlay_text_normal),
                        getString(R.string.night_overlay_text_large)
                    ),
                    selectedIndex = settings.nightOverlayTextSize.value,
                    onOptionSelected = { index ->
                        val size = Settings.NightOverlayTextSize.fromInt(index) ?: return@SegmentedButtonSettingEntry
                        if (settings.nightOverlayTextSize != size) settings.nightOverlayTextSize = size
                    }
                ),
                SettingItem.SegmentedButtonSettingEntry(
                    stableId = "nightBackground",
                    nameResId = R.string.night_overlay_background,
                    options = listOf(
                        getString(R.string.night_overlay_background_black),
                        getString(R.string.night_overlay_background_dark_gray)
                    ),
                    selectedIndex = settings.nightOverlayBackground.value,
                    onOptionSelected = { index ->
                        val bg = Settings.NightOverlayBackground.fromInt(index) ?: return@SegmentedButtonSettingEntry
                        if (settings.nightOverlayBackground != bg) settings.nightOverlayBackground = bg
                    }
                ),
                SettingItem.ToggleSettingEntry(
                    stableId = "nightShowClock",
                    nameResId = R.string.night_overlay_show_clock,
                    descriptionResId = null,
                    isChecked = settings.nightOverlayShowClock,
                    onCheckedChanged = { if (settings.nightOverlayShowClock != it) settings.nightOverlayShowClock = it }
                ),
                SettingItem.ToggleSettingEntry(
                    stableId = "nightShowNavigation",
                    nameResId = R.string.night_overlay_show_navigation,
                    descriptionResId = null,
                    isChecked = settings.nightOverlayShowNavigation,
                    onCheckedChanged = { if (settings.nightOverlayShowNavigation != it) settings.nightOverlayShowNavigation = it }
                ),
                SettingItem.ToggleSettingEntry(
                    stableId = "nightShowTrack",
                    nameResId = R.string.night_overlay_show_track,
                    descriptionResId = null,
                    isChecked = settings.nightOverlayShowTrack,
                    onCheckedChanged = { if (settings.nightOverlayShowTrack != it) settings.nightOverlayShowTrack = it }
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
                // Write only a change, so an untouched slot keeps following its default.
                if (action != selected) when (id) {
                    "left" -> settings.leftTwoFingerGesture = action
                    "right" -> settings.rightTwoFingerGesture = action
                    "leftTap" -> settings.leftTapTwoFingerGesture = action
                    "rightTap" -> settings.rightTapTwoFingerGesture = action
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
            Settings.GestureAction.DIM_SCREEN -> R.string.gesture_action_dim_screen
        }
}
