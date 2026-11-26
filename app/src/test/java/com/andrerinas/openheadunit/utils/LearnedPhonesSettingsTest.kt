package com.andrerinas.openheadunit.utils

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LearnedPhonesSettingsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val raw get() = context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)

    @Before
    fun clearPrefs() {
        raw.edit().clear().commit()
    }

    @Test
    fun `an empty first read stores the key, so a later type-3 write does not seed the learned phones`() {
        val settings = Settings(context)
        assertEquals(emptyList<String>(), settings.nativeLearnedPhones)
        assertTrue(raw.contains("native-learned-phones"))
        assertEquals("", raw.getString("native-learned-phones", null))
        settings.lastConnectedNativeMac = A
        assertEquals(emptyList<String>(), settings.nativeLearnedPhones)
        assertEquals(emptyList<String>(), Settings(context).nativeLearnedPhones)
    }

    @Test
    fun `a type-3 write with no read before it does not seed the learned phones`() {
        val settings = Settings(context)
        settings.lastConnectedNativeMac = A
        assertEquals(emptyList<String>(), settings.nativeLearnedPhones)
    }

    @Test
    fun `an upgrade whose first event is a type-3 write still seeds from the old phone`() {
        raw.edit().putString("last-connected-native-mac", A).commit()
        val settings = Settings(context)
        settings.lastConnectedNativeMac = B
        assertEquals(listOf(A), settings.nativeLearnedPhones)
    }

    @Test
    fun `an upgrade seeds the learned phones once from the last connected phone`() {
        raw.edit().putString("last-connected-native-mac", A).commit()
        val settings = Settings(context)
        assertEquals(listOf(A), settings.nativeLearnedPhones)
        assertEquals(A, raw.getString("native-learned-phones", null))
        settings.lastConnectedNativeMac = B
        assertEquals(listOf(A), settings.nativeLearnedPhones)
    }

    @Test
    fun `a stored learned list is not reseeded`() {
        raw.edit().putString("native-learned-phones", B).putString("last-connected-native-mac", A).commit()
        assertEquals(listOf(B), Settings(context).nativeLearnedPhones)
    }

    private companion object {
        const val A = "AA:BB:CC:DD:EE:01"
        const val B = "AA:BB:CC:DD:EE:02"
    }
}
