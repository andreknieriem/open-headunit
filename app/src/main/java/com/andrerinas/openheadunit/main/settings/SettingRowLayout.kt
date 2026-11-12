package com.andrerinas.openheadunit.main.settings

import android.content.Context
import android.text.Layout
import android.util.AttributeSet
import android.widget.LinearLayout
import android.widget.TextView
import com.andrerinas.openheadunit.R

/**
 * The row of a setting with a value. The adapter puts a short value beside the name
 * ([SettingsLayoutPolicy.showValueInline]); on a narrow screen that can leave the name too little
 * width for its longest word, which the line breaker then splits ("GPS-Korrekturfakt" over "or").
 * Here the value moves under the name instead, where it takes no width from it.
 */
class SettingRowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val inline = findViewById<TextView>(R.id.settingValueInline) ?: return
        val below = findViewById<TextView>(R.id.settingValue) ?: return
        val layout = findViewById<TextView>(R.id.settingName)?.layout ?: return
        if (inline.visibility != VISIBLE || !splitsWord(layout)) return
        // The adapter sets both views on every bind, so a recycled row starts inline again.
        below.text = inline.text
        below.visibility = VISIBLE
        inline.text = ""
        inline.visibility = GONE
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun splitsWord(layout: Layout): Boolean {
        for (line in 0 until layout.lineCount) {
            if (SettingsLayoutPolicy.splitsWordAt(layout.text, layout.getLineEnd(line))) return true
        }
        return false
    }
}
