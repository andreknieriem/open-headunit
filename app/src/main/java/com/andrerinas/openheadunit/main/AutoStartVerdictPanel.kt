package com.andrerinas.openheadunit.main

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.main.settings.SettingsDrawables
import com.andrerinas.openheadunit.main.settings.SettingsPalette
import com.andrerinas.openheadunit.main.settings.styleFilledButton
import com.google.android.material.button.MaterialButton

/** What the headline panel of the auto-start check shows. Null [title] while the check runs. */
data class AutoStartVerdict(
    val title: String?,
    val body: String,
    val problem: Boolean,
    val access: String?,
    val showFix: Boolean,
    val reportReady: Boolean
)

/** Binds layout_auto_start_verdict, beside the list or as its first item. */
class AutoStartVerdictPanel(
    private val root: View,
    private val onFix: () -> Unit,
    private val onAgain: () -> Unit,
    private val onCopy: () -> Unit,
    private val onShare: () -> Unit
) {
    private val colors = SettingsPalette.from(root.context)
    private val iconBox: View = root.findViewById(R.id.verdictIconBox)
    private val icon: ImageView = root.findViewById(R.id.verdictIcon)
    private val title: TextView = root.findViewById(R.id.verdictTitle)
    private val body: TextView = root.findViewById(R.id.verdictBody)
    private val access: TextView = root.findViewById(R.id.verdictAccess)
    private val fix: MaterialButton = root.findViewById(R.id.verdictFix)
    private val again: MaterialButton = root.findViewById(R.id.verdictAgain)
    private val copy: MaterialButton = root.findViewById(R.id.verdictCopy)
    private val share: MaterialButton = root.findViewById(R.id.verdictShare)
    private val problemColor = ContextCompat.getColor(root.context, R.color.settings_status_problem)
    private val problemContainer = ContextCompat.getColor(root.context, R.color.settings_status_problem_container)
    private var shown: AutoStartVerdict? = null

    init {
        val context = root.context
        val radius = context.resources.getDimension(R.dimen.settings_group_radius)
        root.background = SettingsDrawables.box(context, colors.container, radius, colors)
        styleFilledButton(fix, colors)
        again.strokeColor = ColorStateList.valueOf(colors.outline)
        for (button in listOf(again, copy, share)) {
            button.setTextColor(colors.primary)
            button.rippleColor = ColorStateList.valueOf(colors.secondaryContainer)
        }
        access.background = SettingsDrawables.box(context, colors.containerHighest, SettingsDrawables.dp(context, 16f), colors)
        access.setCompoundDrawablesWithIntrinsicBounds(
            AppCompatResources.getDrawable(context, R.drawable.ic_sym_lock), null, null, null
        )
        TextViewCompat.setCompoundDrawableTintList(access, ColorStateList.valueOf(colors.onSurfaceVariant))
        fix.setOnClickListener { onFix() }
        again.setOnClickListener { onAgain() }
        copy.setOnClickListener { onCopy() }
        share.setOnClickListener { onShare() }
    }

    fun bind(v: AutoStartVerdict) {
        val previous = shown
        shown = v
        val context = root.context
        val running = v.title == null
        title.text = v.title ?: context.getString(R.string.auto_start_check_running)
        body.text = v.body
        body.visibility = if (v.body.isEmpty()) View.GONE else View.VISIBLE
        val tone = if (v.problem) problemColor else colors.primary
        val toneContainer = if (v.problem) problemContainer else colors.secondaryContainer
        iconBox.visibility = if (running) View.INVISIBLE else View.VISIBLE
        iconBox.background = SettingsDrawables.dot(toneContainer)
        icon.setImageDrawable(SettingsDrawables.tinted(context, if (v.problem) R.drawable.ic_warning_white else R.drawable.ic_sym_check, tone))
        access.text = v.access
        access.visibility = if (v.access == null) View.GONE else View.VISIBLE
        fix.visibility = if (v.showFix) View.VISIBLE else View.GONE
        again.isEnabled = !running
        copy.isEnabled = v.reportReady
        share.isEnabled = v.reportReady

        // A new headline fades in where the old one was. Animators follow the system's animation
        // scale, so with animations off this is instant.
        if (previous != null && previous.title != v.title && ViewCompat.isAttachedToWindow(root)) {
            for (view in listOf(title, body, iconBox)) {
                view.animate().cancel()
                view.alpha = FADE_FROM
                view.animate().alpha(1f).setDuration(FADE_MS).start()
            }
        }
    }

    private companion object {
        const val FADE_FROM = 0.25f
        const val FADE_MS = 150L
    }
}

/** The headline panel as the first item of the list, on screens too narrow to put it beside. */
class AutoStartVerdictAdapter(
    private val onFix: () -> Unit,
    private val onAgain: () -> Unit,
    private val onCopy: () -> Unit,
    private val onShare: () -> Unit
) : RecyclerView.Adapter<AutoStartVerdictAdapter.Holder>() {

    var verdict: AutoStartVerdict? = null
        set(value) {
            field = value
            notifyItemChanged(0, PAYLOAD_SAME_VIEW)
        }

    class Holder(view: View, val panel: AutoStartVerdictPanel) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.layout_auto_start_verdict, parent, false)
        return Holder(view, AutoStartVerdictPanel(view, onFix, onAgain, onCopy, onShare))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        verdict?.let { holder.panel.bind(it) }
    }

    override fun getItemCount(): Int = 1

    private companion object {
        /** Rebinding in place keeps the holder, so the panel can fade its new headline in. */
        const val PAYLOAD_SAME_VIEW = "sameView"
    }
}
