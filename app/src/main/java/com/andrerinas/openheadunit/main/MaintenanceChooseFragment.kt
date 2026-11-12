package com.andrerinas.openheadunit.main

import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.maintenance.MaintenanceMode
import com.andrerinas.openheadunit.maintenance.MaintenanceTexts
import com.andrerinas.openheadunit.main.settings.SettingItem

/** Remind, track only or off for every item in one place, without opening each one. */
class MaintenanceChooseFragment : MaintenanceScreenFragment() {

    override fun title(): String = getString(R.string.maintenance_choose_items)

    override fun rows(): List<SettingItem> {
        val ctx = requireContext()
        val options = listOf(
            getString(R.string.maintenance_mode_remind),
            getString(R.string.maintenance_mode_track),
            getString(R.string.maintenance_mode_off)
        )
        val rows = mutableListOf<SettingItem>(
            SettingItem.InfoBanner("chooseNote", R.string.maintenance_item_mode_help)
        )
        for (item in store.items()) {
            rows.add(SettingItem.SegmentedButtonSettingEntry(
                stableId = "chooseMode_${item.id}",
                nameResId = R.string.maintenance_item_mode,
                nameOverride = MaintenanceTexts.itemName(ctx, item),
                options = options,
                selectedIndex = item.mode.ordinal,
                onOptionSelected = { index ->
                    store.updateItem(item.copy(mode = MaintenanceMode.values()[index]))
                    refresh()
                }
            ))
        }
        return rows
    }
}
