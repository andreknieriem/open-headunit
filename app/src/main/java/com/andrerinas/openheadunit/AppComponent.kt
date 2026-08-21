package com.andrerinas.openheadunit

import android.app.NotificationManager
import android.content.Context
import android.net.wifi.WifiManager
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.carkey.CarKeysManager
import com.andrerinas.openheadunit.decoder.AudioDecoder
import com.andrerinas.openheadunit.decoder.DeviceMemoryProfile
import com.andrerinas.openheadunit.decoder.VideoDecoder
import com.andrerinas.openheadunit.utils.SUExecutor
import com.andrerinas.openheadunit.utils.Settings

class AppComponent(private val app: App) {

    val settings = Settings(app)
    val videoDecoder = VideoDecoder(settings, DeviceMemoryProfile.readWithOverride(app, settings.debugForceMemoryProfile))
    val audioDecoder = AudioDecoder()

    val notificationManager: NotificationManager
        get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val wifiManager: WifiManager
        get() = app.getSystemService(Context.WIFI_SERVICE) as WifiManager

    val commManager = CommManager(app, settings, audioDecoder, videoDecoder)

    val suExecutor = SUExecutor()

    val carKeysManager = CarKeysManager()
}
