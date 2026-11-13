package com.andrerinas.openheadunit.input

import android.view.KeyEvent
import com.andrerinas.openheadunit.input.KeyBindingPolicy.Resolution
import com.andrerinas.openheadunit.main.FavoriteTarget
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

class InputActionTest {

    private fun roundTrip(action: InputAction) =
        assertEquals(action, InputActionCodec.decode(InputActionCodec.encode(action)))

    @Test fun everyKindOfActionSurvivesEncoding() {
        roundTrip(InputAction.None)
        roundTrip(InputAction.AaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        roundTrip(InputAction.AaKey(KeyCode.KEY_NIGHT_MODE))
        LocalAction.values().forEach { roundTrip(InputAction.Local(it)) }
        roundTrip(InputAction.LaunchApp(FavoriteTarget.App("com.spotify.music")))
        roundTrip(InputAction.LaunchApp(FavoriteTarget.Shortcut("com.example", "a,b|c%2C")))
    }

    @Test fun theStoredFormIsReadable() {
        assertEquals("key:85", InputActionCodec.encode(InputAction.AaKey(85)))
        assertEquals("local:dim", InputActionCodec.encode(InputAction.Local(LocalAction.DIM_SCREEN)))
        assertEquals("app:com.example", InputActionCodec.encode(InputAction.LaunchApp(FavoriteTarget.App("com.example"))))
    }

    @Test fun anythingUnreadableIsNone() {
        listOf(null, "", "garbage", "key:", "key:abc", "local:", "local:teleport", "app:").forEach {
            assertEquals(it.toString(), InputAction.None, InputActionCodec.decode(it))
        }
        assertEquals("none", InputActionCodec.encode(InputAction.LaunchApp(FavoriteTarget.Empty)))
    }

    @Test fun oldGestureValuesMapToTheSameActions() {
        assertEquals(InputAction.None, Settings.GestureAction.DISABLED.toInputAction())
        assertEquals(InputAction.Local(LocalAction.EXIT_DIALOG), Settings.GestureAction.EXIT_DIALOG.toInputAction())
        assertEquals(InputAction.Local(LocalAction.TOGGLE_FULLSCREEN), Settings.GestureAction.TOGGLE_FULLSCREEN.toInputAction())
        assertEquals(InputAction.Local(LocalAction.NIGHT_OVERLAY), Settings.GestureAction.NIGHT_OVERLAY.toInputAction())
        assertEquals(InputAction.Local(LocalAction.DIM_SCREEN), Settings.GestureAction.DIM_SCREEN.toInputAction())
    }

    @Test fun aaKeysAreSentBothWays() {
        val bindings = mapOf(131 to InputAction.AaKey(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(Resolution.SendToAa(KeyEvent.KEYCODE_MEDIA_NEXT), KeyBindingPolicy.resolve(bindings, 131, true))
        assertEquals(Resolution.SendToAa(KeyEvent.KEYCODE_MEDIA_NEXT), KeyBindingPolicy.resolve(bindings, 131, false))
    }

    @Test fun oneShotActionsRunOnReleaseOnly() {
        val app = InputAction.LaunchApp(FavoriteTarget.App("com.example"))
        val dim = InputAction.Local(LocalAction.DIM_SCREEN)
        val bindings = mapOf(1001 to app, 132 to dim)
        assertEquals(Resolution.Swallow, KeyBindingPolicy.resolve(bindings, 1001, true))
        assertEquals(Resolution.Run(app), KeyBindingPolicy.resolve(bindings, 1001, false))
        assertEquals(Resolution.Swallow, KeyBindingPolicy.resolve(bindings, 132, true))
        assertEquals(Resolution.Run(dim), KeyBindingPolicy.resolve(bindings, 132, false))
    }

    @Test fun unboundKeysPassThrough() {
        assertEquals(Resolution.Unbound, KeyBindingPolicy.resolve(emptyMap(), 66, true))
        assertEquals(Resolution.Unbound, KeyBindingPolicy.resolve(mapOf(66 to InputAction.None), 66, false))
    }

    @Test fun legacyMappingsBecomeBindingsOnTheirPhysicalKey() {
        val legacy = mapOf(
            KeyEvent.KEYCODE_MEDIA_NEXT to 131,
            KeyCode.KEY_NIGHT_MODE to 1001,
            // Two targets learned on one key: the lowest code wins, every time.
            KeyEvent.KEYCODE_DPAD_UP to 140,
            KeyEvent.KEYCODE_MEDIA_PLAY to 140,
        )
        assertEquals(
            mapOf(
                131 to InputAction.AaKey(KeyEvent.KEYCODE_MEDIA_NEXT),
                1001 to InputAction.AaKey(KeyCode.KEY_NIGHT_MODE),
                140 to InputAction.AaKey(KeyEvent.KEYCODE_DPAD_UP),
            ),
            KeyBindingPolicy.fromLegacy(legacy)
        )
    }
}
