package com.andrerinas.openheadunit

import android.app.Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.andrerinas.openheadunit.utils.OemAppManager
import androidx.appcompat.app.AppCompatDelegate
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.multidex.MultiDex
import com.andrerinas.openheadunit.main.BackgroundNotification
import com.andrerinas.openheadunit.aap.AapNavigation
import com.andrerinas.openheadunit.ssl.ConscryptInitializer
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.AppThemeManager
import com.andrerinas.openheadunit.utils.Settings
import android.os.SystemClock
import com.andrerinas.openheadunit.main.AppDrawerFragment
import com.andrerinas.openheadunit.main.FloatingButtonManager
import java.io.File

class App : Application(), Application.ActivityLifecycleCallbacks {

    private var startedActivityCount = 0

    private val component: AppComponent by lazy {
        AppComponent(this)
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerActivityLifecycleCallbacks(this)

        try {
            val packageReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    AppDrawerFragment.invalidateCache()
                }
            }
            val packageFilter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            }
            ContextCompat.registerReceiver(this, packageReceiver, packageFilter, ContextCompat.RECEIVER_EXPORTED)
        } catch (e: Exception) {
            AppLog.w("App", "Failed to register package change receiver", e)
        }



        // Enable vector drawable support on older Android versions
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)

        if (ConscryptInitializer.isNeededForTls12()) {
            ConscryptInitializer.initialize()
        }

        if (isUserUnlocked()) {
            initUnlockedOnce()
        } else {
            AppLog.init(null, this) // Initialize with default logging if locked
            AppLog.w("App started in Direct Boot mode (locked). Settings access deferred.")
            // The process outlives the lock screen, so without this it would spend the rest of
            // its life on defaults: the user's log level, their theme and the auto-start mirrors
            // would all stay unread until the process happened to die.
            ContextCompat.registerReceiver(
                this, userUnlockedReceiver, IntentFilter(Intent.ACTION_USER_UNLOCKED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        if (ConscryptInitializer.isAvailable()) {
            AppLog.i("Conscrypt security provider is active")
        } else if (ConscryptInitializer.isNeededForTls12()) {
            AppLog.w("Conscrypt not available - TLS 1.2 may not work on this device")
        }

        AppLog.d( "native library dir ${applicationInfo.nativeLibraryDir}")

        File(applicationInfo.nativeLibraryDir).listFiles()?.forEach { file ->
            AppLog.d( "   ${file.name}")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val serviceChannel = NotificationChannel(defaultChannel, "Headunit Service", NotificationManager.IMPORTANCE_LOW)
            serviceChannel.description = "Persistent service notification"
            serviceChannel.setShowBadge(false)
            notificationManager.createNotificationChannel(serviceChannel)

            val mediaChannel = NotificationChannel(BackgroundNotification.mediaChannel, "Media Playback", NotificationManager.IMPORTANCE_LOW)
            mediaChannel.setSound(null, null)
            mediaChannel.setShowBadge(false)
            notificationManager.createNotificationChannel(mediaChannel)

            AapNavigation.createNotificationChannel(this)

            val bootChannel = NotificationChannel(bootStartChannel, "Boot Auto-Start", NotificationManager.IMPORTANCE_HIGH)
            bootChannel.description = "Shown once after boot to open the app"
            bootChannel.setShowBadge(false)
            notificationManager.createNotificationChannel(bootChannel)
        }

        // Register the main broadcast receiver safely for Android 14+ using ContextCompat
        ContextCompat.registerReceiver(this, AapBroadcastReceiver(), AapBroadcastReceiver.filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /**
     * Everything that needs credential-encrypted storage, including the object graph itself:
     * AppComponent builds a VideoDecoder whose fields read Settings, so touching it before the
     * first unlock throws and takes the whole process down. Runs once, at start or at unlock.
     */
    private fun initUnlockedOnce() {
        if (unlockedInitDone) return
        unlockedInitDone = true

        // Root support
        component.suExecutor.register()
        if (Build.VERSION.SDK_INT >= 26)
            com.andrerinas.openheadunit.connection.wifi.scan.WifiScanControl.initialize(this)

        val settings = Settings(this) // Create a Settings instance
        AppLog.init(settings, this) // Initialize AppLog with settings for conditional logging

        // Sync auto-start settings to device-protected storage so that
        // BootCompleteReceiver, UsbAttachedActivity, and AutoStartReceiver
        // can read them during locked boot (before user unlock)
        Settings.syncAutoStartOnBootToDeviceStorage(this, settings.autoStartOnBoot)
        Settings.syncAutoStartOnUsbToDeviceStorage(this, settings.autoStartOnUsb)
        Settings.syncAutoStartOnWifiToDeviceStorage(this, settings.autoStartOnWifi)
        Settings.syncAutoStartWifiSsidToDeviceStorage(this, settings.autoStartWifiSsid)
        Settings.syncAutoStartBtMacsToDeviceStorage(this, settings.autoStartBluetoothDeviceMacs)
        // The blacklist joins them: an install that predates the mirror has its list only in
        // credential storage, so without this it is invisible until the user edits it.
        Settings.syncUsbBlacklistToDeviceStorage(this, settings.usbBlacklist)

        // Apply app theme (runs the live manager when dynamic, or when a saved place
        // can force the app theme even over a static base).
        AppThemeManager.reapply(this, settings)

        if (settings.autoKillOemApps) {
            CoroutineScope(Dispatchers.IO).launch {
                OemAppManager.runAutoKillIfEnabled(this@App)
            }
        }
    }

    private var unlockedInitDone = false

    private val userUnlockedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            AppLog.i("App: user unlocked, credential storage is available, applying settings")
            initUnlockedOnce()
            try {
                unregisterReceiver(this)
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    private fun isUserUnlocked(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val userManager = getSystemService(Context.USER_SERVICE) as UserManager
            userManager.isUserUnlocked
        } else {
            true
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {
        startedActivityCount++
        if (startedActivityCount == 1) {
            hasStartedActivity = true
            FloatingButtonManager.onAppForegroundChanged(this, isForeground = true)
        }
    }
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {
        startedActivityCount--
        if (startedActivityCount <= 0) {
            startedActivityCount = 0
            hasStartedActivity = false
            FloatingButtonManager.onAppForegroundChanged(this, isForeground = false)
        }
    }
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    companion object {
        init {
            AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
        }

        const val defaultChannel = "headunit_service_v2"
        const val bootStartChannel = "headunit_boot_start"
        val appStartTime = SystemClock.elapsedRealtime()
        var appThemeManager: AppThemeManager? = null
        var isPiPActive = false

        /**
         * Whether any activity of ours is started, which is the one case where the background
         * activity-launch restriction does not apply. Read from the service, hence @Volatile.
         */
        @Volatile
        var hasStartedActivity = false
            private set

        @Volatile
        var instance: App? = null
            private set


        fun get(context: Context): App {
            return context.applicationContext as App
        }
        fun provide(context: Context): AppComponent {
            return get(context).component
        }
    }
}
