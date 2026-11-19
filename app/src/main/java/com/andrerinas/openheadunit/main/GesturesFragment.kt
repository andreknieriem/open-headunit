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
import com.andrerinas.openheadunit.aap.DimPolicy
import com.andrerinas.openheadunit.input.InputActionCodec
import com.andrerinas.openheadunit.main.settings.SettingHelpDialog
import com.andrerinas.openheadunit.main.settings.SettingItem
import com.andrerinas.openheadunit.main.settings.SettingsAdapter
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.ToastUtils
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

class GesturesFragment : Fragment() {

    private lateinit var settings: Settings
    private lateinit var adapter: SettingsAdapter
    private lateinit var toolbar: MaterialToolbar
    private val resetItemId = 1004

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        parentFragmentManager.setFragmentResultListener(REQUEST_KEY, this) { _, result ->
            val slot = Settings.GestureSlot.fromId(result.getString(ActionPickerFragment.RESULT_TAG).orEmpty())
                ?: return@setFragmentResultListener
            val action = InputActionCodec.decode(result.getString(ActionPickerFragment.RESULT_ACTION))
            val settings = Settings(requireContext())
            // Write only a change, so an untouched slot keeps following its default.
            if (action != settings.gestureAction(slot)) settings.setGestureAction(slot, action)
            if (::adapter.isInitialized) updateItems()
        }
    }

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

        SettingHelpDialog.addToolbarHelp(toolbar, toolbar.title, R.string.gestures_help, "gestures")

        val resetItem = toolbar.menu.add(0, resetItemId, 1, getString(R.string.reset))
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
                    slot = Settings.GestureSlot.LEFT,
                    titleResId = R.string.gesture_two_finger_left,
                    helpResId = R.string.gesture_two_finger_edge_swipe_help,
                ),
                gestureSetting(
                    slot = Settings.GestureSlot.RIGHT,
                    titleResId = R.string.gesture_two_finger_right,
                    helpResId = R.string.gesture_two_finger_edge_swipe_help,
                ),
                gestureSetting(
                    slot = Settings.GestureSlot.DOWN,
                    titleResId = R.string.gesture_two_finger_down,
                    helpResId = R.string.gesture_two_finger_down_help,
                ),
                gestureSetting(
                    slot = Settings.GestureSlot.LEFT_TAP,
                    titleResId = R.string.gesture_two_finger_tap_left,
                    helpResId = R.string.gesture_two_finger_edge_tap_help,
                ),
                gestureSetting(
                    slot = Settings.GestureSlot.RIGHT_TAP,
                    titleResId = R.string.gesture_two_finger_tap_right,
                    helpResId = R.string.gesture_two_finger_edge_tap_help,
                ),
                gestureSetting(
                    slot = Settings.GestureSlot.LONG_PRESS,
                    titleResId = R.string.gesture_two_finger_long_press,
                    helpResId = R.string.gesture_two_finger_long_press_help,
                ),
                SettingItem.SegmentedButtonSettingEntry(
                    stableId = "edgeZone",
                    nameResId = R.string.gesture_edge_zone,
                    helpResId = R.string.gesture_edge_zone_help,
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
                SettingItem.CategoryHeader("nightDimmerGroup", R.string.gestures_night_dimmer_group),
                SettingItem.SegmentedButtonSettingEntry(
                    stableId = "nightTextSize",
                    nameResId = R.string.night_overlay_text_size,
                    helpResId = R.string.night_overlay_text_size_help,
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
                    helpResId = R.string.night_overlay_background_help,
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
                    descriptionResId = R.string.night_overlay_show_clock_summary,
                    isChecked = settings.nightOverlayShowClock,
                    onCheckedChanged = { if (settings.nightOverlayShowClock != it) settings.nightOverlayShowClock = it }
                ),
                SettingItem.ToggleSettingEntry(
                    stableId = "nightShowNavigation",
                    nameResId = R.string.night_overlay_show_navigation,
                    descriptionResId = R.string.night_overlay_show_navigation_summary,
                    helpResId = R.string.night_overlay_show_navigation_help,
                    isChecked = settings.nightOverlayShowNavigation,
                    onCheckedChanged = { if (settings.nightOverlayShowNavigation != it) settings.nightOverlayShowNavigation = it }
                ),
                SettingItem.ToggleSettingEntry(
                    stableId = "nightShowTrack",
                    nameResId = R.string.night_overlay_show_track,
                    descriptionResId = R.string.night_overlay_show_track_summary,
                    helpResId = R.string.night_overlay_show_track_help,
                    isChecked = settings.nightOverlayShowTrack,
                    onCheckedChanged = { if (settings.nightOverlayShowTrack != it) settings.nightOverlayShowTrack = it }
                ),
                SettingItem.SliderSettingEntry(
                    stableId = "dimOpacity",
                    nameResId = R.string.dim_opacity,
                    helpResId = R.string.dim_opacity_help,
                    value = "${settings.dimOpacityPercent}%",
                    sliderValue = settings.dimOpacityPercent.toFloat(),
                    valueFrom = DimPolicy.MIN_PERCENT.toFloat(),
                    valueTo = DimPolicy.MAX_PERCENT.toFloat(),
                    stepSize = 10f,
                    onValueChanged = { v ->
                        val percent = v.toInt()
                        if (settings.dimOpacityPercent != percent) settings.dimOpacityPercent = percent
                        updateItems()
                    }
                )
            )
        )
    }

    private fun gestureSetting(
        slot: Settings.GestureSlot,
        titleResId: Int,
        helpResId: Int,
    ) = SettingItem.SettingEntry(
        stableId = slot.id,
        nameResId = titleResId,
        value = ActionCatalog.label(requireContext(), settings.gestureAction(slot)),
        helpResId = helpResId,
        onClick = { openActionPicker(slot, titleResId) }
    )

    private fun openActionPicker(slot: Settings.GestureSlot, titleResId: Int) {
        val controller = findNavController()
        if (controller.currentDestination?.id != R.id.gesturesFragment) return
        controller.navigate(
            R.id.action_gesturesFragment_to_actionPickerFragment,
            ActionPickerFragment.args(
                requestKey = REQUEST_KEY,
                tag = slot.id,
                title = getString(titleResId),
                current = settings.gestureAction(slot),
                noneLabelResId = R.string.gesture_action_disabled
            )
        )
    }

    companion object {
        private const val REQUEST_KEY = "gesture_action"
    }
}
