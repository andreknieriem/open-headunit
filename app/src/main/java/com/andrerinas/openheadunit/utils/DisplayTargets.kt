package com.andrerinas.openheadunit.utils

import android.app.ActivityOptions
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Display

/**
 * The one place the app asks Android what displays exist and which one to project on.
 *
 * `DisplayManager` arrived in API 17, so below that there is only the built-in panel and every
 * answer here is the default display.
 */
object DisplayTargets {

    private val supported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1

    /** Every display Android reports, in `DisplayManager` order, or empty when it cannot be asked. */
    fun list(context: Context): List<DisplayTargetPolicy.DisplayInfo> {
        if (!supported) return emptyList()
        return try {
            val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                ?: return emptyList()
            manager.displays.orEmpty().map { info(it) }
        } catch (e: Exception) {
            AppLog.d("DisplayTargets: could not list the displays: ${e.message}")
            emptyList()
        }
    }

    /** The external displays that could host a projection. */
    fun candidates(context: Context): List<DisplayTargetPolicy.DisplayInfo> =
        DisplayTargetPolicy.candidates(list(context))

    /** Which display this session should use, falling back to the built-in panel on any doubt. */
    fun choose(context: Context, settings: Settings): DisplayTargetPolicy.Choice {
        val displays = list(context)
        val choice = DisplayTargetPolicy.choose(
            mode = DisplayTargetPolicy.Mode.of(settings.preferredDisplayMode),
            preferredDisplayId = settings.preferredDisplayId,
            displays = displays,
        )
        logChoice(choice, displays)
        return choice
    }

    /**
     * The `startActivity` options that put the projection on the chosen display, or null when there
     * is nothing to say: the built-in panel, or a release with no way to name a display.
     */
    fun projectionLaunchOptions(context: Context, settings: Settings): Bundle? =
        launchOptions(choose(context, settings).displayId)

    /** As [projectionLaunchOptions], for a display already chosen. */
    fun launchOptions(displayId: Int): Bundle? {
        if (displayId == DisplayTargetPolicy.DEFAULT_DISPLAY_ID) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return try {
            ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        } catch (e: Exception) {
            AppLog.w("DisplayTargets: could not target display $displayId: ${e.message}")
            null
        }
    }

    /** The live [Display] for an id, or null when it has gone away since it was chosen. */
    fun display(context: Context, displayId: Int): Display? {
        if (!supported) return null
        return try {
            val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            manager?.getDisplay(displayId)?.takeIf { it.isValid }
        } catch (e: Exception) {
            AppLog.d("DisplayTargets: could not resolve display $displayId: ${e.message}")
            null
        }
    }

    /** Which display a context describes, so a measurement can record where it was taken. */
    fun displayIdOf(context: Context): Int {
        if (!supported) return DisplayTargetPolicy.DEFAULT_DISPLAY_ID
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display?.displayId ?: DisplayTargetPolicy.DEFAULT_DISPLAY_ID
            } else {
                @Suppress("DEPRECATION")
                val manager = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
                @Suppress("DEPRECATION")
                manager?.defaultDisplay?.displayId ?: DisplayTargetPolicy.DEFAULT_DISPLAY_ID
            }
        } catch (e: Exception) {
            DisplayTargetPolicy.DEFAULT_DISPLAY_ID
        }
    }

    /**
     * A context whose resources describe [displayId] rather than the built-in panel.
     *
     * The projection is measured through this, because a window on another display still reports
     * the default display's metrics if it is asked through the wrong context.
     */
    fun contextFor(context: Context, displayId: Int): Context {
        if (displayId == DisplayTargetPolicy.DEFAULT_DISPLAY_ID) return context
        val display = display(context, displayId) ?: return context
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                context.createDisplayContext(display)
            } else {
                context
            }
        } catch (e: Exception) {
            AppLog.d("DisplayTargets: could not build a context for display $displayId: ${e.message}")
            context
        }
    }

    /**
     * Prints what is attached and which one was taken, but only when that changes.
     *
     * Several callers ask per session, and an INFO line per call is how a reporter's log fills with
     * one repeated fact instead of the session.
     */
    private var lastLogged: String? = null

    private fun logChoice(choice: DisplayTargetPolicy.Choice, displays: List<DisplayTargetPolicy.DisplayInfo>) {
        val attached = displays.joinToString { "${it.displayId}:${it.name} ${it.widthPx}x${it.heightPx}@${it.densityDpi}" }
        val line = "DisplayTargets: projecting on display ${choice.displayId} because ${choice.reason} [$attached]"
        if (line == lastLogged) return
        lastLogged = line
        AppLog.i(line)
    }

    private fun info(display: Display): DisplayTargetPolicy.DisplayInfo {
        val metrics = DisplayMetrics()
        try {
            display.getRealMetrics(metrics)
        } catch (e: Exception) {
            @Suppress("DEPRECATION")
            display.getMetrics(metrics)
        }
        return DisplayTargetPolicy.DisplayInfo(
            displayId = display.displayId,
            name = display.name ?: "display ${display.displayId}",
            widthPx = metrics.widthPixels,
            heightPx = metrics.heightPixels,
            densityDpi = metrics.densityDpi,
            isPresentation = (display.flags and Display.FLAG_PRESENTATION) != 0,
            isUsable = display.isValid,
        )
    }
}
