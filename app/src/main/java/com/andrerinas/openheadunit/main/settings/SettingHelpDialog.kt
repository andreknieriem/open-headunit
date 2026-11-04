package com.andrerinas.openheadunit.main.settings

import android.content.Context
import android.graphics.PorterDuff
import android.view.MenuItem
import androidx.appcompat.content.res.AppCompatResources
import androidx.annotation.StringRes
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.AppLog
import com.google.android.material.appbar.MaterialToolbar
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

    /** Adds a (?) action to the toolbar that opens the help for the whole screen, before Save or Reset. */
    fun addToolbarHelp(toolbar: MaterialToolbar, title: CharSequence, @StringRes helpResId: Int, id: String) {
        val context = toolbar.context
        val colors = SettingsPalette.from(context)
        val label = context.getString(R.string.setting_help_for, title)
        val item = toolbar.menu.add(0, R.id.settingHelp, 0, label)
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        AppCompatResources.getDrawable(
            context, if (colors.filledIcons) R.drawable.ic_sym_help_fill else R.drawable.ic_sym_help
        )?.mutate()?.let {
            it.setColorFilter(colors.onSurface, PorterDuff.Mode.SRC_IN)
            item.icon = it
        }
        item.setOnMenuItemClickListener {
            show(context, title, helpResId, "screen:$id")
            true
        }
    }
}
