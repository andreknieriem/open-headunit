package com.andrerinas.openheadunit.secondscreen

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Build
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.secondscreen.ms912x.Ms912xModes
import com.andrerinas.openheadunit.secondscreen.ms912x.Ms912xOutput
import com.andrerinas.openheadunit.secondscreen.SecondScreenOutputPolicy.Output
import com.andrerinas.openheadunit.secondscreen.SecondScreenOutputPolicy.Target
import com.andrerinas.openheadunit.secondscreen.network.NetworkStreamOutput
import com.andrerinas.openheadunit.secondscreen.usbdisplay.UsbDisplayOutput
import com.andrerinas.openheadunit.secondscreen.usbdisplay.UsbDisplayProbe
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
        Output.NETWORK -> SecondScreenOutputPolicy.Availability(network = SecondScreenOutputPolicy.networkTarget(settings.auxNetworkSize))
        Output.MS912X -> SecondScreenOutputPolicy.Availability(ms912x = ms912xTarget(context, settings))
        Output.USB_DISPLAY -> SecondScreenOutputPolicy.Availability(usbDisplay = usbDisplayTarget(context, settings))
    }

    private fun androidDisplayTarget(context: Context, settings: Settings): Target? {
        val projectionDisplayId = DisplayTargets.choose(context, settings).displayId
        val panel = DisplayTargets.list(context).firstOrNull {
            it.displayId == settings.auxDisplayId && it.isUsable && it.displayId != projectionDisplayId
        } ?: return null
        return Target(panel.widthPx, panel.heightPx, panel.densityDpi)
    }

    /** The adapter's mode, when one is attached and this release can read decoded pictures back. */
    private fun ms912xTarget(context: Context, settings: Settings): Target? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
        val attached = usb.deviceList.values.any {
            UsbDisplayAdapterPolicy.kindOf(it.vendorId, it.productId, emptyList()) == UsbDisplayAdapterPolicy.Kind.MS912X
        }
        if (!attached) return null
        val mode = settings.ms912xMode
        return Target(mode.width, mode.height, Ms912xModes.densityFor(mode))
    }

    /**
     * The USB display's own size: asked now when the permission is already held, otherwise what it
     * said last time. Remembered, so a display that answers once can be announced from then on.
     */
    fun usbDisplayTarget(context: Context, settings: Settings): Target? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) return null
        val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
        val found = UsbDisplayProbe.find(usb) ?: return null
        val info = UsbDisplayProbe.probe(usb, found)
        if (info != null) {
            val dpi = if (info.densityDpi in 1..640) info.densityDpi else 160
            settings.usbDisplayLastTarget = Target(info.widthPx, info.heightPx, dpi)
        }
        return settings.usbDisplayLastTarget
    }

    /** Opens the announced output for a session that is starting. Idempotent. */
    @Synchronized
    fun open(context: Context, settings: Settings, onKeyframeNeeded: () -> Unit): SecondScreenOutput? {
        current?.let { return it }
        val output = announced ?: return null
        val created: SecondScreenOutput = when (output) {
            Output.NETWORK -> NetworkStreamOutput(settings.auxNetworkPort)
            Output.MS912X -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val mode = settings.ms912xMode
                Ms912xOutput(context, App.provide(context).requireAuxVideoDecoder(), mode,
                    Ms912xModes.effectiveFormat(mode, settings.ms912xFormat))
            } else return null
            Output.USB_DISPLAY -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                UsbDisplayOutput(context)
            } else return null
            Output.ANDROID_DISPLAY -> return null
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
