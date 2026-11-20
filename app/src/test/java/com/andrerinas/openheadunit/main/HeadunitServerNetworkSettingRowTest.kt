package com.andrerinas.openheadunit.main

import android.app.Application
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.andrerinas.openheadunit.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Connection Method row's three options, on the real segmented layout, at the widths the
 * settings list can have: every option's label is shown whole in every language. The segment
 * holds two lines and clips a third without an ellipsis, so this is the only way to see it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class HeadunitServerNetworkSettingRowTest {

    private val locales = listOf("", "ar", "cs", "de", "es", "es-rES", "fr", "hi", "hu", "in", "it", "ja", "ka", "ko",
        "nl", "pl", "pt-rBR", "pt-rPT", "ro", "ru", "th", "tr", "uk", "vi", "zh-rCN", "zh-rTW")

    private fun assertWholeIn(screen: String) {
        for (locale in locales) {
            RuntimeEnvironment.setQualifiers(if (locale.isEmpty()) screen else "$locale-$screen")
            val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
            controller.get().setTheme(R.style.AppTheme)
            val activity = controller.create().get()
            try {
                val dm = activity.resources.displayMetrics
                val root = FrameLayout(activity)
                val row = activity.layoutInflater.inflate(R.layout.layout_setting_item_segmented, root, true)
                row.findViewById<TextView>(R.id.settingName).setText(R.string.headunit_server_network_label)
                val labels = listOf(R.string.headunit_server_network_phone, R.string.headunit_server_network_unit,
                    R.string.headunit_server_network_shared)
                val buttons = listOf(R.id.btnOption1, R.id.btnOption2, R.id.btnOption3).map { row.findViewById<TextView>(it) }
                buttons.zip(labels).forEach { (b, l) -> b.setText(l); b.visibility = View.VISIBLE }
                root.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.AT_MOST))
                root.layout(0, 0, dm.widthPixels, root.measuredHeight)
                for (b in buttons) {
                    val layout = b.layout
                    val shown = layout.lineCount <= b.maxLines &&
                        layout.getLineEnd(layout.lineCount - 1) == b.text.length &&
                        (0 until layout.lineCount).none { layout.getEllipsisCount(it) > 0 } &&
                        (0 until layout.lineCount).all { layout.getLineWidth(it) <= b.width - b.totalPaddingLeft - b.totalPaddingRight + 1 }
                    assertTrue("${locale.ifEmpty { "en" }} on $screen: '${b.text}' is cut (lines=${layout.lineCount})", shown)
                }
            } finally {
                controller.destroy()
            }
        }
    }

    // The full width of a portrait phone is the narrowest the list gets.
    @Test fun `a portrait phone`() = assertWholeIn("w360dp-h780dp-port-xxhdpi")
    @Test fun `an 800x450 head unit`() = assertWholeIn("w533dp-h300dp-land-hdpi")
}
