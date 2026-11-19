package com.andrerinas.openheadunit.connection.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ToastUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * UI helpers for surfacing structured [ClassifiedConnectionError] codes in error dialogs, toast
 * notifications, and status screens, and copying privacy-safe diagnostics to the clipboard.
 */
object ConnectionDiagnosticsUi {

    private const val CLIPBOARD_LABEL = "Open HeadUnit Diagnostics"
    private const val COPY_DIAGNOSTICS_BUTTON_TEXT = "Copy Diagnostics"

    /**
     * Formats [snapshot] via [DiagnosticsExporter] and copies it directly to the system clipboard.
     *
     * @return the formatted, privacy-safe diagnostics string that was placed on the clipboard.
     */
    fun copyDiagnosticsToClipboard(
        context: Context,
        snapshot: DiagnosticsSnapshot = ConnectionDiagnosticsTracker.currentSnapshot(context),
    ): String {
        val formatted = DiagnosticsExporter.format(snapshot)
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText(CLIPBOARD_LABEL, formatted))
            AppLog.i("Diagnostics: copied privacy-safe diagnostics report to clipboard (errorCode=${snapshot.errorCode?.name ?: "NONE"})")
            ToastUtils.showToast(
                context,
                "Diagnostics copied to clipboard",
                Toast.LENGTH_SHORT,
                force = true,
            )
        } catch (e: Exception) {
            AppLog.w("Diagnostics: failed to copy diagnostics to clipboard: ${e.message}")
        }
        return formatted
    }

    /**
     * Displays a toast notification containing the exact [ConnectionErrorCode] and user-friendly
     * description.
     */
    fun showErrorToast(
        context: Context,
        error: ClassifiedConnectionError,
    ) {
        ToastUtils.showToast(
            context,
            error.formattedToastText(),
            Toast.LENGTH_LONG,
            force = true,
        )
    }

    /**
     * Displays a structured error dialog containing the exact [ConnectionErrorCode], stage,
     * user-friendly description, context-sensitive troubleshooting suggestions, and a
     * "Copy Diagnostics" button.
     */
    fun showErrorDialog(
        context: Context,
        error: ClassifiedConnectionError? = ConnectionDiagnosticsTracker.latestError.value,
        onDismiss: (() -> Unit)? = null,
    ): AlertDialog {
        val snapshot = ConnectionDiagnosticsTracker.currentSnapshot(context)
        val title = error?.formattedDialogTitle() ?: "Connection Diagnostics"
        val message = error?.formattedDialogMessage() ?: DiagnosticsExporter.format(snapshot)

        val dialog = MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(title)
            .setMessage(message)
            .setNeutralButton(COPY_DIAGNOSTICS_BUTTON_TEXT) { _, _ ->
                copyDiagnosticsToClipboard(context, ConnectionDiagnosticsTracker.currentSnapshot(context))
            }
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { onDismiss?.invoke() }
            .create()
        dialog.show()
        return dialog
    }
}
