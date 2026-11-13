package com.andrerinas.openheadunit.main

import android.content.Context
import android.view.KeyEvent
import androidx.annotation.StringRes
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.input.InputAction
import com.andrerinas.openheadunit.input.KeyCode
import com.andrerinas.openheadunit.input.LocalAction

/** Every action the shared picker offers, for key bindings and gestures alike. */
object ActionCatalog {

    data class Entry(@StringRes val nameResId: Int, val action: InputAction)

    val localActions = listOf(
        Entry(R.string.gesture_action_exit_dialog, InputAction.Local(LocalAction.EXIT_DIALOG)),
        Entry(R.string.gesture_action_toggle_fullscreen, InputAction.Local(LocalAction.TOGGLE_FULLSCREEN)),
        Entry(R.string.gesture_action_night_overlay, InputAction.Local(LocalAction.NIGHT_OVERLAY)),
        Entry(R.string.gesture_action_dim_screen, InputAction.Local(LocalAction.DIM_SCREEN)),
    )

    val aaKeys = listOf(
        aa(R.string.key_soft_left, KeyEvent.KEYCODE_SOFT_LEFT),
        aa(R.string.key_soft_right, KeyEvent.KEYCODE_SOFT_RIGHT),
        aa(R.string.key_back, KeyEvent.KEYCODE_BACK),
        aa(R.string.key_dpad_up, KeyEvent.KEYCODE_DPAD_UP),
        aa(R.string.key_dpad_down, KeyEvent.KEYCODE_DPAD_DOWN),
        aa(R.string.key_dpad_left, KeyEvent.KEYCODE_DPAD_LEFT),
        aa(R.string.key_dpad_right, KeyEvent.KEYCODE_DPAD_RIGHT),
        aa(R.string.key_dpad_center, KeyEvent.KEYCODE_DPAD_CENTER),
        aa(R.string.key_media_play, KeyEvent.KEYCODE_MEDIA_PLAY),
        aa(R.string.key_media_pause, KeyEvent.KEYCODE_MEDIA_PAUSE),
        aa(R.string.key_media_play_pause, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
        aa(R.string.key_media_next, KeyEvent.KEYCODE_MEDIA_NEXT),
        aa(R.string.key_media_prev, KeyEvent.KEYCODE_MEDIA_PREVIOUS),
        aa(R.string.key_search, KeyEvent.KEYCODE_SEARCH),
        aa(R.string.key_call, KeyEvent.KEYCODE_CALL),
        aa(R.string.key_endcall, KeyEvent.KEYCODE_ENDCALL),
        aa(R.string.key_music, KeyEvent.KEYCODE_MUSIC),
        aa(R.string.key_nav, 65538),
        aa(R.string.key_night, KeyCode.KEY_NIGHT_MODE),
        aa(R.string.key_enter, KeyEvent.KEYCODE_ENTER),
        aa(R.string.key_home, KeyEvent.KEYCODE_HOME),
        aa(R.string.key_app_switch, KeyEvent.KEYCODE_APP_SWITCH),
        aa(R.string.key_button_a, KeyEvent.KEYCODE_BUTTON_A),
        aa(R.string.key_button_b, KeyEvent.KEYCODE_BUTTON_B),
    )

    private fun aa(@StringRes nameResId: Int, keyCode: Int) = Entry(nameResId, InputAction.AaKey(keyCode))

    fun label(context: Context, action: InputAction): String = when (action) {
        InputAction.None -> context.getString(R.string.gesture_action_disabled)
        is InputAction.Local -> context.getString(localActions.first { it.action == action }.nameResId)
        is InputAction.AaKey -> aaKeys.firstOrNull { it.action == action }?.let { context.getString(it.nameResId) }
            ?: context.getString(R.string.action_aa_key_code, action.keyCode)
        is InputAction.LaunchApp ->
            context.getString(R.string.action_launch_named_app, AppLauncher.label(context, action.target))
    }
}
