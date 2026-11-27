package com.andrerinas.openheadunit.main.settings

import android.content.Context
import android.text.InputType
import android.widget.EditText
import com.andrerinas.openheadunit.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** The number prompt that the settings page and its sub pages share. */
fun showNumericInputDialog(
    context: Context,
    title: String,
    message: String?,
    initialValue: Int,
    onConfirm: (Int) -> Unit
) {
    val editView = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(if (initialValue == 0 && title.contains("DPI", true)) "" else initialValue.toString())
    }

    // Use a container to add padding around the EditText
    val container = android.widget.FrameLayout(context)
    val params = android.widget.FrameLayout.LayoutParams(
        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
    )
    val margin = (24 * context.resources.displayMetrics.density).toInt()
    params.setMargins(margin, 8, margin, 8)
    container.addView(editView, params)

    val dialog = MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
        .setTitle(title)
        .apply { if (message != null) setMessage(message) }
        .setView(container)
        .setPositiveButton(android.R.string.ok) { dialog, _ ->
            val newVal = (editView.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
            onConfirm(newVal)
            dialog.dismiss()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .setOnDismissListener {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(editView.windowToken, 0)
        }
        .create()

    dialog.window?.clearFlags(
        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
    )
    dialog.show()
    editView.requestFocus()
}
