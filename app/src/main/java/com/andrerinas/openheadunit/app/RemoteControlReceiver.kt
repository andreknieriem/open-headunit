package com.andrerinas.openheadunit.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.utils.AppLog

class RemoteControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        AppLog.i("RemoteControlReceiver received: $action")

        // Broadcast for UI debugging (KeymapFragment)
        val debugIntent = Intent("com.andrerinas.openheadunit.DEBUG_KEY").apply {
            putExtra("action", action)
            intent.extras?.let { putExtras(it) }
            setPackage(context.packageName)
        }
        context.sendBroadcast(debugIntent)

        if (Intent.ACTION_MEDIA_BUTTON == action) {
            val event = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
            }
            
            event?.let {
                AppLog.i("ACTION_MEDIA_BUTTON: " + it.keyCode + " (handled via MediaSession)")
                // We no longer send to commManager here to prevent double-skips.
                // The active MediaSession in AapService handles ACTION_MEDIA_BUTTON.

                // Also broadcast for the UI
                val keyIntent = Intent(com.andrerinas.openheadunit.contract.KeyIntent.action).apply {
                    putExtra(com.andrerinas.openheadunit.contract.KeyIntent.extraEvent, it)
                    setPackage(context.packageName)
                }
                context.sendBroadcast(keyIntent)
            }
        } else {
            // Handle command-based intents (common on many Android Headunits)
            val command = intent.getStringExtra("command") ?: intent.getStringExtra("action") ?: intent.getStringExtra("action_command")

            // Broadcast command for UI debug (if not already handled by ACTION_MEDIA_BUTTON block)
            if (action != Intent.ACTION_MEDIA_BUTTON) {
                val debugIntent = Intent("com.andrerinas.openheadunit.DEBUG_KEY").apply {
                    putExtra("action", action)
                    putExtra("command", command)
                    intent.extras?.let { putExtras(it) }
                    setPackage(context.packageName)
                }
                context.sendBroadcast(debugIntent)
            }

            val commManager = App.provide(context).commManager
            if (commManager.connectionState.value !is CommManager.ConnectionState.TransportStarted) {
                AppLog.i("RemoteControlReceiver: Transport not started, skipping command execution")
                return
            }

            when (command) {
                "next", "skip_next", "skip", "forward", "skip_forward" -> {
                    commManager.sendKey(KeyEvent.KEYCODE_MEDIA_NEXT, true, null, "remote-command")
                    commManager.sendKey(KeyEvent.KEYCODE_MEDIA_NEXT, false, null, "remote-command")
                }
                "previous", "skip_previous", "prev", "rewind", "back", "skip_back", "skip_backward" -> {
                    commManager.sendKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, true, null, "remote-command")
                    commManager.sendKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, false, null, "remote-command")
                }
                "play", "start", "resume" -> {
                    commManager.sendKey(KeyEvent.KEYCODE_MEDIA_PLAY, true, null, "remote-command")
                    commManager.sendKey(KeyEvent.KEYCODE_MEDIA_PLAY, false, null, "remote-command")
                }
                "pause", "stop" -> {
                    commManager.sendKey(KeyEvent.KEYCODE_MEDIA_PAUSE, true, null, "remote-command")
                    commManager.sendKey(KeyEvent.KEYCODE_MEDIA_PAUSE, false, null, "remote-command")
                }
                "togglepause", "playpause", "play_pause", "media_play_pause" -> {
                    commManager.sendKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, true, null, "remote-command")
                    commManager.sendKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, false, null, "remote-command")
                }
                "mute", "volume_mute" -> {
                    commManager.sendKey(KeyEvent.KEYCODE_VOLUME_MUTE, true, null, "remote-mute")
                    commManager.sendKey(KeyEvent.KEYCODE_VOLUME_MUTE, false, null, "remote-mute")
                }
                "voice", "mic", "microphone", "search" -> {
                    commManager.sendKey(KeyEvent.KEYCODE_SEARCH, true, null, "remote-command")
                    commManager.sendKey(KeyEvent.KEYCODE_SEARCH, false, null, "remote-command")
                }
                else -> {
                    // Some headunits send a raw keycode as an int extra
                    val extraKeyCode = intent.getIntExtra("keycode", -1)
                        .takeIf { it > 0 }
                        ?: intent.getIntExtra("key_code", -1).takeIf { it > 0 }
                    if (extraKeyCode != null) {
                        AppLog.i("RemoteControlReceiver: raw keycode=$extraKeyCode from command=$command")
                        commManager.sendKey(extraKeyCode, true, null, "remote-keycode")
                        commManager.sendKey(extraKeyCode, false, null, "remote-keycode")
                    } else {
                        AppLog.i("RemoteControlReceiver: Unknown command='$command' from action='$action'")
                    }
                }
            }
        }
    }
}
