package com.andrerinas.openheadunit.utils

import android.R
import android.content.Context
import android.util.TypedValue
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object DialogUtils {
    fun showTextInputDialog(
        context: Context,
        titleResId: Int,
        currentValue: String?,
        onResult: (String) -> Unit,
    ) {
        showTextInputDialogWithMessage(context, titleResId, null, currentValue, onResult)
    }

    fun showTextInputDialogWithMessage(
        context: Context,
        titleResId: Int,
        messageResId: Int?,
        currentValue: String?,
        onResult: (String) -> Unit,
    ) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }

        if (messageResId != null) {
            val messageText = TextView(context).apply {
                setText(messageResId)
                val textColorAttr = TypedValue()
                context.theme.resolveAttribute(
                    R.attr.textColorSecondary,
                    textColorAttr,
                    true,
                )
                setTextColor(ContextCompat.getColor(context, textColorAttr.resourceId))
                textSize = 13f
                setPadding(0, 0, 0, 24)
            }
            container.addView(messageText)
        }

        val editText = EditText(context).apply {
            setText(currentValue)
            setSelection(text.length)
        }
        container.addView(editText)

        val dialog = MaterialAlertDialogBuilder(context, com.andrerinas.openheadunit.R.style.DarkAlertDialog)
            .setTitle(titleResId)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                val value = editText.text.toString().trim()
                onResult(value)
            }
            .setNeutralButton(com.andrerinas.openheadunit.R.string.reset) { _, _ ->
                onResult("")
            }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(editText.windowToken, 0)
            }
            .create()

        dialog.window?.clearFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
        )
        dialog.show()
        if (Settings(context).hudMirroring) {
            val root = dialog.window?.findViewById<android.view.View>(android.R.id.content) ?: dialog.window?.decorView
            root?.scaleX = -1.0f
        }
        editText.requestFocus()
    }

    /**
     * Two fields edited and applied as one value. The neutral button hands back a pair of empty
     * strings, the same convention the single-field dialog above uses for "let the app choose";
     * [onNeutral] is for a caller that has to tell that apart from an empty OK.
     */
    fun showTwoFieldDialog(
        context: Context,
        titleResId: Int,
        messageResId: Int,
        firstHintResId: Int,
        firstValue: String?,
        secondHintResId: Int,
        secondValue: String?,
        neutralResId: Int = com.andrerinas.openheadunit.R.string.reset,
        onNeutral: (() -> Unit)? = null,
        onResult: (String, String) -> Unit,
    ) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }

        val messageText = TextView(context).apply {
            setText(messageResId)
            val textColorAttr = TypedValue()
            context.theme.resolveAttribute(R.attr.textColorSecondary, textColorAttr, true)
            setTextColor(ContextCompat.getColor(context, textColorAttr.resourceId))
            textSize = 13f
            setPadding(0, 0, 0, 24)
        }
        container.addView(messageText)

        val first = EditText(context).apply {
            setHint(firstHintResId)
            setText(firstValue)
            setSelection(text.length)
        }
        container.addView(first)

        val second = EditText(context).apply {
            setHint(secondHintResId)
            setText(secondValue)
        }
        container.addView(second)

        val dialog = MaterialAlertDialogBuilder(context, com.andrerinas.openheadunit.R.style.DarkAlertDialog)
            .setTitle(titleResId)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                onResult(first.text.toString().trim(), second.text.toString().trim())
            }
            .setNeutralButton(neutralResId) { _, _ -> onNeutral?.invoke() ?: onResult("", "") }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(first.windowToken, 0)
            }
            .create()

        dialog.window?.clearFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
        )
        dialog.show()
        if (Settings(context).hudMirroring) {
            val root = dialog.window?.findViewById<android.view.View>(android.R.id.content) ?: dialog.window?.decorView
            root?.scaleX = -1.0f
        }
        first.requestFocus()
    }
}
