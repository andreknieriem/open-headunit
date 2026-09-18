package com.andrerinas.openheadunit.connection

import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import com.andrerinas.openheadunit.R

/**
 * The view half of the bring-up status pill, over an inflated `view_connection_stage_pill`.
 *
 * Shared because the home screen and the projection's loading screen wear the same pill, so a
 * step the user is reading survives the handoff between them. Decides nothing: the owner asks
 * [ConnectionStageTracker] what to show and calls [applyStage].
 */
class ConnectionStagePill(private val root: View) {

    private val headline: TextView? = root.findViewById(R.id.auto_connect_pill_text)
    private val stageText: TextView? = root.findViewById(R.id.auto_connect_pill_stage_text)
    private val networkText: TextView? = root.findViewById(R.id.auto_connect_pill_network_text)
    private val cancel: ImageButton? = root.findViewById(R.id.auto_connect_pill_cancel)

    val isVisible: Boolean get() = root.visibility == View.VISIBLE

    fun setHeadline(text: CharSequence) {
        headline?.text = text
    }

    /** A null action hides the X, which is what the projection's copy wants: nothing to cancel. */
    fun setCancelAction(action: (() -> Unit)?) {
        val button = cancel ?: return
        if (action == null) {
            button.visibility = View.GONE
            button.setOnClickListener(null)
        } else {
            button.visibility = View.VISIBLE
            button.setOnClickListener { action() }
        }
    }

    /** Raises the pill, seeding both lines without animation so a rebuild restores what was up. */
    fun show() {
        if (isVisible) return
        applyStage(ConnectionStageTracker.stage.value, animate = false)
        applyNetwork(ConnectionStageTracker.network.value, animate = false)
        root.visibility = View.VISIBLE
        root.bringToFront()
    }

    fun hide() {
        if (!isVisible) return
        root.visibility = View.GONE
    }

    fun bringToFront() {
        if (isVisible) root.bringToFront()
    }

    /**
     * Sets the second line. The crossfade doubles as cover for the pill resizing, which it does on
     * every step because it wraps its content and the labels are translated.
     */
    fun applyStage(stage: ConnectionStage?, animate: Boolean) {
        val view = stageText ?: return
        if (stage == null) {
            view.animate().cancel()
            view.visibility = View.GONE
            return
        }
        val label = root.context.getString(stage.label)
        if (view.visibility == View.VISIBLE && view.text == label) return

        view.animate().cancel()
        if (!animate) {
            view.text = label
            view.alpha = STAGE_TEXT_ALPHA
            view.visibility = View.VISIBLE
            return
        }
        // A discrete announcement per step, not an accessibilityLiveRegion: the pill is now
        // permanently on screen, and a live region on one of those floods a screen reader.
        root.announceForAccessibility(label)
        crossfade(view, label)
    }

    /** Sets the third line. Not announced: the channel is not a step. */
    fun applyNetwork(detail: ConnectionNetworkDetail?, animate: Boolean) {
        val view = networkText ?: return
        if (detail == null) {
            view.animate().cancel()
            view.visibility = View.GONE
            return
        }
        val label = networkLabel(detail)
        if (view.visibility == View.VISIBLE && view.text == label) return

        view.animate().cancel()
        if (!animate || view.visibility != View.VISIBLE) {
            view.text = label
            view.alpha = if (animate) 0f else STAGE_TEXT_ALPHA
            view.visibility = View.VISIBLE
            if (animate) view.animate().alpha(STAGE_TEXT_ALPHA).setDuration(STAGE_FADE_IN_MS).start()
            return
        }
        crossfade(view, label)
    }

    fun networkLabel(detail: ConnectionNetworkDetail): String {
        val line = ConnectionNetworkDetailPolicy.lineFor(detail)
        return root.context.getString(line.id, *line.args.toTypedArray())
    }

    private fun crossfade(view: TextView, label: String) {
        if (view.visibility != View.VISIBLE) {
            view.text = label
            view.alpha = 0f
            view.visibility = View.VISIBLE
            view.animate().alpha(STAGE_TEXT_ALPHA).setDuration(STAGE_FADE_IN_MS).start()
            return
        }
        view.animate().alpha(0f).setDuration(STAGE_FADE_OUT_MS).withEndAction {
            view.text = label
            view.animate().alpha(STAGE_TEXT_ALPHA).setDuration(STAGE_FADE_IN_MS).start()
        }.start()
    }

    companion object {
        private const val STAGE_TEXT_ALPHA = 0.8f
        private const val STAGE_FADE_OUT_MS = 100L
        private const val STAGE_FADE_IN_MS = 150L
    }
}
