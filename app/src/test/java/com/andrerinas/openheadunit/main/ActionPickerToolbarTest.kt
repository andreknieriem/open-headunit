package com.andrerinas.openheadunit.main

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.input.InputAction
import com.google.android.material.appbar.MaterialToolbar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The action picker's toolbar holds two lines, the key and "Choose action". With a large system
 * font a toolbar held to one action bar's height cut the second line on a head unit. Android 11
 * scales fonts linearly, as the Android 10 units these run do; newer releases scale large text
 * less and would hide the fault.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [30])
class ActionPickerToolbarTest {

    private fun assertBothLinesWhole(fontScale: Float) {
        RuntimeEnvironment.setFontScale(fontScale)
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        val activity = controller.setup().get()
        try {
            val fragment = ActionPickerFragment().apply {
                arguments = ActionPickerFragment.args(
                    requestKey = "k", tag = "66", title = "ENTER", current = InputAction.None,
                    noneLabelResId = R.string.action_remove_binding, offersDoNothing = true,
                )
            }
            activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
            val root = activity.window.decorView
            val dm = activity.resources.displayMetrics
            root.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, dm.widthPixels, dm.heightPixels)
            shadowOf(Looper.getMainLooper()).idle()
            val toolbar = fragment.requireView().findViewById<MaterialToolbar>(R.id.toolbar)
            val lines = (0 until toolbar.childCount).map { toolbar.getChildAt(it) }.filterIsInstance<TextView>()
            assertEquals("title and subtitle", 2, lines.size)
            for (line in lines) {
                val m = line.paint.fontMetricsInt
                assertTrue("font $fontScale: '${line.text}' ends at ${line.bottom}, the toolbar at ${toolbar.height}",
                    line.bottom <= toolbar.height)
                assertTrue("font $fontScale: '${line.text}' is ${line.height}px for a ${m.descent - m.ascent}px line",
                    line.height >= m.descent - m.ascent)
            }
        } finally {
            controller.destroy()
        }
    }

    @Test @Config(qualifiers = "w1920dp-h720dp-land-mdpi") fun `a 1920x720 unit at every font size`() =
        listOf(1f, 1.15f, 1.3f, 1.5f).forEach { assertBothLinesWhole(it) }

    @Test @Config(qualifiers = "w533dp-h300dp-land-hdpi") fun `an 800x450 unit with a large font`() =
        assertBothLinesWhole(1.5f)

    @Test @Config(qualifiers = "w411dp-h891dp-port-xxhdpi") fun `a portrait phone with a large font`() =
        assertBothLinesWhole(1.5f)
}
