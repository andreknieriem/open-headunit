package com.andrerinas.openheadunit.location

import android.content.Context
import android.location.Location
import android.media.AudioManager
import android.view.KeyEvent
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Applies [SpeedVolumePolicy] to the live session: one volume step per GPS fix toward the target,
 * sent where the volume keys go - to the phone when the user sends them there, to this head unit's
 * media stream otherwise.
 *
 * One step per fix is the rate limit. A first fix that arrives at motorway speed ramps the volume
 * over a few seconds instead of jumping, which is also how a driver would do it by hand.
 */
class SpeedVolume(private val context: Context) {
    private val component by lazy { App.provide(context) }

    /** Steps currently added on top of the driver's own level. */
    private var steps = 0

    /** Where [steps] went, so they come back off the same place even if the setting changes. */
    private var onPhone = false

    fun onFix(location: Location) {
        if (!location.hasSpeed()) return
        val settings = component.settings
        val speedKmh = location.speed * 3.6f
        val target = SpeedVolumePolicy.targetSteps(settings.speedVolumeMode, speedKmh, steps)
        if (target == steps) return
        if (steps == 0) onPhone = settings.volumeKeysToPhone
        val raise = target > steps
        if (!step(raise)) return
        steps += if (raise) 1 else -1
        AppLog.i("SpeedVolume: ${speedKmh.toInt()} km/h, +$steps step(s) on the ${if (onPhone) "phone" else "head unit"}")
    }

    /**
     * End of session. Steps on this head unit come back off; steps on the phone cannot, the link is
     * already gone - but a session normally ends parked, where they have come off already.
     */
    fun reset() {
        if (!onPhone) repeat(steps) { step(raise = false) }
        steps = 0
    }

    /** One volume step; false when it could not be applied, so it is not counted. */
    private fun step(raise: Boolean): Boolean {
        if (onPhone) {
            val commManager = component.commManager
            if (commManager.connectionState.value !is CommManager.ConnectionState.TransportStarted) return false
            val keyCode = if (raise) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN
            // The direct send, not sendKey: this is no physical press, so the keymap must not remap
            // it and the debounce has no duplicate to catch.
            // The phone's level is invisible from here, so a step lost at its maximum still counts
            // and later comes back off below the driver's level. Rare enough to leave.
            commManager.send(keyCode, true)
            commManager.send(keyCode, false)
            return true
        }
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = component.settings.mediaAudioStream
        return try {
            val before = audioManager.getStreamVolume(stream)
            audioManager.adjustStreamVolume(stream, if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
            // A step the stream had no room for (already at its maximum or zero) does not count.
            audioManager.getStreamVolume(stream) != before
        } catch (e: SecurityException) {
            // A user-picked notification or ring stream can refuse under Do Not Disturb.
            AppLog.w("SpeedVolume: stream $stream refused the volume step: ${e.message}")
            false
        }
    }
}
