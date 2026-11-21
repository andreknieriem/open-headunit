package com.andrerinas.openheadunit.connection.carkey.topway

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.connection.carkey.CarKeyReceiver
import com.andrerinas.openheadunit.input.TopwayKey
import com.andrerinas.openheadunit.utils.AppLog
import java.io.File

/**
 * Wheel and panel keys on Topway / XYAUTO units, from `android.tw.john.TWUtil` on `/dev/tw`.
 * Registered only where both exist, and only during a session or while the keymap screen learns.
 */
class CarTopwayReceiver : CarKeyReceiver {

    private var twUtil: Any? = null
    private var handler: Handler? = null
    private var lastCode = TopwayKey.NO_KEY
    private var lastAtMs = 0L

    override val isSupported: Boolean by lazy {
        try {
            Class.forName(TWUTIL_CLASS)
            File(DEVICE).exists()
        } catch (e: Throwable) {
            false
        }
    }

    override val isSUNeeded = false
    override val isSessionScoped = true

    override fun register(context: Context) {
        if (twUtil != null) return
        val app = context.applicationContext
        var opened: Any? = null
        try {
            val clazz = Class.forName(TWUTIL_CLASS)
            val instance = clazz.getDeclaredConstructor().newInstance()
            val filter = shortArrayOf(TopwayKey.WHAT_KEY_EVENT.toShort(), TopwayKey.WHAT_SYNTH_KEY_EVENT.toShort())
            val result = clazz.getMethod("open", ShortArray::class.java).invoke(instance, filter) as? Int ?: -1
            if (result != 0) {
                AppLog.w("CarTopwayReceiver: TWUtil.open($DEVICE) returned $result")
                return
            }
            opened = instance
            val keys = object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) = onMessage(app, msg)
            }
            clazz.getMethod("start").invoke(instance)
            clazz.getMethod("addHandler", String::class.java, Handler::class.java).invoke(instance, HANDLER_TAG, keys)
            twUtil = instance
            handler = keys
            AppLog.i("CarTopwayReceiver: Listening for Topway keys on $DEVICE")
        } catch (e: Throwable) {
            AppLog.e("CarTopwayReceiver: TWUtil key listener failed", e)
            // An open device left behind would add one more on every later session.
            opened?.let { release(it) }
        }
    }

    override fun unregister() {
        val instance = twUtil ?: return
        twUtil = null
        handler?.removeCallbacksAndMessages(null)
        handler = null
        release(instance)
        AppLog.i("CarTopwayReceiver: Stopped listening for Topway keys")
    }

    private fun release(instance: Any) {
        val clazz = instance.javaClass
        for (call in listOf("removeHandler", "stop", "close")) {
            try {
                if (call == "removeHandler") {
                    clazz.getMethod(call, String::class.java).invoke(instance, HANDLER_TAG)
                } else {
                    clazz.getMethod(call).invoke(instance)
                }
            } catch (e: Throwable) {
                AppLog.w("CarTopwayReceiver: TWUtil.$call failed: $e")
            }
        }
    }

    private fun onMessage(context: Context, msg: Message) {
        val code = TopwayKey.extractKeyCode(msg.what, msg.arg1, msg.arg2)
        if (code == TopwayKey.NO_KEY) return
        val now = SystemClock.elapsedRealtime()
        val repeat = TopwayKey.isRepeat(lastCode, lastAtMs, code, now)
        lastAtMs = TopwayKey.windowStartMs(lastCode, lastAtMs, code, now)
        lastCode = code
        if (repeat) return

        val virtual = TopwayKey.toKeyCode(code)
        val send = TopwayKey.sendCode(code, App.provide(context).settings.keyBindings)
        val name = TopwayKey.nameOf(code) ?: "unnamed"
        AppLog.i("CarTopwayReceiver: Key %s 0x%02X (what=0x%04X arg1=%d) -> keycode %d, sends %d"
            .format(name, code, msg.what, msg.arg1, virtual, send))
        showRawKey(context, "Topway.TWUtil", virtual,
            "topway_code" to "0x%02X (%s)".format(code, name), "what" to "0x%04X".format(msg.what))
        // No press identity: the firmware may also inject this key as a key event, and the copy must merge.
        handleClick(context, virtual, send, ROUTE)
    }

    private companion object {
        const val TWUTIL_CLASS = "android.tw.john.TWUtil"
        const val DEVICE = "/dev/tw"
        const val HANDLER_TAG = "OpenHeadunitKeyRx"
        const val ROUTE = "carkey-topway"
    }
}
