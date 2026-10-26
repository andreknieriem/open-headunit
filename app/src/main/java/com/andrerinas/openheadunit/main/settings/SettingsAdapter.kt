package com.andrerinas.openheadunit.main.settings

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.openheadunit.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.shape.ShapeAppearanceModel

// Sealed class to represent different types of items in the settings list
sealed class SettingItem {
    abstract val stableId: String // Unique ID for DiffUtil

    data class SettingEntry(
        override val stableId: String, // Unique ID for the setting (e.g., "gpsNavigation")
        @StringRes val nameResId: Int,
        var value: String, // Current display value of the setting
        val nameOverride: String? = null, // Dynamic title (used instead of nameResId when set)
        // Extra terms matched by the settings search (e.g. picker options or the
        // names of the settings inside a sub-screen). Not shown in the UI.
        val searchKeywords: String? = null,
        val isEnabled: Boolean = true,
        val onClick: (settingId: String) -> Unit // Callback when the setting is clicked
    ) : SettingItem()

    /**
     * A picker row with a speaker button beside it, for choosing an output stream and hearing it
     * without leaving the screen. The test plays the value shown in the row, which is the pending
     * choice rather than the saved one — the point is to try a stream before committing to it.
     */
    data class StreamSettingEntry(
        override val stableId: String,
        @StringRes val nameResId: Int,
        var value: String,
        @StringRes val descriptionResId: Int? = null,
        val searchKeywords: String? = null,
        val onClick: (settingId: String) -> Unit,
        val onTest: () -> Unit
    ) : SettingItem()

    data class ToggleSettingEntry(
        override val stableId: String,
        @StringRes val nameResId: Int,
        @StringRes val descriptionResId: Int?,
        var isChecked: Boolean,
        val isEnabled: Boolean = true,
        val nameOverride: String? = null,
        /** Replaces [descriptionResId] when the subtitle depends on the device, not the string. */
        val descriptionOverride: String? = null,
        val searchKeywords: String? = null,
        val onCheckedChanged: (Boolean) -> Unit
    ) : SettingItem()

    data class SliderSettingEntry(
        override val stableId: String,
        @StringRes val nameResId: Int,
        var value: String,
        var sliderValue: Float,
        val valueFrom: Float,
        val valueTo: Float,
        val stepSize: Float,
        val onValueChanged: (Float) -> Unit
    ) : SettingItem()

    data class CategoryHeader(override val stableId: String, @StringRes val titleResId: Int) : SettingItem()

    data class InfoBanner(
        override val stableId: String,
        @StringRes val textResId: Int,
        val text: String? = null // Dynamic text (used instead of textResId when set)
    ) : SettingItem()

    data class ActionButton(
        override val stableId: String,
        @StringRes val textResId: Int,
        val isEnabled: Boolean = true,
        val onClick: () -> Unit
    ) : SettingItem()

    data class SegmentedButtonSettingEntry(
        override val stableId: String,
        @StringRes val nameResId: Int,
        val options: List<String>, // Exactly 3 options for now as per layout
        var selectedIndex: Int,
        val onOptionSelected: (Int) -> Unit
    ) : SettingItem()

    /**
     * The note above the list naming the settings the chosen connection type hides, with a
     * shortcut ([actionResId]) to change that choice.
     */
    data class ScopeNotice(
        override val stableId: String,
        val text: String,
        /** Outline-only icon, used on extreme dark. */
        @DrawableRes val iconResId: Int,
        /** Filled icon, used in day and night. */
        @DrawableRes val filledIconResId: Int,
        @StringRes val actionResId: Int,
        val onAction: () -> Unit
    ) : SettingItem()
}

class SettingsAdapter : ListAdapter<SettingItem, RecyclerView.ViewHolder>(SettingsDiffCallback()) {

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_SETTING = 1
        private const val VIEW_TYPE_TOGGLE = 3
        private const val VIEW_TYPE_SLIDER = 4
        private const val VIEW_TYPE_INFO_BANNER = 5
        private const val VIEW_TYPE_ACTION_BUTTON = 6
        private const val VIEW_TYPE_SEGMENTED = 7
        private const val VIEW_TYPE_STREAM = 8
        private const val VIEW_TYPE_SCOPE_NOTICE = 9

        /** Rebind payload that only refreshes a row's group corners. */
        private const val PAYLOAD_GROUP_SHAPE = "groupShape"

    }

    // Resolved from the first parent's theme; every screen using this adapter shares one theme.
    private var palette: SettingsPalette? = null

    private fun palette(parent: View): SettingsPalette =
        palette ?: SettingsPalette.from(parent.context).also { palette = it }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is SettingItem.CategoryHeader -> VIEW_TYPE_HEADER
            is SettingItem.SettingEntry -> VIEW_TYPE_SETTING
            is SettingItem.ToggleSettingEntry -> VIEW_TYPE_TOGGLE
            is SettingItem.SliderSettingEntry -> VIEW_TYPE_SLIDER
            is SettingItem.InfoBanner -> VIEW_TYPE_INFO_BANNER
            is SettingItem.ActionButton -> VIEW_TYPE_ACTION_BUTTON
            is SettingItem.SegmentedButtonSettingEntry -> VIEW_TYPE_SEGMENTED
            is SettingItem.StreamSettingEntry -> VIEW_TYPE_STREAM
            is SettingItem.ScopeNotice -> VIEW_TYPE_SCOPE_NOTICE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val colors = palette(parent)
        return when (viewType) {
            VIEW_TYPE_HEADER -> HeaderViewHolder(inflater.inflate(R.layout.layout_category_header, parent, false))
            VIEW_TYPE_SETTING -> SettingViewHolder(inflater.inflate(R.layout.layout_setting_item, parent, false))
            VIEW_TYPE_TOGGLE -> ToggleSettingViewHolder(inflater.inflate(R.layout.layout_setting_item_toggle, parent, false), colors)
            VIEW_TYPE_SLIDER -> SliderSettingViewHolder(inflater.inflate(R.layout.layout_setting_item_slider, parent, false), colors)
            VIEW_TYPE_INFO_BANNER -> InfoBannerViewHolder(inflater.inflate(R.layout.layout_setting_info_banner, parent, false))
            VIEW_TYPE_ACTION_BUTTON -> ActionButtonViewHolder(inflater.inflate(R.layout.layout_setting_action_button, parent, false), colors)
            VIEW_TYPE_SEGMENTED -> SegmentedButtonViewHolder(inflater.inflate(R.layout.layout_setting_item_segmented, parent, false), colors)
            VIEW_TYPE_STREAM -> StreamSettingViewHolder(inflater.inflate(R.layout.layout_setting_item_stream, parent, false), colors)
            VIEW_TYPE_SCOPE_NOTICE -> ScopeNoticeViewHolder(inflater.inflate(R.layout.layout_setting_scope_notice, parent, false), colors)
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        applyGroupShape(holder, position)

        when (item) {
            is SettingItem.CategoryHeader -> (holder as HeaderViewHolder).bind(item)
            is SettingItem.SettingEntry -> (holder as SettingViewHolder).bind(item)
            is SettingItem.ToggleSettingEntry -> (holder as ToggleSettingViewHolder).bind(item)
            is SettingItem.SliderSettingEntry -> (holder as SliderSettingViewHolder).bind(item)
            is SettingItem.InfoBanner -> (holder as InfoBannerViewHolder).bind(item)
            is SettingItem.ActionButton -> (holder as ActionButtonViewHolder).bind(item)
            is SettingItem.SegmentedButtonSettingEntry -> (holder as SegmentedButtonViewHolder).bind(item)
            is SettingItem.StreamSettingEntry -> (holder as StreamSettingViewHolder).bind(item)
            is SettingItem.ScopeNotice -> (holder as ScopeNoticeViewHolder).bind(item)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_GROUP_SHAPE }) {
            applyGroupShape(holder, position)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    /**
     * A row's corners depend on its neighbours, and DiffUtil only rebinds the rows that changed.
     * When filtering inserts or removes rows, the ones beside them would keep stale corners, so
     * every list change refreshes the shapes (and only the shapes) of all rows.
     */
    override fun onCurrentListChanged(previousList: MutableList<SettingItem>, currentList: MutableList<SettingItem>) {
        super.onCurrentListChanged(previousList, currentList)
        if (previousList.isNotEmpty() && currentList.isNotEmpty()) {
            notifyItemRangeChanged(0, currentList.size, PAYLOAD_GROUP_SHAPE)
        }
    }

    private fun applyGroupShape(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        if (!isGroupRow(item)) return
        val view = holder.itemView
        val prev = if (position > 0) getItem(position - 1) else null
        val next = if (position < itemCount - 1) getItem(position + 1) else null
        val groupPosition = GroupPosition.of(isFirst = !isGroupRow(prev), isLast = !isGroupRow(next))
        val colors = palette(view)
        val fill = if (item is SettingItem.InfoBanner) colors.containerHighest else colors.container
        val radius = view.resources.getDimension(R.dimen.settings_group_radius)
        view.background = SettingsDrawables.groupItem(view.context, colors, groupPosition, radius, fill)
        // A group right under the scope note (the "no results" note, say) has no section header
        // to space it, so it takes the header's gap itself. Reset otherwise: holders are recycled.
        (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            val top = if (prev is SettingItem.ScopeNotice) view.resources.getDimensionPixelSize(R.dimen.settings_section_spacing) else 0
            if (params.topMargin != top) {
                params.topMargin = top
                view.layoutParams = params
            }
        }
    }

    /** Rows that join their neighbours into one rounded group; headers and buttons break it. */
    private fun isGroupRow(item: SettingItem?): Boolean = SettingsLayoutPolicy.joinsGroup(item)

    // --- ViewHolder implementations ---

    class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.categoryTitle)
        fun bind(header: SettingItem.CategoryHeader) {
            title.setText(header.titleResId)
        }
    }

    class SettingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val settingName: TextView = itemView.findViewById(R.id.settingName)
        private val settingValue: TextView = itemView.findViewById(R.id.settingValue)
        private val settingValueInline: TextView = itemView.findViewById(R.id.settingValueInline)

        fun bind(setting: SettingItem.SettingEntry) {
            if (setting.nameOverride != null) settingName.text = setting.nameOverride
            else settingName.setText(setting.nameResId)
            // Both views are set on every bind, because the holder is recycled.
            val inline = SettingsLayoutPolicy.showValueInline(setting.value)
            settingValueInline.text = if (inline) setting.value else ""
            settingValueInline.visibility = if (inline) View.VISIBLE else View.GONE
            settingValue.text = if (inline) "" else setting.value
            settingValue.visibility = if (inline || setting.value.isBlank()) View.GONE else View.VISIBLE
            itemView.alpha = if (setting.isEnabled) 1.0f else 0.5f
            itemView.isClickable = setting.isEnabled
            itemView.setOnClickListener { if (setting.isEnabled) setting.onClick(setting.stableId) }
        }
    }

    class StreamSettingViewHolder(itemView: View, colors: SettingsPalette) : RecyclerView.ViewHolder(itemView) {
        private val settingName: TextView = itemView.findViewById(R.id.settingName)
        private val settingValue: TextView = itemView.findViewById(R.id.settingValue)
        private val settingDescription: TextView = itemView.findViewById(R.id.settingDescription)
        private val testButton: MaterialButton = itemView.findViewById(R.id.settingTestButton)

        init {
            testButton.strokeColor = ColorStateList.valueOf(colors.outline)
            testButton.iconTint = ColorStateList.valueOf(colors.onSurfaceVariant)
            testButton.rippleColor = ColorStateList.valueOf(colors.secondaryContainer)
        }

        fun bind(setting: SettingItem.StreamSettingEntry) {
            settingName.setText(setting.nameResId)
            settingValue.text = setting.value
            // Both branches set the visibility, because the holder is recycled.
            if (setting.descriptionResId != null) {
                settingDescription.setText(setting.descriptionResId)
                settingDescription.visibility = View.VISIBLE
            } else {
                settingDescription.visibility = View.GONE
            }
            itemView.setOnClickListener { setting.onClick(setting.stableId) }
            testButton.setOnClickListener { setting.onTest() }
        }
    }

    class ToggleSettingViewHolder(itemView: View, colors: SettingsPalette) : RecyclerView.ViewHolder(itemView) {
        private val settingName: TextView = itemView.findViewById(R.id.settingName)
        private val settingDescription: TextView = itemView.findViewById(R.id.settingDescription)
        private val settingSwitch: SwitchCompat = itemView.findViewById(R.id.settingSwitch)

        init {
            val context = itemView.context
            settingSwitch.trackDrawable = SettingsDrawables.switchTrack(context, colors)
            settingSwitch.thumbDrawable = SettingsDrawables.switchThumb(context, colors)
            // The drawables already carry their colors; a theme tint would repaint them.
            settingSwitch.trackTintList = null
            settingSwitch.thumbTintList = null
        }

        fun bind(setting: SettingItem.ToggleSettingEntry) {
            if (setting.nameOverride != null) settingName.text = setting.nameOverride
            else settingName.setText(setting.nameResId)
            // Both branches set the visibility, because the holder is recycled. Hiding the view
            // without ever showing it again meant that once a toggle with no description had been
            // bound here, every later toggle reusing this holder rendered without its subtitle.
            if (setting.descriptionOverride != null) {
                settingDescription.text = setting.descriptionOverride
                settingDescription.visibility = View.VISIBLE
            } else if (setting.descriptionResId != null) {
                settingDescription.setText(setting.descriptionResId)
                settingDescription.visibility = View.VISIBLE
            } else {
                settingDescription.visibility = View.GONE
            }
            settingSwitch.setOnCheckedChangeListener(null)
            settingSwitch.isChecked = setting.isChecked
            settingSwitch.isEnabled = setting.isEnabled
            itemView.alpha = if (setting.isEnabled) 1.0f else 0.5f
            itemView.isClickable = setting.isEnabled
            settingSwitch.setOnCheckedChangeListener { _, isChecked ->
                setting.onCheckedChanged(isChecked)
            }
            itemView.setOnClickListener {
                if (setting.isEnabled) settingSwitch.toggle()
            }
        }
    }

    class InfoBannerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val infoText: TextView = itemView.findViewById(R.id.infoText)
        fun bind(item: SettingItem.InfoBanner) {
            if (item.text != null) infoText.text = item.text
            else infoText.setText(item.textResId)
        }
    }

    class ActionButtonViewHolder(itemView: View, colors: SettingsPalette) : RecyclerView.ViewHolder(itemView) {
        private val button: MaterialButton = itemView.findViewById(R.id.action_button)

        init {
            styleFilledButton(button, colors)
            // A full pill at whatever height the panel size gives the button.
            button.cornerRadius = itemView.resources.getDimensionPixelSize(R.dimen.settings_action_button_height) / 2
        }

        fun bind(item: SettingItem.ActionButton) {
            button.setText(item.textResId)
            button.isEnabled = item.isEnabled
            button.setOnClickListener { item.onClick() }
        }
    }

    class ScopeNoticeViewHolder(itemView: View, private val colors: SettingsPalette) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.scopeIcon)
        private val text: TextView = itemView.findViewById(R.id.scopeText)
        private val action: TextView = itemView.findViewById(R.id.scopeAction)

        init {
            val radius = itemView.resources.getDimension(R.dimen.settings_scope_notice_radius)
            itemView.background = SettingsDrawables.box(itemView.context, colors.secondaryContainer, radius, colors)
        }

        fun bind(item: SettingItem.ScopeNotice) {
            icon.setImageResource(if (colors.filledIcons) item.filledIconResId else item.iconResId)
            text.text = item.text
            action.setText(item.actionResId)
            action.setOnClickListener { item.onAction() }
        }
    }

    class SliderSettingViewHolder(itemView: View, colors: SettingsPalette) : RecyclerView.ViewHolder(itemView) {
        private val settingName: TextView = itemView.findViewById(R.id.settingName)
        private val settingValue: TextView = itemView.findViewById(R.id.settingValue)
        private val settingSlider: SeekBar = itemView.findViewById(R.id.settingSlider)

        init {
            tintSeekBar(settingSlider, colors)
        }

        fun bind(setting: SettingItem.SliderSettingEntry) {
            settingName.setText(setting.nameResId)
            settingValue.text = setting.value

            // SeekBar works with integers 0 to max.
            // We map the float range [valueFrom, valueTo] to [0, 1000] for precision if needed,
            // but for desaturation 0-100 is enough.
            val range = setting.valueTo - setting.valueFrom
            val steps = if (range > 0) 100 else 1

            settingSlider.max = steps
            val progress = (((setting.sliderValue - setting.valueFrom) / range) * steps).toInt()
            settingSlider.progress = progress

            settingSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val newValue = setting.valueFrom + (progress.toFloat() / steps) * range
                        val intVal = newValue.toInt()
                        val suffix = when {
                            setting.value.endsWith("%") -> "%"
                            setting.value.endsWith("dp") -> "dp"
                            setting.value.endsWith("s") -> "s"
                            else -> ""
                        }
                        settingValue.text = "$intVal$suffix"
                        setting.onValueChanged(newValue)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
    }

    class SegmentedButtonViewHolder(itemView: View, colors: SettingsPalette) : RecyclerView.ViewHolder(itemView) {
        private val settingName: TextView = itemView.findViewById(R.id.settingName)
        private val buttons: List<MaterialButton> = listOf(
            itemView.findViewById(R.id.btnOption1),
            itemView.findViewById(R.id.btnOption2),
            itemView.findViewById(R.id.btnOption3)
        )

        init {
            val background = SettingsDrawables.checkedColors(colors.secondaryContainer, Color.TRANSPARENT)
            val text = SettingsDrawables.checkedColors(colors.onSecondaryContainer, colors.onSurface)
            val stroke = ColorStateList.valueOf(colors.outline)
            for (button in buttons) {
                button.backgroundTintList = background
                button.setTextColor(text)
                button.strokeColor = stroke
                button.rippleColor = ColorStateList.valueOf(colors.secondaryContainer)
            }
        }

        fun bind(setting: SettingItem.SegmentedButtonSettingEntry) {
            settingName.setText(setting.nameResId)

            val visibleButtons = buttons.filterIndexed { index, _ -> index < setting.options.size }
            buttons.forEachIndexed { index, button ->
                if (index < setting.options.size) {
                    button.text = setting.options[index]
                    button.visibility = View.VISIBLE
                } else {
                    button.visibility = View.GONE
                }
            }

            // Pill ends on the outer buttons, square joints between them.
            val radius = itemView.resources.getDimension(R.dimen.settings_segment_radius)
            visibleButtons.forEachIndexed { index, button ->
                val start = if (index == 0) radius else 0f
                val end = if (index == visibleButtons.size - 1) radius else 0f
                button.shapeAppearanceModel = ShapeAppearanceModel.builder()
                    .setTopLeftCornerSize(start).setBottomLeftCornerSize(start)
                    .setTopRightCornerSize(end).setBottomRightCornerSize(end)
                    .build()
                button.isChecked = index == setting.selectedIndex

                button.setOnClickListener {
                    if (index != setting.selectedIndex) {
                        // Instant feedback: uncheck all, check this one.
                        for (other in visibleButtons) other.isChecked = false
                        button.isChecked = true
                        setting.onOptionSelected(index)
                    } else {
                        // A checkable button unchecks itself on a second tap; keep the choice.
                        button.isChecked = true
                    }
                }
            }
        }
    }

    // DiffUtil.ItemCallback implementation
    private class SettingsDiffCallback : DiffUtil.ItemCallback<SettingItem>() {
        override fun areItemsTheSame(oldItem: SettingItem, newItem: SettingItem): Boolean {
            return oldItem.stableId == newItem.stableId
        }

        override fun areContentsTheSame(oldItem: SettingItem, newItem: SettingItem): Boolean {
            return when {
                oldItem is SettingItem.SettingEntry && newItem is SettingItem.SettingEntry ->
                    oldItem.nameResId == newItem.nameResId && oldItem.value == newItem.value && oldItem.isEnabled == newItem.isEnabled
                oldItem is SettingItem.ToggleSettingEntry && newItem is SettingItem.ToggleSettingEntry ->
                    oldItem.nameResId == newItem.nameResId && oldItem.descriptionResId == newItem.descriptionResId && oldItem.isChecked == newItem.isChecked && oldItem.isEnabled == newItem.isEnabled && oldItem.nameOverride == newItem.nameOverride && oldItem.descriptionOverride == newItem.descriptionOverride
                oldItem is SettingItem.SliderSettingEntry && newItem is SettingItem.SliderSettingEntry ->
                    oldItem.nameResId == newItem.nameResId && oldItem.value == newItem.value && oldItem.sliderValue == newItem.sliderValue
                oldItem is SettingItem.CategoryHeader && newItem is SettingItem.CategoryHeader ->
                    oldItem.titleResId == newItem.titleResId
                oldItem is SettingItem.InfoBanner && newItem is SettingItem.InfoBanner ->
                    oldItem.textResId == newItem.textResId && oldItem.text == newItem.text
                oldItem is SettingItem.ActionButton && newItem is SettingItem.ActionButton ->
                    oldItem.textResId == newItem.textResId && oldItem.isEnabled == newItem.isEnabled
                oldItem is SettingItem.StreamSettingEntry && newItem is SettingItem.StreamSettingEntry ->
                    oldItem.nameResId == newItem.nameResId && oldItem.value == newItem.value &&
                        oldItem.descriptionResId == newItem.descriptionResId
                oldItem is SettingItem.SegmentedButtonSettingEntry && newItem is SettingItem.SegmentedButtonSettingEntry ->
                    oldItem.nameResId == newItem.nameResId && oldItem.options == newItem.options && oldItem.selectedIndex == newItem.selectedIndex
                oldItem is SettingItem.ScopeNotice && newItem is SettingItem.ScopeNotice ->
                    oldItem.text == newItem.text && oldItem.iconResId == newItem.iconResId
                else -> false
            }
        }
    }
}

// Material 3 disabled button: container at 12% and content at 38% of on-surface.
private const val DISABLED_CONTAINER_ALPHA = 0x1F
private const val DISABLED_CONTENT_ALPHA = 0x61

/** A filled Material 3 button in the settings palette, greyed out while disabled. */
internal fun styleFilledButton(button: MaterialButton, colors: SettingsPalette) {
    val disabledContainer = ColorUtils.setAlphaComponent(colors.onSurface, DISABLED_CONTAINER_ALPHA)
    val disabledContent = ColorUtils.setAlphaComponent(colors.onSurface, DISABLED_CONTENT_ALPHA)
    button.backgroundTintList = SettingsDrawables.enabledColors(colors.primary, disabledContainer)
    button.setTextColor(SettingsDrawables.enabledColors(colors.onPrimary, disabledContent))
    button.iconTint = SettingsDrawables.enabledColors(colors.onPrimary, disabledContent)
}

/**
 * Paints a SeekBar in the palette: primary for the progress and thumb, the secondary container
 * for the rest of the track. API 21 has tint lists. Before that the drawable is a layer list
 * (background, secondary progress, progress) and each layer is filtered on its own, because
 * tinting the whole list would paint the track and the progress the same color.
 */
internal fun tintSeekBar(seekBar: SeekBar, colors: SettingsPalette) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        seekBar.progressTintList = ColorStateList.valueOf(colors.primary)
        seekBar.progressBackgroundTintList = ColorStateList.valueOf(colors.secondaryContainer)
        seekBar.thumbTintList = ColorStateList.valueOf(colors.primary)
    } else {
        val layers = seekBar.progressDrawable?.mutate() as? LayerDrawable
        layers?.findDrawableByLayerId(android.R.id.background)
            ?.setColorFilter(colors.secondaryContainer, PorterDuff.Mode.SRC_IN)
        layers?.findDrawableByLayerId(android.R.id.secondaryProgress)
            ?.setColorFilter(colors.secondaryContainer, PorterDuff.Mode.SRC_IN)
        layers?.findDrawableByLayerId(android.R.id.progress)
            ?.setColorFilter(colors.primary, PorterDuff.Mode.SRC_IN)
        seekBar.thumb?.mutate()?.setColorFilter(colors.primary, PorterDuff.Mode.SRC_IN)
    }
}
