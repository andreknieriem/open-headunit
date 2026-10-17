package com.andrerinas.openheadunit.aap

import android.content.Context
import android.content.SharedPreferences
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.decoder.audio.AudioSinkCodec
import com.andrerinas.openheadunit.utils.SettingsBackupManager
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class AudioCodecSettingsTest {
    @Test fun `missing option keeps uniform codec and existing AAC preference survives opt-in`() {
        for (savedAac in listOf(false, true)) {
            val context = mock(Context::class.java)
            val prefs = mock(SharedPreferences::class.java)
            val editor = mock(SharedPreferences.Editor::class.java)
            val booleans = mutableMapOf("use-aac-audio" to savedAac)
            `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs)
            `when`(prefs.getBoolean(anyString(), anyBoolean())).thenAnswer {
                booleans[it.getArgument<String>(0)] ?: it.getArgument<Boolean>(1)
            }
            `when`(prefs.getInt(anyString(), anyInt())).thenAnswer { it.getArgument<Int>(1) }
            `when`(prefs.getString(anyString(), nullable(String::class.java))).thenAnswer { it.getArgument<String?>(1) }
            `when`(prefs.edit()).thenReturn(editor)
            `when`(editor.putBoolean(anyString(), anyBoolean())).thenAnswer {
                booleans[it.getArgument<String>(0)] = it.getArgument(1); editor
            }
            val settings = Settings(context)
            assertFalse(settings.usePcmGuidance)
            assertFalse(settings.prefer48kGuidance)
            val initial = AudioSessionConfig.from(settings)
            val channels = listOf(Channel.ID_AUD, Channel.ID_AU1, Channel.ID_AU2)
            channels.forEach { assertEquals(savedAac, initial.codecFor(it).isAac) }

            settings.usePcmGuidance = true
            assertEquals(savedAac, settings.useAacAudio)
            val mixed = AudioSessionConfig.from(settings)
            assertNotEquals(initial, mixed)
            assertEquals(savedAac, mixed.codecFor(Channel.ID_AUD).isAac)
            assertEquals(AudioSinkCodec.PCM, mixed.codecFor(Channel.ID_AU1))
            assertEquals(AudioSinkCodec.PCM, mixed.codecFor(Channel.ID_AU2))
            // The previous session is immutable until negotiation replaces it.
            channels.forEach { assertEquals(savedAac, initial.codecFor(it).isAac) }
            settings.prefer48kGuidance = true
            val highRate = AudioSessionConfig.from(settings)
            assertNotEquals(mixed, highRate)
            assertEquals(listOf(0, 1), highRate.configurationIndicesFor(4, true, AudioSinkCodec.PCM))
            assertEquals(listOf(0), mixed.configurationIndicesFor(4, true, AudioSinkCodec.PCM))
            settings.usePcmGuidance = false
            assertEquals(listOf(0), AudioSessionConfig.from(settings).configurationIndicesFor(4, true, AudioSinkCodec.PCM))
            assertTrue(settings.prefer48kGuidance)
            assertEquals(initial, AudioSessionConfig.from(settings))
            settings.prefer48kGuidance = false
            assertEquals(initial, AudioSessionConfig.from(settings))
        }
    }

    @Test fun `reset removes the mixed choice and requires fresh projection negotiation`() {
        val prefs = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        `when`(prefs.all).thenReturn(mapOf("use-pcm-guidance" to true, "prefer-48k-guidance" to true))
        `when`(prefs.edit()).thenReturn(editor)
        `when`(editor.commit()).thenReturn(true)
        assertEquals(SettingsBackupManager.ValueType.BOOLEAN, SettingsBackupManager.backupKeys["use-pcm-guidance"])
        val result = SettingsBackupManager.resetPreferencesToDefaults(prefs)
        assertEquals(setOf("use-pcm-guidance", "prefer-48k-guidance"), result.changedKeys)
        assertEquals(SettingsBackupManager.ValueType.BOOLEAN, SettingsBackupManager.backupKeys["prefer-48k-guidance"])
        assertTrue(SettingsBackupManager.requiresProjectionRestart(setOf("prefer-48k-guidance")))
        verify(editor).remove("prefer-48k-guidance")
        assertTrue(SettingsBackupManager.requiresProjectionRestart(result.changedKeys))
        verify(editor).remove("use-pcm-guidance")
    }

}
