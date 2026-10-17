package com.andrerinas.openheadunit.main

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * One-time question after updating into the redesigned home screen: use the new design or keep
 * the classic one. Either answer can be changed later in Customization.
 *
 * Like [RenameNotice], it is not cancelable and the flag is only spent on an answer, so a screen
 * change that hides it brings it back on the next screen. It waits for onboarding and for the
 * other one-time notices, so it never stacks on top of them.
 */
object HomeDesignNotice {

    private var dialog: AlertDialog? = null

    /** @param onClassicChosen called when the user keeps the classic design, to swap the screen. */
    fun maybeShow(activity: Activity?, settings: Settings, onClassicChosen: () -> Unit) {
        if (activity == null || activity.isFinishing) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed) return
        if (settings.homeDesignNoticeShown) return

        // Settled on the first run of a fresh install, so a later update never asks someone who
        // has only ever seen the new design.
        if (!isUpgrade(activity)) {
            settings.homeDesignNoticeShown = true
            return
        }

        if (dialog?.isShowing == true) return
        if (settings.onboardingVersion < OnboardingActivity.CURRENT_ONBOARDING_VERSION) return
        if (!settings.renameNoticeShown || !settings.aa174NoticeShown) return

        try {
            dialog = MaterialAlertDialogBuilder(activity, R.style.DarkAlertDialog)
                .setTitle(R.string.home_design_notice_title)
                .setMessage(R.string.home_design_notice_message)
                .setCancelable(false)
                .setPositiveButton(R.string.home_design_notice_use_new) { d, _ ->
                    settings.homeDesignNoticeShown = true
                    settings.useClassicHome = false
                    d.dismiss()
                }
                .setNegativeButton(R.string.home_design_notice_keep_classic) { d, _ ->
                    settings.homeDesignNoticeShown = true
                    settings.useClassicHome = true
                    d.dismiss()
                    onClassicChosen()
                }
                .show()
        } catch (e: WindowManager.BadTokenException) {
            AppLog.w("HomeDesignNotice: Window token invalid when showing dialog: ${e.message}")
        } catch (e: Exception) {
            AppLog.e("HomeDesignNotice: Failed to show dialog: ${e.message}")
        }
    }

    private fun isUpgrade(activity: Activity): Boolean = try {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        HomeDesignNoticePolicy.isUpgrade(info.firstInstallTime, info.lastUpdateTime)
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun dismiss() {
        try {
            if (dialog?.isShowing == true) {
                dialog?.dismiss()
            }
        } catch (e: Exception) {
            AppLog.w("HomeDesignNotice: Error dismissing dialog: ${e.message}")
        } finally {
            dialog = null
        }
    }
}
