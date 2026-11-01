package com.andrerinas.openheadunit.view

import android.content.Context
import android.graphics.Color
import android.view.View

/** A black layer that lets every touch through: not clickable, no listener, no transform. */
class ProjectionDimmerView(context: Context) : View(context) {
    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
    }

    var shownAlpha = -1
        private set

    fun show(alpha: Int) {
        shownAlpha = alpha
        setBackgroundColor(Color.argb(alpha, 0, 0, 0))
        visibility = VISIBLE
    }

    fun hide() {
        visibility = GONE
    }
}
