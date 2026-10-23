package com.andrerinas.openheadunit.main.settings

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.openheadunit.R

/** A section of the settings list, as the category rail and chips show it. */
data class SettingsCategory(
    val id: String,
    @StringRes val titleResId: Int,
    /** Adapter position of the section header in the settings list. */
    val headerPosition: Int
)

/** The Material Symbols Rounded icon of each settings section, filled and outline-only. */
object SettingsCategoryIcons {
    private val icons: Map<String, Pair<Int, Int>> = mapOf(
        "general" to (R.drawable.ic_sym_tune to R.drawable.ic_sym_tune_fill),
        "wirelessConnection" to (R.drawable.ic_sym_wifi to R.drawable.ic_sym_wifi_fill),
        "automation" to (R.drawable.ic_sym_bolt to R.drawable.ic_sym_bolt_fill),
        "moreFeatures" to (R.drawable.ic_sym_extension to R.drawable.ic_sym_extension_fill),
        "navigation" to (R.drawable.ic_sym_navigation to R.drawable.ic_sym_navigation_fill),
        "graphic" to (R.drawable.ic_sym_display_settings to R.drawable.ic_sym_display_settings_fill),
        "theming" to (R.drawable.ic_sym_palette to R.drawable.ic_sym_palette_fill),
        "video" to (R.drawable.ic_sym_videocam to R.drawable.ic_sym_videocam_fill),
        "input" to (R.drawable.ic_sym_keyboard to R.drawable.ic_sym_keyboard_fill),
        "audio" to (R.drawable.ic_sym_volume_up to R.drawable.ic_sym_volume_up_fill),
        "UI" to (R.drawable.ic_sym_dashboard to R.drawable.ic_sym_dashboard_fill),
        "backup" to (R.drawable.ic_sym_settings_backup_restore to R.drawable.ic_sym_settings_backup_restore_fill),
        "resetSettingsCategory" to (R.drawable.ic_sym_restart_alt to R.drawable.ic_sym_restart_alt_fill),
        "debug" to (R.drawable.ic_sym_bug_report to R.drawable.ic_sym_bug_report_fill),
        "info" to (R.drawable.ic_sym_info to R.drawable.ic_sym_info_fill)
    )

    @DrawableRes
    fun iconFor(categoryId: String, filled: Boolean): Int {
        val pair = icons[categoryId] ?: (R.drawable.ic_sym_tune to R.drawable.ic_sym_tune_fill)
        return if (filled) pair.second else pair.first
    }
}

/**
 * The category navigation beside (landscape) or above (portrait) the settings list. One adapter
 * for both: [asRail] picks the row layout of the side rail or the pill of the chip row.
 */
class SettingsCategoryAdapter(
    private val asRail: Boolean,
    private val onPick: (SettingsCategory) -> Unit
) : RecyclerView.Adapter<SettingsCategoryAdapter.CategoryViewHolder>() {

    private var categories: List<SettingsCategory> = emptyList()
    private var activeId: String? = null
    private var palette: SettingsPalette? = null

    fun categoryIdAt(index: Int): String? = categories.getOrNull(index)?.id

    @SuppressLint("NotifyDataSetChanged")
    fun submit(newCategories: List<SettingsCategory>) {
        categories = newCategories
        if (categories.none { it.id == activeId }) activeId = categories.firstOrNull()?.id
        // At most fifteen sections: a full rebind is cheaper than diffing them.
        notifyDataSetChanged()
    }

    /** Highlights [id]; returns its position so the caller can keep it on screen, or -1. */
    fun setActive(id: String?): Int {
        if (id == activeId) return categories.indexOfFirst { it.id == id }
        val old = categories.indexOfFirst { it.id == activeId }
        activeId = id
        val new = categories.indexOfFirst { it.id == id }
        if (old >= 0) notifyItemChanged(old)
        if (new >= 0) notifyItemChanged(new)
        return new
    }

    override fun getItemCount(): Int = categories.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val layout = if (asRail) R.layout.item_settings_category_rail else R.layout.item_settings_category_chip
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return CategoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        val category = categories[position]
        val colors = palette ?: SettingsPalette.from(holder.itemView.context).also { palette = it }
        holder.bind(category, category.id == activeId, colors)
    }

    inner class CategoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.categoryIcon)
        private val title: TextView = itemView.findViewById(R.id.categoryTitle)

        fun bind(category: SettingsCategory, active: Boolean, colors: SettingsPalette) {
            val context = itemView.context
            val content = if (active) colors.onSecondaryContainer else colors.onSurfaceVariant
            title.setText(category.titleResId)
            title.setTextColor(content)
            icon.setImageDrawable(
                SettingsDrawables.tinted(context, SettingsCategoryIcons.iconFor(category.id, colors.filledIcons), content)
            )
            // Rail rows are a full pill (their height is fixed); chips use Material's 8dp corners.
            val radius = if (asRail) context.resources.getDimension(R.dimen.settings_rail_item_height) / 2f
                else SettingsDrawables.dp(context, CHIP_RADIUS_DP)
            val fill = if (active) colors.secondaryContainer else android.graphics.Color.TRANSPARENT
            // Chips keep an outline when idle so the row reads as tappable; the rail does not.
            val stroke = if (!asRail && !active) colors.outlineVariant else null
            itemView.background = SettingsDrawables.selectionPill(context, fill, stroke, radius, colors)
            itemView.isSelected = active
            itemView.setOnClickListener { onPick(category) }
        }
    }

    private companion object {
        const val CHIP_RADIUS_DP = 8f
    }
}
