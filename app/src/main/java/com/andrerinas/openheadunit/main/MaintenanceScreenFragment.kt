package com.andrerinas.openheadunit.main

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.maintenance.MaintenanceStore
import com.andrerinas.openheadunit.maintenance.MaintenanceTexts
import com.andrerinas.openheadunit.main.settings.SettingItem
import com.andrerinas.openheadunit.main.settings.SettingsAdapter
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The frame the maintenance screens share: a toolbar, the settings list, and the small dialogs
 * they edit with.
 *
 * Unlike most settings screens there is no Save button. These are records (the odometer, a
 * service done today), not configuration that needs a restart, and a service marked as done must
 * not be lost to a back press. Every change is written when it is made.
 */
abstract class MaintenanceScreenFragment : Fragment() {

    protected val store: MaintenanceStore get() = App.provide(requireContext()).maintenanceStore

    private lateinit var toolbar: MaterialToolbar
    private lateinit var recyclerView: RecyclerView
    private val adapter = SettingsAdapter()

    protected abstract fun title(): String

    protected abstract fun rows(): List<SettingItem>

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_maintenance, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar = view.findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { navigateBack() }
        recyclerView = view.findViewById(R.id.recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        // The odometer and the GPS state change while the screen is open; the list diff keeps
        // a refresh this often cheap.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    refresh()
                    delay(REFRESH_MS)
                }
            }
        }
    }

    protected fun refresh() {
        if (view == null || !isAdded) return
        toolbar.title = title()
        val scrollState = recyclerView.layoutManager?.onSaveInstanceState()
        adapter.submitList(rows()) {
            scrollState?.let { recyclerView.layoutManager?.onRestoreInstanceState(it) }
        }
    }

    protected fun navigateBack() {
        try {
            if (!findNavController().navigateUp()) requireActivity().finish()
        } catch (e: Exception) {
            requireActivity().finish()
        }
    }

    /**
     * Asks for a whole number between [min] and [max]. [onCancel] runs when the dialog is closed
     * without a valid number, so a setup in progress can step back.
     */
    protected fun numberDialog(
        title: String,
        message: String?,
        current: Long?,
        min: Long,
        max: Long,
        onCancel: () -> Unit = {},
        onResult: (Long) -> Unit,
    ) {
        val field = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(current?.toString() ?: "")
            setSelection(text.length)
        }
        var accepted = false
        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(title)
            .setView(dialogBody(message, field))
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { if (!accepted) onCancel() }
            .create()
        showWithKeyboard(dialog, field)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = field.text.toString().trim().toLongOrNull()
            if (value == null || value < min || value > max) {
                field.error = getString(
                    R.string.maintenance_number_range, MaintenanceTexts.number(min), MaintenanceTexts.number(max)
                )
                return@setOnClickListener
            }
            accepted = true
            dialog.dismiss()
            onResult(value)
        }
    }

    protected fun textDialog(@StringRes title: Int, @StringRes hint: Int, current: String, onResult: (String) -> Unit) {
        val field = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setHint(hint)
            setText(current)
            setSelection(text.length)
        }
        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(title)
            .setView(dialogBody(null, field))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = field.text.toString().trim()
                if (value.isNotEmpty()) onResult(value)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        showWithKeyboard(dialog, field)
    }

    protected fun confirm(title: String, message: String, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> onConfirm() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun dialogBody(message: String?, field: EditText): View {
        val padding = resources.getDimensionPixelSize(R.dimen.maintenance_dialog_padding)
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            if (message != null) {
                addView(TextView(context).apply {
                    text = message
                    setPadding(0, 0, 0, padding / 2)
                })
            }
            addView(field)
        }
    }

    // Head unit dialogs come up without the keyboard unless these flags are cleared.
    private fun showWithKeyboard(dialog: AlertDialog, field: EditText) {
        dialog.window?.clearFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        )
        dialog.show()
        field.requestFocus()
    }

    private companion object {
        const val REFRESH_MS = 5_000L
    }
}
