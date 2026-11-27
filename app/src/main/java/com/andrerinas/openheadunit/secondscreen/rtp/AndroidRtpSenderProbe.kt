package com.andrerinas.openheadunit.secondscreen.rtp

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.DisplayMetrics
import android.view.Display
import com.andrerinas.openheadunit.utils.AppLog
import java.io.File

/**
 * Reads the UDP socket tables and logs added, changed and removed displays while the push is open.
 * Both only log: they name a possible second sender, they do not act on one.
 */
class AndroidRtpSenderProbe(
    context: Context,
    private val destination: ByteArray,
    private val port: Int,
) : RtpSenderProbe {

    private val context = context.applicationContext
    private val lastLines = HashMap<Int, String>()
    private var listener: DisplayManager.DisplayListener? = null

    override fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) return
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = log("added", displayId)
            override fun onDisplayChanged(displayId: Int) = log("changed", displayId)
            override fun onDisplayRemoved(displayId: Int) {
                lastLines.remove(displayId)
                if (displayId != Display.DEFAULT_DISPLAY) {
                    AppLog.i("SecondScreen: " + RtpSenderProbePolicy.displayRemovedLine(displayId))
                }
            }

            private fun log(kind: String, displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) return
                val display = manager.getDisplay(displayId) ?: return
                val metrics = DisplayMetrics()
                display.getRealMetrics(metrics)
                val presentation = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT &&
                    (display.flags and Display.FLAG_PRESENTATION) != 0
                val line = RtpSenderProbePolicy.displayLine(kind, displayId, display.name,
                    metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, presentation)
                val state = line.removePrefix("display $kind ")
                if (kind == "changed" && state == lastLines[displayId]) return
                lastLines[displayId] = state
                AppLog.i("SecondScreen: $line")
            }
        }
        manager.registerDisplayListener(l, Handler(Looper.getMainLooper()))
        listener = l
    }

    override fun sockets(): String? {
        val tables = listOf("/proc/net/udp", "/proc/net/udp6").map { File(it).readText() }
        val packages = context.packageManager
        return RtpSenderProbePolicy.socketSummary(tables, destination, port, Process.myUid()) {
            packages.getNameForUid(it) ?: "unknown"
        }
    }

    override fun stop() {
        val l = listener ?: return
        listener = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) return
        (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.unregisterDisplayListener(l)
    }
}
