package com.andrerinas.openheadunit.main

import android.app.Activity
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.ActivityState
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * One-time app rename notice.
 *
 * Shown on whatever screen is in front when the app opens, including during an auto-connect and
 * on top of an active Android Auto projection. It is not cancelable and the "shown once" flag is
 * only spent when the user actually taps the button, so if a screen change hides it before it is
 * seen it simply comes back on the next screen. It still waits for onboarding to finish so it is
 * never spent behind the wizard.
 */
object RenameNotice {

    private var dialog: AlertDialog? = null

    fun isShowing(): Boolean = dialog?.isShowing == true

    fun maybeShow(activity: Activity?, settings: Settings) {
        if (activity == null || ActivityState.isGone(activity)) return
        if (settings.renameNoticeShown) return
        if (dialog?.isShowing == true) return
        if (settings.onboardingVersion < OnboardingActivity.CURRENT_ONBOARDING_VERSION) return

        val firstInstallTime = try {
            activity.packageManager.getPackageInfo(activity.packageName, 0).firstInstallTime
        } catch (e: PackageManager.NameNotFoundException) {
            0L
        }
        if (!RenameNoticePolicy.shouldOffer(firstInstallTime)) return

        try {
            dialog = MaterialAlertDialogBuilder(activity, R.style.DarkAlertDialog)
                .setIcon(R.drawable.ic_rename_notice)
                .setTitle(R.string.rename_notice_title)
                .setMessage(R.string.rename_notice_message)
                .setCancelable(false)
                .setPositiveButton(R.string.rename_notice_button) { d, _ ->
                    // Consume the one-time flag only once the user has actually acknowledged it.
                    settings.renameNoticeShown = true
                    d.dismiss()
                }
                .show()
        } catch (e: WindowManager.BadTokenException) {
            AppLog.w("RenameNotice: Window token invalid when showing dialog (activity state changed): ${e.message}")
        } catch (e: Exception) {
            AppLog.e("RenameNotice: Failed to show dialog: ${e.message}")
        }
    }

    fun dismiss() {
        try {
            if (dialog?.isShowing == true) {
                dialog?.dismiss()
            }
        } catch (e: Exception) {
            AppLog.w("RenameNotice: Error dismissing dialog: ${e.message}")
        } finally {
            dialog = null
        }
    }
}
