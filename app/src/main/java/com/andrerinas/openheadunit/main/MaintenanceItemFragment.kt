package com.andrerinas.openheadunit.main

import android.app.DatePickerDialog
import android.widget.Toast
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.maintenance.Distance
import com.andrerinas.openheadunit.maintenance.MaintenanceCatalog
import com.andrerinas.openheadunit.maintenance.MaintenanceItem
import com.andrerinas.openheadunit.maintenance.MaintenanceMode
import com.andrerinas.openheadunit.maintenance.MaintenanceTexts
import com.andrerinas.openheadunit.main.settings.SettingItem
import com.andrerinas.openheadunit.utils.ToastUtils
import java.util.Calendar

/** One item: its mode, when it is due, its last service and its history. */
class MaintenanceItemFragment : MaintenanceScreenFragment() {

    private val itemId: String get() = arguments?.getString(ARG_ITEM_ID).orEmpty()

    /** Leaving already, so a refresh that finds the item gone does not navigate twice. */
    private var closing = false

    private fun item(): MaintenanceItem? = store.item(itemId)

    override fun title(): String = item()?.let { MaintenanceTexts.itemName(requireContext(), it) }
        ?: getString(R.string.maintenance_settings)

    override fun rows(): List<SettingItem> {
        val item = item() ?: run {
            // Deleted here, or gone with a restored backup.
            close()
            return emptyList()
        }
        val ctx = requireContext()
        val miles = store.miles
        val odometer = store.odometerMeters()
        val now = System.currentTimeMillis()
        val rows = mutableListOf<SettingItem>()

        rows.add(SettingItem.InfoBanner(
            stableId = "itemStatus",
            textResId = R.string.maintenance_category_items,
            text = MaintenanceTexts.status(ctx, item, odometer, now, miles)
        ))
        rows.add(SettingItem.SegmentedButtonSettingEntry(
            stableId = "itemMode",
            nameResId = R.string.maintenance_item_mode,
            helpResId = R.string.maintenance_item_mode_help,
            options = modeLabels(),
            selectedIndex = item.mode.ordinal,
            onOptionSelected = { index -> save(item.copy(mode = MaintenanceMode.values()[index])) }
        ))

        rows.add(SettingItem.CategoryHeader("itemDue", R.string.maintenance_category_due))
        rows.add(SettingItem.ToggleSettingEntry(
            stableId = "itemByDistance",
            nameResId = R.string.maintenance_by_distance,
            descriptionResId = null,
            isChecked = item.byDistance,
            onCheckedChanged = { checked ->
                if (!checked && !item.byTime) keepOneLimit() else save(withDistance(item, checked))
            }
        ))
        val intervalMeters = item.intervalMeters
        if (intervalMeters != null) {
            rows.add(SettingItem.SettingEntry(
                stableId = "itemIntervalDistance",
                nameResId = R.string.maintenance_interval_distance,
                value = MaintenanceTexts.distance(ctx, intervalMeters, miles),
                onClick = {
                    numberDialog(
                        getString(R.string.maintenance_interval_distance),
                        getString(R.string.maintenance_interval_distance_hint),
                        Distance.toUnits(intervalMeters, miles), DISTANCE_STEP, MAX_INTERVAL_UNITS
                    ) { units ->
                        // In whole thousands, the step an interval is written in.
                        val rounded = Math.max(DISTANCE_STEP, Math.round(units / DISTANCE_STEP.toDouble()) * DISTANCE_STEP)
                        val meters = Distance.fromUnits(rounded, miles)
                        save(item.copy(intervalMeters = meters, warnMeters = Math.min(item.warnMeters, meters)))
                    }
                }
            ))
            rows.add(SettingItem.SettingEntry(
                stableId = "itemWarnDistance",
                nameResId = R.string.maintenance_warn_distance,
                value = MaintenanceTexts.distance(ctx, item.warnMeters, miles),
                onClick = {
                    numberDialog(
                        getString(R.string.maintenance_warn_distance), null,
                        Distance.toUnits(item.warnMeters, miles), 0, Distance.toUnits(intervalMeters, miles)
                    ) { units -> save(item.copy(warnMeters = Distance.fromUnits(units, miles))) }
                }
            ))
        }
        rows.add(SettingItem.ToggleSettingEntry(
            stableId = "itemByTime",
            nameResId = R.string.maintenance_by_time,
            descriptionResId = null,
            isChecked = item.byTime,
            onCheckedChanged = { checked ->
                if (!checked && !item.byDistance) keepOneLimit() else save(withTime(item, checked))
            }
        ))
        val intervalMonths = item.intervalMonths
        if (intervalMonths != null) {
            rows.add(SettingItem.SettingEntry(
                stableId = "itemIntervalTime",
                nameResId = R.string.maintenance_interval_time,
                value = MaintenanceTexts.months(ctx, intervalMonths),
                onClick = {
                    numberDialog(
                        getString(R.string.maintenance_interval_time), null,
                        intervalMonths.toLong(), 1, MAX_INTERVAL_MONTHS
                    ) { months ->
                        val maxWarn = months.toInt() * DAYS_PER_MONTH
                        save(item.copy(intervalMonths = months.toInt(), warnDays = Math.min(item.warnDays, maxWarn)))
                    }
                }
            ))
            rows.add(SettingItem.SettingEntry(
                stableId = "itemWarnTime",
                nameResId = R.string.maintenance_warn_time,
                value = MaintenanceTexts.days(ctx, item.warnDays),
                onClick = {
                    numberDialog(
                        getString(R.string.maintenance_warn_time), null,
                        item.warnDays.toLong(), 0, (intervalMonths * DAYS_PER_MONTH).toLong()
                    ) { days -> save(item.copy(warnDays = days.toInt())) }
                }
            ))
        }

        rows.add(SettingItem.CategoryHeader("itemService", R.string.maintenance_category_service))
        rows.add(SettingItem.SettingEntry(
            stableId = "itemLastDistance",
            nameResId = R.string.maintenance_last_distance,
            value = item.lastMeters?.let { MaintenanceTexts.distance(ctx, it, miles) } ?: getString(R.string.not_set),
            onClick = {
                numberDialog(
                    getString(R.string.maintenance_last_distance), null,
                    item.lastMeters?.let { Distance.toUnits(it, miles) }, 0, Distance.toUnits(odometer, miles)
                ) { units -> save(item.copy(lastMeters = Distance.fromUnits(units, miles))) }
            }
        ))
        rows.add(SettingItem.SettingEntry(
            stableId = "itemLastDate",
            nameResId = R.string.maintenance_last_date,
            value = item.lastMs?.let { MaintenanceTexts.date(ctx, it) } ?: getString(R.string.not_set),
            onClick = { pickDate(item.lastMs ?: now) { ms -> save(item.copy(lastMs = ms)) } }
        ))
        rows.add(SettingItem.ActionButton(
            stableId = "itemMarkDone",
            textResId = R.string.maintenance_mark_done,
            onClick = {
                confirm(
                    getString(R.string.maintenance_mark_done),
                    getString(R.string.maintenance_mark_done_message, MaintenanceTexts.distance(ctx, odometer, miles))
                ) {
                    store.markDone(item.id, odometer, System.currentTimeMillis())
                    ToastUtils.showToast(ctx, R.string.maintenance_marked_done, Toast.LENGTH_SHORT, force = true)
                    refresh()
                }
            }
        ))

        rows.add(SettingItem.CategoryHeader("itemHistory", R.string.maintenance_history))
        if (item.history.isEmpty()) {
            rows.add(SettingItem.InfoBanner("itemHistoryEmpty", R.string.maintenance_history_empty))
        }
        item.history.withIndex().reversed().forEach { (index, record) ->
            rows.add(SettingItem.SettingEntry(
                stableId = "itemHistory_$index",
                nameResId = R.string.maintenance_history,
                nameOverride = MaintenanceTexts.date(ctx, record.atMs),
                value = record.atMeters?.let { MaintenanceTexts.distance(ctx, it, miles) }.orEmpty(),
                onClick = {
                    confirm(getString(R.string.maintenance_history), getString(R.string.maintenance_history_delete)) {
                        save(item.copy(history = item.history.filterIndexed { i, _ -> i != index }))
                    }
                }
            ))
        }

        if (item.customName != null) {
            rows.add(SettingItem.CategoryHeader("itemCustom", R.string.maintenance_category_custom))
            rows.add(SettingItem.SettingEntry(
                stableId = "itemName",
                nameResId = R.string.maintenance_item_name,
                value = item.customName,
                onClick = {
                    textDialog(R.string.maintenance_item_name, R.string.maintenance_add_item_hint, item.customName) { name ->
                        save(item.copy(customName = name))
                    }
                }
            ))
            rows.add(SettingItem.ActionButton(
                stableId = "itemDelete",
                textResId = R.string.maintenance_delete_item,
                onClick = {
                    confirm(
                        getString(R.string.maintenance_delete_item),
                        getString(R.string.maintenance_delete_item_message, item.customName)
                    ) {
                        store.removeItem(item.id)
                        close()
                    }
                }
            ))
        }
        return rows
    }

    private fun close() {
        if (closing) return
        closing = true
        navigateBack()
    }

    private fun modeLabels() = listOf(
        getString(R.string.maintenance_mode_remind),
        getString(R.string.maintenance_mode_track),
        getString(R.string.maintenance_mode_off)
    )

    private fun save(item: MaintenanceItem) {
        store.updateItem(item)
        refresh()
    }

    private fun keepOneLimit() {
        ToastUtils.showToast(requireContext(), R.string.maintenance_need_one_limit, Toast.LENGTH_SHORT, force = true)
        refresh()
    }

    /** Back on by distance: the catalog's interval for a built-in item, the custom default otherwise. */
    private fun withDistance(item: MaintenanceItem, on: Boolean): MaintenanceItem {
        if (!on) return item.copy(intervalMeters = null, warnMeters = 0)
        val km = MaintenanceCatalog.template(item.templateId)?.km ?: MaintenanceCatalog.CUSTOM_KM
        val meters = MaintenanceCatalog.defaultIntervalMeters(km, store.miles)
        return item.copy(intervalMeters = meters, warnMeters = MaintenanceCatalog.defaultWarnMeters(meters))
    }

    private fun withTime(item: MaintenanceItem, on: Boolean): MaintenanceItem {
        if (!on) return item.copy(intervalMonths = null, warnDays = 0)
        val months = MaintenanceCatalog.template(item.templateId)?.months ?: MaintenanceCatalog.CUSTOM_MONTHS
        return item.copy(intervalMonths = months, warnDays = MaintenanceCatalog.defaultWarnDays(months))
    }

    private fun pickDate(currentMs: Long, onPicked: (Long) -> Unit) {
        val calendar = Calendar.getInstance().apply { timeInMillis = currentMs }
        DatePickerDialog(requireContext(), { _, year, month, day ->
            // Midday, so a time zone change cannot move it to the day before or after.
            val picked = Calendar.getInstance().apply {
                clear()
                set(year, month, day, 12, 0)
            }
            onPicked(Math.min(picked.timeInMillis, System.currentTimeMillis()))
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
    }

    companion object {
        const val ARG_ITEM_ID = "itemId"

        private const val DISTANCE_STEP = 1_000L
        private const val MAX_INTERVAL_UNITS = 500_000L
        private const val MAX_INTERVAL_MONTHS = 240L
        private const val DAYS_PER_MONTH = 31
    }
}
