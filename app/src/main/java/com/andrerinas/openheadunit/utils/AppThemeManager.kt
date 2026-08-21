package com.andrerinas.openheadunit.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings as SystemSettings
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import com.andrerinas.openheadunit.contract.LocationUpdateIntent
import java.util.Calendar

class AppThemeManager(
    private val context: Context,
    private val settings: Settings
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private var nightModeCalculator = NightMode(settings, false)

    private var lastEmittedNight: Boolean? = null
    private var currentLux: Float = -1f
    private var currentBrightness: Int = -1
    private var isFirstSensorReading = true
    private var isSensorRegistered = false
    private var isObserverRegistered = false
    private var isCarSignalObserverRegistered = false

    private val handler = Handler(Looper.getMainLooper())

    private var pendingValue: Boolean? = null
    private val debounceRunnable = Runnable {
        pendingValue?.let { isNight ->
            if (lastEmittedNight != isNight) {
                lastEmittedNight = isNight
                applyNightMode(isNight)
            }
        }
    }

    private val brightnessObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            update()
        }
    }

    // Car ILL+ is a binary signal already debounced by the car hardware, so emit
    // immediately instead of going through the 2s sensor-noise debounce.
    private val carSignalObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            update(debounce = false)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == LocationUpdateIntent.action) {
                val oldLux = currentLux
                val oldBright = currentBrightness
                nightModeCalculator = NightMode(settings, true)
                nightModeCalculator.currentLux = oldLux
                nightModeCalculator.currentBrightness = oldBright
            }
            update()
        }
    }

    fun start() {
        AppLog.d("AppThemeManager: Starting with mode=${settings.appTheme}, luxThreshold=${settings.appThemeThresholdLux}, brightnessThreshold=${settings.appThemeThresholdBrightness}")
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(LocationUpdateIntent.action)
        }

        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        refreshListeners()

        lastEmittedNight = null
        update(debounce = false)
    }

    fun stop() {
        try { context.unregisterReceiver(receiver) } catch (e: Exception) {}
        if (isSensorRegistered) {
            sensorManager.unregisterListener(this)
            isSensorRegistered = false
        }
        if (isObserverRegistered) {
            context.contentResolver.unregisterContentObserver(brightnessObserver)
            isObserverRegistered = false
        }
        if (isCarSignalObserverRegistered) {
            context.contentResolver.unregisterContentObserver(carSignalObserver)
            isCarSignalObserverRegistered = false
        }
        handler.removeCallbacks(debounceRunnable)
    }

    private fun refreshListeners() {
        val theme = settings.appTheme

        if (theme == Settings.AppTheme.LIGHT_SENSOR) {
            if (!isSensorRegistered && lightSensor != null) {
                sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
                isSensorRegistered = true
            }
        } else {
            if (isSensorRegistered) {
                sensorManager.unregisterListener(this)
                isSensorRegistered = false
            }
        }

        if (theme == Settings.AppTheme.SCREEN_BRIGHTNESS) {
            if (!isObserverRegistered) {
                context.contentResolver.registerContentObserver(
                    SystemSettings.System.getUriFor(SystemSettings.System.SCREEN_BRIGHTNESS),
                    false, brightnessObserver
                )
                if (Build.VERSION.SDK_INT >= 28) {
                    context.contentResolver.registerContentObserver(
                        SystemSettings.System.getUriFor("screen_brightness_float"),
                        false, brightnessObserver
                    )
                }
                isObserverRegistered = true
            }
        } else {
            if (isObserverRegistered) {
                context.contentResolver.unregisterContentObserver(brightnessObserver)
                isObserverRegistered = false
            }
        }

        if (theme == Settings.AppTheme.CAR_SIGNAL) {
            if (!isCarSignalObserverRegistered) {
                context.contentResolver.registerContentObserver(
                    SystemSettings.System.getUriFor("car_mode"),
                    false, carSignalObserver
                )
                isCarSignalObserverRegistered = true
            }
        } else {
            if (isCarSignalObserverRegistered) {
                context.contentResolver.unregisterContentObserver(carSignalObserver)
                isCarSignalObserverRegistered = false
            }
        }
    }

    /**
     * Re-evaluates the theme on the EXISTING manager. Unlike [reapply] it does not build
     * a new manager, so it only recreates activities when the day/night value actually
     * changes (avoiding a recreate loop when called repeatedly).
     */
    fun forceRefresh() = update(debounce = false)

    private fun update(debounce: Boolean = true) {
        var isNight = false
        val threshold = settings.appThemeThresholdLux
        val thresholdBrightness = settings.appThemeThresholdBrightness

        when (settings.appTheme) {
            Settings.AppTheme.LOCATION -> {
                // Dark/light depending on the saved place the device is inside, or the
                // user's outside-places default when outside all of them.
                isNight = com.andrerinas.openheadunit.location.GeofenceLocation
                    .resolveNight(context, settings)
            }
            Settings.AppTheme.LIGHT_SENSOR -> {
                if (currentLux >= 0) {
                    val hyst = 10.0f
                    val currentIsNight = lastEmittedNight ?: false
                    isNight = if (currentIsNight) {
                        currentLux < (threshold + hyst)
                    } else {
                        currentLux < threshold
                    }
                    AppLog.d("AppThemeManager: LIGHT_SENSOR lux=$currentLux threshold=$threshold isNight=$isNight")
                }
            }
            Settings.AppTheme.SCREEN_BRIGHTNESS -> {
                currentBrightness = readBrightness()
                if (currentBrightness >= 0) {
                    val hyst = 10
                    val currentIsNight = lastEmittedNight ?: false
                    isNight = if (currentIsNight) {
                        currentBrightness < (thresholdBrightness + hyst)
                    } else {
                        currentBrightness < thresholdBrightness
                    }
                    AppLog.d("AppThemeManager: SCREEN_BRIGHTNESS brightness=$currentBrightness threshold=$thresholdBrightness isNight=$isNight")
                }
            }
            Settings.AppTheme.CAR_SIGNAL -> {
                val carMode = try {
                    SystemSettings.System.getInt(context.contentResolver, "car_mode")
                } catch (_: Exception) { -1 }
                if (carMode >= 0) {
                    isNight = carMode != 0
                    AppLog.d("AppThemeManager: CAR_SIGNAL car_mode=$carMode isNight=$isNight")
                } else {
                    AppLog.w("AppThemeManager: CAR_SIGNAL key 'car_mode' not present; treating as day")
                    isNight = lastEmittedNight ?: false
                }
            }
            Settings.AppTheme.AUTO_SUNRISE -> {
                nightModeCalculator = NightMode(settings, true)
                // Decoupled from settings.nightMode so the app theme can use sunrise/sunset
                // even when the Android Auto night-mode is set to DAY/NIGHT/CAR_SIGNAL/etc.
                isNight = nightModeCalculator.isSunriseSunsetNight
                AppLog.d("AppThemeManager: AUTO_SUNRISE isNight=$isNight")
            }
            Settings.AppTheme.MANUAL_TIME -> {
                val now = Calendar.getInstance()
                val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
                val start = settings.appThemeManualStart
                val end = settings.appThemeManualEnd
                isNight = if (start <= end) {
                    currentMinutes in start..end
                } else {
                    currentMinutes >= start || currentMinutes <= end
                }
                AppLog.d("AppThemeManager: MANUAL_TIME currentMinutes=$currentMinutes start=$start end=$end isNight=$isNight")
            }
            else -> return
        }

        if (debounce) {
            if (pendingValue != isNight) {
                pendingValue = isNight
                handler.removeCallbacks(debounceRunnable)
                handler.postDelayed(debounceRunnable, 2000)
            }
        } else {
            handler.removeCallbacks(debounceRunnable)
            if (lastEmittedNight != isNight) {
                lastEmittedNight = isNight
                applyNightMode(isNight)
            }
        }
    }

    private fun applyNightMode(isNight: Boolean) {
        val mode = if (isNight) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        AppLog.d("AppThemeManager: Setting night mode to ${if (isNight) "NIGHT" else "DAY"}")
        AppCompatDelegate.setDefaultNightMode(mode)
        signalThemeChange()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_LIGHT) {
            val newLux = event.values[0]
            if (kotlin.math.abs(newLux - currentLux) >= 1.0f || isFirstSensorReading) {
                currentLux = newLux
                nightModeCalculator.currentLux = currentLux
                if (isFirstSensorReading) {
                    isFirstSensorReading = false
                    update(debounce = false)
                } else {
                    update()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun readBrightness(): Int {
        // On API 28+ (Android 9+) the brightness slider primarily writes to
        // `screen_brightness_float` (0.0..1.0). The legacy integer SCREEN_BRIGHTNESS
        // may be stale, missing (when slider hits 0), or driven by auto-brightness,
        // which on some Android 10 ROMs makes it diverge from the slider. Prefer the
        // float when available and fall back to the integer otherwise.
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                val f = SystemSettings.System.getFloat(
                    context.contentResolver, "screen_brightness_float"
                )
                if (!f.isNaN()) {
                    return (f * 255f).toInt().coerceIn(0, 255)
                }
            } catch (_: Exception) { /* fall through to integer */ }
        }
        return try {
            SystemSettings.System.getInt(
                context.contentResolver, SystemSettings.System.SCREEN_BRIGHTNESS
            ).coerceIn(0, 255)
        } catch (_: Exception) { -1 }
    }

    fun getCurrentLux(): Float = currentLux
    fun getCurrentBrightness(): Int = currentBrightness

    companion object {
        val themeVersion = MutableLiveData<Int>()
        private var versionCounter = 0

        private fun signalThemeChange() {
            versionCounter++
            if (Looper.myLooper() == Looper.getMainLooper()) {
                themeVersion.value = versionCounter
            } else {
                themeVersion.postValue(versionCounter)
            }
        }

        fun signalVisualChange() {
            signalThemeChange()
        }

        fun applyStaticTheme(settings: Settings) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                Handler(Looper.getMainLooper()).post {
                    applyStaticTheme(settings)
                }
                return
            }
            val mode = when (settings.appTheme) {
                Settings.AppTheme.AUTOMATIC -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                Settings.AppTheme.CLEAR -> AppCompatDelegate.MODE_NIGHT_NO
                Settings.AppTheme.DARK, Settings.AppTheme.EXTREME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> return
            }
            AppCompatDelegate.setDefaultNightMode(mode)
            signalThemeChange()
        }

        fun isStaticMode(theme: Settings.AppTheme): Boolean {
            return theme == Settings.AppTheme.AUTOMATIC ||
                    theme == Settings.AppTheme.CLEAR ||
                    theme == Settings.AppTheme.DARK ||
                    theme == Settings.AppTheme.EXTREME_DARK
        }

        /**
         * (Re)applies the app theme engine: runs the live manager for dynamic themes
         * (including Location by area), otherwise applies the static theme directly.
         * Call after any change to the app theme, the sunrise source, or the places.
         */
        fun reapply(context: Context, settings: Settings) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                Handler(Looper.getMainLooper()).post {
                    reapply(context, settings)
                }
                return
            }
            com.andrerinas.openheadunit.App.appThemeManager?.stop()
            com.andrerinas.openheadunit.App.appThemeManager = null
            if (!isStaticMode(settings.appTheme)) {
                val manager = AppThemeManager(context.applicationContext, settings)
                com.andrerinas.openheadunit.App.appThemeManager = manager
                manager.start()
            } else {
                applyStaticTheme(settings)
            }
        }
    }
}
