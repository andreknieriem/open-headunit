package com.andrerinas.openheadunit.main

import android.content.Context

/** Opens a stored app or shortcut: for home favorites, key bindings and gestures alike. */
object AppLauncher {

    /** @return false when nothing could be opened. */
    fun launch(context: Context, target: FavoriteTarget): Boolean = when (target) {
        is FavoriteTarget.Empty -> false
        is FavoriteTarget.App -> AppDrawerFragment.launchPackage(context, target.packageName)
        is FavoriteTarget.Shortcut ->
            LauncherShortcuts.launch(context, target.packageName, target.shortcutId) ||
                // Unreadable outside the home role: the app is the closest thing that still opens.
                (!LauncherShortcuts.canRead(context) &&
                    AppDrawerFragment.launchPackage(context, target.packageName))
    }

    /** The app's name, or the package name when the app is gone. */
    fun label(context: Context, target: FavoriteTarget): String {
        val packageName = when (target) {
            is FavoriteTarget.Empty -> return ""
            is FavoriteTarget.App -> target.packageName
            is FavoriteTarget.Shortcut -> target.packageName
        }
        val appLabel = try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: Exception) {
            packageName
        }
        if (target !is FavoriteTarget.Shortcut) return appLabel
        // A shortcut is named after what the drawer showed, which is cached once it loaded.
        val shortcutLabel = AppDrawerCache.cachedApps
            ?.firstOrNull { it.packageName == packageName && it.shortcutId == target.shortcutId }?.label
        return if (shortcutLabel != null) "$appLabel: $shortcutLabel" else appLabel
    }
}
