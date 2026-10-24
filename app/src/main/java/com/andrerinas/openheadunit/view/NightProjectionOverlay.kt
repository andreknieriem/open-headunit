package com.andrerinas.openheadunit.view

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.NightOverlayState
import java.util.Date
import androidx.core.graphics.toColorInt

/** Covers the picture without hiding or detaching the decoder's surface. */
class NightProjectionOverlay(context: Context) : FrameLayout(context) {
    private val debugNavLayoutColors = false

    // Per-text scale knobs. Keep them code-driven for now; later they can be loaded from Settings.
    private var scaleClock = 1.2f
    private var scaleDistance = 1.3f
    private var scaleManeuverTextFallback = 1.0f
    private var scaleRoad = 0.7f
    private var scaleExtraInfo = 1.0f
    private var scaleEtaSummary = 1.0f
    private var scaleCurrentRoad = 1.0f
    private var scaleRouteNumber = 1.0f
    private var scaleLaneGuidance = 1.0f
    private var scaleTrafficDelay = 1.0f
    private var scaleSpeedLimitSign = 1.0f
    private var scaleSpeedValue = 1.0f
    private var scaleTrackTitle = 1.0f

    private val handler = Handler(Looper.getMainLooper())
    private val clock = label()
    private val maneuverIcon = AppCompatImageView(context).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        scaleType = ImageView.ScaleType.FIT_CENTER
        setColorFilter(Color.GRAY)
        visibility = GONE
    }
    private val maneuverTextFallback = label().apply {
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        visibility = GONE
    }
    private val distance = label().apply {
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
    }
    private val road = label().apply {
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
    }
    private val extraInfo = label()
    private val etaSummary = label()
    private val currentRoad = label()
    private val routeNumber = label()
    private val laneGuidance = label()
    private val trafficDelay = label().apply {
        setTextColor("#FFB000".toColorInt())
    }
    private val title = label()
    private val topRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
    }
    private val topInfoColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
    }
    private val maneuverBlock = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.START
        addView(topRow)
        addView(extraInfo)
        addView(etaSummary)
        addView(currentRoad)
        addView(routeNumber)
        addView(laneGuidance)
        addView(trafficDelay)
    }
    private val speedLimitSign = label().apply {
        gravity = Gravity.CENTER
        setTextColor(Color.BLACK)
        setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke((2f * resources.displayMetrics.density).toInt(), Color.RED)
        }
        visibility = GONE
    }
    private val speedValue = label().apply {
        setTypeface(typeface, Typeface.BOLD)
        visibility = GONE
    }
    private val speedBlock = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        addView(speedLimitSign)
        addView(speedValue)
        visibility = GONE
    }
    private val navAndSpeedRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        addView(maneuverBlock)
        addView(speedBlock)
        visibility = GONE
    }
    private var configuredWidth = 0
    private var configuredHeight = 0
    private val details = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
    }
    private val clockTick = object : Runnable {
        override fun run() {
            handler.removeCallbacks(this)
            if (!isShown) return
            clock.text = DateFormat.getTimeFormat(context).format(Date())
            handler.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L)
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.night_overlay_dismiss)
        visibility = GONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // Above the performance HUD as well as all projection backends.
            elevation = 201f
        }
        details.addView(clock)
        topRow.addView(maneuverIcon)
        topRow.addView(maneuverTextFallback)
        topInfoColumn.addView(distance)
        topInfoColumn.addView(road)
        topRow.addView(topInfoColumn)
        details.addView(navAndSpeedRow)
        details.addView(title)
        applyDebugNavColors()
        addView(details, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setOnClickListener { hide() }
    }

    private fun applyDebugNavColors() {
        if (!debugNavLayoutColors) return
        navAndSpeedRow.setBackgroundColor(Color.parseColor("#33FF1744"))
        maneuverBlock.setBackgroundColor(Color.parseColor("#332196F3"))
        topRow.setBackgroundColor(Color.parseColor("#334CAF50"))
        topInfoColumn.setBackgroundColor(Color.parseColor("#33FFC107"))
        speedBlock.setBackgroundColor(Color.parseColor("#339C27B0"))

        distance.setBackgroundColor(Color.parseColor("#3340C4FF"))
        road.setBackgroundColor(Color.parseColor("#334DFF88"))
        extraInfo.setBackgroundColor(Color.parseColor("#33FFD54F"))
        etaSummary.setBackgroundColor(Color.parseColor("#33B39DDB"))
        currentRoad.setBackgroundColor(Color.parseColor("#33FF8A65"))
        routeNumber.setBackgroundColor(Color.parseColor("#33A5D6A7"))
        laneGuidance.setBackgroundColor(Color.parseColor("#33CE93D8"))
        trafficDelay.setBackgroundColor(Color.parseColor("#33FFB74D"))
    }

    private fun label() = AppCompatTextView(context).apply {
        setTextColor(Color.GRAY)
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun show() {
        visibility = VISIBLE
        bringToFront()
        clockTick.run()
    }

    fun hide() {
        visibility = GONE
        handler.removeCallbacks(clockTick)
    }

    fun render(content: NightOverlayState.Content) {
        val navigation = content.navigation
        val iconRes = navigation?.icon?.let(::iconFor)
        maneuverIcon.isVisible = iconRes != null
        if (iconRes != null) {
            maneuverIcon.setImageResource(iconRes)
        }
        maneuverTextFallback.text = navigation?.maneuver.orEmpty()
        maneuverTextFallback.isVisible = iconRes == null && maneuverTextFallback.text.isNotBlank()
        distance.text = navigation?.distanceMeters?.let(::formatDistance).orEmpty()
        distance.isVisible = distance.text.isNotBlank()
        topRow.isVisible = maneuverIcon.isVisible || maneuverTextFallback.isVisible || distance.isVisible || road.isVisible

        road.text = navigation?.road.orEmpty()
        road.isVisible = road.text.isNotBlank()
        extraInfo.text = navigation?.details.orEmpty()
        extraInfo.isVisible = extraInfo.text.isNotBlank()
        etaSummary.text = navigation?.etaSummary.orEmpty()
        etaSummary.isVisible = etaSummary.text.isNotBlank()
        currentRoad.text = navigation?.currentRoad?.let {
            context.getString(R.string.night_overlay_current_road, it)
        }.orEmpty()
        currentRoad.isVisible = currentRoad.text.isNotBlank()
        routeNumber.text = navigation?.routeNumber?.let {
            context.getString(R.string.night_overlay_route, it)
        }.orEmpty()
        routeNumber.isVisible = routeNumber.text.isNotBlank()
        laneGuidance.text = navigation?.laneGuidance.orEmpty()
        laneGuidance.isVisible = laneGuidance.text.isNotBlank()
        trafficDelay.text = navigation?.trafficDelaySeconds
            ?.takeIf { it > 0 }
            ?.let { delay ->
                context.getString(
                    R.string.night_overlay_traffic_delay,
                    formatDurationCompact(delay.toLong()),
                )
            }.orEmpty()
        trafficDelay.isVisible = trafficDelay.text.isNotBlank()

        speedLimitSign.text = navigation?.speedLimitKmh?.toString().orEmpty()
        speedLimitSign.isVisible = speedLimitSign.text.isNotBlank()
        speedValue.text = ""
        speedValue.isVisible = false
        speedBlock.isVisible = speedLimitSign.isVisible

        maneuverBlock.isVisible = topRow.isVisible || road.isVisible || extraInfo.isVisible ||
            etaSummary.isVisible || currentRoad.isVisible || routeNumber.isVisible ||
            laneGuidance.isVisible || trafficDelay.isVisible
        navAndSpeedRow.isVisible = maneuverBlock.isVisible || speedBlock.isVisible

        val showTrack = content.isPlaying != false
        title.text = listOf(content.title, content.artist).filter { it.isNotBlank() }.joinToString(" - ")
        title.isVisible = showTrack && title.text.isNotBlank()
    }

    fun updateTextSizes() {
        configuredWidth = 0
        configuredHeight = 0
        requestLayout()
    }

    private fun configureTextSizes(width: Int, height: Int) {
        val density = resources.displayMetrics.density
        val shorterSide = minOf(width, height)
        val shorterDp = shorterSide / density
        val clockHeight = (shorterSide * 0.25f * scaleClock).toInt()
        val rowHeight = (shorterSide * 0.06f).toInt()
        val iconSize = (shorterSide * 0.2f).toInt()
            .coerceIn((48f * density).toInt(), (148f * density).toInt())
        val speedSignSize = (shorterSide * 0.08f).toInt()
            .coerceIn((34f * density).toInt(), (92f * density).toInt())
        val padding = (shorterDp * 0.04f * density).toInt()
        val tinyGap = (8f * density).toInt()
        val topInfoWidth = (
            width - (padding * 2) - iconSize - speedSignSize - (tinyGap * 6)
            ).coerceAtLeast((120f * density).toInt())
        clock.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, clockHeight)
        details.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER,
        ).apply {
            leftMargin = padding
            rightMargin = padding
        }
        navAndSpeedRow.layoutParams = LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
        )
        maneuverBlock.layoutParams = LinearLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
        speedBlock.layoutParams = LinearLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                marginStart = tinyGap * 2
            }
        }
        topRow.layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        maneuverIcon.layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                marginEnd = tinyGap
            }
        }
        maneuverTextFallback.layoutParams = LinearLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT,
            iconSize,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                marginEnd = tinyGap
            }
        }
        topInfoColumn.layoutParams = LinearLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
        )
        distance.maxWidth = topInfoWidth
        road.maxWidth = topInfoWidth
        distance.layoutParams = LinearLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = tinyGap / 2
        }
        road.layoutParams = LinearLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
        )
        extraInfo.layoutParams = LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            (rowHeight * scaleExtraInfo).toInt().coerceAtLeast(rowHeight / 2),
        )
        etaSummary.layoutParams = LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            (rowHeight * scaleEtaSummary).toInt().coerceAtLeast(rowHeight / 2),
        )
        currentRoad.layoutParams = LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            (rowHeight * scaleCurrentRoad).toInt().coerceAtLeast(rowHeight / 2),
        )
        routeNumber.layoutParams = LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            (rowHeight * scaleRouteNumber).toInt().coerceAtLeast(rowHeight / 2),
        )
        laneGuidance.layoutParams = LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            (rowHeight * scaleLaneGuidance).toInt().coerceAtLeast(rowHeight / 2),
        )
        trafficDelay.layoutParams = LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            (rowHeight * scaleTrafficDelay).toInt().coerceAtLeast(rowHeight / 2),
        )
        title.layoutParams = LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            (rowHeight * scaleTrackTitle).toInt().coerceAtLeast(rowHeight / 2),
        ).apply {
            topMargin = tinyGap * 3
        }
        speedLimitSign.layoutParams = LinearLayout.LayoutParams(speedSignSize, speedSignSize)
        speedValue.layoutParams = LinearLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT,
            (rowHeight * scaleSpeedValue).toInt().coerceAtLeast(rowHeight / 2),
        )
        val clockSp = (shorterDp * 0.25f * scaleClock)
            .coerceIn(48f * scaleClock, 120f * scaleClock)
        val distanceSp = (shorterDp * 0.095f * scaleDistance)
            .coerceIn(16f * scaleDistance, 50f * scaleDistance)
        val maneuverFallbackSp = (shorterDp * 0.085f * scaleManeuverTextFallback)
            .coerceIn(16f * scaleManeuverTextFallback, 40f * scaleManeuverTextFallback)
        val roadSp = (shorterDp * 0.078f * scaleRoad)
            .coerceIn(14f * scaleRoad, 40f * scaleRoad)
        val extraInfoSp = (shorterDp * 0.078f * scaleExtraInfo)
            .coerceIn(14f * scaleExtraInfo, 40f * scaleExtraInfo)
        val etaSummarySp = (shorterDp * 0.058f * scaleEtaSummary)
            .coerceIn(14f * scaleEtaSummary, 28f * scaleEtaSummary)
        val currentRoadSp = (shorterDp * 0.055f * scaleCurrentRoad)
            .coerceIn(13f * scaleCurrentRoad, 26f * scaleCurrentRoad)
        val routeNumberSp = (shorterDp * 0.055f * scaleRouteNumber)
            .coerceIn(13f * scaleRouteNumber, 26f * scaleRouteNumber)
        val laneGuidanceSp = (shorterDp * 0.055f * scaleLaneGuidance)
            .coerceIn(13f * scaleLaneGuidance, 26f * scaleLaneGuidance)
        val trafficDelaySp = (shorterDp * 0.055f * scaleTrafficDelay)
            .coerceIn(13f * scaleTrafficDelay, 26f * scaleTrafficDelay)
        val speedLimitSignSp = (shorterDp * 0.05f * scaleSpeedLimitSign)
            .coerceIn(14f * scaleSpeedLimitSign, 26f * scaleSpeedLimitSign)
        val speedValueSp = (shorterDp * 0.05f * scaleSpeedValue)
            .coerceIn(14f * scaleSpeedValue, 26f * scaleSpeedValue)
        val titleSp = (shorterDp * 0.065f * scaleTrackTitle)
            .coerceIn(16f * scaleTrackTitle, 32f * scaleTrackTitle)
        setFixedTextSize(clock, clockSp)
        setFixedTextSize(distance, distanceSp)
        setFixedTextSize(maneuverTextFallback, maneuverFallbackSp)
        setFixedTextSize(road, roadSp)
        setFixedTextSize(extraInfo, extraInfoSp)
        setFixedTextSize(etaSummary, etaSummarySp)
        setFixedTextSize(currentRoad, currentRoadSp)
        setFixedTextSize(routeNumber, routeNumberSp)
        setFixedTextSize(laneGuidance, laneGuidanceSp)
        setFixedTextSize(trafficDelay, trafficDelaySp)
        setFixedTextSize(speedLimitSign, speedLimitSignSp)
        setFixedTextSize(speedValue, speedValueSp)
        setFixedTextSize(title, titleSp)
    }

    private fun formatDistance(meters: Int): String {
        return if (meters < 1000) context.getString(R.string.night_overlay_meters, meters)
        else context.getString(R.string.night_overlay_kilometers, meters / 1000.0)
    }

    private fun formatDurationCompact(totalSeconds: Long): String {
        val minutes = (totalSeconds / 60L).coerceAtLeast(0L)
        val hours = minutes / 60L
        val rest = minutes % 60L
        return if (hours > 0L) {
            context.getString(R.string.night_overlay_duration_hours_minutes, hours, rest)
        } else {
            context.getString(R.string.night_overlay_duration_minutes, rest)
        }
    }

    private fun iconFor(icon: NightOverlayState.NavigationIcon): Int {
        return when (icon) {
            NightOverlayState.NavigationIcon.DEPART -> R.drawable.ic_nav_depart
            NightOverlayState.NavigationIcon.STRAIGHT -> R.drawable.ic_nav_straight
            NightOverlayState.NavigationIcon.TURN_SLIGHT_LEFT -> R.drawable.ic_nav_turn_slight_left
            NightOverlayState.NavigationIcon.TURN_LEFT -> R.drawable.ic_nav_turn_left
            NightOverlayState.NavigationIcon.TURN_SHARP_LEFT -> R.drawable.ic_nav_turn_sharp_left
            NightOverlayState.NavigationIcon.U_TURN_LEFT -> R.drawable.ic_nav_u_turn_left
            NightOverlayState.NavigationIcon.RAMP_LEFT -> R.drawable.ic_nav_ramp_left
            NightOverlayState.NavigationIcon.FORK_LEFT -> R.drawable.ic_nav_fork_left
            NightOverlayState.NavigationIcon.MERGE -> R.drawable.ic_nav_merge
            NightOverlayState.NavigationIcon.ROUNDABOUT -> R.drawable.ic_nav_roundabout
            NightOverlayState.NavigationIcon.TURN_SLIGHT_RIGHT -> R.drawable.ic_nav_turn_slight_right
            NightOverlayState.NavigationIcon.TURN_RIGHT -> R.drawable.ic_nav_turn_right
            NightOverlayState.NavigationIcon.TURN_SHARP_RIGHT -> R.drawable.ic_nav_turn_sharp_right
            NightOverlayState.NavigationIcon.U_TURN_RIGHT -> R.drawable.ic_nav_u_turn_right
            NightOverlayState.NavigationIcon.RAMP_RIGHT -> R.drawable.ic_nav_ramp_right
            NightOverlayState.NavigationIcon.FORK_RIGHT -> R.drawable.ic_nav_fork_right
            NightOverlayState.NavigationIcon.FERRY_BOAT -> R.drawable.ic_nav_ferry_boat
            NightOverlayState.NavigationIcon.FERRY_TRAIN -> R.drawable.ic_nav_ferry_train
            NightOverlayState.NavigationIcon.DESTINATION -> R.drawable.ic_nav_destination
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (availableWidth > 0 && availableHeight > 0 &&
            (configuredWidth != availableWidth || configuredHeight != availableHeight)) {
            configuredWidth = availableWidth
            configuredHeight = availableHeight
            // Child layout parameters must be ready before FrameLayout measures them.
            configureTextSizes(availableWidth, availableHeight)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun setFixedTextSize(view: AppCompatTextView, sizeSp: Float) {
        TextViewCompat.setAutoSizeTextTypeWithDefaults(view, TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        clockTick.run()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) clockTick.run()
        else handler.removeCallbacks(clockTick)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(clockTick)
        super.onDetachedFromWindow()
    }
}
