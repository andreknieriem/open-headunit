package com.andrerinas.openheadunit.main

import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.NavigationShareControl
import com.andrerinas.openheadunit.utils.ToastUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The multi-select list of installed apps behind the "Share navigation with apps" row. Only reached
 * where [NavigationShareControl.isAvailable] is true, so its copy comes from there.
 */
object NavigationSharePicker {

    /**
     * Shows the installed apps with [selectedPackages] ticked. OK commits the working set, "Remove"
     * the empty set, Cancel nothing. A chosen package that is no longer installed keeps a row, by
     * package name, or the only way to clear it would be to install the app again.
     */
    fun show(context: Context, selectedPackages: Set<String>, onCommit: (Set<String>) -> Unit) {
        val installed = launchableApps(context)
        val installedPackages = installed.map { it.packageName }.toSet()
        val orphans = selectedPackages.filterNot { it in installedPackages }.sorted()
        val rowPackages = installed.map { it.packageName } + orphans
        if (rowPackages.isEmpty()) {
            ToastUtils.showToast(context, NavigationShareControl.noAppsRes, Toast.LENGTH_LONG, force = true)
            return
        }
        val labels = (installed.map { "${it.label} (${it.packageName})" } + orphans).toTypedArray()
        val checked = rowPackages.map { it in selectedPackages }.toBooleanArray()
        val working = selectedPackages.toMutableSet()

        MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(NavigationShareControl.rowNameRes)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val pkg = rowPackages[which]
                if (isChecked) working.add(pkg) else working.remove(pkg)
            }
            .setPositiveButton(android.R.string.ok) { _, _ -> onCommit(working.toSet()) }
            .setNeutralButton(R.string.remove) { _, _ -> onCommit(emptySet()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** The row's value: the chosen apps by name, or "Off" while none is chosen. */
    fun summaryFor(context: Context, packages: Set<String>): String {
        if (packages.isEmpty()) return context.getString(NavigationShareControl.rowOffRes)
        return packages.sorted().joinToString { labelFor(context, it) }
    }

    private fun labelFor(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().ifBlank { pkg }
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }

    /** One row per app with a launcher entry, this app left out, from the drawer's own list. */
    private fun launchableApps(context: Context): List<AppDrawerItem> =
        (AppDrawerCache.cachedApps ?: AppDrawerFragment.queryInstalledApps(context))
            .filter { it.shortcutId == null }
            .distinctBy { it.packageName }
}
