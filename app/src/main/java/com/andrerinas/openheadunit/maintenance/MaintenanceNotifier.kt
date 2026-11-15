package com.andrerinas.openheadunit.maintenance

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.main.SettingsActivity
import com.andrerinas.openheadunit.utils.AppLog

/**
 * The Android notification for maintenance: one for everything pending, never one per item.
 *
 * It is an extra. Many head units never show notifications, so the notice and the home carry
 * the reminders on their own and nothing waits on this.
 */
object MaintenanceNotifier {

    private const val TAG = "MaintenanceNotifier"
    private const val CHANNEL_ID = "maintenance"
    private const val NOTIFICATION_ID = 4_206

    private const val ACTION_NEXT_START = "com.andrerinas.openheadunit.maintenance.NEXT_START"
    private const val ACTION_DISMISSED = "com.andrerinas.openheadunit.maintenance.DISMISSED"

    /** Posts or refreshes the notification for [pending], or takes it away when nothing is. */
    fun show(context: Context, pending: List<MaintenanceReminderPolicy.Entry>, miles: Boolean) {
        val first = pending.firstOrNull() ?: return cancel(context)
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            AppLog.i("$TAG: notifications are off for this app, only the notice and the home remind")
            return
        }
        ensureChannel(context)
        val detail = MaintenanceTexts.noticeDetail(context, first, miles)
        val text = if (pending.size > 1) context.getString(R.string.home_maintenance_more, detail, pending.size - 1) else detail
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_aa)
            .setContentTitle(MaintenanceTexts.noticeTitle(context, first))
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            // Refreshed when an item is marked as done from the notice; only a new post may sound.
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, 0, openListIntent(context), immutable()))
            .setDeleteIntent(broadcast(context, ACTION_DISMISSED))
            .addAction(0, context.getString(R.string.maintenance_notification_open),
                PendingIntent.getActivity(context, 1, openListIntent(context), immutable()))
            .addAction(0, context.getString(R.string.maintenance_notice_next_start), broadcast(context, ACTION_NEXT_START))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Android 13 and up without the permission; areNotificationsEnabled covers it, but some
            // ROMs disagree with themselves.
            AppLog.w("$TAG: the notification was refused: ${e.message}")
        }
    }

    /**
     * Brings a posted notification up to date after an answer in the notice, without posting one
     * the user turned off or swiped away in this run.
     */
    fun refresh(context: Context) {
        val store = App.provide(context).maintenanceStore
        if (!store.enabled || !store.remindNotification || store.notificationDismissed) return cancel(context)
        show(context, store.pendingReminders(System.currentTimeMillis()), store.miles)
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        // Created on every post so the name follows the app language; Android keeps the user's
        // choices for the channel.
        val channel = NotificationChannel(
            CHANNEL_ID, context.getString(R.string.maintenance_notification_channel), NotificationManager.IMPORTANCE_DEFAULT
        )
        channel.description = context.getString(R.string.maintenance_notification_channel_description)
        manager.createNotificationChannel(channel)
    }

    private fun openListIntent(context: Context): Intent = Intent(context, SettingsActivity::class.java)
        .putExtra(SettingsActivity.EXTRA_DESTINATION, R.id.maintenanceFragment)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun broadcast(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        context, action.hashCode(), Intent(context, Receiver::class.java).setAction(action), immutable()
    )

    private fun immutable(): Int = PendingIntent.FLAG_UPDATE_CURRENT or
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

    /** The notification's own buttons and its swipe. Not exported: only our PendingIntents reach it. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val store = App.provide(context).maintenanceStore
            when (intent.action) {
                ACTION_NEXT_START -> {
                    store.setAside(store.pendingReminders(System.currentTimeMillis()).map { it.item.id })
                    store.notificationDismissed = true
                    cancel(context)
                    AppLog.i("$TAG: set aside until the next start from the notification")
                }
                ACTION_DISMISSED -> {
                    store.notificationDismissed = true
                    AppLog.i("$TAG: swiped away, back on the next start")
                }
            }
        }
    }
}
