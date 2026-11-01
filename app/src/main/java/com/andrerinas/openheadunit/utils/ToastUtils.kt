package com.andrerinas.openheadunit.utils

import android.content.Context
import android.widget.Toast
import com.andrerinas.openheadunit.App

/**
 * The one seam every toast goes through. Connection, disconnect and mode-switch status takes the
 * default and obeys the setting, which is what its description promises; pass [force] for anything
 * that must arrive whatever the setting says, meaning a failure, a refusal, or a confirmation
 * outside the connection flow. A null context skips: a toast is never worth a crash.
 */
object ToastUtils {
    @JvmStatic
    fun showToast(context: Context?, text: String, duration: Int = Toast.LENGTH_SHORT, force: Boolean = false) {
        if (context == null) return
        if (force || isToastEnabled(context)) {
            Toast.makeText(context, text, duration).show()
        }
    }

    @JvmStatic
    fun showToast(context: Context?, resId: Int, duration: Int = Toast.LENGTH_SHORT, force: Boolean = false) {
        if (context == null) return
        if (force || isToastEnabled(context)) {
            Toast.makeText(context, resId, duration).show()
        }
    }

    private fun isToastEnabled(context: Context): Boolean {
        // Locked USB switching still reports status, but cannot read the user's CE preference.
        if (!App.isUserUnlocked(context)) return true
        return try {
            App.provide(context).settings.showToastMessages
        } catch (_: Exception) {
            true
        }
    }
}
