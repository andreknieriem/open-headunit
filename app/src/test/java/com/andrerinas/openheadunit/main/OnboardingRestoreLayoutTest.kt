package com.andrerinas.openheadunit.main

import android.app.Application
import android.util.Xml
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import com.andrerinas.openheadunit.R
import com.google.android.material.button.MaterialButton
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [28], qualifiers = "ru-w320dp-h480dp-mdpi")
class OnboardingRestoreLayoutTest {
    @Test fun `restore and later are adjacent readable actions on a narrow Russian screen`() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)
        val footer = context.resources.getLayout(R.layout.activity_onboarding).use { parser ->
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG &&
                    Xml.asAttributeSet(parser).getAttributeResourceValue(
                        "http://schemas.android.com/apk/res/android", "id", 0
                    ) == R.id.onb_footer
                ) break
                parser.next()
            }
            LayoutInflater.from(context).inflate(parser, null) as LinearLayout
        }
        footer.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.AT_MOST))
        footer.layout(0, 0, footer.measuredWidth, footer.measuredHeight)
        val skip = footer.findViewById<MaterialButton>(R.id.onb_skip)
        val restore = footer.findViewById<MaterialButton>(R.id.onb_restore)
        assertSame(skip.parent, restore.parent)
        assertEquals((skip.top + skip.bottom) / 2f, (restore.top + restore.bottom) / 2f, 1f)
        assertTrue(skip.right <= restore.left)
        assertEquals("Восстановить настройки из бэкапа", restore.text.toString())
        assertTrue(restore.layout.lineCount > 1)
        for (button in listOf(skip, restore)) {
            assertTrue(button.measuredHeight >= 48)
            for (line in 0 until button.layout.lineCount) {
                assertEquals(0, button.layout.getEllipsisCount(line))
            }
        }
        assertTrue(footer.measuredHeight < 240)
    }
}
