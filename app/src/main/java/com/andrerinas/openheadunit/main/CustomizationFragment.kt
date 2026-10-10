package com.andrerinas.openheadunit.main

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.app.BaseActivity
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ColorUtils
import com.andrerinas.openheadunit.utils.HomeUiHelper
import com.andrerinas.openheadunit.utils.PickImageContract
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.ToastUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CustomizationFragment : Fragment() {

    private lateinit var settings: Settings
    private var bgDefaultView: View? = null
    private var bgCustomImageView: ImageView? = null
    private var txtStatus: TextView? = null
    private var btnSelectImage: MaterialButton? = null
    private var btnResetBg: MaterialButton? = null

    // Background Selection & Night Behavior views
    private var toggleGroupBgType: MaterialButtonToggleGroup? = null
    private var btnBgClassic: MaterialButton? = null
    private var btnBgGradient: MaterialButton? = null
    private var btnBgCustom: MaterialButton? = null
    private var layoutCustomImageActions: View? = null
    private var isInternalBgTypeChange = false

    private var toggleGroupNightBehavior: MaterialButtonToggleGroup? = null
    private var btnNightDim: MaterialButton? = null
    private var btnNightPureBlack: MaterialButton? = null
    private var btnNightNone: MaterialButton? = null

    // Button color previews & rows
    private var previewBtnSelfMode: MaterialButton? = null
    private var previewBtnUsb: MaterialButton? = null
    private var previewBtnWifi: MaterialButton? = null
    private var previewBtnSettings: MaterialButton? = null

    private var indicatorSelfMode: View? = null
    private var indicatorUsb: View? = null
    private var indicatorWifi: View? = null
    private var indicatorSettings: View? = null

    private var rowColorSelfMode: View? = null
    private var rowColorUsb: View? = null
    private var rowColorWifi: View? = null
    private var rowColorSettings: View? = null
    private var switchAutoMonochromeNight: SwitchMaterial? = null
    private var btnMakeAllMonochrome: MaterialButton? = null
    private var btnResetColors: MaterialButton? = null

    // Button Scale views
    private var previewContainer: View? = null
    private var sliderButtonScale: com.google.android.material.slider.Slider? = null
    private var txtButtonScaleValue: TextView? = null
    private var btnResetScale: MaterialButton? = null

    // Display & Text Size (UI Scale)
    private var cardUiScale: View? = null
    private var txtUiScaleValue: TextView? = null

    // Samsung-style Fullscreen Live Preview Overlay
    private var customizationMainContent: View? = null
    private var overlayHomePreview: View? = null
    private var overlayHomeContent: android.widget.FrameLayout? = null
    private var sliderOverlayButtonScale: com.google.android.material.slider.Slider? = null
    private var txtOverlayScaleLabel: TextView? = null
    private var currentOverlayHomeView: View? = null

    private val hideOverlayRunnable = Runnable {
        overlayHomePreview?.animate()
            ?.alpha(0f)
            ?.setDuration(250)
            ?.withEndAction {
                overlayHomePreview?.visibility = View.GONE
            }
            ?.start()
        customizationMainContent?.animate()?.alpha(1f)?.setDuration(250)?.start()
    }

    private val imagePicker = registerForActivityResult(PickImageContract()) { uri ->
        uri?.let { handleImageSelected(it) }
    }

    private val sliderTouchListener = object : com.google.android.material.slider.Slider.OnSliderTouchListener {
        override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {
            showSamsungStyleOverlay()
        }

        override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
            hideSamsungStyleOverlay()
        }
    }

    private val sliderChangeListener = com.google.android.material.slider.Slider.OnChangeListener { _, value, fromUser ->
        val scaleVal = value.toInt()
        updatePreviewScale(scaleVal)
        if (fromUser) {
            settings.homeButtonScalePercent = scaleVal
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_customization, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = App.provide(requireContext()).settings

        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        customizationMainContent = view.findViewById(R.id.customization_main_content)

        // Background Image views
        bgDefaultView = view.findViewById(R.id.bg_default_view)
        bgCustomImageView = view.findViewById(R.id.bg_custom_image_view)
        txtStatus = view.findViewById(R.id.txt_status)
        btnSelectImage = view.findViewById(R.id.btn_select_image)
        btnResetBg = view.findViewById(R.id.btn_reset_bg)

        // Background type selection
        toggleGroupBgType = view.findViewById(R.id.toggle_group_bg_type)
        btnBgClassic = view.findViewById(R.id.btn_bg_classic)
        btnBgGradient = view.findViewById(R.id.btn_bg_gradient)
        btnBgCustom = view.findViewById(R.id.btn_bg_custom)
        layoutCustomImageActions = view.findViewById(R.id.layout_custom_image_actions)

        // Background night behavior
        toggleGroupNightBehavior = view.findViewById(R.id.toggle_group_night_behavior)
        btnNightDim = view.findViewById(R.id.btn_night_dim)
        btnNightPureBlack = view.findViewById(R.id.btn_night_pure_black)
        btnNightNone = view.findViewById(R.id.btn_night_none)

        // Button Color views
        previewBtnSelfMode = view.findViewById(R.id.preview_btn_self_mode)
        previewBtnUsb = view.findViewById(R.id.preview_btn_usb)
        previewBtnWifi = view.findViewById(R.id.preview_btn_wifi)
        previewBtnSettings = view.findViewById(R.id.preview_btn_settings)

        indicatorSelfMode = view.findViewById(R.id.indicator_self_mode)
        indicatorUsb = view.findViewById(R.id.indicator_usb)
        indicatorWifi = view.findViewById(R.id.indicator_wifi)
        indicatorSettings = view.findViewById(R.id.indicator_settings)

        rowColorSelfMode = view.findViewById(R.id.row_color_self_mode)
        rowColorUsb = view.findViewById(R.id.row_color_usb)
        rowColorWifi = view.findViewById(R.id.row_color_wifi)
        rowColorSettings = view.findViewById(R.id.row_color_settings)
        switchAutoMonochromeNight = view.findViewById(R.id.switch_auto_monochrome_night)
        btnMakeAllMonochrome = view.findViewById(R.id.btn_make_all_monochrome)
        btnResetColors = view.findViewById(R.id.btn_reset_colors)

        // Button Scale views
        previewContainer = view.findViewById(R.id.preview_container)
        sliderButtonScale = view.findViewById(R.id.slider_button_scale)
        txtButtonScaleValue = view.findViewById(R.id.txt_button_scale_value)
        btnResetScale = view.findViewById(R.id.btn_reset_scale)

        // Display & Text Size (UI Scale)
        cardUiScale = view.findViewById(R.id.card_ui_scale)
        txtUiScaleValue = view.findViewById(R.id.txt_ui_scale_value)
        updateUiScaleValue()
        cardUiScale?.setOnClickListener {
            showUiScaleDialog()
        }

        // Samsung-style Overlay views
        overlayHomePreview = view.findViewById(R.id.overlay_home_preview)
        overlayHomeContent = view.findViewById(R.id.overlay_home_content)
        sliderOverlayButtonScale = view.findViewById(R.id.slider_overlay_button_scale)
        txtOverlayScaleLabel = view.findViewById(R.id.txt_overlay_scale_label)

        // Background listeners
        btnSelectImage?.setOnClickListener {
            try {
                imagePicker.launch(Unit)
            } catch (e: Exception) {
                AppLog.e("Failed to launch image picker: ${e.message}")
                ToastUtils.showToast(requireContext(), R.string.loading_screen_file_error, Toast.LENGTH_SHORT, force = true)
            }
        }

        btnResetBg?.setOnClickListener {
            resetToDefault()
        }

        // Button Color listeners
        rowColorSelfMode?.setOnClickListener {
            showColorPickerDialog(
                R.string.btn_color_self_mode,
                settings.customSelfModeButtonColor,
                R.drawable.gradient_blue,
                R.drawable.ic_launch_white
            ) { color ->
                settings.customSelfModeButtonColor = color
            }
        }

        rowColorUsb?.setOnClickListener {
            showColorPickerDialog(
                R.string.btn_color_usb,
                settings.customUsbButtonColor,
                R.drawable.gradient_orange,
                R.drawable.ic_usb_white
            ) { color ->
                settings.customUsbButtonColor = color
            }
        }

        rowColorWifi?.setOnClickListener {
            showColorPickerDialog(
                R.string.btn_color_wifi,
                settings.customWifiButtonColor,
                R.drawable.gradient_purple,
                R.drawable.ic_network_wifi_white
            ) { color ->
                settings.customWifiButtonColor = color
            }
        }

        rowColorSettings?.setOnClickListener {
            showColorPickerDialog(
                R.string.btn_color_settings,
                settings.customSettingsButtonColor,
                R.drawable.gradient_darkblue,
                R.drawable.ic_settings_white
            ) { color ->
                settings.customSettingsButtonColor = color
            }
        }

        btnResetColors?.setOnClickListener {
            resetAllButtonColors()
        }

        toggleGroupBgType?.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isInternalBgTypeChange || !isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btn_bg_classic -> {
                    settings.useGradientBackground = false
                    settings.homeBackgroundImagePath = ""
                    notifyMainActivityBackgroundChanged()
                    refreshUI()
                }
                R.id.btn_bg_gradient -> {
                    settings.useGradientBackground = true
                    settings.homeBackgroundImagePath = ""
                    notifyMainActivityBackgroundChanged()
                    refreshUI()
                }
                R.id.btn_bg_custom -> {
                    val path = settings.homeBackgroundImagePath
                    if (path.isNotEmpty() && File(path).exists()) {
                        notifyMainActivityBackgroundChanged()
                        refreshUI()
                    } else {
                        try {
                            imagePicker.launch(Unit)
                        } catch (e: Exception) {
                            AppLog.e("Failed to launch image picker: ${e.message}")
                            ToastUtils.showToast(requireContext(), R.string.loading_screen_file_error, Toast.LENGTH_SHORT, force = true)
                        }
                    }
                }
            }
        }

        toggleGroupNightBehavior?.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btn_night_dim -> settings.homeBackgroundNightMode = Settings.BackgroundNightMode.DIM
                    R.id.btn_night_pure_black -> settings.homeBackgroundNightMode = Settings.BackgroundNightMode.PURE_BLACK
                    R.id.btn_night_none -> settings.homeBackgroundNightMode = Settings.BackgroundNightMode.NONE
                }
                notifyMainActivityBackgroundChanged()
                refreshUI()
            }
        }

        switchAutoMonochromeNight?.setOnCheckedChangeListener { _, isChecked ->
            settings.autoMonochromeButtonsAtNight = isChecked
            settings.monochromeIcons = isChecked
            refreshUI()
        }

        btnMakeAllMonochrome?.setOnClickListener {
            val grayColor = 0xFF555555.toInt()
            settings.customSelfModeButtonColor = grayColor
            settings.customUsbButtonColor = grayColor
            settings.customWifiButtonColor = grayColor
            settings.customSettingsButtonColor = grayColor
            refreshUI()
            ToastUtils.showToast(requireContext(), R.string.btn_make_all_monochrome, Toast.LENGTH_SHORT, force = true)
        }

        // Button Scale listeners
        val currentScale = settings.homeButtonScalePercent
        val currentFloat = currentScale.coerceIn(60, 120).toFloat()
        sliderButtonScale?.value = currentFloat
        sliderOverlayButtonScale?.value = currentFloat
        txtButtonScaleValue?.text = "$currentScale%"
        updatePreviewScale(currentScale)

        sliderButtonScale?.addOnChangeListener(sliderChangeListener)
        sliderOverlayButtonScale?.addOnChangeListener(sliderChangeListener)

        sliderButtonScale?.addOnSliderTouchListener(sliderTouchListener)
        sliderOverlayButtonScale?.addOnSliderTouchListener(sliderTouchListener)

        btnResetScale?.setOnClickListener {
            settings.homeButtonScalePercent = 100
            sliderButtonScale?.value = 100f
            sliderOverlayButtonScale?.value = 100f
            txtButtonScaleValue?.text = "100%"
            updatePreviewScale(100)
        }

        refreshUI()
    }

    private fun showSamsungStyleOverlay() {
        overlayHomePreview?.removeCallbacks(hideOverlayRunnable)
        customizationMainContent?.animate()?.alpha(0f)?.setDuration(200)?.start()
        overlayHomePreview?.apply {
            visibility = View.VISIBLE
            alpha = 0f
        }?.animate()?.alpha(1f)?.setDuration(200)?.start()
    }

    private fun hideSamsungStyleOverlay() {
        overlayHomePreview?.postDelayed(hideOverlayRunnable, 600)
    }

    private fun updatePreviewScale(scalePercent: Int) {
        val ctx = context ?: return
        val validScale = if (scalePercent in 60..120) scalePercent else 100
        val scaleFactor = validScale / 100.0f

        // Sync inline card preview
        previewContainer?.scaleX = scaleFactor
        previewContainer?.scaleY = scaleFactor

        // Sync text labels & sliders safely
        txtButtonScaleValue?.text = "$validScale%"
        txtOverlayScaleLabel?.text = "$validScale%"
        val validFloat = validScale.toFloat()
        if (sliderButtonScale?.value?.toInt() != validScale) {
            sliderButtonScale?.value = validFloat
        }
        if (sliderOverlayButtonScale?.value?.toInt() != validScale) {
            sliderOverlayButtonScale?.value = validFloat
        }

        val container = overlayHomeContent ?: return

        // Inflate real home screen layout into container if needed
        if (currentOverlayHomeView == null || container.childCount == 0) {
            container.removeAllViews()
            currentOverlayHomeView = layoutInflater.inflate(R.layout.fragment_home, container, false)
            container.addView(currentOverlayHomeView)
        }

        val homeView = currentOverlayHomeView ?: return

        // 1. Sync Background
        val bgDefault = homeView.findViewById<View>(R.id.bg_default_view)
        val bgCustom = homeView.findViewById<ImageView>(R.id.bg_custom_image_view)
        val path = settings.homeBackgroundImagePath
        val hasCustomImage = path.isNotEmpty() && File(path).exists()
        val isNightActive = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        if (hasCustomImage && bgCustom != null) {
            bgDefault?.visibility = View.GONE
            bgCustom.visibility = View.VISIBLE
            bgCustom.colorFilter = null
            Glide.with(this)
                .load(File(path))
                .signature(ObjectKey(File(path).lastModified()))
                .centerCrop()
                .into(bgCustom)
        } else {
            bgCustom?.visibility = View.GONE
            bgDefault?.visibility = View.VISIBLE
            if (settings.useGradientBackground) {
                bgDefault?.background = ContextCompat.getDrawable(ctx, R.drawable.bg_gradient)
            } else {
                bgDefault?.background = ContextCompat.getDrawable(ctx, R.drawable.bg)
            }
        }

        // 2. Sync Button Colors & Monochrome Theme
        HomeUiHelper.applyButtonStyles(ctx, homeView, settings, isNightActive)

        // 3. Sync Button Visibility
        HomeUiHelper.applyButtonVisibility(homeView, settings)

        // 4. Scale Home Buttons
        val density = resources.displayMetrics.density
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        HomeUiHelper.applyButtonScale(homeView, validScale, isPortrait, density)
    }

    private fun handleImageSelected(uri: Uri) {
        val ctx = context ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val destFile = File(ctx.filesDir, "custom_home_bg.png")
            var success = false

            try {
                val dm = ctx.resources.displayMetrics
                val maxDim = maxOf(dm.widthPixels, dm.heightPixels, 1920)

                // Use Glide to load and scale the Uri safely on background thread.
                // Glide handles content providers, permissions, EXIF orientation, and downsampling.
                val bitmap = Glide.with(ctx.applicationContext)
                    .asBitmap()
                    .load(uri)
                    .override(maxDim, maxDim)
                    .submit()
                    .get()

                if (bitmap != null) {
                    destFile.outputStream().use { outStream ->
                        if (bitmap.hasAlpha()) {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outStream)
                        } else {
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, outStream)
                        }
                    }
                    success = true
                    AppLog.i("Custom background saved: ${bitmap.width}x${bitmap.height}, ${destFile.length() / 1024} KB")
                }
            } catch (e: Exception) {
                AppLog.e("Failed to decode custom background image via Glide: ${e.message}")
                try {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        destFile.outputStream().use { output -> input.copyTo(output) }
                        success = true
                    }
                } catch (fallbackEx: Exception) {
                    AppLog.e("Fallback copy failed: ${fallbackEx.message}")
                    success = false
                }
            }

            withContext(Dispatchers.Main) {
                if (success && destFile.exists() && destFile.length() > 0) {
                    settings.homeBackgroundImagePath = destFile.absolutePath
                    refreshUI()
                    notifyMainActivityBackgroundChanged()
                } else {
                    ToastUtils.showToast(ctx, R.string.loading_screen_file_error, Toast.LENGTH_SHORT, force = true)
                }
            }
        }
    }

    private fun resetToDefault() {
        val path = settings.homeBackgroundImagePath
        if (path.isNotEmpty()) {
            val file = File(path)
            if (file.exists()) {
                try { file.delete() } catch (_: Exception) {}
            }
        }
        settings.homeBackgroundImagePath = ""
        refreshUI()
        notifyMainActivityBackgroundChanged()
    }

    private fun resetAllButtonColors() {
        settings.customSelfModeButtonColor = 0
        settings.customUsbButtonColor = 0
        settings.customWifiButtonColor = 0
        settings.customSettingsButtonColor = 0
        refreshUI()
    }

    private fun refreshUI() {
        val ctx = context ?: return
        val path = settings.homeBackgroundImagePath
        val hasCustomImage = path.isNotEmpty() && File(path).exists()

        // Update Background Type Toggle Group
        isInternalBgTypeChange = true
        if (hasCustomImage) {
            toggleGroupBgType?.check(R.id.btn_bg_custom)
            layoutCustomImageActions?.visibility = View.VISIBLE
        } else if (settings.useGradientBackground) {
            toggleGroupBgType?.check(R.id.btn_bg_gradient)
            layoutCustomImageActions?.visibility = View.GONE
        } else {
            toggleGroupBgType?.check(R.id.btn_bg_classic)
            layoutCustomImageActions?.visibility = View.GONE
        }
        isInternalBgTypeChange = false

        // Update Night Behavior Toggle
        when (settings.homeBackgroundNightMode) {
            Settings.BackgroundNightMode.DIM -> toggleGroupNightBehavior?.check(R.id.btn_night_dim)
            Settings.BackgroundNightMode.PURE_BLACK -> toggleGroupNightBehavior?.check(R.id.btn_night_pure_black)
            Settings.BackgroundNightMode.NONE -> toggleGroupNightBehavior?.check(R.id.btn_night_none)
        }

        // Update Top Card Preview (always shows clean background, independent of night mode)
        if (hasCustomImage) {
            bgDefaultView?.visibility = View.GONE
            bgCustomImageView?.let { iv ->
                iv.visibility = View.VISIBLE
                iv.colorFilter = null
                Glide.with(this)
                    .load(File(path))
                    .signature(ObjectKey(File(path).lastModified()))
                    .centerCrop()
                    .into(iv)
            }
            val sizeKb = File(path).length() / 1024
            txtStatus?.text = "${getString(R.string.home_background_custom)} (${sizeKb} KB)"
            btnResetBg?.isEnabled = true
        } else {
            bgCustomImageView?.let { iv ->
                Glide.with(this).clear(iv)
                iv.visibility = View.GONE
            }
            bgDefaultView?.visibility = View.VISIBLE
            btnResetBg?.isEnabled = false

            if (settings.useGradientBackground) {
                val drawable = ContextCompat.getDrawable(ctx, R.drawable.bg_gradient)?.mutate()
                drawable?.colorFilter = null
                bgDefaultView?.background = drawable
                txtStatus?.text = getString(R.string.bg_type_gradient)
            } else {
                val drawable = ContextCompat.getDrawable(ctx, R.drawable.bg)?.mutate()
                drawable?.colorFilter = null
                bgDefaultView?.background = drawable
                txtStatus?.text = getString(R.string.bg_type_standard)
            }
        }

        switchAutoMonochromeNight?.isChecked = settings.autoMonochromeButtonsAtNight

        val showsSelf = settings.showsSelf()
        val showsUsb = settings.showsUsb()
        val showsWifi = settings.showsWifi()

        // Update button color previews & indicators (previews stay visible but styled as disabled when unchecked)
        updateButtonPreview(
            previewBtnSelfMode,
            indicatorSelfMode,
            settings.customSelfModeButtonColor,
            R.drawable.gradient_blue,
            showsSelf,
            ctx
        )
        updateButtonPreview(
            previewBtnUsb,
            indicatorUsb,
            settings.customUsbButtonColor,
            R.drawable.gradient_orange,
            showsUsb,
            ctx
        )
        updateButtonPreview(
            previewBtnWifi,
            indicatorWifi,
            settings.customWifiButtonColor,
            R.drawable.gradient_purple,
            showsWifi,
            ctx
        )
        updateButtonPreview(
            previewBtnSettings,
            indicatorSettings,
            settings.customSettingsButtonColor,
            R.drawable.gradient_darkblue,
            true,
            ctx
        )

        // Hide color customization rows for unchecked connection modes
        rowColorSelfMode?.visibility = if (showsSelf) View.VISIBLE else View.GONE
        rowColorUsb?.visibility = if (showsUsb) View.VISIBLE else View.GONE
        rowColorWifi?.visibility = if (showsWifi) View.VISIBLE else View.GONE

        val hasCustomColors = settings.customSelfModeButtonColor != 0 ||
                settings.customUsbButtonColor != 0 ||
                settings.customWifiButtonColor != 0 ||
                settings.customSettingsButtonColor != 0
        btnResetColors?.isEnabled = hasCustomColors
    }

    private fun updateButtonPreview(
        previewButton: MaterialButton?,
        indicatorView: View?,
        customColor: Int,
        defaultDrawableRes: Int,
        isEnabled: Boolean,
        ctx: Context
    ) {
        if (previewButton != null) {
            previewButton.visibility = View.VISIBLE
            previewButton.isEnabled = isEnabled
            if (!isEnabled) {
                val disabledBackground = ContextCompat.getDrawable(ctx, R.drawable.gradient_monochrome)
                val disabledIconTint = ContextCompat.getColorStateList(ctx, R.color.disabled_icon_tint)
                previewButton.background = disabledBackground?.constantState?.newDrawable()?.mutate()
                previewButton.iconTint = disabledIconTint
            } else {
                val whiteTint = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                if (customColor != 0) {
                    val customDrawable = ColorUtils.createGradientDrawable(customColor, 32f, ctx)
                    previewButton.background = customDrawable
                } else {
                    val defaultDrawable = ContextCompat.getDrawable(ctx, defaultDrawableRes)
                    previewButton.background = defaultDrawable
                }
                previewButton.iconTint = whiteTint
            }
        }

        if (indicatorView != null) {
            if (customColor != 0) {
                val indicatorDrawable = ColorUtils.createGradientDrawable(customColor, 12f, ctx)
                indicatorView.background = indicatorDrawable
            } else {
                indicatorView.background = ContextCompat.getDrawable(ctx, defaultDrawableRes)
            }
        }
    }

    private fun showColorPickerDialog(
        titleRes: Int,
        currentColor: Int,
        defaultDrawableRes: Int,
        iconRes: Int,
        onSave: (Int) -> Unit
    ) {
        val ctx = context ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_color_picker, null)

        val txtTitle = dialogView.findViewById<TextView>(R.id.dialog_title)
        val previewButton = dialogView.findViewById<MaterialButton>(R.id.dialog_preview_button)
        val presetContainer = dialogView.findViewById<android.widget.LinearLayout>(R.id.preset_container)
        val hexEditText = dialogView.findViewById<TextInputEditText>(R.id.hex_input_edit_text)

        txtTitle.setText(titleRes)
        previewButton.setIconResource(iconRes)

        var selectedColor = currentColor

        fun updateDialogPreview(color: Int) {
            selectedColor = color
            if (color != 0) {
                previewButton.background = ColorUtils.createGradientDrawable(color, 36f, ctx)
            } else {
                previewButton.background = ContextCompat.getDrawable(ctx, defaultDrawableRes)
            }
        }

        updateDialogPreview(currentColor)

        var isInternalTextChange = false
        if (currentColor != 0) {
            isInternalTextChange = true
            val hexStr = String.format("#%06X", 0xFFFFFF and currentColor)
            hexEditText.setText(hexStr)
            isInternalTextChange = false
        } else {
            isInternalTextChange = true
            hexEditText.setText("")
            isInternalTextChange = false
        }

        val presets = listOf(
            Pair("Default", 0),
            Pair("Cyan", Color.parseColor("#2CC6F2")),
            Pair("Blue", Color.parseColor("#027FEE")),
            Pair("Orange", Color.parseColor("#F4A157")),
            Pair("Red", Color.parseColor("#E74C3C")),
            Pair("Green", Color.parseColor("#2ECC71")),
            Pair("Purple", Color.parseColor("#DE93FD")),
            Pair("Pink", Color.parseColor("#E91E63")),
            Pair("Yellow", Color.parseColor("#F1C40F")),
            Pair("Teal", Color.parseColor("#009688")),
            Pair("Slate", Color.parseColor("#576D82")),
            Pair("Dark", Color.parseColor("#2C3E50"))
        )

        presetContainer.removeAllViews()
        val density = ctx.resources.displayMetrics.density
        val heightPx = (36 * density).toInt()
        val marginPx = (3 * density).toInt()

        // Split 12 presets into 2 rows of 6 items with weight=1 so they never clip in portrait
        presets.chunked(6).forEach { rowPresets ->
            val rowLayout = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                weightSum = 6f
            }

            rowPresets.forEach { (_, colorInt) ->
                val swatch = View(ctx)
                val lp = android.widget.LinearLayout.LayoutParams(0, heightPx, 1f).apply {
                    setMargins(marginPx, marginPx, marginPx, marginPx)
                }
                swatch.layoutParams = lp

                if (colorInt == 0) {
                    swatch.background = ContextCompat.getDrawable(ctx, defaultDrawableRes)
                } else {
                    swatch.background = ColorUtils.createGradientDrawable(colorInt, 18f, ctx)
                }

                swatch.setOnClickListener {
                    updateDialogPreview(colorInt)
                    isInternalTextChange = true
                    if (colorInt != 0) {
                        hexEditText.setText(String.format("#%06X", 0xFFFFFF and colorInt))
                    } else {
                        hexEditText.setText("")
                    }
                    isInternalTextChange = false
                }
                rowLayout.addView(swatch)
            }
            presetContainer.addView(rowLayout)
        }

        val textWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                if (isInternalTextChange) return
                val input = text?.toString()?.trim() ?: ""
                if (input.isNotEmpty()) {
                    val parsed = ColorUtils.parseColorSafely(input, -1)
                    if (parsed != -1) {
                        updateDialogPreview(parsed)
                    }
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        }
        hexEditText.addTextChangedListener(textWatcher)

        MaterialAlertDialogBuilder(ctx)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onSave(selectedColor)
                refreshUI()
            }
            .setNeutralButton(R.string.color_preset_default) { _, _ ->
                onSave(0)
                refreshUI()
            }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener {
                hexEditText.removeTextChangedListener(textWatcher)
                val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(hexEditText.windowToken, 0)
            }
            .show()
    }

    private fun notifyMainActivityBackgroundChanged() {
        (activity as? BaseActivity)?.applyWindowBackground()
    }

    override fun onResume() {
        super.onResume()
        updateUiScaleValue()
    }

    private fun updateUiScaleValue() {
        txtUiScaleValue?.text = "${getString(R.string.ui_scale_home)}: ${settings.uiScaleHomePercent}% · ${getString(R.string.ui_scale_settings)}: ${settings.uiScaleSettingsPercent}%"
    }

    private fun showUiScaleDialog() {
        val ctx = context ?: return
        val density = resources.displayMetrics.density

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        fun makeRow(labelText: String, initialPercent: Int): Triple<TextView, SeekBar, TextView> {
            val label = TextView(ctx).apply {
                text = labelText
                setPadding(0, (8 * density).toInt(), 0, (4 * density).toInt())
            }
            val seek = SeekBar(ctx).apply {
                max = 5
                progress = ((initialPercent - 100) / 10).coerceIn(0, 5)
            }
            val value = TextView(ctx).apply {
                text = "$initialPercent%"
                setPadding(0, (4 * density).toInt(), 0, (12 * density).toInt())
            }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val pct = 100 + progress * 10
                    value.text = "$pct%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            return Triple(label, seek, value)
        }

        val homeInitial = settings.uiScaleHomePercent
        val settingsInitial = settings.uiScaleSettingsPercent

        val (homeLabel, homeSeek, homeValue) = makeRow(getString(R.string.ui_scale_home), homeInitial)
        val (settingsLabel, settingsSeek, settingsValue) = makeRow(getString(R.string.ui_scale_settings), settingsInitial)

        container.addView(homeLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        container.addView(homeSeek, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        container.addView(homeValue, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        container.addView(settingsLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        container.addView(settingsSeek, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        container.addView(settingsValue, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            addView(container, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }

        MaterialAlertDialogBuilder(ctx, R.style.DarkAlertDialog)
            .setTitle(R.string.ui_scale)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                val newHome = 100 + (homeSeek.progress * 10)
                val newSettings = 100 + (settingsSeek.progress * 10)
                val oldSettings = settings.uiScaleSettingsPercent
                val oldHome = settings.uiScaleHomePercent

                settings.uiScaleHomePercent = newHome
                settings.uiScaleSettingsPercent = newSettings
                settings.commit()

                updateUiScaleValue()
                dialog.dismiss()

                if (newSettings != oldSettings) {
                    requireActivity().recreate()
                }
                if (newHome != oldHome) {
                    val intent = Intent(MainActivity.ACTION_RECREATE_MAIN).apply {
                        setPackage(requireContext().packageName)
                    }
                    requireContext().sendBroadcast(intent)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        bgCustomImageView?.let {
            try { Glide.with(this).clear(it) } catch (_: Exception) {}
        }
    }
}
