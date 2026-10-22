package com.andrerinas.openheadunit.main.settings

import android.content.Context
import androidx.annotation.StringRes
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.AppLog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** The dialog a (?) opens. The message scrolls inside the dialog, so a long text fits any panel. */
object SettingHelpDialog {

    fun show(context: Context, title: CharSequence, @StringRes helpResId: Int, id: String) {
        AppLog.d("SettingHelpDialog: help opened for $id")
        MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(title)
            .setMessage(helpResId)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
