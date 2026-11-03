package com.andrerinas.openheadunit.main

import android.content.Context
import android.content.SharedPreferences
import com.andrerinas.openheadunit.aap.GalLaboratoryOptions
import com.andrerinas.openheadunit.aap.GalLaboratoryOptions.Mode
import com.andrerinas.openheadunit.aap.GalProfile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
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
    private fun newSettings(store: MutableMap<String, Any?> = HashMap()): Settings {
        val editor = mock<SharedPreferences.Editor>()
        val prefs = mock<SharedPreferences>()
        whenever(prefs.edit()).thenReturn(editor)
        whenever(prefs.getString(any(), anyOrNull())).thenAnswer { (store[it.arguments[0]] as String?) ?: it.arguments[1] }
        whenever(prefs.getInt(any(), any())).thenAnswer { (store[it.arguments[0]] as Int?) ?: it.arguments[1] }
        whenever(prefs.getBoolean(any(), any())).thenAnswer { (store[it.arguments[0]] as Boolean?) ?: it.arguments[1] }
        whenever(prefs.getLong(any(), any())).thenAnswer { (store[it.arguments[0]] as Long?) ?: it.arguments[1] }
        whenever(prefs.getFloat(any(), any())).thenAnswer { (store[it.arguments[0]] as Float?) ?: it.arguments[1] }
        whenever(prefs.contains(any())).thenAnswer { store.containsKey(it.arguments[0]) }
        whenever(prefs.all).thenAnswer { store.toMap() }
        whenever(editor.putString(any(), anyOrNull())).thenAnswer { store[it.arguments[0] as String] = it.arguments[1]; editor }
        whenever(editor.putInt(any(), any())).thenAnswer { store[it.arguments[0] as String] = it.arguments[1]; editor }
        whenever(editor.putBoolean(any(), any())).thenAnswer { store[it.arguments[0] as String] = it.arguments[1]; editor }
        whenever(editor.putLong(any(), any())).thenAnswer { store[it.arguments[0] as String] = it.arguments[1]; editor }
        whenever(editor.putFloat(any(), any())).thenAnswer { store[it.arguments[0] as String] = it.arguments[1]; editor }
        whenever(editor.clear()).thenAnswer { store.clear(); editor }
        whenever(editor.remove(any())).thenAnswer { store.remove(it.arguments[0]); editor }
        whenever(editor.commit()).thenReturn(true)
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

    @Test fun `GAL defaults to compatibility and persists every candidate version`() {
        val store = HashMap<String, Any?>()
        val settings = newSettings(store)
        assertEquals("1.2", settings.galProtocolVersion)
        for (version in listOf("1.2", "1.6", "5.0", "5.1", "6.0", "6.1", GalProfile.AUTO_SETTING)) {
            settings.galProtocolVersion = version
            assertEquals(version, store["gal-protocol-version"])
            assertEquals(version, newSettings(store).galProtocolVersion)
        }
    }

    @Test fun `invalid saved GAL values fall back to compatibility`() {
        for (invalid in listOf(null, "", "1.8", "6.2", "5", "invalid", 61)) {
            val settings = newSettings(hashMapOf("gal-protocol-version" to invalid))
            assertEquals("1.2", settings.galProtocolVersion)
        }
    }

    @Test fun `invalid GAL writes persist compatibility`() {
        val store = HashMap<String, Any?>()
        val settings = newSettings(store)
        settings.galProtocolVersion = "unsupported"
        assertEquals("1.2", store["gal-protocol-version"])
        assertEquals("1.2", settings.galProtocolVersion)
    }

    @Test fun `Laboratory selection saves independently without overwriting parent pending edits`() {
        val settings = newSettings()
        val parent = SettingsPendingCopy().apply { loadFrom(settings) }
        parent.fpsLimit = settings.fpsLimit + 1
        val child = LaboratoryPendingCopy.from(settings)
        child.selectVersion("6.1")
        child.options = child.options.copy(avSync = Mode.ON, syncOffsetMs = -20)
        assertTrue(child.differsFrom(settings))
        assertEquals("1.2", settings.galProtocolVersion)
        child.saveTo(settings)
        assertFalse(child.differsFrom(settings))
        assertEquals("6.1", settings.galProtocolVersion)
        assertEquals(-20, settings.galLaboratoryOptions.syncOffsetMs)
        assertTrue(parent.differsFrom(settings))
        assertEquals(settings.fpsLimit + 1, parent.fpsLimit)
    }

    @Test fun `discarded Laboratory edits leave saved version and options unchanged`() {
        val settings = newSettings()
        val original = LaboratoryPendingCopy.from(settings)
        val discarded = LaboratoryPendingCopy.from(settings)
        discarded.selectVersion("6.1")
        discarded.options = discarded.options.copy(audioBuffer = Mode.ON, audioBufferLimitMs = 200)
        assertTrue(discarded.differsFrom(settings))
        assertEquals(original, LaboratoryPendingCopy.from(settings))
    }

    @Test fun `serialized Laboratory pending state restores unsaved edits and can save`() {
        val settings = newSettings()
        val child = LaboratoryPendingCopy.from(settings)
        child.selectVersion(GalProfile.AUTO_SETTING)
        child.options = child.options.copy(mediaStats = Mode.ON, statisticsIntervalMs = 750)
        val bytes = ByteArrayOutputStream().also { output ->
            ObjectOutputStream(output).use { it.writeObject(child) }
        }.toByteArray()
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as LaboratoryPendingCopy }
        assertEquals(child, restored)
        assertTrue(restored.differsFrom(settings))
        restored.saveTo(settings)
        assertEquals(GalProfile.AUTO_SETTING, settings.galProtocolVersion)
        assertEquals(restored, LaboratoryPendingCopy.from(settings))
    }

    @Test fun `profile picker downgrade preserves options and disables all dependent numbers`() {
        val child = LaboratoryPendingCopy("6.1", GalLaboratoryOptions(videoTiming = Mode.ON, videoMinDelayMs = 12, videoMaxDelayMs = 50))
        val original = child.options
        child.selectVersion("1.2")
        assertEquals(original, child.options)
        LaboratoryNumber.entries.forEach { assertFalse(child.numericEnabled(it)) }
        assertFalse(child.setNumber(LaboratoryNumber.VIDEO_MIN, "20"))
        child.selectVersion("6.1")
        assertEquals(original, child.options)
        assertTrue(child.numericEnabled(LaboratoryNumber.VIDEO_MIN))
        assertTrue(child.setNumber(LaboratoryNumber.VIDEO_MIN, "20"))
        assertEquals(20, child.options.videoMinDelayMs)
    }

    @Test fun `numeric inputs inherit parent mode and reject invalid values without mutation`() {
        val child = LaboratoryPendingCopy("5.0", GalLaboratoryOptions())
        LaboratoryNumber.entries.forEach { field ->
            assertTrue(child.numericEnabled(field))
            assertTrue(child.setNumber(field, field.range.first.toString()))
            val accepted = child.options
            assertFalse(child.setNumber(field, (field.range.first - 1).toString()))
            assertFalse(child.setNumber(field, (field.range.last + 1).toString()))
            assertFalse(child.setNumber(field, "2147483648"))
            assertFalse(child.setNumber(field, "1.5"))
            assertEquals(accepted, child.options)
            assertTrue(child.setNumber(field, ""))
        }
        child.options = child.options.copy(avSync = Mode.OFF, videoTiming = Mode.OFF, audioBuffer = Mode.OFF, mediaStats = Mode.OFF)
        LaboratoryNumber.entries.forEach {
            assertFalse(child.numericEnabled(it))
            assertFalse(child.setNumber(it, it.range.first.toString()))
        }
    }

    @Test fun `video minimum and maximum must be ordered and local reset retains version`() {
        val child = LaboratoryPendingCopy("6.0", GalLaboratoryOptions(videoMinDelayMs = 10, videoMaxDelayMs = 40))
        assertFalse(child.setNumber(LaboratoryNumber.VIDEO_MIN, "41"))
        assertFalse(child.setNumber(LaboratoryNumber.VIDEO_MAX, "9"))
        assertEquals(10, child.options.videoMinDelayMs)
        assertEquals(40, child.options.videoMaxDelayMs)
        assertTrue(child.setNumber(LaboratoryNumber.VIDEO_MIN, "40"))
        child.resetOptions()
        assertEquals("6.0", child.version)
        assertEquals(GalLaboratoryOptions(), child.options)
    }

    @Test fun `Laboratory save survives settings recreation and reset removes numeric overrides`() {
        val store = HashMap<String, Any?>()
        val settings = newSettings(store)
        val child = LaboratoryPendingCopy("6.0", GalLaboratoryOptions(
            guidance48Khz = false, modernDisplayConfig = false, audioUpdates = false, videoUpdates = false,
            avSync = Mode.ON, videoTiming = Mode.OFF, audioBuffer = Mode.ON, mediaStats = Mode.OFF,
            maxLagToleranceMs = 50, syncIntervalMs = 100, maxAudioDelayMs = 40, maxVideoDelayMs = 30,
            averageLagWeight = 250, syncOffsetMs = -12, videoMinDelayMs = 10, videoMaxDelayMs = 20,
            audioBufferLimitMs = 200, statisticsIntervalMs = 1000))
        child.saveTo(settings)
        assertEquals(child, LaboratoryPendingCopy.from(newSettings(store)))
        child.resetOptions()
        child.saveTo(settings)
        assertEquals(GalLaboratoryOptions(), newSettings(store).galLaboratoryOptions)
        assertFalse(store.containsKey("gal-laboratory-syncOffsetMs"))
    }

    @Test fun `Laboratory version choices preserve manual mapping and select auto by value`() {
        assertEquals(listOf("1.2", "1.6", "5.0", "5.1", "6.0", "6.1", GalProfile.AUTO_SETTING),
            LaboratoryPendingCopy.versionChoices)
        val child = LaboratoryPendingCopy.from(newSettings())
        for (version in LaboratoryPendingCopy.versionChoices) {
            child.selectVersion(version)
            assertEquals(version, child.version)
            assertEquals(GalProfile.fromSetting(version), child.profile)
        }
        child.selectVersion("unsupported")
        assertEquals("1.2", child.version)
    }

    @Test fun `auto uses highest UI capabilities without changing overrides or saved selection`() {
        val store = HashMap<String, Any?>()
        val settings = newSettings(store)
        val child = LaboratoryPendingCopy("1.2", GalLaboratoryOptions(
            guidance48Khz = false, modernDisplayConfig = false, audioUpdates = false, videoUpdates = false,
            avSync = Mode.ON, videoTiming = Mode.ON, audioBuffer = Mode.ON, mediaStats = Mode.ON,
            syncOffsetMs = -25, videoMinDelayMs = 10, videoMaxDelayMs = 40,
            audioBufferLimitMs = 200, statisticsIntervalMs = 750))
        val overrides = child.options
        child.selectVersion(GalProfile.AUTO_SETTING)
        assertEquals(overrides, child.options)
        assertEquals(LaboratoryPendingCopy("6.1", overrides).capabilities, child.capabilities)
        LaboratoryNumber.entries.forEach { assertTrue(child.numericEnabled(it)) }
        child.saveTo(settings)
        val restored = LaboratoryPendingCopy.from(newSettings(store))
        assertEquals(child, restored)
        assertEquals(GalProfile.AUTO_SETTING, store["gal-protocol-version"])
        restored.selectVersion("1.2")
        LaboratoryNumber.entries.forEach { assertFalse(restored.numericEnabled(it)) }
        restored.selectVersion(GalProfile.AUTO_SETTING)
        assertEquals(overrides, restored.options)
        restored.resetOptions()
        assertEquals(GalProfile.AUTO_SETTING, restored.version)
        restored.saveTo(settings)
        val reset = LaboratoryPendingCopy.from(newSettings(store))
        assertEquals(GalProfile.AUTO_SETTING, reset.version)
        assertEquals(GalLaboratoryOptions(), reset.options)
        assertFalse(store.containsKey("gal-laboratory-syncOffsetMs"))
    }

    @Test fun `malformed Laboratory preferences restore bounded usable editor values`() {
        val settings = newSettings(hashMapOf(
            "gal-laboratory-guidance48Khz" to "false",
            "gal-laboratory-avSync" to 1,
            "gal-laboratory-audioBuffer" to "invalid",
            "gal-laboratory-syncOffsetMs" to -999,
            "gal-laboratory-maxAudioDelayMs" to "20",
            "gal-laboratory-videoMinDelayMs" to 50,
            "gal-laboratory-videoMaxDelayMs" to 10))
        val child = LaboratoryPendingCopy.from(settings)
        assertTrue(child.options.guidance48Khz)
        assertEquals(Mode.AUTO, child.options.avSync)
        assertEquals(Mode.AUTO, child.options.audioBuffer)
        assertEquals(-500, child.options.syncOffsetMs)
        assertEquals(null, child.options.maxAudioDelayMs)
        assertEquals(50, child.options.videoMinDelayMs)
        assertEquals(50, child.options.videoMaxDelayMs)
    }

    @Test fun `the tab choice is not part of the copy`() {
        assertFalse(fields().any { it.name == "advancedSettings" })
    }
}
