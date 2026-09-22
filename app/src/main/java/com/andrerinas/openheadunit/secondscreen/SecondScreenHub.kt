package com.andrerinas.openheadunit.secondscreen

import android.content.Context
import com.andrerinas.openheadunit.secondscreen.SecondScreenOutputPolicy.Output
import com.andrerinas.openheadunit.secondscreen.SecondScreenOutputPolicy.Target
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.DisplayTargets
import com.andrerinas.openheadunit.utils.Settings

/**
 * The second screen of the current session: what was announced, and the output carrying it.
 *
 * An Android display is hosted by the projection activity, so only the other outputs live here.
 */
object SecondScreenHub {

    /** The output the last service discovery announced, or null when it announced none. */
    @Volatile
    var announced: Output? = null
        private set

    /** The panel size that output announced, for the Android display to crop the margin by. */
    @Volatile
    var announcedTarget: Target? = null
        private set

    @Volatile
    private var current: SecondScreenOutput? = null

    /**
     * The size to announce for the chosen output, recorded so the session opens that same output.
     * Called once per service discovery.
     */
    fun announce(context: Context, settings: Settings): Target? {
        if (!settings.auxDisplayEnabled) {
            announced = null
            announcedTarget = null
            return null
        }
        val output = settings.auxOutput
        val target = SecondScreenOutputPolicy.target(output, availability(context, settings, output))
        announced = if (target != null) output else null
        announcedTarget = target
        if (target == null) {
            AppLog.w("SecondScreen: the ${output.name} output is not available, so one display is announced")
        }
        return target
    }

    private fun availability(context: Context, settings: Settings, output: Output) = when (output) {
        Output.ANDROID_DISPLAY -> SecondScreenOutputPolicy.Availability(androidDisplay = androidDisplayTarget(context, settings))
        Output.NETWORK, Output.MS912X, Output.USB_DISPLAY -> SecondScreenOutputPolicy.Availability()
    }

    private fun androidDisplayTarget(context: Context, settings: Settings): Target? {
        val projectionDisplayId = DisplayTargets.choose(context, settings).displayId
        val panel = DisplayTargets.list(context).firstOrNull {
            it.displayId == settings.auxDisplayId && it.isUsable && it.displayId != projectionDisplayId
        } ?: return null
        return Target(panel.widthPx, panel.heightPx, panel.densityDpi)
    }

    /** Opens the announced output for a session that is starting. Idempotent. */
    @Synchronized
    fun open(context: Context, settings: Settings, onKeyframeNeeded: () -> Unit): SecondScreenOutput? {
        current?.let { return it }
        val output = announced ?: return null
        val created: SecondScreenOutput = when (output) {
            Output.ANDROID_DISPLAY, Output.NETWORK, Output.MS912X, Output.USB_DISPLAY -> return null
        }
        created.onKeyframeNeeded = onKeyframeNeeded
        try {
            created.start()
        } catch (e: Exception) {
            // Never fatal to the session: the main picture is the one the driver is using.
            AppLog.e("SecondScreen: could not start the ${output.name} output: ${e.message}")
            return null
        }
        current = created
        AppLog.i("SecondScreen: the ${output.name} output is open")
        return created
    }

    /** The open output when it takes the stream undecoded, else null. */
    fun encoded(): EncodedOutput? = current as? EncodedOutput

    @Synchronized
    fun close() {
        val output = current ?: return
        current = null
        try {
            output.stop()
        } catch (e: Exception) {
            AppLog.w("SecondScreen: stopping the ${output.output.name} output failed: ${e.message}")
        }
        AppLog.i("SecondScreen: the ${output.output.name} output is closed")
    }
}
