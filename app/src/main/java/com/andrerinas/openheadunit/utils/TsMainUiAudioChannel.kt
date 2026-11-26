package com.andrerinas.openheadunit.utils

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * Topway units with `com.ts.MainUI` move the speakers to the car while its stock screen shows, and
 * move them back only for MainUI's own apps. `forfan.intent.action.CLOSEMEDIA` with the int `mode` 1
 * hands them back to Android without opening MainUI's launcher.
 */
object TsMainUiAudioChannel {

    const val MAINUI_PACKAGE = "com.ts.MainUI"
    private const val ACTION_CLOSE_MEDIA = "forfan.intent.action.CLOSEMEDIA"
    private const val EXTRA_MODE = "mode"
    private const val MODE_STAY = 1
    /** MainUI can still be closing its own screen when the projection resumes, so ask once more. */
    private const val RETRY_DELAY_MS = 1000L

    @Volatile private var present: Boolean? = null
    private val handler = Handler(Looper.getMainLooper())

    fun isPresent(context: Context): Boolean = present ?: runCatching {
        context.packageManager.getPackageInfo(MAINUI_PACKAGE, 0)
    }.isSuccess.also { present = it }

    /** Asks MainUI to hand the speakers back to Android. Does nothing on other units. */
    fun restore(context: Context, reason: String) {
        if (!isPresent(context)) return
        val app = context.applicationContext
        send(app)
        handler.postDelayed({ send(app) }, RETRY_DELAY_MS)
        AppLog.i("TsMainUiAudioChannel: asked $MAINUI_PACKAGE for Android audio ($reason)")
    }

    private fun send(context: Context) {
        try {
            // Explicit, so a manifest receiver in MainUI still hears it on Android 8 and later.
            context.sendBroadcast(Intent(ACTION_CLOSE_MEDIA).setPackage(MAINUI_PACKAGE).putExtra(EXTRA_MODE, MODE_STAY))
        } catch (e: Exception) {
            AppLog.w("TsMainUiAudioChannel: broadcast failed: ${e.message}")
        }
    }
}
