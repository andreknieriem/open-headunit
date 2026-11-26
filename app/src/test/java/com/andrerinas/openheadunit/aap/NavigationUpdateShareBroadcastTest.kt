package com.andrerinas.openheadunit.aap

import android.app.Application
import com.andrerinas.openheadunit.contract.NavigationUpdateIntent
import com.andrerinas.openheadunit.utils.NavigationShareControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class NavigationUpdateShareBroadcastTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Test fun `with no app named only the signature broadcast goes out`() {
        AapNavigationHelper(app).sendFullNavigationBroadcast(AapNavigationHelper.NavigationSnapshot(), 2)

        val sent = shadowOf(app).broadcastIntents
        assertEquals(1, sent.size)
        assertEquals(NavigationUpdateIntent.action, sent.single().action)
        assertNull(sent.single().`package`)
    }

    @Test fun `a named app gets its own addressed copy with the same extras`() {
        AapNavigationHelper(app, sharePackages = { setOf("club.x70.polosa") })
            .sendFullNavigationBroadcast(AapNavigationHelper.NavigationSnapshot(), 2)

        val sent = shadowOf(app).broadcastIntents
        // The playstore flavor never shares, so there it stays the signature broadcast alone.
        val expected = if (NavigationShareControl.isAvailable()) listOf(null, "club.x70.polosa") else listOf(null)
        assertEquals(expected, sent.map { it.`package` })
        sent.forEach { assertEquals(NavigationUpdateIntent.action, it.action) }
        if (sent.size == 2) {
            val (signed, addressed) = sent
            assertEquals(signed.extras!!.keySet(), addressed.extras!!.keySet())
            for (key in signed.extras!!.keySet()) {
                assertEquals(key, signed.extras!!.get(key), addressed.extras!!.get(key))
            }
        }
    }
}
