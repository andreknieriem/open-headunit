package com.andrerinas.openheadunit.connection.carkey.fyt

import android.content.Context
import android.content.Intent
import com.andrerinas.openheadunit.utils.AppLog

/** Sends [FytCallScreenPolicy]'s ask to the FYT server app. Does nothing on any other unit. */
object FytCallScreen {
    @Volatile private var fytUnit: Boolean? = null
    @Volatile private var suppressing = false

    fun isFytUnit(context: Context): Boolean = fytUnit ?: runCatching {
        context.packageManager.getPackageInfo(FytCallScreenPolicy.SERVER_PACKAGE, 0)
        true
    }.getOrDefault(false).also { fytUnit = it }

    /** Returns false when there is nothing to repeat. */
    fun suppress(context: Context, enabled: Boolean, foreground: Boolean): Boolean {
        if (!FytCallScreenPolicy.shouldSuppress(enabled, isFytUnit(context), foreground)) return false
        if (!suppressing) AppLog.i("FytCallScreen: asking the unit to keep its call screen off the projection")
        suppressing = true
        send(context, FytCallScreenPolicy.KEY_SUPPRESS)
        return true
    }

    /** Hands the call screen back, so a call taken outside the projection shows as before. */
    fun restore(context: Context) {
        if (!suppressing) return
        suppressing = false
        AppLog.i("FytCallScreen: handing the call screen back to the unit")
        send(context, FytCallScreenPolicy.KEY_RESTORE)
    }

    private fun send(context: Context, key: String) {
        runCatching {
            context.sendBroadcast(
                Intent(FytCallScreenPolicy.ACTION)
                    .setPackage(FytCallScreenPolicy.SERVER_PACKAGE)
                    .putExtra(FytCallScreenPolicy.EXTRA_KEY, key)
            )
        }.onFailure { AppLog.w("FytCallScreen: broadcast failed: ${it.message}") }
    }
}
