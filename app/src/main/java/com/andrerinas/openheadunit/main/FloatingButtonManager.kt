package com.andrerinas.openheadunit.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import com.andrerinas.openheadunit.App

object FloatingButtonManager {

    @Volatile
    var isAppForeground: Boolean = false
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    fun onAppForegroundChanged(context: Context, isForeground: Boolean) {
        isAppForeground = isForeground
        mainHandler.post {
            update(context)
        }
    }

    fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AndroidSettings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun update(context: Context) {
        // Activity callbacks also run for the Direct-Boot-aware USB trampoline. Check when the
        // queued update executes so a locked-started process can use the saved settings at unlock.
        if (!App.isUserUnlocked(context)) return
        val settings = App.provide(context).settings
        val enabled = settings.enableFloatingButton
        val permissionGranted = hasOverlayPermission(context)
        val shouldShow = enabled && permissionGranted && !isAppForeground

        if (shouldShow) {
            FloatingButtonService.start(context)
        } else {
            FloatingButtonService.stop(context)
            FloatingButtonService.removeOverlayDirect(context)
        }
    }

    fun removeOverlay(context: Context) {
        FloatingButtonService.stop(context)
        FloatingButtonService.removeOverlayDirect(context)
    }

    fun requestOverlayPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !AndroidSettings.canDrawOverlays(context)) {
            val intent = Intent(
                AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
