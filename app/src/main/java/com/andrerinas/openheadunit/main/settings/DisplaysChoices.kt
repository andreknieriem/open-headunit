package com.andrerinas.openheadunit.main.settings

import android.content.Context
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.secondscreen.SecondScreenOutputPolicy
import com.andrerinas.openheadunit.secondscreen.ms912x.Ms912xMode
import com.andrerinas.openheadunit.secondscreen.ms912x.Ms912xWireFormat
import com.andrerinas.openheadunit.secondscreen.rtp.RtpRefreshPolicy
import com.andrerinas.openheadunit.utils.DisplayTargetPolicy
import com.andrerinas.openheadunit.utils.DisplayTargets
import com.andrerinas.openheadunit.utils.Settings

/** The labels and choices of the Displays page, which the main list reads for its entry row too. */
object DisplaysChoices {

    /** The second-screen outputs this build offers, in the order the picker lists them. */
    val OFFERED_OUTPUTS = listOf(
        SecondScreenOutputPolicy.Output.ANDROID_DISPLAY,
        SecondScreenOutputPolicy.Output.NETWORK,
        SecondScreenOutputPolicy.Output.RTP_PUSH,
        SecondScreenOutputPolicy.Output.MS912X,
        SecondScreenOutputPolicy.Output.USB_DISPLAY,
    )

    fun outputLabel(context: Context, output: SecondScreenOutputPolicy.Output): String = context.getString(when (output) {
        SecondScreenOutputPolicy.Output.ANDROID_DISPLAY -> R.string.aux_output_android_display
        SecondScreenOutputPolicy.Output.NETWORK -> R.string.aux_output_network
        SecondScreenOutputPolicy.Output.MS912X -> R.string.aux_output_ms912x
        SecondScreenOutputPolicy.Output.USB_DISPLAY -> R.string.aux_output_usb_display
        SecondScreenOutputPolicy.Output.RTP_PUSH -> R.string.aux_output_rtp_push
    })

    /** "Off" first, then each offered output; [selected] is the index the settings point at. */
    class OutputChoices(val labels: List<String>, val selected: Int)

    fun outputChoices(context: Context, settings: Settings): OutputChoices {
        val labels = listOf(context.getString(R.string.aux_display_off)) + OFFERED_OUTPUTS.map { outputLabel(context, it) }
        val selected = if (!settings.auxDisplayEnabled) 0
        else OFFERED_OUTPUTS.indexOf(settings.auxOutput).let { if (it >= 0) it + 1 else 0 }
        return OutputChoices(labels, selected)
    }

    /** One line per projection choice: the label, and what picking it stores. */
    class ProjectionChoices(val labels: List<String>, val picks: List<() -> Unit>, val selected: Int)

    /**
     * A display that is not attached right now still shows, so the user can see what is stored
     * rather than finding the row silently reset to the built-in panel.
     */
    fun projectionChoices(context: Context, settings: Settings): ProjectionChoices {
        val attached = DisplayTargets.candidates(context)
        val mode = DisplayTargetPolicy.Mode.of(settings.preferredDisplayMode)
        val pinnedId = settings.preferredDisplayId
        val pinnedName = attached.firstOrNull { it.displayId == pinnedId }?.name

        val entries = mutableListOf<Pair<String, () -> Unit>>()
        entries.add(context.getString(R.string.projection_display_builtin) to {
            storeProjection(settings, DisplayTargetPolicy.Mode.DEFAULT, null)
        })
        entries.add(context.getString(R.string.projection_display_automatic) to {
            storeProjection(settings, DisplayTargetPolicy.Mode.AUTO, null)
        })
        attached.forEach { display ->
            entries.add(displayLabel(display) to {
                storeProjection(settings, DisplayTargetPolicy.Mode.SECONDARY, display.displayId)
            })
        }
        if (mode == DisplayTargetPolicy.Mode.SECONDARY && pinnedName == null) {
            entries.add(context.getString(R.string.projection_display_missing, "display $pinnedId") to {
                storeProjection(settings, DisplayTargetPolicy.Mode.SECONDARY, pinnedId)
            })
        }

        val selectedIndex = when (mode) {
            DisplayTargetPolicy.Mode.DEFAULT -> 0
            DisplayTargetPolicy.Mode.AUTO -> 1
            DisplayTargetPolicy.Mode.SECONDARY ->
                attached.indexOfFirst { it.displayId == pinnedId }.let { if (it >= 0) it + 2 else entries.size - 1 }
        }
        return ProjectionChoices(entries.map { it.first }, entries.map { it.second }, selectedIndex.coerceIn(0, entries.size - 1))
    }

    /** Writes only what changed, so a default stays live (ADR 0001); a null [displayId] keeps the stored one. */
    private fun storeProjection(settings: Settings, mode: DisplayTargetPolicy.Mode, displayId: Int?) {
        if (settings.preferredDisplayMode != mode.ordinal) settings.preferredDisplayMode = mode.ordinal
        if (displayId != null && settings.preferredDisplayId != displayId) settings.preferredDisplayId = displayId
    }

    fun displayLabel(display: DisplayTargetPolicy.DisplayInfo): String = "${display.name} (${display.widthPx}x${display.heightPx})"

    fun streamSizeLabels(): List<String> = SecondScreenOutputPolicy.NETWORK_SIZES.map { "${it.widthPx}x${it.heightPx}" }

    fun rtpRefreshLabels(context: Context): List<String> = RtpRefreshPolicy.INTERVALS_S.map {
        if (it == 0) context.getString(R.string.aux_display_off) else context.getString(R.string.aux_rtp_refresh_seconds, it)
    }

    val MS912X_MODES: List<Ms912xMode> = Ms912xMode.values().toList()

    fun ms912xModeLabels(): List<String> = MS912X_MODES.map { "${it.width}x${it.height}" }

    val MS912X_FORMATS: List<Ms912xWireFormat> = Ms912xWireFormat.values().toList()

    fun ms912xFormatLabels(context: Context): List<String> = MS912X_FORMATS.map {
        context.getString(if (it == Ms912xWireFormat.YUV422) R.string.aux_ms912x_format_yuv else R.string.aux_ms912x_format_rgb)
    }

    /** Index 1 is the cluster, 0 the auxiliary display. */
    fun roleLabels(context: Context): List<String> = listOf(
        context.getString(R.string.aux_display_role_auxiliary),
        context.getString(R.string.aux_display_role_cluster),
    )

    /** Index 1 is the turn card, 0 the map. */
    fun contentLabels(context: Context): List<String> = listOf(
        context.getString(R.string.aux_display_content_map),
        context.getString(R.string.aux_display_content_turn_card),
    )

    /** The page's row names and every choice its rows offer, so a search in the main list finds the entry row. */
    fun searchKeywords(context: Context, settings: Settings): String {
        val names = listOf(
            R.string.projection_display, R.string.aux_display, R.string.aux_android_display,
            R.string.aux_network_size, R.string.aux_network_port, R.string.aux_rtp_destination,
            R.string.aux_rtp_port, R.string.aux_rtp_refresh, R.string.aux_ms912x_mode,
            R.string.aux_ms912x_format, R.string.aux_display_role, R.string.aux_display_content,
        ).map { context.getString(it) }
        val projection = if (DisplayTargets.canMoveProjection) projectionChoices(context, settings).labels else emptyList()
        val androidDisplays = DisplayTargets.candidates(context).map { displayLabel(it) }
        return (names + projection + androidDisplays + OFFERED_OUTPUTS.map { outputLabel(context, it) } +
            streamSizeLabels() + rtpRefreshLabels(context) + ms912xModeLabels() + ms912xFormatLabels(context) +
            roleLabels(context) + contentLabels(context)).distinct().joinToString(" ")
    }

    /** The entry row's value in the main list. */
    fun summaryText(context: Context, settings: Settings): String {
        val projection = if (DisplayTargets.canMoveProjection) {
            projectionChoices(context, settings).let { it.labels[it.selected] }
        } else ""
        val output = outputChoices(context, settings)
        val summary = DisplaysSummaryPolicy.summarize(projection, settings.auxDisplayEnabled, output.labels[output.selected])
        val id = summary.textResId ?: return summary.args.first()
        return context.getString(id, *summary.args.toTypedArray())
    }
}
