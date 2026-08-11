package com.andrerinas.openheadunit.main

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
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
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ColorUtils
import com.andrerinas.openheadunit.utils.PickImageContract
import com.andrerinas.openheadunit.utils.Settings
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
    private var btnResetColors: MaterialButton? = null

    // Button Scale views
    private var previewContainer: View? = null
    private var sliderButtonScale: com.google.android.material.slider.Slider? = null
    private var txtButtonScaleValue: TextView? = null
    private var btnResetScale: MaterialButton? = null

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
        btnResetColors = view.findViewById(R.id.btn_reset_colors)

        // Button Scale views
        previewContainer = view.findViewById(R.id.preview_container)
        sliderButtonScale = view.findViewById(R.id.slider_button_scale)
        txtButtonScaleValue = view.findViewById(R.id.txt_button_scale_value)
        btnResetScale = view.findViewById(R.id.btn_reset_scale)

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
                Toast.makeText(requireContext(), R.string.loading_screen_file_error, Toast.LENGTH_SHORT).show()
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
        if (hasCustomImage && bgCustom != null) {
            bgDefault?.visibility = View.GONE
            bgCustom.visibility = View.VISIBLE
            Glide.with(this).load(File(path)).centerCrop().into(bgCustom)
        } else {
            bgCustom?.visibility = View.GONE
            bgDefault?.visibility = View.VISIBLE
        }

        // 2. Sync Button Colors & Monochrome Theme
        val selfBtn = homeView.findViewById<MaterialButton>(R.id.self_mode_button)
        val usbBtn = homeView.findViewById<MaterialButton>(R.id.usb_button)
        val wifiBtn = homeView.findViewById<MaterialButton>(R.id.wifi_button)
        val settingsBtn = homeView.findViewById<MaterialButton>(R.id.settings_button)

        val isNightActive = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val isDarkTheme = settings.appTheme == Settings.AppTheme.DARK ||
                          settings.appTheme == Settings.AppTheme.EXTREME_DARK ||
                          isNightActive

        if (isDarkTheme && settings.monochromeIcons) {
            val monochromeBackground = ContextCompat.getDrawable(ctx, R.drawable.gradient_monochrome)
            val grayTint = ColorStateList.valueOf(0xFF808080.toInt())
            listOfNotNull(selfBtn, usbBtn, wifiBtn, settingsBtn).forEach { button ->
                button.background = monochromeBackground?.constantState?.newDrawable()?.mutate()
                button.iconTint = grayTint
            }
        } else {
            val whiteTint = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            val configs = listOf(
                Triple(selfBtn, R.drawable.gradient_blue, settings.customSelfModeButtonColor),
                Triple(usbBtn, R.drawable.gradient_orange, settings.customUsbButtonColor),
                Triple(wifiBtn, R.drawable.gradient_purple, settings.customWifiButtonColor),
                Triple(settingsBtn, R.drawable.gradient_darkblue, settings.customSettingsButtonColor)
            )
            configs.forEach { (button, defaultDrawableRes, customColor) ->
                if (button != null) {
                    if (customColor != 0) {
                        button.background = ColorUtils.createGradientDrawable(customColor, 32f, ctx)
                    } else {
                        button.background = ContextCompat.getDrawable(ctx, defaultDrawableRes)
                    }
                    button.iconTint = whiteTint
                }
            }
        }

        // 3. SCALE THE HOME BUTTONS LAYOUT EXACTLY LIKE HOMEFRAGMENT DOES WITH MARGIN MAGIC!
        val density = resources.displayMetrics.density
        val buttons = listOfNotNull(selfBtn, usbBtn, wifiBtn, settingsBtn)
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

        if (isPortrait) {
            val basePaddingDp = 12f
            val adjustedPaddingPx = ((basePaddingDp * (2.0f - scaleFactor)).coerceIn(4f, 16f) * density).toInt()
            buttons.forEach { button ->
                (button.parent as? View)?.setPadding(adjustedPaddingPx, adjustedPaddingPx, adjustedPaddingPx, adjustedPaddingPx)
            }
        } else {
            val baseMarginDp = 40f
            val adjustedMarginPx = ((baseMarginDp * (2.0f - scaleFactor)).coerceIn(12f, 48f) * density).toInt()
            buttons.forEach { button ->
                val params = button.layoutParams as? ViewGroup.MarginLayoutParams
                if (params != null) {
                    params.setMargins(adjustedMarginPx, adjustedMarginPx, adjustedMarginPx, adjustedMarginPx)
                    button.layoutParams = params
                }
            }
        }

        val mainButtonsLayout = homeView.findViewById<View>(R.id.main_buttons_layout)
        if (mainButtonsLayout != null) {
            mainButtonsLayout.scaleX = scaleFactor
            mainButtonsLayout.scaleY = scaleFactor
        }
    }

    private fun handleImageSelected(uri: Uri) {
        val ctx = context ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val destFile = File(ctx.filesDir, "custom_home_bg.png")
            var success = false

            try {
                if (uri.scheme == "file") {
                    val srcFile = uri.path?.let { File(it) }
                    if (srcFile != null && srcFile.exists()) {
                        srcFile.inputStream().use { input ->
                            destFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        success = true
                    }
                } else {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        destFile.outputStream().use { output -> input.copyTo(output) }
                        success = true
                    }
                }
            } catch (e: Exception) {
                AppLog.e("Failed to copy custom background image: ${e.message}")
                success = false
            }

            withContext(Dispatchers.Main) {
                if (success && destFile.exists() && destFile.length() > 0) {
                    settings.homeBackgroundImagePath = destFile.absolutePath
                    refreshUI()
                    notifyMainActivityBackgroundChanged()
                } else {
                    Toast.makeText(ctx, R.string.loading_screen_file_error, Toast.LENGTH_SHORT).show()
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

        if (hasCustomImage) {
            bgDefaultView?.visibility = View.GONE
            bgCustomImageView?.let { iv ->
                iv.visibility = View.VISIBLE
                Glide.with(this)
                    .load(File(path))
                    .centerCrop()
                    .into(iv)
            }
            txtStatus?.text = getString(R.string.home_background_custom)
            btnResetBg?.isEnabled = true
        } else {
            bgCustomImageView?.let { iv ->
                Glide.with(this).clear(iv)
                iv.visibility = View.GONE
            }
            bgDefaultView?.visibility = View.VISIBLE
            txtStatus?.text = getString(R.string.home_background_default)
            btnResetBg?.isEnabled = false
        }

        // Update button color previews & indicators
        updateButtonPreview(
            previewBtnSelfMode,
            indicatorSelfMode,
            settings.customSelfModeButtonColor,
            R.drawable.gradient_blue,
            ctx
        )
        updateButtonPreview(
            previewBtnUsb,
            indicatorUsb,
            settings.customUsbButtonColor,
            R.drawable.gradient_orange,
            ctx
        )
        updateButtonPreview(
            previewBtnWifi,
            indicatorWifi,
            settings.customWifiButtonColor,
            R.drawable.gradient_purple,
            ctx
        )
        updateButtonPreview(
            previewBtnSettings,
            indicatorSettings,
            settings.customSettingsButtonColor,
            R.drawable.gradient_darkblue,
            ctx
        )

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
        ctx: Context
    ) {
        if (customColor != 0) {
            val customDrawable = ColorUtils.createGradientDrawable(customColor, 32f, ctx)
            val indicatorDrawable = ColorUtils.createGradientDrawable(customColor, 12f, ctx)
            previewButton?.background = customDrawable
            indicatorView?.background = indicatorDrawable
        } else {
            val defaultDrawable = ContextCompat.getDrawable(ctx, defaultDrawableRes)
            previewButton?.background = defaultDrawable
            indicatorView?.background = ContextCompat.getDrawable(ctx, defaultDrawableRes)
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

        val textWatcher = hexEditText.doOnTextChanged { text, _, _, _ ->
            if (isInternalTextChange) return@doOnTextChanged
            val input = text?.toString()?.trim() ?: ""
            if (input.isNotEmpty()) {
                val parsed = ColorUtils.parseColorSafely(input, -1)
                if (parsed != -1) {
                    updateDialogPreview(parsed)
                }
            }
        }

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
            }
            .show()
    }

    private fun notifyMainActivityBackgroundChanged() {
        (activity as? MainActivity)?.applyCustomHomeBackground()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        bgCustomImageView?.let {
            try { Glide.with(this).clear(it) } catch (_: Exception) {}
        }
    }
}
