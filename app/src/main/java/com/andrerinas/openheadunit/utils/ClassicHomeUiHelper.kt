package com.andrerinas.openheadunit.utils

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.R
import com.google.android.material.button.MaterialButton

/**
 * Paints the classic home screen, the 2x2 grid of equal buttons, for users who keep it after the
 * redesign. Kept as it was so switching back looks exactly like before. See [HomeUiHelper] for
 * the new design.
 */
object ClassicHomeUiHelper {

    private data class ButtonConfig(
        val button: MaterialButton?,
        val defaultDrawableRes: Int,
        val customColor: Int,
        val isEnabled: Boolean
    )

    fun isEnabled(chosen: Boolean, isConnected: Boolean): Boolean = chosen || isConnected

    fun applyButtonVisibility(
        rootView: View,
        settings: Settings,
        isConnected: Boolean = false
    ) {
        val showsSelf = isEnabled(settings.showsSelf(), isConnected)
        val showsUsb = settings.showsUsb()
        val showsWifi = settings.showsWifi()

        val selfBtn = rootView.findViewById<View>(R.id.self_mode_button)
        val selfTxt = rootView.findViewById<View>(R.id.self_mode_text)
        val usbBtn = rootView.findViewById<View>(R.id.usb_button)
        val usbTxt = rootView.findViewById<View>(R.id.usb_text)
        val wifiBtn = rootView.findViewById<View>(R.id.wifi_button)
        val wifiTxt = rootView.findViewById<View>(R.id.wifi_text)

        selfBtn?.visibility = View.VISIBLE
        selfBtn?.isEnabled = showsSelf
        selfBtn?.isClickable = showsSelf
        selfTxt?.visibility = View.VISIBLE
        selfTxt?.alpha = if (showsSelf) 1.0f else 0.4f

        usbBtn?.visibility = View.VISIBLE
        usbBtn?.isEnabled = showsUsb
        usbBtn?.isClickable = showsUsb
        usbTxt?.visibility = View.VISIBLE
        usbTxt?.alpha = if (showsUsb) 1.0f else 0.4f

        wifiBtn?.visibility = View.VISIBLE
        wifiBtn?.isEnabled = showsWifi
        wifiBtn?.isClickable = showsWifi
        wifiTxt?.visibility = View.VISIBLE
        wifiTxt?.alpha = if (showsWifi) 1.0f else 0.4f
    }

    fun applyButtonScale(
        rootView: View,
        scalePercent: Int,
        isPortrait: Boolean,
        density: Float
    ) {
        val validScale = if (scalePercent in 60..120) scalePercent else 100
        val scaleFactor = validScale / 100.0f

        val selfBtn = rootView.findViewById<View>(R.id.self_mode_button)
        val usbBtn = rootView.findViewById<View>(R.id.usb_button)
        val wifiBtn = rootView.findViewById<View>(R.id.wifi_button)
        val settingsBtn = rootView.findViewById<View>(R.id.settings_button)
        val buttons = listOfNotNull(selfBtn, usbBtn, wifiBtn, settingsBtn)

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

        val mainButtonsLayout = rootView.findViewById<View>(R.id.main_buttons_layout)
        if (mainButtonsLayout != null) {
            mainButtonsLayout.scaleX = scaleFactor
            mainButtonsLayout.scaleY = scaleFactor
        }
    }

    fun applyButtonStyles(
        context: Context,
        rootView: View,
        settings: Settings,
        isNightActive: Boolean,
        isConnected: Boolean = false
    ) {
        val selfBtn = rootView.findViewById<MaterialButton>(R.id.self_mode_button)
        val usbBtn = rootView.findViewById<MaterialButton>(R.id.usb_button)
        val wifiBtn = rootView.findViewById<MaterialButton>(R.id.wifi_button)
        val settingsBtn = rootView.findViewById<MaterialButton>(R.id.settings_button)

        val isDarkTheme = settings.appTheme == Settings.AppTheme.DARK ||
                settings.appTheme == Settings.AppTheme.EXTREME_DARK ||
                isNightActive

        val showsSelf = isEnabled(settings.showsSelf(), isConnected)
        val showsUsb = settings.showsUsb()
        val showsWifi = settings.showsWifi()

        val disabledBackground = ContextCompat.getDrawable(context, R.drawable.gradient_monochrome)
        val disabledIconTint = ContextCompat.getColorStateList(context, R.color.disabled_icon_tint)

        val isMonochrome = isDarkTheme && settings.autoMonochromeButtonsAtNight
        val monochromeBackground = ContextCompat.getDrawable(context, R.drawable.gradient_monochrome)
        val monochromeIconTint = ContextCompat.getColorStateList(context, R.color.monochrome_icon_tint)
        val whiteTint = ColorStateList.valueOf(0xFFFFFFFF.toInt())

        val buttonConfigs = listOf(
            ButtonConfig(selfBtn, R.drawable.gradient_blue, settings.customSelfModeButtonColor, showsSelf),
            ButtonConfig(usbBtn, R.drawable.gradient_orange, settings.customUsbButtonColor, showsUsb),
            ButtonConfig(wifiBtn, R.drawable.gradient_purple, settings.customWifiButtonColor, showsWifi),
            ButtonConfig(settingsBtn, R.drawable.gradient_darkblue, settings.customSettingsButtonColor, true)
        )

        buttonConfigs.forEach { (button, defaultDrawableRes, customColor, isEnabled) ->
            if (button != null) {
                if (!isEnabled) {
                    button.background = disabledBackground?.constantState?.newDrawable()?.mutate()
                    button.iconTint = disabledIconTint
                } else if (isMonochrome) {
                    button.background = monochromeBackground?.constantState?.newDrawable()?.mutate()
                    button.iconTint = monochromeIconTint
                } else {
                    if (customColor != 0) {
                        button.background = ColorUtils.createGradientDrawable(customColor, 32f, context)
                    } else {
                        button.background = ContextCompat.getDrawable(context, defaultDrawableRes)
                    }
                    button.iconTint = whiteTint
                }
            }
        }
    }

    fun applyConnectionState(context: Context, rootView: View, isConnected: Boolean, wifiScanning: Boolean) {
        rootView.findViewById<TextView>(R.id.self_mode_text)?.text =
            context.getString(if (isConnected) R.string.to_android_auto else R.string.self_mode)
        rootView.findViewById<TextView>(R.id.wifi_text)?.text =
            context.getString(if (wifiScanning) R.string.searching else R.string.wifi)
        rootView.findViewById<View>(R.id.wifi_button)?.alpha = if (wifiScanning) 0.6f else 1.0f
    }

    fun applyTextColors(rootView: View, settings: Settings) {
        val nightModeFlags = rootView.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isLightMode = nightModeFlags != Configuration.UI_MODE_NIGHT_YES

        val labelViews = listOf(R.id.self_mode_text, R.id.wifi_text, R.id.usb_text, R.id.settings_text)
            .mapNotNull { rootView.findViewById<TextView>(it) }

        if (settings.useGradientBackground && isLightMode) {
            val darkColor = Color.parseColor("#1a1a1a")
            labelViews.forEach { tv ->
                tv.setTextColor(darkColor)
                tv.setShadowLayer(2f, 0f, 0f, Color.WHITE)
            }
        } else {
            val lightColor = Color.parseColor("#f7f7f7")
            labelViews.forEach { tv ->
                tv.setTextColor(lightColor)
                tv.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            }
        }

        rootView.findViewById<TextView>(R.id.exit_button)?.setTextColor(Color.WHITE)
        rootView.findViewById<TextView>(R.id.apps_button)?.setTextColor(Color.WHITE)
    }

    /**
     * Portrait-only: after the first layout pass we know the exact pixel
     * dimensions of the screen and the reserved areas (WiFi-pill spacer at
     * the top, Exit button at the bottom).  We calculate the largest square
     * button that fits in a 2-row grid and constrain the grid's max-width
     * accordingly.  This prevents overflow on square / wide-portrait tablets
     * while allowing full-width buttons on tall phones.
     *
     * Formula:
     *   availableH  = containerH − topSpacerH − exitButtonH
     *   maxBtnSize  = min(containerW / 2, availableH / 2) − cell-padding
     *   maxGridW    = maxBtnSize * 2 + cell-padding * 4   (2 cols, padding each side)
     *
     * @return the listener, for the caller to remove when its view goes away.
     */
    fun constrainPortraitGridWidth(rootView: View): ViewTreeObserver.OnGlobalLayoutListener? {
        val gridLayout = rootView.findViewById<View>(R.id.main_buttons_layout) as? LinearLayout
            ?: return null
        val density = rootView.resources.displayMetrics.density

        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val container = gridLayout.parent as? View ?: return
                val containerW = container.width
                val containerH = container.height
                if (containerW == 0 || containerH == 0) return

                // Reserved vertical space: 64 dp top-spacer + ~56 dp exit button (margin incl.)
                val reservedPx = (64 + 56) * density
                // Extra per-row overhead: label (~20 sp≈20dp) + marginTop (6 dp) + cell padding top+bottom (24 dp)
                val rowOverheadPx = 50 * density

                val availableH = containerH - reservedPx
                // Max button edge that fits in one row without labels overflowing
                val maxBtnFromH = ((availableH / 2f) - rowOverheadPx).toInt()
                val maxBtnFromW = containerW / 2
                val maxBtn = minOf(maxBtnFromH, maxBtnFromW)

                if (maxBtn <= 0) return

                // Grid max-width = 2 buttons + 4 × cell-horizontal-padding (12 dp each side)
                val cellPadPx = (12 * 2 * 2 * density).toInt() // 2 cols × 2 sides × 12 dp
                val maxGridW = maxBtn * 2 + cellPadPx

                val defaultMaxW = (1200 * density).toInt()
                val targetMaxWidth = if (maxGridW < containerW) maxGridW else defaultMaxW
                val params = gridLayout.layoutParams as? ConstraintLayout.LayoutParams ?: return
                if (params.matchConstraintMaxWidth != targetMaxWidth) {
                    params.matchConstraintMaxWidth = targetMaxWidth
                    gridLayout.layoutParams = params
                }
            }
        }
        gridLayout.viewTreeObserver.addOnGlobalLayoutListener(listener)
        return listener
    }
}
