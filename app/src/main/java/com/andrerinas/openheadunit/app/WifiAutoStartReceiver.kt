package com.andrerinas.openheadunit.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.NetworkInfo
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.App
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.main.MainActivity
import com.andrerinas.openheadunit.main.SettingsActivity
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import android.os.Build
import android.os.UserManager

class WifiAutoStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WifiManager.NETWORK_STATE_CHANGED_ACTION) return

        val networkInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO)
        }

        if (networkInfo != null && networkInfo.isConnected) {
            checkAndStart(context)
        }
    }

    companion object {
        fun checkAndStart(context: Context) {
            // Read settings from device-protected storage for maximum reliability
            if (!Settings.isAutoStartOnWifiEnabled(context)) return
            val targetSsid = Settings.getAutoStartWifiSsid(context).removeSurrounding("\"")
            if (targetSsid.isEmpty()) return

            // Before the first unlock there is no credential storage, which both the session and
            // the object graph read, so there is nothing to start yet. BootCompleteReceiver runs
            // this decision again on ACTION_USER_UNLOCKED. Asked after the settings above so a
            // user who never enabled this does not get the line on every locked boot.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                !(context.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked) {
                AppLog.w("WifiAutoStartReceiver: device is locked, deferring WiFi auto-start until unlock.")
                return
            }

            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo: WifiInfo? = wifiManager.connectionInfo
            
            val currentSsid = wifiInfo?.ssid?.removeSurrounding("\"")
            
            if (currentSsid == null || currentSsid == "<unknown ssid>") {
                AppLog.d("WifiAutoStartReceiver: No valid SSID connected yet.")
                return
            }

            AppLog.d("WifiAutoStartReceiver: Checking WiFi: $currentSsid (Target: $targetSsid)")

            if (currentSsid.equals(targetSsid, ignoreCase = true)) {
                AppLog.i("WifiAutoStartReceiver: MATCH! Starting AapService via WiFi Auto-start...")

                // Don't trigger if already connected
                if (App.provide(context).commManager.isConnected) {
                    AppLog.d("WifiAutoStartReceiver: Already connected to Android Auto. Ignoring event.")
                    return
                }

                // Start the service
                val serviceIntent = Intent(context, AapService::class.java)
                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } catch (e: Exception) {
                    AppLog.e("Failed to start AapService from background: ${e.message}")
                }

                // The app is already open there, and raising the home screen would close settings.
                if (SettingsActivity.isVisible) {
                    AppLog.i("WifiAutoStartReceiver: the settings screen is on show, so the home screen is not raised.")
                    return
                }

                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(MainActivity.EXTRA_LAUNCH_SOURCE, "WiFi auto-start")
                }

                // Android < 10 (API < 29): Direct startActivity works without background restrictions
                // and prevents opening/locking the system notification shade on Android 6/7 head units.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    try {
                        context.startActivity(launchIntent)
                        AppLog.i("WifiAutoStartReceiver: Direct startActivity succeeded (API ${Build.VERSION.SDK_INT} < 29).")
                        return
                    } catch (e: Exception) {
                        AppLog.w("WifiAutoStartReceiver: Direct startActivity failed, falling back to notification: ${e.message}")
                    }
                }

                // Android 10+ (API 29+): FullScreenIntent notification fallback for OS background restrictions
                val pendingIntent = PendingIntent.getActivity(
                    context, 0, launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
                )

                val notification = NotificationCompat.Builder(context, App.bootStartChannel)
                    .setSmallIcon(R.drawable.ic_stat_aa)
                    .setContentTitle(context.getString(R.string.wifi_autostart_title))
                    .setContentText(context.getString(R.string.wifi_autostart_content, currentSsid))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_EVENT)
                    .setAutoCancel(true)
                    .setFullScreenIntent(pendingIntent, true)
                    .setContentIntent(pendingIntent)
                    .build()

                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(99, notification)
                AppLog.i("WifiAutoStartReceiver: Triggered FullScreenIntent notification.")
            }
        }
    }
}
