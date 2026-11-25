package com.andrerinas.openheadunit.view

import android.app.Application
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.NightOverlayOptions
import com.andrerinas.openheadunit.aap.NightOverlayState
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The night overlay with route guidance, laid out on the real view with real text measuring: on
 * portrait and landscape phones, a tablet and a head unit, in every language the app has, at every
 * text size option and at system font sizes up to Android's largest, the distance beside the
 * maneuver icon is never cut, for the widest string of each distance format and for ordinary
 * ones, with an icon and with the fallback text in its place.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class NightOverlayLayoutTest {

    // The widest string of each format; ordinary distances are narrower in the same format.
    private val widest = listOf(888, 88_800, 888_000)
    private val ordinary = listOf(450, 123_456)

    /** Every language the app has, read from the resource folders: "" is the default (English). */
    private val languages: List<String> =
        listOf("") + File("src/main/res").listFiles { f -> f.isDirectory && f.name.startsWith("values-") }!!
            .filter { File(it, "strings.xml").exists() }
            .map { it.name.removePrefix("values-") }
            .sorted()

    /** The default, a common larger setting, and the largest Android offers (non-linear from Android 14). */
    private val fontScales = listOf(1f, 1.3f, 2f)

    private val textSizes = Settings.NightOverlayTextSize.entries

    private fun layOut(
        activity: AppCompatActivity,
        textSize: Settings.NightOverlayTextSize,
        meters: Int,
        withIcon: Boolean,
    ): NightProjectionOverlay {
        val dm = activity.resources.displayMetrics
        val overlay = NightProjectionOverlay(activity)
        overlay.applyOptions(NightOverlayOptions(textSize = textSize))
        overlay.render(NightOverlayState.Content(
            navigation = NightOverlayState.Navigation(
                maneuver = "Turn right onto the main road",
                distanceMeters = meters,
                timeToManeuverSeconds = null,
                road = "Main Road",
                icon = if (withIcon) NightOverlayState.NavigationIcon.TURN_RIGHT else null,
            ),
            title = "Track",
            artist = "Artist",
            isPlaying = true,
        ))
        overlay.visibility = View.VISIBLE
        // The overlay sets its children's sizes in onMeasure, so measure twice to settle.
        repeat(2) {
            overlay.measure(
                View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY),
            )
            overlay.layout(0, 0, dm.widthPixels, dm.heightPixels)
        }
        return overlay
    }

    private fun ellipsized(v: TextView) =
        v.layout != null && (0 until v.layout.lineCount).any { v.layout.getEllipsisCount(it) > 0 }

    private fun rightIn(root: View, v: View): Int {
        var x = v.right
        var p = v.parent as? View
        while (p != null && p !== root) { x += p.left; p = p.parent as? View }
        return x
    }

    /** One activity per screen, language and font size, then let go. */
    private fun assertDistanceFits(distances: List<Int> = widest + ordinary) {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        val activity = controller.create().get()
        try {
            // The fallback text takes the icon's place: checked with the widest kilometre string.
            val cases = distances.map { it to true } + (88_800 to false)
            for (textSize in textSizes) for ((meters, withIcon) in cases) {
                val what = "$textSize, $meters m, ${if (withIcon) "icon" else "fallback text"}"
                val overlay = layOut(activity, textSize, meters, withIcon)
                val distance = overlay.findViewById<TextView>(R.id.overlay_distance)
                assertEquals("$what: the distance shows", View.VISIBLE, distance.visibility)
                assertTrue("$what: '${distance.text}' is whole", !ellipsized(distance))
                assertTrue("$what: the distance stays on the screen", rightIn(overlay, distance) <= overlay.width)
                if (!withIcon) {
                    val icon = overlay.findViewById<View>(R.id.overlay_maneuver_icon)
                    val fallback = overlay.findViewById<TextView>(R.id.overlay_maneuver_text_fallback)
                    assertEquals("$what: the fallback text shows", View.VISIBLE, fallback.visibility)
                    assertTrue(
                        "$what: the fallback text is no wider than the icon would be",
                        fallback.width <= icon.layoutParams.width,
                    )
                }
            }
        } finally {
            controller.destroy()
        }
    }

    // Portrait: the shorter side is the width. 320 dp is the narrowest normal phone screen Android
    // allows, so the least room; 360 dp is where the cut was measured; 411 dp a common modern phone.
    @Test @Config(qualifiers = "w320dp-h640dp-port-hdpi") fun `a 320 dp portrait phone`() = assertDistanceFits()
    @Test @Config(qualifiers = "w360dp-h800dp-port-xhdpi") fun `a 360 dp portrait phone`() = assertDistanceFits()
    @Test @Config(qualifiers = "w411dp-h891dp-port-xxhdpi") fun `a 411 dp portrait phone`() = assertDistanceFits()
    @Test @Config(qualifiers = "w600dp-h960dp-port-hdpi") fun `a portrait tablet`() = assertDistanceFits()

    // Landscape keeps the full sizes; the distance still fits.
    @Test @Config(qualifiers = "w800dp-h360dp-land-xhdpi") fun `a landscape phone`() = assertDistanceFits()
    @Test @Config(qualifiers = "w1024dp-h600dp-land-mdpi") fun `a 1024x600 head unit`() = assertDistanceFits()

    @Test
    fun `the language list holds every language folder`() {
        assertTrue("found only ${languages.size} languages", languages.size >= 20)
        assertTrue(languages.containsAll(listOf("", "de", "hi", "zh-rCN")))
    }

    /**
     * Every language at every font size, on the narrowest phone: separators, units, scripts and
     * digit shapes differ in width, and the system font size makes all of them wider.
     */
    @Test
    fun `every language and font size on a 320 dp portrait phone`() {
        for (language in languages) for (fontScale in fontScales) {
            val prefix = if (language.isEmpty()) "" else "$language-"
            RuntimeEnvironment.setQualifiers("${prefix}w320dp-h640dp-port-hdpi")
            RuntimeEnvironment.setFontScale(fontScale)
            try {
                assertDistanceFits(widest)
            } catch (e: AssertionError) {
                throw AssertionError("language '${language.ifEmpty { "default" }}', font x$fontScale: ${e.message}", e)
            }
        }
    }
}
