package com.andrerinas.openheadunit.view

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.NightOverlayState
import java.util.Date

/** Covers the picture without hiding or detaching the decoder's surface. */
class NightProjectionOverlay(context: Context) : FrameLayout(context) {
    private val debugNavLayoutColors = false

    // Per-text scale knobs. Keep them code-driven for now; later they can be loaded from Settings.
    private var scaleClock = 1.0f
    private var scaleDistance = 1.0f
    private var scaleManeuverTextFallback = 1.0f
    private var scaleRoad = 1.0f
    private var scaleExtraInfo = 1.0f
    private var scaleTrackTitle = 1.0f

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var clock: AppCompatTextView
    private lateinit var maneuverIcon: AppCompatImageView
    private lateinit var maneuverTextFallback: AppCompatTextView
    private lateinit var distance: AppCompatTextView
    private lateinit var road: AppCompatTextView
    private lateinit var extraInfo: AppCompatTextView
    private lateinit var title: AppCompatTextView
    private lateinit var topRow: LinearLayout
    private lateinit var topInfoColumn: LinearLayout
    private lateinit var maneuverBlock: LinearLayout
    private lateinit var navRow: LinearLayout
    private var configuredWidth = 0
    private var configuredHeight = 0
    private lateinit var details: LinearLayout
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
        LayoutInflater.from(context).inflate(R.layout.night_projection_overlay, this, true)
        bindViews()
        configureStaticViewStyles()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // Above the performance HUD as well as all projection backends.
            elevation = 201f
        }
        applyDebugNavColors()
        setOnClickListener { hide() }
    }

    private fun bindViews() {
        details = findViewById(R.id.overlay_details)
        clock = findViewById(R.id.overlay_clock)
        navRow = findViewById(R.id.overlay_nav_row)
        maneuverBlock = findViewById(R.id.overlay_maneuver_block)
        topRow = findViewById(R.id.overlay_top_row)
        maneuverIcon = findViewById(R.id.overlay_maneuver_icon)
        maneuverTextFallback = findViewById(R.id.overlay_maneuver_text_fallback)
        topInfoColumn = findViewById(R.id.overlay_top_info_column)
        distance = findViewById(R.id.overlay_distance)
        road = findViewById(R.id.overlay_road)
        extraInfo = findViewById(R.id.overlay_extra_info)
        title = findViewById(R.id.overlay_title)
    }

    private fun configureStaticViewStyles() {
        val labels = listOf(
            clock, maneuverTextFallback, distance, road, extraInfo, title,
        )
        labels.forEach(::setupLabel)

        maneuverIcon.apply {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            scaleType = ImageView.ScaleType.FIT_CENTER
            setColorFilter(Color.GRAY)
            visibility = GONE
        }
        maneuverTextFallback.apply {
            setTypeface(typeface, Typeface.BOLD)
            visibility = GONE
        }
        distance.apply {
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        road.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        extraInfo.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        navRow.visibility = GONE
    }

    private fun applyDebugNavColors() {
        if (!debugNavLayoutColors) return
        navRow.setBackgroundColor(Color.parseColor("#33FF1744"))
        maneuverBlock.setBackgroundColor(Color.parseColor("#332196F3"))
        topRow.setBackgroundColor(Color.parseColor("#334CAF50"))
        topInfoColumn.setBackgroundColor(Color.parseColor("#33FFC107"))

        distance.setBackgroundColor(Color.parseColor("#3340C4FF"))
        road.setBackgroundColor(Color.parseColor("#334DFF88"))
        extraInfo.setBackgroundColor(Color.parseColor("#33FFD54F"))
    }

    private fun setupLabel(view: AppCompatTextView) {
        view.setTextColor(Color.GRAY)
        view.gravity = Gravity.CENTER
        view.maxLines = 1
        view.ellipsize = TextUtils.TruncateAt.END
        view.includeFontPadding = false
        view.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
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
        distance.text = formatDistanceWithTime(
            navigation?.distanceMeters,
            navigation?.timeToManeuverSeconds,
        )
        distance.setTextColor(
            when {
                isSecondWarning(navigation?.distanceMeters, navigation?.timeToManeuverSeconds) ->
                    "#FF1744".toColorInt()

                isFirstWarning(navigation?.distanceMeters, navigation?.timeToManeuverSeconds) ->
                    "#FFB000".toColorInt()

                else -> Color.GRAY
            },
        )
        distance.isVisible = distance.text.isNotBlank()

        road.text = navigation?.road.orEmpty()
        road.isVisible = road.text.isNotBlank()
        extraInfo.text = navigation?.details.orEmpty()
        extraInfo.isVisible = extraInfo.text.isNotBlank()
        topRow.isVisible = maneuverIcon.isVisible || maneuverTextFallback.isVisible ||
            distance.isVisible || road.isVisible || extraInfo.isVisible
        maneuverBlock.isVisible = topRow.isVisible || road.isVisible || extraInfo.isVisible
        navRow.isVisible = maneuverBlock.isVisible

        val showTrack = content.isPlaying != false
        title.text =
            listOf(content.title, content.artist).filter { it.isNotBlank() }.joinToString(" - ")
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
        val iconSize = (shorterSide * 0.2f).toInt()
            .coerceIn((48f * density).toInt(), (148f * density).toInt())
        val padding = (shorterDp * 0.04f * density).toInt()
        val tinyGap = (8f * density).toInt()
        val topInfoWidth = (
            width - (padding * 2) - iconSize - (tinyGap * 4)
            ).coerceAtLeast((120f * density).toInt())
        clock.layoutParams = LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
        )
        details.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER,
        ).apply {
            leftMargin = padding
            rightMargin = padding
        }
        navRow.layoutParams = LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = tinyGap * 3
        }
        maneuverBlock.layoutParams = LinearLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
        topRow.layoutParams =
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
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
        extraInfo.maxWidth = topInfoWidth
        distance.layoutParams = LinearLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = tinyGap / 2
        }
        road.layoutParams = LinearLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = tinyGap / 2
        }
        extraInfo.layoutParams = LinearLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
        )
        title.layoutParams = LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = tinyGap * 3
        }
        val clockSp = (shorterDp * 0.30f * scaleClock)
            .coerceIn(57.6f * scaleClock, 144f * scaleClock)
        val distanceSp = (shorterDp * 0.1235f * scaleDistance)
            .coerceIn(20.8f * scaleDistance, 65f * scaleDistance)
        val maneuverFallbackSp = (shorterDp * 0.085f * scaleManeuverTextFallback)
            .coerceIn(16f * scaleManeuverTextFallback, 40f * scaleManeuverTextFallback)
        val roadSp = (shorterDp * 0.0546f * scaleRoad)
            .coerceIn(9.8f * scaleRoad, 28f * scaleRoad)
        val extraInfoSp = (shorterDp * 0.0546f * scaleExtraInfo)
            .coerceIn(9.8f * scaleExtraInfo, 28f * scaleExtraInfo)
        val titleSp = (shorterDp * 0.065f * scaleTrackTitle)
            .coerceIn(16f * scaleTrackTitle, 32f * scaleTrackTitle)
        setFixedTextSize(clock, clockSp)
        setFixedTextSize(distance, distanceSp)
        setFixedTextSize(maneuverTextFallback, maneuverFallbackSp)
        setFixedTextSize(road, roadSp)
        setFixedTextSize(extraInfo, extraInfoSp)
        setFixedTextSize(title, titleSp)
    }

    private fun formatDistance(meters: Int): String {
        return if (meters < 1000) context.getString(R.string.night_overlay_meters, meters)
        else context.getString(R.string.night_overlay_kilometers, meters / 1000.0)
    }

    private fun formatManeuverTime(seconds: Int): String {
        return if (seconds < 60) "$seconds s" else {
            val minutes = seconds / 60
            "${minutes} min"
        }
    }

    private fun formatDistanceWithTime(distanceMeters: Int?, timeToManeuverSeconds: Int?): String {
        val distanceText = distanceMeters?.let(::formatDistance).orEmpty()
        //val timeText = timeToManeuverSeconds?.takeIf { it >= 0 }?.let(::formatManeuverTime)
        return when {
            distanceText.isBlank() -> ""
            // timeText == null -> distanceText
            //else -> "$distanceText ($timeText)"
            else -> distanceText
        }
    }

    private fun isFirstWarning(distanceMeters: Int?, timeToManeuverSeconds: Int?): Boolean {
        val byTime = timeToManeuverSeconds?.let { it in 0..20 } ?: false
        val byDistance = distanceMeters?.let { it in 0..500 } ?: false
        return byTime || byDistance
    }

    private fun isSecondWarning(distanceMeters: Int?, timeToManeuverSeconds: Int?): Boolean {
        val byTime = timeToManeuverSeconds?.let { it in 0..10 } ?: false
        val byDistance = distanceMeters?.let { it in 0..200 } ?: false
        return byTime || byDistance
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
            (configuredWidth != availableWidth || configuredHeight != availableHeight)
        ) {
            configuredWidth = availableWidth
            configuredHeight = availableHeight
            // Child layout parameters must be ready before FrameLayout measures them.
            configureTextSizes(availableWidth, availableHeight)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun setFixedTextSize(view: AppCompatTextView, sizeSp: Float) {
        TextViewCompat.setAutoSizeTextTypeWithDefaults(
            view,
            TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE,
        )
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
