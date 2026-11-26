package com.andrerinas.openheadunit.connection.carkey.dynavin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.connection.carkey.CarKeyReceiver
import com.andrerinas.openheadunit.input.DynavinPanelKey
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Panel keys and the MMI knob on Dynavin units. Registered only where the `dynavin_mcu` service
 * exists. The MCU never sends the joystick releases, so the joystick comes from MCU key frames.
 */
class CarDynavinReceiver : BroadcastReceiver(), CarKeyReceiver {

    private val main = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var mcu: IBinder? = null
    @Volatile private var mcuActive = false

    private val listener = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != 1) return super.onTransact(code, data, reply, flags)
            try {
                data.enforceInterface(LISTENER)
                if (data.readInt() != 0) {
                    val cmd = data.readInt() and 0x1FFFFFFF
                    data.readInt()
                    val frame = data.createByteArray()
                    val key = if (cmd == E_PROTOS_KEY) DynavinPanelKey.mcuKey(frame) else null
                    if (key != null) main.post { onKey(key, "mcu") }
                }
                if (flags and FLAG_ONEWAY == 0) reply?.writeNoException()
            } catch (e: Exception) {
                AppLog.w("CarDynavinReceiver: Unreadable MCU frame: ${e.message}")
            }
            return true
        }
    }.apply { attachInterface(null, LISTENER) }

    override val isSupported: Boolean get() = service(MCU_SERVICE) != null
    override val isSUNeeded = false

    override fun register(context: Context) {
        // Exported because the sender is the vendor's service, as with the other OEM key actions.
        ContextCompat.registerReceiver(
            context, this, IntentFilter(DynavinPanelKey.ACTION), ContextCompat.RECEIVER_EXPORTED,
        )
        this.context = context
        val binder = service(MCU_SERVICE)
        try {
            if (binder != null) {
                callMcu(binder, TXN_ADD_LISTENER, context.packageName)
                mcu = binder
                mcuActive = true
            }
        } catch (e: Exception) {
            AppLog.w("CarDynavinReceiver: MCU key listener refused, knob joystick off: ${e.message}")
        }
        AppLog.i("CarDynavinReceiver: Listening for Dynavin keys (MCU frames ${if (mcuActive) "on" else "off"})")
    }

    override fun unregister() {
        mcuActive = false
        val ctx = context
        mcu?.let { binder ->
            try {
                callMcu(binder, TXN_REMOVE_LISTENER, ctx?.packageName ?: "")
            } catch (e: Exception) {
                AppLog.w("CarDynavinReceiver: MCU listener removal failed: ${e.message}")
            }
        }
        mcu = null
        context = null
        ctx?.unregisterReceiver(this)
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != DynavinPanelKey.ACTION) return
        val code = intent.getIntExtra(DynavinPanelKey.EXTRA_KEYCODE, 0)
        if (code <= 0) return
        // The MCU frames already carried this key, on press.
        if (mcuActive && DynavinPanelKey.isMcuKey(code)) return
        onKey(code, "broadcast")
    }

    private fun onKey(dynavinCode: Int, via: String) {
        val ctx = context ?: return
        // The car's own picture is on screen, so the panel keys belong to the car.
        if (carScreenShown()) return
        val virtual = DynavinPanelKey.toKeyCode(dynavinCode)
        val send = DynavinPanelKey.sendCode(dynavinCode, App.provide(ctx).settings.keyBindings)
        AppLog.i("CarDynavinReceiver: Key $dynavinCode via $via -> keycode $virtual, sends $send")
        showRawKey(ctx, DynavinPanelKey.ACTION, virtual, "dynavin_code" to dynavinCode.toString(), "via" to via)
        handleClick(ctx, virtual, send, ROUTE, SystemClock.uptimeMillis().takeIf { DynavinPanelKey.isKnobTurn(dynavinCode) })
    }

    /** IDynavinMcuService: 1 adds a key listener, 2 removes it. */
    private fun callMcu(binder: IBinder, txn: Int, packageName: String) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(MCU_INTERFACE)
            data.writeStrongBinder(listener)
            data.writeString(packageName)
            binder.transact(txn, data, reply, 0)
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Reads the vendor's "car screen shown" mark. Any failure counts as not shown. */
    private fun carScreenShown(): Boolean {
        val binder = service(PROP_SERVICE) ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(PROP_INTERFACE)
            data.writeString(PROP_CAR_SCREEN)
            binder.transact(TXN_PROP_GET, data, reply, 0)
            reply.readException()
            reply.readString() == "true"
        } catch (e: Exception) {
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun service(name: String): IBinder? = try {
        Class.forName("android.os.ServiceManager").getMethod("checkService", String::class.java)
            .invoke(null, name) as? IBinder
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val ROUTE = "carkey-dynavin"
        const val MCU_SERVICE = "dynavin_mcu"
        const val MCU_INTERFACE = "android.dynavin.mcu.IDynavinMcuService"
        const val LISTENER = "android.dynavin.mcu.IMcuListener"
        const val E_PROTOS_KEY = 0x10500
        const val TXN_ADD_LISTENER = 1
        const val TXN_REMOVE_LISTENER = 2
        const val PROP_SERVICE = "dynavin_prop"
        const val PROP_INTERFACE = "android.dynavin.prop.IDynavinPropService"
        const val PROP_CAR_SCREEN = "dynavin_mark_mcu_heartbeat_lvdsInCar"
        const val TXN_PROP_GET = 4
    }
}
