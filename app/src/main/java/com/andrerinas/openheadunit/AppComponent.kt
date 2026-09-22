package com.andrerinas.openheadunit

import android.app.NotificationManager
import android.content.Context
import android.net.wifi.WifiManager
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.decoder.audio.AudioDecoder
import com.andrerinas.openheadunit.decoder.video.DeviceMemoryProfile
import com.andrerinas.openheadunit.decoder.video.VideoDecoder
import com.andrerinas.openheadunit.connection.carkey.CarKeysManager
import com.andrerinas.openheadunit.utils.SUExecutor
import com.andrerinas.openheadunit.utils.Settings

class AppComponent(private val app: App) {

    val settings = Settings(app)
    // A function, not a reading: this decoder is a process singleton, so anything resolved here
    // once would outlive every settings change the user makes.
    val videoDecoder = VideoDecoder(settings) {
        DeviceMemoryProfile.readWithOverride(app, settings.debugForceMemoryProfile)
    }
    val audioDecoder = AudioDecoder()

    /**
     * The auxiliary display's decoder, built only once a session advertises a second video sink.
     *
     * Its own instance rather than a second surface on the one above: MediaCodec renders to one
     * surface, and the two streams carry different pictures.
     */
    @Volatile
    var auxVideoDecoder: VideoDecoder? = null
        private set

    /** Builds the auxiliary decoder on first use and hands back the same one after. */
    @Synchronized
    fun requireAuxVideoDecoder(): VideoDecoder =
        auxVideoDecoder ?: VideoDecoder(settings) {
            DeviceMemoryProfile.readWithOverride(app, settings.debugForceMemoryProfile)
        }.also { auxVideoDecoder = it }

    val notificationManager: NotificationManager
        get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val wifiManager: WifiManager
        get() = app.getSystemService(Context.WIFI_SERVICE) as WifiManager

    val commManager = CommManager(app, settings, audioDecoder, videoDecoder)

    val suExecutor = SUExecutor()

    val carKeysManager = CarKeysManager()
}
