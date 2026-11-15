package com.andrerinas.openheadunit.main

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.app.BaseActivity
import com.andrerinas.openheadunit.maintenance.Distance
import com.andrerinas.openheadunit.maintenance.MaintenanceDuePolicy
import com.andrerinas.openheadunit.maintenance.MaintenanceNoticePolicy
import com.andrerinas.openheadunit.maintenance.MaintenanceNotifier
import com.andrerinas.openheadunit.maintenance.MaintenanceReminderPolicy
import com.andrerinas.openheadunit.maintenance.MaintenanceStore
import com.andrerinas.openheadunit.maintenance.MaintenanceTexts
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.SystemUI
import com.andrerinas.openheadunit.utils.ToastUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Calendar

/**
 * The full screen maintenance notice, shown when the unit starts and when the car stops after a
 * drive. One item at a time, the most urgent first, each with its answer: done, later, next start.
 *
 * Closing it, with the cross or Back, is "next time I start" for whatever it did not get to.
 */
class MaintenanceNoticeActivity : BaseActivity() {

    private val store: MaintenanceStore get() = App.provide(this).maintenanceStore

    /** The items this notice goes through, fixed when it opens so answering one never reorders the rest. */
    private var queue = arrayListOf<String>()
    private var position = 0

    private lateinit var root: View
    private lateinit var header: TextView
    private lateinit var title: TextView
    private lateinit var detail: TextView
    private lateinit var progress: ProgressBar
    private lateinit var last: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = Settings(this).screenOrientation.androidOrientation
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_maintenance_notice)
        root = findViewById(R.id.notice_root)
        header = findViewById(R.id.notice_header)
        title = findViewById(R.id.notice_title)
        detail = findViewById(R.id.notice_detail)
        progress = findViewById(R.id.notice_progress)
        last = findViewById(R.id.notice_last)
        layOut()

        findViewById<View>(R.id.notice_close).setOnClickListener { closeAll() }
        findViewById<Button>(R.id.notice_done).setOnClickListener { current()?.let { askDone(it) } }
        findViewById<Button>(R.id.notice_later).setOnClickListener { current()?.let { askLater(it) } }
        findViewById<Button>(R.id.notice_next_start).setOnClickListener { current()?.let { nextStart(it) } }
        findViewById<Button>(R.id.notice_details).setOnClickListener { current()?.let { openDetails(it) } }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = closeAll()
        })

        if (savedInstanceState != null) {
            queue = savedInstanceState.getStringArrayList(KEY_QUEUE) ?: arrayListOf()
            position = savedInstanceState.getInt(KEY_POSITION, 0)
        } else {
            startQueue()
        }

        SystemUI.apply(window, root, Settings(this).fullscreenMode, notesCanvas = false)
        SystemUI.keepLegacyBarsHidden(window, Settings(this).fullscreenMode)
    }

    override fun onStart() {
        super.onStart()
        isShowing = true
    }

    override fun onResume() {
        super.onResume()
        // Back from the item's details, where it may have been marked as done or edited.
        render()
    }

    override fun onStop() {
        super.onStop()
        isShowing = false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(KEY_QUEUE, queue)
        outState.putInt(KEY_POSITION, position)
    }

    private fun startQueue() {
        queue = ArrayList(store.pendingReminders(System.currentTimeMillis()).map { it.item.id })
        position = 0
        AppLog.i("$TAG: showing ${queue.size} item(s) (moment=${intent.getStringExtra(EXTRA_MOMENT)})")
    }

    /** Side by side when the screen is wider than tall, stacked otherwise. */
    private fun layOut() {
        val wide = resources.configuration.screenWidthDp > resources.configuration.screenHeightDp
        findViewById<LinearLayout>(R.id.notice_body).orientation =
            if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val info = findViewById<View>(R.id.notice_info)
        info.layoutParams = (info.layoutParams as LinearLayout.LayoutParams).apply {
            width = if (wide) 0 else LinearLayout.LayoutParams.MATCH_PARENT
            weight = if (wide) 1f else 0f
        }
        val actions = findViewById<View>(R.id.notice_actions)
        actions.layoutParams = (actions.layoutParams as LinearLayout.LayoutParams).apply {
            width = if (wide) resources.getDimensionPixelSize(R.dimen.maintenance_notice_actions_width)
            else LinearLayout.LayoutParams.MATCH_PARENT
        }
    }

    /**
     * The entry on screen, skipping what was answered elsewhere since the notice opened (the
     * notification, the details screen). Null once nothing is left, and the notice is closing.
     */
    private fun current(): MaintenanceReminderPolicy.Entry? {
        val pending = store.pendingReminders(System.currentTimeMillis()).associateBy { it.item.id }
        while (position < queue.size) {
            pending[queue[position]]?.let { return it }
            position++
        }
        return null
    }

    private fun render() {
        val entry = current() ?: return finishQueue()
        val overdue = entry.due.state == MaintenanceDuePolicy.State.OVERDUE
        root.setBackgroundColor(color(if (overdue) R.color.maintenance_notice_overdue_bg else R.color.maintenance_notice_soon_bg))
        val moment = getString(
            if (intent.getStringExtra(EXTRA_MOMENT) == MaintenanceNoticePolicy.Moment.STOP.name) R.string.maintenance_notice_stopped
            else R.string.maintenance_notice_started
        )
        header.text = if (queue.size > 1) {
            getString(R.string.maintenance_item_value, moment, getString(R.string.maintenance_notice_position, position + 1, queue.size))
        } else moment
        title.text = MaintenanceTexts.noticeTitle(this, entry)
        detail.text = MaintenanceTexts.noticeDetail(this, entry, store.miles)
        detail.setTextColor(color(if (overdue) R.color.maintenance_notice_overdue_soft else R.color.maintenance_notice_soon_soft))
        // A new drawable each time: the layer list's clip level is per instance.
        progress.progressDrawable = ContextCompat.getDrawable(
            this, if (overdue) R.drawable.maintenance_notice_progress_overdue else R.drawable.maintenance_notice_progress_soon
        )
        progress.progress = Math.round(Math.min(1.0, Math.max(0.0, entry.due.progress)) * 100).toInt()
        last.text = MaintenanceTexts.noticeLast(this, entry.item, store.miles)
    }

    private fun advance(message: String) {
        ToastUtils.showToast(this, message, Toast.LENGTH_SHORT, force = true)
        position++
        MaintenanceNotifier.refresh(this)
        render()
    }

    private fun finishQueue() {
        MaintenanceNotifier.refresh(this)
        finish()
    }

    private fun closeAll() {
        val left = queue.drop(position)
        store.setAside(left)
        if (left.isNotEmpty()) AppLog.i("$TAG: closed, ${left.size} item(s) wait for the next start")
        finishQueue()
    }

    private fun nextStart(entry: MaintenanceReminderPolicy.Entry) {
        store.setAside(listOf(entry.item.id))
        advance(getString(R.string.maintenance_notice_next_start_done))
    }

    private fun askLater(entry: MaintenanceReminderPolicy.Entry) {
        val miles = store.miles
        val choices = MaintenanceNoticePolicy.laterChoices(entry.item, miles)
        if (choices.isEmpty()) return nextStart(entry)
        val labels = choices.map { getString(R.string.maintenance_notice_later_in, MaintenanceTexts.later(this, it, miles)) }
        MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
            .setTitle(R.string.maintenance_notice_later)
            .setItems(labels.toTypedArray()) { _, which ->
                val choice = choices[which]
                store.snooze(entry.item.id, choice, System.currentTimeMillis())
                advance(getString(R.string.maintenance_notice_later_done, MaintenanceTexts.later(this, choice, miles)))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Done, with the odometer and the date the service was really made, both editable. */
    private fun askDone(entry: MaintenanceReminderPolicy.Entry) {
        val miles = store.miles
        var atMs = System.currentTimeMillis()
        val padding = resources.getDimensionPixelSize(R.dimen.maintenance_dialog_padding)
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Distance.toUnits(store.odometerMeters(), miles).toString())
        }
        val dateButton = Button(this).apply { text = MaintenanceTexts.date(context, atMs) }
        dateButton.setOnClickListener {
            pickDate(atMs) { picked ->
                atMs = picked
                dateButton.text = MaintenanceTexts.date(this, picked)
            }
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(label(getString(if (miles) R.string.maintenance_notice_done_at_mi else R.string.maintenance_notice_done_at_km)))
            addView(field)
            addView(label(getString(R.string.maintenance_last_date)))
            addView(dateButton)
        }
        val dialog = MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
            .setTitle(getString(R.string.maintenance_notice_done_title, MaintenanceTexts.itemName(this, entry.item)))
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        // Head unit dialogs come up without the keyboard unless these flags are cleared.
        dialog.window?.clearFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        )
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val units = field.text.toString().trim().toLongOrNull()
            if (units == null || units < 0 || units > MAX_ODOMETER_UNITS) {
                field.error = getString(
                    R.string.maintenance_number_range, MaintenanceTexts.number(0), MaintenanceTexts.number(MAX_ODOMETER_UNITS)
                )
                return@setOnClickListener
            }
            dialog.dismiss()
            store.markDone(entry.item.id, Distance.fromUnits(units, miles), atMs)
            advance(getString(R.string.maintenance_marked_done))
        }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setPadding(0, resources.getDimensionPixelSize(R.dimen.maintenance_dialog_padding) / 2, 0, 0)
    }

    private fun pickDate(currentMs: Long, onPicked: (Long) -> Unit) {
        val calendar = Calendar.getInstance().apply { timeInMillis = currentMs }
        DatePickerDialog(this, { _, year, month, day ->
            // Midday, so a time zone change cannot move it to the day before or after.
            val picked = Calendar.getInstance().apply {
                clear()
                set(year, month, day, 12, 0)
            }
            onPicked(Math.min(picked.timeInMillis, System.currentTimeMillis()))
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
    }

    /** The item's own screen, over this notice, which is still here on the way back. */
    private fun openDetails(entry: MaintenanceReminderPolicy.Entry) {
        startActivity(
            Intent(this, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_DESTINATION, R.id.maintenanceFragment)
                .putExtra(SettingsActivity.EXTRA_MAINTENANCE_ITEM_ID, entry.item.id)
        )
    }

    private fun color(res: Int) = ContextCompat.getColor(this, res)

    companion object {
        private const val TAG = "MaintenanceNotice"
        private const val KEY_QUEUE = "queue"
        private const val KEY_POSITION = "position"
        private const val EXTRA_MOMENT = "moment"

        /** Seven digits, more than any dashboard shows. */
        private const val MAX_ODOMETER_UNITS = 9_999_999L

        /** On screen now, so another moment refreshes it instead of stacking a second one. */
        @Volatile
        var isShowing = false
            private set

        fun intent(context: Context, moment: MaintenanceNoticePolicy.Moment): Intent =
            Intent(context, MaintenanceNoticeActivity::class.java)
                .putExtra(EXTRA_MOMENT, moment.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
