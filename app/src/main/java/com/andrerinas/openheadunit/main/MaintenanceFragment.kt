package com.andrerinas.openheadunit.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.maintenance.Distance
import com.andrerinas.openheadunit.maintenance.Gearbox
import com.andrerinas.openheadunit.maintenance.MaintenanceBannerPolicy
import com.andrerinas.openheadunit.maintenance.MaintenanceNotifier
import com.andrerinas.openheadunit.maintenance.MaintenanceTexts
import com.andrerinas.openheadunit.maintenance.MaintenanceTracker
import com.andrerinas.openheadunit.maintenance.Steering
import com.andrerinas.openheadunit.main.settings.SettingItem
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ToastUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

/**
 * Settings > Maintenance: turning the mileage on, correcting it, the car, keeping the app alive,
 * and the list of items.
 */
class MaintenanceFragment : MaintenanceScreenFragment() {

    /** The permission request came from the switch, so a grant goes on with the setup. */
    private var enablingAfterPermission = false

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // GPS needs precise location. From Android 12 a request for it alone is ignored, so both
        // are asked; approximate only leaves the dialog able to ask for precise next time.
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val enabling = enablingAfterPermission
        enablingAfterPermission = false
        if (granted) {
            AppLog.i("$TAG: precise location granted")
            if (enabling) continueEnabling() else syncService()
            refresh()
            return@registerForActivityResult
        }
        val canAskAgain = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
        // The same three outcomes as the home speed: granted, ask again later, or blocked.
        when (SpeedPermissionPolicy.afterRequest(granted = false, canAskAgain = canAskAgain)) {
            SpeedPermissionPolicy.Outcome.EXPLAIN ->
                ToastUtils.showToast(requireContext(), R.string.maintenance_location_needed, Toast.LENGTH_LONG, force = true)
            SpeedPermissionPolicy.Outcome.OPEN_APP_SETTINGS -> showLocationBlockedNotice()
            SpeedPermissionPolicy.Outcome.SHOW_SPEED -> Unit
        }
        refresh()
    }

    override fun title(): String = getString(R.string.maintenance_settings)

    override fun rows(): List<SettingItem> {
        val ctx = requireContext()
        val enabled = store.enabled
        val items = mutableListOf<SettingItem>()
        items.add(SettingItem.ToggleSettingEntry(
            stableId = "maintenanceEnabled",
            nameResId = R.string.maintenance_enabled,
            descriptionResId = R.string.maintenance_enabled_summary,
            helpResId = R.string.maintenance_enabled_help,
            isChecked = enabled,
            onCheckedChanged = { checked ->
                if (checked) {
                    startEnabling()
                } else {
                    store.turnOff()
                    refresh()
                }
            }
        ))
        if (!enabled) return items

        val miles = store.miles
        val odometer = store.odometerMeters()
        val missed = store.missedMeters
        if (missed > 0) {
            items.add(SettingItem.InfoBanner(
                stableId = "maintenanceMissed",
                textResId = R.string.maintenance_missed_banner,
                text = getString(R.string.maintenance_missed_banner, MaintenanceTexts.distance(ctx, missed, miles))
            ))
        }

        items.add(SettingItem.CategoryHeader("maintenanceMileage", R.string.maintenance_category_mileage))
        items.add(SettingItem.SettingEntry(
            stableId = "maintenanceOdometer",
            nameResId = R.string.maintenance_odometer,
            descriptionResId = R.string.maintenance_odometer_summary,
            value = MaintenanceTexts.distance(ctx, odometer, miles),
            onClick = { showCorrectDialog() }
        ))
        items.add(SettingItem.SettingEntry(
            stableId = "maintenanceFactor",
            nameResId = R.string.maintenance_factor,
            helpResId = R.string.maintenance_factor_help,
            value = getString(
                if (store.correctionClamped) R.string.maintenance_factor_value_clamped else R.string.maintenance_factor_value,
                MaintenanceTexts.factor(store.correctionFactor)
            ),
            onClick = { showCorrectDialog() }
        ))
        items.add(gpsRow(ctx))
        items.add(SettingItem.SettingEntry(
            stableId = "maintenanceUnits",
            nameResId = R.string.maintenance_units,
            value = getString(if (miles) R.string.maintenance_units_mi else R.string.maintenance_units_km),
            onClick = { showUnitsDialog() }
        ))

        items.add(SettingItem.CategoryHeader("maintenanceCar", R.string.maintenance_category_car))
        items.add(SettingItem.SegmentedButtonSettingEntry(
            stableId = "maintenanceGearbox",
            nameResId = R.string.maintenance_gearbox,
            helpResId = R.string.maintenance_gearbox_help,
            options = gearboxLabels(),
            selectedIndex = store.gearbox.ordinal,
            onOptionSelected = { index ->
                store.changeCar(Gearbox.values()[index], store.steering, System.currentTimeMillis())
                refresh()
            }
        ))
        items.add(SettingItem.SegmentedButtonSettingEntry(
            stableId = "maintenanceSteering",
            nameResId = R.string.maintenance_steering,
            helpResId = R.string.maintenance_steering_help,
            options = steeringLabels(),
            selectedIndex = store.steering.ordinal,
            onOptionSelected = { index ->
                store.changeCar(store.gearbox, Steering.values()[index], System.currentTimeMillis())
                refresh()
            }
        ))

        items.add(SettingItem.CategoryHeader("maintenanceRemind", R.string.maintenance_category_remind))
        items.add(SettingItem.ToggleSettingEntry(
            stableId = "maintenanceRemindScreen",
            nameResId = R.string.maintenance_remind_screen,
            descriptionResId = R.string.maintenance_remind_screen_summary,
            isChecked = store.remindScreen,
            onCheckedChanged = { checked ->
                store.remindScreen = checked
                refresh()
            }
        ))
        items.add(SettingItem.ToggleSettingEntry(
            stableId = "maintenanceRemindNotification",
            nameResId = R.string.maintenance_remind_notification,
            descriptionResId = if (store.remindNotification && !notificationsAllowed(ctx)) {
                R.string.maintenance_remind_notification_blocked
            } else R.string.maintenance_remind_notification_summary,
            isChecked = store.remindNotification,
            onCheckedChanged = { checked ->
                store.remindNotification = checked
                if (checked) askNotifications { refresh() } else {
                    MaintenanceNotifier.cancel(ctx)
                    refresh()
                }
            }
        ))
        items.add(SettingItem.ToggleSettingEntry(
            stableId = "maintenanceRemindBanner",
            nameResId = R.string.maintenance_remind_banner,
            descriptionResId = R.string.maintenance_remind_banner_summary,
            isChecked = store.remindBanner,
            onCheckedChanged = { checked ->
                store.remindBanner = checked
                refresh()
            }
        ))
        if (store.remindBanner) {
            items.add(SettingItem.SettingEntry(
                stableId = "maintenanceBannerPosition",
                nameResId = R.string.maintenance_banner_position,
                helpResId = R.string.maintenance_banner_position_help,
                value = getString(bannerPositionLabels.getValue(store.bannerPosition)),
                onClick = { showBannerPositionDialog() }
            ))
        }
        if (!store.remindScreen && !store.remindNotification && !store.remindBanner) {
            items.add(SettingItem.InfoBanner("maintenanceRemindNone", R.string.maintenance_remind_all_off_note))
        }

        items.add(SettingItem.CategoryHeader("maintenanceRunning", R.string.maintenance_category_running))
        items.add(SettingItem.InfoBanner(
            stableId = "maintenanceKeepRunning",
            textResId = R.string.maintenance_keep_running_note
        ))
        items.add(SettingItem.SettingEntry(
            stableId = "maintenanceBattery",
            nameResId = R.string.maintenance_battery,
            helpResId = R.string.maintenance_battery_help,
            value = getString(batteryState(ctx)),
            onClick = { requestBatteryExemption() }
        ))

        items.add(SettingItem.CategoryHeader("maintenanceItems", R.string.maintenance_category_items))
        val now = System.currentTimeMillis()
        for (item in store.items()) {
            items.add(SettingItem.SettingEntry(
                stableId = "maintenanceItem_${item.id}",
                nameResId = R.string.maintenance_category_items,
                nameOverride = MaintenanceTexts.itemName(ctx, item),
                value = MaintenanceTexts.status(ctx, item, odometer, now, miles),
                onClick = { openItem(item.id) }
            ))
        }
        items.add(SettingItem.SettingEntry(
            stableId = "maintenanceChoose",
            nameResId = R.string.maintenance_choose_items,
            descriptionResId = R.string.maintenance_choose_items_summary,
            value = "",
            onClick = {
                navigate { findNavController().navigate(R.id.action_maintenanceFragment_to_maintenanceChooseFragment) }
            }
        ))
        items.add(SettingItem.ActionButton(
            stableId = "maintenanceAdd",
            textResId = R.string.maintenance_add_item,
            onClick = {
                textDialog(R.string.maintenance_add_item, R.string.maintenance_add_item_hint, "") { name ->
                    openItem(store.addCustomItem(name, System.currentTimeMillis()).id)
                }
            }
        ))
        return items
    }

    private fun gpsRow(ctx: Context): SettingItem.SettingEntry {
        val manager = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val providerEnabled = try {
            manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        } catch (e: Exception) {
            false
        }
        val lastFix = store.lastFixElapsedMs
        val (value, onClick) = when {
            !MaintenanceTracker.hasPermission(ctx) -> R.string.maintenance_gps_no_permission to { requestLocation(enabling = false) }
            !providerEnabled -> R.string.maintenance_gps_off to { openSystemScreen(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS) }
            lastFix > 0 && SystemClock.elapsedRealtime() - lastFix < FIX_FRESH_MS -> R.string.maintenance_gps_counting to {}
            else -> R.string.maintenance_gps_waiting to {}
        }
        return SettingItem.SettingEntry(
            stableId = "maintenanceGps",
            nameResId = R.string.maintenance_gps,
            value = getString(value),
            onClick = { onClick() }
        )
    }

    private fun gearboxLabels() = listOf(
        getString(R.string.maintenance_gearbox_manual),
        getString(R.string.maintenance_gearbox_automatic),
        getString(R.string.maintenance_gearbox_cvt)
    )

    private fun steeringLabels() = listOf(
        getString(R.string.maintenance_steering_hydraulic),
        getString(R.string.maintenance_steering_electric),
        getString(R.string.maintenance_steering_unsure)
    )

    private fun openItem(id: String) {
        navigate {
            findNavController().navigate(
                R.id.action_maintenanceFragment_to_maintenanceItemFragment,
                bundleOf(MaintenanceItemFragment.ARG_ITEM_ID to id)
            )
        }
    }

    // A double tap can fire a second navigation after the first one already left this screen.
    private fun navigate(action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            AppLog.w("$TAG: navigation ignored: ${e.message}")
        }
    }

    // --- turning it on ---

    private fun startEnabling() {
        if (MaintenanceTracker.hasPermission(requireContext())) continueEnabling() else requestLocation(enabling = true)
    }

    private fun requestLocation(enabling: Boolean) {
        enablingAfterPermission = enabling
        locationPermissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    /** A car seen before keeps its items and only needs the dashboard number again. */
    private fun continueEnabling() {
        if (store.items().isNotEmpty()) {
            store.resume()
            syncService()
            refresh()
            showCorrectDialog()
            return
        }
        askUnits()
    }

    private fun askUnits() {
        var chosen = false
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.maintenance_units)
            .setItems(arrayOf(getString(R.string.maintenance_units_km), getString(R.string.maintenance_units_mi))) { _, which ->
                chosen = true
                askOdometer(miles = which == 1)
            }
            .setOnDismissListener { if (!chosen) refresh() }
            .show()
    }

    private fun askOdometer(miles: Boolean) {
        numberDialog(
            title = getString(R.string.maintenance_odometer),
            message = getString(R.string.maintenance_setup_odometer_message),
            current = null,
            min = 0,
            max = MAX_ODOMETER_UNITS,
            onCancel = { refresh() },
        ) { units -> askGearbox(miles, Distance.fromUnits(units, miles)) }
    }

    private fun askGearbox(miles: Boolean, odometerMeters: Long) {
        var chosen = false
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.maintenance_gearbox)
            .setItems(gearboxLabels().toTypedArray()) { _, which ->
                chosen = true
                askSteering(miles, odometerMeters, Gearbox.values()[which])
            }
            .setOnDismissListener { if (!chosen) refresh() }
            .show()
    }

    private fun askSteering(miles: Boolean, odometerMeters: Long, gearbox: Gearbox) {
        var chosen = false
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.maintenance_steering)
            .setItems(steeringLabels().toTypedArray()) { _, which ->
                chosen = true
                store.setUp(odometerMeters, miles, gearbox, Steering.values()[which], System.currentTimeMillis())
                syncService()
                refresh()
                showKeepRunning()
            }
            .setOnDismissListener { if (!chosen) refresh() }
            .show()
    }

    /** The last setup step: why the app should stay alive, then the exemption when it lacks one. */
    private fun showKeepRunning() {
        val keepRunning = { showKeepRunningHelp { if (needsBatteryExemption()) openBatteryExemption() } }
        if (store.remindNotification) askNotifications(keepRunning) else keepRunning()
    }

    // --- the notification ---

    /** Runs after the notification permission question, whatever the answer. */
    private var afterNotificationAnswer: (() -> Unit)? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        AppLog.i("$TAG: notification permission ${if (granted) "granted" else "refused"}")
        if (!granted) {
            // Nothing breaks without it: the notice and the home still remind.
            ToastUtils.showToast(requireContext(), R.string.maintenance_remind_notification_blocked, Toast.LENGTH_LONG, force = true)
        }
        afterNotificationAnswer?.invoke()
        afterNotificationAnswer = null
    }

    /** Android 13 and up ask for notifications; earlier versions allow them from the start. */
    private fun askNotifications(then: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || notificationsAllowed(requireContext())) {
            then()
            return
        }
        afterNotificationAnswer = then
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun notificationsAllowed(ctx: Context): Boolean = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    /**
     * The service counts the mileage. Starting it from this screen keeps it running and lets it
     * claim location for the background, which a start from a broadcast cannot.
     */
    private fun syncService() {
        val ctx = context ?: return
        ContextCompat.startForegroundService(ctx,
            Intent(ctx, AapService::class.java).setAction(AapService.ACTION_MAINTENANCE_SYNC))
    }

    // --- the mileage ---

    private fun showCorrectDialog() {
        val ctx = requireContext()
        val miles = store.miles
        var message = getString(R.string.maintenance_correct_message, MaintenanceTexts.factor(store.correctionFactor))
        if (store.correctionClamped) message += "\n\n" + getString(R.string.maintenance_correct_clamped_note)
        numberDialog(
            title = getString(R.string.maintenance_correct_title),
            message = message,
            current = Distance.toUnits(store.odometerMeters(), miles),
            min = 0,
            max = MAX_ODOMETER_UNITS,
        ) { units ->
            val result = store.correct(Distance.fromUnits(units, miles), System.currentTimeMillis())
            val text = when {
                result.updated && result.clamped ->
                    getString(R.string.maintenance_corrected_factor_clamped, MaintenanceTexts.factor(result.factor))
                result.updated -> getString(R.string.maintenance_corrected_factor, MaintenanceTexts.factor(result.factor))
                else -> getString(R.string.maintenance_corrected)
            }
            ToastUtils.showToast(ctx, text, Toast.LENGTH_LONG, force = true)
            refresh()
        }
    }

    private val bannerPositionLabels = mapOf(
        MaintenanceBannerPolicy.Position.AUTO to R.string.maintenance_banner_position_auto,
        MaintenanceBannerPolicy.Position.TOP to R.string.maintenance_banner_position_top,
        MaintenanceBannerPolicy.Position.ABOVE_BAR to R.string.maintenance_banner_position_above_bar,
        MaintenanceBannerPolicy.Position.SIDE to R.string.maintenance_banner_position_side,
    )

    private fun showBannerPositionDialog() {
        val positions = MaintenanceBannerPolicy.Position.values()
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.maintenance_banner_position)
            .setSingleChoiceItems(
                positions.map { getString(bannerPositionLabels.getValue(it)) }.toTypedArray(),
                positions.indexOf(store.bannerPosition)
            ) { dialog, which ->
                store.bannerPosition = positions[which]
                dialog.dismiss()
                refresh()
            }
            .show()
    }

    private fun showUnitsDialog() {
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.maintenance_units)
            .setSingleChoiceItems(
                arrayOf(getString(R.string.maintenance_units_km), getString(R.string.maintenance_units_mi)),
                if (store.miles) 1 else 0
            ) { dialog, which ->
                store.miles = which == 1
                dialog.dismiss()
                refresh()
            }
            .show()
    }

    // --- keeping the app alive ---

    private fun batteryState(ctx: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return R.string.maintenance_battery_not_needed
        val power = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return if (power?.isIgnoringBatteryOptimizations(ctx.packageName) == true) {
            R.string.maintenance_battery_excluded
        } else {
            R.string.maintenance_battery_not_excluded
        }
    }

    private fun requestBatteryExemption() {
        if (needsBatteryExemption()) openBatteryExemption() else showKeepRunningHelp {}
    }

    // The SDK check repeats batteryState's so lint can see the guard openBatteryExemption needs.
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.M)
    private fun needsBatteryExemption(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
        batteryState(requireContext()) == R.string.maintenance_battery_not_excluded

    /**
     * Doze arrived in Android 6, and with it the exemption. This opens the system list where the
     * user excludes the app, rather than the direct request: that one needs a permission Google
     * Play only allows for a few kinds of apps. Some head unit ROMs strip the list too, so the
     * app's own page is the fallback.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun openBatteryExemption() {
        val opened = openSystemScreen(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, quiet = true) ||
            openSystemScreen(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, withPackage = true)
        AppLog.i("$TAG: battery optimization exemption requested, a settings screen opened=$opened")
    }

    private fun showKeepRunningHelp(onOk: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.maintenance_category_running)
            .setMessage(R.string.maintenance_battery_help)
            .setPositiveButton(android.R.string.ok) { _, _ -> onOk() }
            .show()
    }

    private fun openSystemScreen(action: String, withPackage: Boolean = false, quiet: Boolean = false): Boolean {
        val ctx = context ?: return false
        return try {
            val intent = Intent(action)
            if (withPackage) intent.data = "package:${ctx.packageName}".toUri()
            startActivity(intent)
            true
        } catch (e: Exception) {
            AppLog.w("$TAG: this unit has no screen for $action: ${e.message}")
            if (!quiet) ToastUtils.showToast(ctx, R.string.cannot_open_settings, Toast.LENGTH_SHORT, force = true)
            false
        }
    }

    private fun showLocationBlockedNotice() {
        val root = view ?: return
        AppLog.i("$TAG: precise location is blocked, offering the app settings")
        Snackbar.make(root, R.string.maintenance_location_blocked, Snackbar.LENGTH_LONG)
            .setAction(R.string.open_settings) {
                openSystemScreen(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, withPackage = true)
            }
            .show()
    }

    private companion object {
        const val TAG = "MaintenanceFragment"

        /** A fix older than this means the GPS is not delivering right now. */
        const val FIX_FRESH_MS = 10_000L

        /** Seven digits, more than any dashboard shows. */
        const val MAX_ODOMETER_UNITS = 9_999_999L
    }
}
