package com.andrerinas.openheadunit.connection.carkey

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.contract.KeyIntent
import com.andrerinas.openheadunit.input.LockedInputPolicy
import com.andrerinas.openheadunit.input.ProjectionKeyPolicy
import com.andrerinas.openheadunit.utils.AppLog

/**
 * A comprehensive receiver for steering wheel and hardware buttons,
 * covering both standard Android events and proprietary Chinese headunit broadcasts.
 */
class CarKeyBroadcastReceiver : BroadcastReceiver(), CarKeyReceiver {

    companion object {
        val ACTIONS = arrayOf(
            "android.intent.action.MEDIA_BUTTON",
            "hy.intent.action.MEDIA_BUTTON", // Huayu / Hyundai Protocol
            "geely.intent.action.MEDIA_BUTTON", // Geely / Flyme Auto
            "ecarx.intent.action.MEDIA_BUTTON", // ECARX
            "com.ecarx.intent.action.MEDIA_BUTTON",
            "com.geely.intent.action.MEDIA_BUTTON",
            "com.ecarx.media.action.KEY_EVENT",
            "com.geely.car.media.action.KEY_EVENT",
            "com.nwd.action.ACTION_KEY_VALUE", // NWD (NewWell)
            "com.microntek.irkeyUp", // Microntek (MTCE/MTCB)
            "com.microntek.irkeyDown",
            "com.winca.service.Setting.KEY_ACTION", // Winca
            "android.intent.action.C3_HARDKEY", // FlyAudio / C3
            "IKeyClick.KEY_CLICK",
            "com.eryanet.music.prev", // Eryanet (Eonon etc.)
            "com.eryanet.music.next",
            "com.eryanet.media.playorpause",
            "com.eryanet.media.play",
            "com.eryanet.media.pause",
            "com.bz.action.phone.pickup", // BZ (Joying etc.)
            "com.bz.action.phone.hangup",
            "com.tencent.qqmusiccar.action.MEDIA_BUTTON_INNER_ONKEY",
            "cn.kuwo.kwmusicauto.action.MEDIA_BUTTON"
        )

        private fun isMediaButtonAction(action: String): Boolean =
            action.endsWith("MEDIA_BUTTON") || action.endsWith("KEY_EVENT")
                || action == "com.tencent.qqmusiccar.action.MEDIA_BUTTON_INNER_ONKEY"
                || action == "cn.kuwo.kwmusicauto.action.MEDIA_BUTTON"

        /** The key an intent carries, read with no side effect. Virtual ids and commands give null. */
        internal fun keyCodeIn(action: String, intent: Intent): Int? {
            if (isMediaButtonAction(action)) {
                val event = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                if (event != null) return event.keyCode
                if ((intent.getStringExtra("command") ?: intent.getStringExtra("cmd")) != null) return null
                return intent.getIntExtra("keycode", -1).takeIf { it > 0 }
                    ?: intent.getIntExtra("key_code", -1).takeIf { it > 0 }
            }
            return when (action) {
                "com.microntek.irkeyDown", "com.microntek.irkeyUp" ->
                    intent.getIntExtra("keyCode", -1).takeIf { it != -1 }
                "android.intent.action.C3_HARDKEY" -> {
                    val keyCode = intent.getIntExtra("android.intent.extra.c3_hardkey_keycode", -1)
                    val c3Action = intent.getIntExtra("android.intent.extra.c3_hardkey_action", -1)
                    keyCode.takeIf { it != -1 && c3Action != -1 }
                }
                "com.winca.service.Setting.KEY_ACTION" ->
                    intent.getIntExtra("com.winca.service.Setting.KEY_ACTION_EXTRA", -1).takeIf { it != -1 }
                "IKeyClick.KEY_CLICK" -> intent.getIntExtra("CLICK_KEY", -1).takeIf { it != -1 }
                else -> null
            }
        }
    }

    private var context: Context? = null

    override val isSupported = true
    override val isSUNeeded = false

    override fun register(context: Context) {
        this.context = context

        val filter = IntentFilter().apply {
            priority = 1000
            ACTIONS.forEach { addAction(it) }
        }

        ContextCompat.registerReceiver(
            context,
            this,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun unregister() {
        this.context?.unregisterReceiver(this)
        this.context = null
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        AppLog.i("CarKeyReceiver: Handling intent action: $action")

        // Broadcast for KeymapFragment debugger (raw intent data)
        context.sendBroadcast(Intent("com.andrerinas.openheadunit.DEBUG_KEY").apply {
            setPackage(context.packageName)
            putExtra("action", action)
            intent.extras?.let { putExtras(it) }
        })

        // Abort so the built-in radio stays quiet, only during a session and never for a key
        // the firmware must act on.
        if (isOrderedBroadcast) {
            val app = App.provide(context)
            val keepsKey = ProjectionKeyPolicy.firmwareKeepsKey(keyCodeIn(action, intent),
                app.settings.keyBindings, app.settings.volumeKeysToPhone, LockedInputPolicy.engaged)
            if (app.commManager.isConnected && !keepsKey) {
                abortBroadcast()
            }
        }

        // 1. Standard / OEM Media Button extraction (already has KeyEvent with proper DOWN/UP)
        if (isMediaButtonAction(action)) {
            val event = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            if (event != null) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    handleKey(context, event.keyCode, true)
                } else if (event.action == KeyEvent.ACTION_UP) {
                    handleKey(context, event.keyCode, false)
                }
            } else {
                val cmd = intent.getStringExtra("command") ?: intent.getStringExtra("cmd")
                val key = intent.getIntExtra("keycode", -1).takeIf { it > 0 }
                    ?: intent.getIntExtra("key_code", -1).takeIf { it > 0 }

                if (cmd != null) {
                    when (cmd.lowercase()) {
                        "next", "skip_next", "skip" -> handleClick(context, KeyEvent.KEYCODE_MEDIA_NEXT)
                        "previous", "skip_previous", "prev" -> handleClick(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                        "play", "start" -> handleClick(context, KeyEvent.KEYCODE_MEDIA_PLAY)
                        "pause" -> handleClick(context, KeyEvent.KEYCODE_MEDIA_PAUSE)
                        "togglepause", "playpause" -> handleClick(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    }
                } else if (key != null) {
                    handleClick(context, key)
                }
            }
            return
        }

        // 2. Proprietary extraction — extract keycode or use virtual IDs for mapping
        when (action) {
            // --- Protocols with proper DOWN/UP separation ---
            "com.microntek.irkeyDown", "com.microntek.irkeyUp" -> {
                val keyCode = intent.getIntExtra("keyCode", -1)
                if (keyCode != -1) handleKey(context, keyCode, action.endsWith("keyDown"))
            }
            "android.intent.action.C3_HARDKEY" -> {
                val keyCode = intent.getIntExtra("android.intent.extra.c3_hardkey_keycode", -1)
                val c3Action = intent.getIntExtra("android.intent.extra.c3_hardkey_action", -1)
                if (keyCode != -1 && c3Action != -1) handleKey(context, keyCode, c3Action == 0)
            }

            // --- Protocols that fire once (no DOWN/UP) → use virtual IDs for mapping ---
            "com.nwd.action.ACTION_KEY_VALUE" -> {
                val value = intent.getByteExtra("extra_key_value", 0).toInt()
                if (value != 0) handleClick(context, 1000 + value)
            }
            "com.winca.service.Setting.KEY_ACTION" ->
                intent.getIntExtra("com.winca.service.Setting.KEY_ACTION_EXTRA", -1)
                    .takeIf { it != -1 }?.let { handleClick(context, it) }
            "IKeyClick.KEY_CLICK" ->
                intent.getIntExtra("CLICK_KEY", -1)
                    .takeIf { it != -1 }?.let { handleClick(context, it) }
            "com.eryanet.music.prev" -> handleClick(context, 2001)
            "com.eryanet.music.next" -> handleClick(context, 2002)
            "com.eryanet.media.playorpause" -> handleClick(context, 2003)
            "com.eryanet.media.play" -> handleClick(context, 2004)
            "com.eryanet.media.pause" -> handleClick(context, 2005)
            "com.bz.action.phone.pickup" -> handleClick(context, 3001)
            "com.bz.action.phone.hangup" -> handleClick(context, 3002)
        }
    }
}
