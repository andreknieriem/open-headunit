package com.andrerinas.openheadunit.connection.carkey.ola

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.connection.carkey.CarKeyReceiver
import com.andrerinas.openheadunit.input.LockedInputPolicy
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Ola S1 handlebar volume. The buttons never become key events: the dash only steps STREAM_MUSIC.
 * With Send Volume Buttons To The Phone on, each step goes to the phone as a volume key, and the
 * dash level comes back when the session ends. Registered only where MoveOS is installed.
 */
class OlaVolumeReceiver : BroadcastReceiver(), CarKeyReceiver {

    private var context: Context? = null
    private var audioManager: AudioManager? = null
    private var mid = -1
    private var restore = -1

    override val isSupported = true
    override val isSUNeeded = false
    override val isSessionScoped = true
    // Volume steps need no learning, and arming moves the dash volume, so the keymap screen skips it.
    override val servesLearning = false

    override fun isSupportedOn(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(MOVE_OS_PACKAGE, 0)
    }.isSuccess

    override fun register(context: Context) {
        if (this.context != null) return
        // A rider who keeps volume on the dash keeps the dash's own handling.
        if (!App.provide(context).settings.volumeKeysToPhone) {
            AppLog.i("OlaVolumeReceiver: volume stays on the dash")
            return
        }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        restore = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        mid = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2
        setMusicVolume(audio, mid)
        // Exported because the framework sends this broadcast to every app.
        ContextCompat.registerReceiver(
            context, this, IntentFilter(VOLUME_CHANGED_ACTION), ContextCompat.RECEIVER_EXPORTED,
        )
        this.context = context
        audioManager = audio
        AppLog.i("OlaVolumeReceiver: armed (mid=$mid, restore=$restore)")
    }

    override fun unregister() {
        val registeredOn = context ?: return
        context = null
        try {
            registeredOn.unregisterReceiver(this)
        } catch (e: IllegalArgumentException) {
            AppLog.w("OlaVolumeReceiver: was not registered: ${e.message}")
        }
        val audio = audioManager
        if (audio != null && restore >= 0) setMusicVolume(audio, restore)
        audioManager = null
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || intent.getIntExtra(EXTRA_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
        val key = OlaVolumeStepPolicy.keyFor(
            from = intent.getIntExtra(EXTRA_PREV_VALUE, -1),
            to = intent.getIntExtra(EXTRA_VALUE, -1),
            mid = mid,
        ) ?: return
        // A locked unit keeps every volume key, so the dash keeps this step.
        if (LockedInputPolicy.engaged) return
        // Turned off mid-session: the dash keeps its steps and nothing snaps them back.
        if (!App.provide(context).settings.volumeKeysToPhone) return
        AppLog.i("OlaVolumeReceiver: dash volume step, sending keycode $key to the phone")
        // Each step is its own press and no key event copies it, so a quick second press is not dropped.
        handleClick(context, key, source = ROUTE, downTime = SystemClock.uptimeMillis())
        audioManager?.let { setMusicVolume(it, mid) }
    }

    private fun setMusicVolume(audio: AudioManager, index: Int) {
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
        } catch (e: SecurityException) {
            AppLog.w("OlaVolumeReceiver: could not set the dash volume: ${e.message}")
        }
    }

    private companion object {
        const val ROUTE = "carkey-ola"
        const val MOVE_OS_PACKAGE = "com.olaelectric.hmiclient"
        // AudioManager's hidden names for the broadcast the framework sends on every volume change.
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val EXTRA_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        const val EXTRA_PREV_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"
    }
}
