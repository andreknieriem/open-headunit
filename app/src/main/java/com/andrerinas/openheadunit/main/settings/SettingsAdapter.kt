package com.andrerinas.openheadunit.main.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import android.os.Build
import androidx.annotation.StringRes
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.openheadunit.R

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
        val onClick: (settingId: String) -> Unit // Callback when the setting is clicked
    ) : SettingItem()

    data class ToggleSettingEntry(
        override val stableId: String,
        @StringRes val nameResId: Int,
        @StringRes val descriptionResId: Int?,
        var isChecked: Boolean,
        val isEnabled: Boolean = true,
        val nameOverride: String? = null,
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
}

class SettingsAdapter : ListAdapter<SettingItem, RecyclerView.ViewHolder>(SettingsDiffCallback()) { // Inherit from ListAdapter

    // Define View Types
    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_SETTING = 1
        private const val VIEW_TYPE_TOGGLE = 3
        private const val VIEW_TYPE_SLIDER = 4
        private const val VIEW_TYPE_INFO_BANNER = 5
        private const val VIEW_TYPE_ACTION_BUTTON = 6
        private const val VIEW_TYPE_SEGMENTED = 7
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) { // Use getItem() from ListAdapter
            is SettingItem.CategoryHeader -> VIEW_TYPE_HEADER
            is SettingItem.SettingEntry -> VIEW_TYPE_SETTING
            is SettingItem.ToggleSettingEntry -> VIEW_TYPE_TOGGLE
            is SettingItem.SliderSettingEntry -> VIEW_TYPE_SLIDER
            is SettingItem.InfoBanner -> VIEW_TYPE_INFO_BANNER
            is SettingItem.ActionButton -> VIEW_TYPE_ACTION_BUTTON
            is SettingItem.SegmentedButtonSettingEntry -> VIEW_TYPE_SEGMENTED
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> HeaderViewHolder(inflater.inflate(R.layout.layout_category_header, parent, false))
            VIEW_TYPE_SETTING -> SettingViewHolder(inflater.inflate(R.layout.layout_setting_item, parent, false))
            VIEW_TYPE_TOGGLE -> ToggleSettingViewHolder(inflater.inflate(R.layout.layout_setting_item_toggle, parent, false))
            VIEW_TYPE_SLIDER -> SliderSettingViewHolder(inflater.inflate(R.layout.layout_setting_item_slider, parent, false))
            VIEW_TYPE_INFO_BANNER -> InfoBannerViewHolder(inflater.inflate(R.layout.layout_setting_info_banner, parent, false))
            VIEW_TYPE_ACTION_BUTTON -> ActionButtonViewHolder(inflater.inflate(R.layout.layout_setting_action_button, parent, false))
            VIEW_TYPE_SEGMENTED -> SegmentedButtonViewHolder(inflater.inflate(R.layout.layout_setting_item_segmented, parent, false))
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)

        if (holder is SettingViewHolder || holder is ToggleSettingViewHolder || holder is SliderSettingViewHolder || holder is ActionButtonViewHolder || holder is SegmentedButtonViewHolder) {
            updateItemVisuals(holder.itemView, position)
        }

        when (item) {
            is SettingItem.CategoryHeader -> (holder as HeaderViewHolder).bind(item)
            is SettingItem.SettingEntry -> (holder as SettingViewHolder).bind(item)
            is SettingItem.ToggleSettingEntry -> (holder as ToggleSettingViewHolder).bind(item)
            is SettingItem.SliderSettingEntry -> (holder as SliderSettingViewHolder).bind(item)
            is SettingItem.InfoBanner -> (holder as InfoBannerViewHolder).bind(item)
            is SettingItem.ActionButton -> (holder as ActionButtonViewHolder).bind(item)
            is SettingItem.SegmentedButtonSettingEntry -> (holder as SegmentedButtonViewHolder).bind(item)
        }
    }

    private fun updateItemVisuals(view: View, position: Int) {
        val prev = if (position > 0) getItem(position - 1) else null
        val next = if (position < itemCount - 1) getItem(position + 1) else null

        val isTop = prev is SettingItem.CategoryHeader || prev == null
        val isBottom = next is SettingItem.CategoryHeader || next == null

        val bgRes = when {
            isTop && isBottom -> R.drawable.bg_setting_single
            isTop -> R.drawable.bg_setting_top
            isBottom -> R.drawable.bg_setting_bottom
            else -> R.drawable.bg_setting_middle
        }
        view.setBackgroundResource(bgRes)
    }

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

        fun bind(setting: SettingItem.SettingEntry) {
            if (setting.nameOverride != null) settingName.text = setting.nameOverride
            else settingName.setText(setting.nameResId)
            settingValue.text = setting.value
            itemView.setOnClickListener { setting.onClick(setting.stableId) }
        }
    }

    class ToggleSettingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val settingName: TextView = itemView.findViewById(R.id.settingName)
        private val settingDescription: TextView = itemView.findViewById(R.id.settingDescription)
        private val settingSwitch: Switch = itemView.findViewById(R.id.settingSwitch)

        fun bind(setting: SettingItem.ToggleSettingEntry) {
            if (setting.nameOverride != null) settingName.text = setting.nameOverride
            else settingName.setText(setting.nameResId)
            if (setting.descriptionResId != null) settingDescription.setText(setting.descriptionResId)
            else settingDescription.visibility = View.GONE
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

    class ActionButtonViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val button: com.google.android.material.button.MaterialButton = itemView.findViewById(R.id.action_button)
        fun bind(item: SettingItem.ActionButton) {
            button.setText(item.textResId)
            button.isEnabled = item.isEnabled
            button.setOnClickListener { item.onClick() }
        }
    }

    class SliderSettingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val settingName: TextView = itemView.findViewById(R.id.settingName)
        private val settingValue: TextView = itemView.findViewById(R.id.settingValue)
        private val settingSlider: android.widget.SeekBar = itemView.findViewById(R.id.settingSlider)

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

            settingSlider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val newValue = setting.valueFrom + (progress.toFloat() / steps) * range
                        setting.onValueChanged(newValue)
                    }
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })
        }
    }

    class SegmentedButtonViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val settingName: TextView = itemView.findViewById(R.id.settingName)
        private val btn1: com.google.android.material.button.MaterialButton = itemView.findViewById(R.id.btnOption1)
        private val btn2: com.google.android.material.button.MaterialButton = itemView.findViewById(R.id.btnOption2)
        private val btn3: com.google.android.material.button.MaterialButton = itemView.findViewById(R.id.btnOption3)

        fun bind(setting: SettingItem.SegmentedButtonSettingEntry) {
            settingName.setText(setting.nameResId)

            val buttons = listOf(btn1, btn2, btn3)
            val visibleButtons = buttons.filterIndexed { index, _ -> index < setting.options.size }

            buttons.forEachIndexed { index, button ->
                if (index < setting.options.size) {
                    button.text = setting.options[index]
                    button.visibility = View.VISIBLE
                } else {
                    button.visibility = View.GONE
                }
            }

            // Fixed 16dp radius like cards/save button
            val radius = 16f * itemView.resources.displayMetrics.density

            visibleButtons.forEachIndexed { index, button ->
                val shapeModel = when (index) {
                    0 -> com.google.android.material.shape.ShapeAppearanceModel.builder()
                        .setTopLeftCornerSize(radius).setBottomLeftCornerSize(radius)
                        .setTopRightCornerSize(0f).setBottomRightCornerSize(0f)
                        .build()
                    visibleButtons.size - 1 -> com.google.android.material.shape.ShapeAppearanceModel.builder()
                        .setTopRightCornerSize(radius).setBottomRightCornerSize(radius)
                        .setTopLeftCornerSize(0f).setBottomLeftCornerSize(0f)
                        .build()
                    else -> com.google.android.material.shape.ShapeAppearanceModel.builder()
                        .setAllCornerSizes(0f)
                        .build()
                }
                button.shapeAppearanceModel = shapeModel

                // Manual selection state handling
                val isSelected = index == setting.selectedIndex
                button.isChecked = isSelected

                // Bring active button to front so its 2dp border wins the overlap
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    button.elevation = if (isSelected) 2f else 0f
                    button.stateListAnimator = null // Disable default elevation animations
                }

                // Ensure stroke is 2dp and themed
                button.strokeWidth = (2 * itemView.resources.displayMetrics.density).toInt()

                button.setOnClickListener {
                    if (index != setting.selectedIndex) {
                        // Instant UI feedback: uncheck all, check this one
                        visibleButtons.forEach {
                            it.isChecked = false
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) it.elevation = 0f
                        }
                        button.isChecked = true
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) button.elevation = 2f

                        setting.onOptionSelected(index)
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
                    oldItem.nameResId == newItem.nameResId && oldItem.value == newItem.value
                oldItem is SettingItem.ToggleSettingEntry && newItem is SettingItem.ToggleSettingEntry ->
                    oldItem.nameResId == newItem.nameResId && oldItem.descriptionResId == newItem.descriptionResId && oldItem.isChecked == newItem.isChecked && oldItem.isEnabled == newItem.isEnabled && oldItem.nameOverride == newItem.nameOverride
                oldItem is SettingItem.SliderSettingEntry && newItem is SettingItem.SliderSettingEntry ->
                    oldItem.nameResId == newItem.nameResId && oldItem.value == newItem.value && oldItem.sliderValue == newItem.sliderValue
                oldItem is SettingItem.CategoryHeader && newItem is SettingItem.CategoryHeader ->
                    oldItem.titleResId == newItem.titleResId
                oldItem is SettingItem.InfoBanner && newItem is SettingItem.InfoBanner ->
                    oldItem.textResId == newItem.textResId && oldItem.text == newItem.text
                oldItem is SettingItem.ActionButton && newItem is SettingItem.ActionButton ->
                    oldItem.textResId == newItem.textResId && oldItem.isEnabled == newItem.isEnabled
                oldItem is SettingItem.SegmentedButtonSettingEntry && newItem is SettingItem.SegmentedButtonSettingEntry ->
                    oldItem.nameResId == newItem.nameResId && oldItem.options == newItem.options && oldItem.selectedIndex == newItem.selectedIndex
                else -> false
            }
        }
    }
}
