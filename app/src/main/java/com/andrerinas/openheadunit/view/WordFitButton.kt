package com.andrerinas.openheadunit.view

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.text.Layout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import com.andrerinas.openheadunit.main.LabelFitPolicy
import com.google.android.material.button.MaterialButton

/**
 * A segment of a settings choice that never splits a word across its two lines, the way
 * [WordFitTextView] does for the home tiles: see [LabelFitPolicy]. Three segments share a 320 dp
 * row, and a word like "Schaltgetriebe" is wider than a third of it, so the text shrinks just
 * enough for that word instead of breaking as "Schaltgetri" over "ebe".
 */
class WordFitButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle,
) : MaterialButton(context, attrs, defStyleAttr) {

    /** The size from the style, in px: fitting only ever makes the text smaller than this. */
    private val requestedPx: Float = textSize
    private var xmlMaxLines = -1
    private val measurePaint = TextPaint()

    init {
        avoidHyphenation()
    }

    // Lint asks for LineBreaker.BREAK_STRATEGY_SIMPLE, which is API 29; Layout's constant has
    // the same value (0) and exists from API 23, where this branch starts.
    @SuppressLint("WrongConstant")
    private fun avoidHyphenation() {
        // From Android 6 the line breaker may hyphenate a word to even out the lines.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (xmlMaxLines < 0) xmlMaxLines = maxLines
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            // A pixel or two of slack: a word measured to the exact width can still be broken.
            val available = (MeasureSpec.getSize(widthMeasureSpec) - totalPaddingLeft - totalPaddingRight - SLACK_PX).toFloat()
            val floor = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, MIN_SP, resources.displayMetrics)
            val estimate = LabelFitPolicy.fit(requestedPx, widestWord(requestedPx), available, floor)
            applyFit(LabelFitPolicy.settle(estimate, requestedPx, available, floor) { widestWord(it) })
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun widestWord(sizePx: Float): Float {
        measurePaint.set(paint)
        measurePaint.textSize = sizePx
        return LabelFitPolicy.words(text).maxOfOrNull { measurePaint.measureText(it) } ?: 0f
    }

    private fun applyFit(fit: LabelFitPolicy.Fit) {
        if (textSize != fit.size) setTextSize(TypedValue.COMPLEX_UNIT_PX, fit.size)
        val lines = if (fit.singleLine) 1 else xmlMaxLines
        if (maxLines != lines) {
            maxLines = lines
            ellipsize = TextUtils.TruncateAt.END
        }
    }

    private companion object {
        /** The smallest segment text worth reading on a head unit. */
        const val MIN_SP = 10f
        const val SLACK_PX = 2
    }
}
