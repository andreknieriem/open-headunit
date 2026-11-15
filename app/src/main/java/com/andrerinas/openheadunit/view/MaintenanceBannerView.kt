package com.andrerinas.openheadunit.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.MarginLayoutParamsCompat
import androidx.core.view.ViewCompat
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.maintenance.MaintenanceBannerPolicy

/**
 * The maintenance banner drawn over the projection while the car is moving.
 *
 * It never takes a touch: [dispatchTouchEvent] says no, so every touch falls through to the view
 * under it and on to Android Auto. Its look is a few plain shapes, and its only animations are the
 * slide in, the countdown line and the fade out, all with ViewPropertyAnimator, so nothing is
 * allocated or drawn per video frame beyond the banner's own few pixels.
 */
class MaintenanceBannerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private var placement = MaintenanceBannerPolicy.Placement.TOP
    private var passengerOnLeft = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = false

    /** Fills it in. The colors follow the state: red for overdue, amber for coming up. */
    fun bind(title: String, detail: String, overdue: Boolean) {
        val accent = ContextCompat.getColor(context,
            if (overdue) R.color.maintenance_notice_overdue_accent else R.color.maintenance_notice_soon_accent)
        findViewById<TextView>(R.id.banner_title).text = title
        findViewById<TextView>(R.id.banner_detail).text = detail
        findViewById<ImageView>(R.id.banner_icon).setColorFilter(accent)
        ViewCompat.setBackground(findViewById(R.id.banner_icon_circle), GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(context,
                if (overdue) R.color.maintenance_banner_overdue_circle else R.color.maintenance_banner_soon_circle))
        })
        findViewById<View>(R.id.banner_countdown).setBackgroundColor(accent)
    }

    /**
     * Layout parameters for [placement] in a container of [containerWidth] by [containerHeight]
     * pixels, on the passenger side unless it sits centered above the Android Auto bar.
     * The side is physical (where the passenger sits), not the reading direction, so it stays
     * put in Arabic.
     */
    @SuppressLint("RtlHardcoded")
    fun place(
        placement: MaintenanceBannerPolicy.Placement,
        passengerOnLeft: Boolean,
        containerWidth: Int,
        containerHeight: Int,
    ): FrameLayout.LayoutParams {
        this.placement = placement
        this.passengerOnLeft = passengerOnLeft
        val side = if (passengerOnLeft) Gravity.LEFT else Gravity.RIGHT
        val margin = dp(16)
        val stacked = placement == MaintenanceBannerPolicy.Placement.SIDE
        val row = findViewById<LinearLayout>(R.id.banner_row)
        row.orientation = if (stacked) VERTICAL else HORIZONTAL
        row.gravity = if (stacked) Gravity.START else Gravity.CENTER_VERTICAL
        val texts = findViewById<View>(R.id.banner_texts)
        val textsParams = texts.layoutParams as LayoutParams
        val gap = if (stacked) 0 else dp(16)
        textsParams.width = if (stacked) LayoutParams.MATCH_PARENT else 0
        textsParams.weight = if (stacked) 0f else 1f
        textsParams.setMargins(gap, if (stacked) dp(14) else 0, 0, 0)
        MarginLayoutParamsCompat.setMarginStart(textsParams, gap)
        MarginLayoutParamsCompat.setMarginEnd(textsParams, 0)
        texts.layoutParams = textsParams
        val wanted = when (placement) {
            MaintenanceBannerPolicy.Placement.TOP -> dp(440)
            MaintenanceBannerPolicy.Placement.ABOVE_BAR -> dp(480)
            MaintenanceBannerPolicy.Placement.SIDE -> dp(260)
        }
        val width = minOf(wanted, containerWidth - 2 * margin).coerceAtLeast(dp(200))
        return when (placement) {
            MaintenanceBannerPolicy.Placement.TOP ->
                FrameLayout.LayoutParams(width, LayoutParams.WRAP_CONTENT, Gravity.TOP or side).apply {
                    setMargins(margin, margin, margin, margin)
                }
            // Android Auto's bar is about an eighth of the screen high; this clears it on the
            // usual layouts without knowing its exact size, which the phone never tells us.
            MaintenanceBannerPolicy.Placement.ABOVE_BAR ->
                FrameLayout.LayoutParams(width, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                    setMargins(margin, margin, margin, (containerHeight * ABOVE_BAR_FRACTION).toInt())
                }
            MaintenanceBannerPolicy.Placement.SIDE ->
                FrameLayout.LayoutParams(width, LayoutParams.WRAP_CONTENT, Gravity.TOP or side).apply {
                    setMargins(margin, (containerHeight * SIDE_TOP_FRACTION).toInt(), margin, margin)
                }
        }
    }

    /** Slides in from its edge and runs the countdown line for [durationMs]. */
    fun enter(durationMs: Long) {
        val (dx, dy) = offset()
        translationX = dx
        translationY = dy
        alpha = 0f
        animate().translationX(0f).translationY(0f).alpha(1f)
            .setDuration(ENTER_MS).setInterpolator(DecelerateInterpolator(2f)).start()
        val countdown = findViewById<View>(R.id.banner_countdown)
        countdown.post {
            // It shrinks toward the start of the text, which is the right side in Arabic.
            val rtl = ViewCompat.getLayoutDirection(this) == ViewCompat.LAYOUT_DIRECTION_RTL
            countdown.pivotX = if (rtl) countdown.width.toFloat() else 0f
            countdown.scaleX = 1f
            countdown.animate().scaleX(0f).setDuration(durationMs)
                .setInterpolator(LinearInterpolator()).start()
        }
    }

    /** Fades out toward its edge, then runs [then] (which takes it off the screen). */
    fun leave(then: () -> Unit) {
        val (dx, dy) = offset()
        animate().translationX(dx / 2).translationY(dy / 2).alpha(0f)
            .setDuration(LEAVE_MS).setInterpolator(AccelerateInterpolator())
            .withEndAction { then() }.start()
    }

    /** Where it comes from: above the top edge, below for the bar, or from the passenger side. */
    private fun offset(): Pair<Float, Float> = when (placement) {
        MaintenanceBannerPolicy.Placement.TOP -> 0f to -dp(24).toFloat()
        MaintenanceBannerPolicy.Placement.ABOVE_BAR -> 0f to dp(24).toFloat()
        MaintenanceBannerPolicy.Placement.SIDE -> (if (passengerOnLeft) -dp(24) else dp(24)).toFloat() to 0f
    }

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()

    private companion object {
        const val ENTER_MS = 420L
        const val LEAVE_MS = 220L
        const val ABOVE_BAR_FRACTION = 0.14f
        const val SIDE_TOP_FRACTION = 0.25f
    }
}
