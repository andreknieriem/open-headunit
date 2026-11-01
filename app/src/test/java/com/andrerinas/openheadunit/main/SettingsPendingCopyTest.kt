package com.andrerinas.openheadunit.main

import android.content.Context
import android.content.SharedPreferences
import com.andrerinas.openheadunit.connection.wifi.direct.StoredP2pIdentity
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeDriverSelectionPolicy
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SettingsPendingCopyTest {
    private fun newSettings(): Settings {
        val store = HashMap<String, Any?>()
        val editor = mock<SharedPreferences.Editor>()
        val prefs = mock<SharedPreferences>()
        whenever(prefs.edit()).thenReturn(editor)
        whenever(prefs.getString(any(), anyOrNull())).thenAnswer { (store[it.arguments[0]] as String?) ?: it.arguments[1] }
        whenever(prefs.getInt(any(), any())).thenAnswer { (store[it.arguments[0]] as Int?) ?: it.arguments[1] }
        whenever(prefs.getBoolean(any(), any())).thenAnswer { (store[it.arguments[0]] as Boolean?) ?: it.arguments[1] }
        whenever(prefs.getLong(any(), any())).thenAnswer { (store[it.arguments[0]] as Long?) ?: it.arguments[1] }
        whenever(prefs.getFloat(any(), any())).thenAnswer { (store[it.arguments[0]] as Float?) ?: it.arguments[1] }
        @Suppress("UNCHECKED_CAST")
        whenever(prefs.getStringSet(any(), anyOrNull())).thenAnswer { (store[it.arguments[0]] as Set<String>?) ?: it.arguments[1] }
        whenever(prefs.contains(any())).thenAnswer { store.containsKey(it.arguments[0]) }
        whenever(editor.putString(any(), anyOrNull())).thenAnswer { store[it.arguments[0] as String] = it.arguments[1]; editor }
        whenever(editor.putInt(any(), any())).thenAnswer { store[it.arguments[0] as String] = it.arguments[1]; editor }
        whenever(editor.putBoolean(any(), any())).thenAnswer { store[it.arguments[0] as String] = it.arguments[1]; editor }
        whenever(editor.putLong(any(), any())).thenAnswer { store[it.arguments[0] as String] = it.arguments[1]; editor }
        whenever(editor.putFloat(any(), any())).thenAnswer { store[it.arguments[0] as String] = it.arguments[1]; editor }
        whenever(editor.putStringSet(any(), anyOrNull())).thenAnswer { store[it.arguments[0] as String] = it.arguments[1]; editor }
        val context = mock<Context>()
        whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
        return Settings(context)
    }

    private fun writeBackupKeys(settings: Settings, suffix: String) {
        settings.micInputSource = 5
        settings.staticBSSID = "aa:bb:cc:dd:ee:$suffix"
        settings.staticP2pBSSID = "11:22:33:44:55:$suffix"
        settings.nativeDriverSelectionMode = NativeDriverSelectionPolicy.Mode.values().first { it != NativeDriverSelectionPolicy.Mode.AUTO }
        settings.nativeDriverSelectionTimeoutSec = NativeDriverSelectionPolicy.DEFAULT_TIMEOUT_SEC + 5
        settings.nativePreferredDeviceMac = "00:11:22:33:44:$suffix"
    }

    private fun fields() = SettingsPendingCopy::class.java.declaredFields
        .filter { !it.isSynthetic && !java.lang.reflect.Modifier.isStatic(it.modifiers) }
        .onEach { it.isAccessible = true }

    @Test fun `a copy loaded from settings shows no change`() {
        val settings = newSettings()
        writeBackupKeys(settings, "01")
        val copy = SettingsPendingCopy()
        copy.loadFrom(settings)
        assertFalse(copy.differsFrom(settings))
        assertEquals(5, copy.micInputSource)
        assertEquals("aa:bb:cc:dd:ee:01", copy.staticBSSID)
        assertEquals("11:22:33:44:55:01", copy.staticP2pBSSID)
        assertEquals(settings.nativeDriverSelectionMode, copy.nativeDriverSelectionMode)
        assertEquals(settings.nativeDriverSelectionTimeoutSec, copy.nativeDriverSelectionTimeout)
        assertEquals("00:11:22:33:44:01", copy.nativePreferredDeviceMac)
    }

    @Test fun `loading fills every field`() {
        val copy = SettingsPendingCopy()
        copy.loadFrom(newSettings())
        for (f in fields()) {
            // Null here means the app draws the pair, so it is a valid loaded value.
            if (f.name == "wifiDirectGroupIdentity") continue
            assertNotNull("${f.name} was not loaded", f.get(copy))
        }
    }

    @Test fun `every field takes part in the comparison`() {
        val settings = newSettings()
        for (f in fields()) {
            val copy = SettingsPendingCopy()
            copy.loadFrom(settings)
            val now = f.get(copy)
            val changed: Any = when {
                now is Boolean -> !now
                now is Int -> now + 1
                now is String -> now + "x"
                now is Set<*> -> now + "x"
                now is Enum<*> -> now.javaClass.enumConstants!!.first { it != now }
                f.type == StoredP2pIdentity::class.java ->
                    if (now == null) StoredP2pIdentity("a", "b") else StoredP2pIdentity("changed", "changed")
                else -> { fail("${f.name} has an unhandled type ${f.type}"); return }
            }
            f.set(copy, changed)
            assertTrue("${f.name} is not compared", copy.differsFrom(settings))
        }
    }

    @Test fun `a reload after a direct write matches the new values`() {
        val settings = newSettings()
        val copy = SettingsPendingCopy()
        copy.loadFrom(settings)
        writeBackupKeys(settings, "02")
        assertTrue(copy.differsFrom(settings))
        copy.loadFrom(settings)
        assertFalse(copy.differsFrom(settings))
    }

    @Test fun `the tab choice is not part of the copy`() {
        assertFalse(fields().any { it.name == "advancedSettings" })
    }
}
