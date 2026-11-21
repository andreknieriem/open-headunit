package com.andrerinas.openheadunit.connection.carkey

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.connection.carkey.byd.CarBydReceiver
import com.andrerinas.openheadunit.connection.carkey.dongfeng.CarDongfengReceiver
import com.andrerinas.openheadunit.connection.carkey.dynavin.CarDynavinReceiver
import com.andrerinas.openheadunit.connection.carkey.fyt.CarFYTReceiver
import com.andrerinas.openheadunit.connection.carkey.ola.OlaVolumeReceiver
import com.andrerinas.openheadunit.connection.carkey.qf.CarQfReceiver
import com.andrerinas.openheadunit.connection.carkey.roadrover.CarRoadroverReceiver
import com.andrerinas.openheadunit.connection.carkey.topway.CarTopwayReceiver
import com.andrerinas.openheadunit.contract.KeyIntent
import com.andrerinas.openheadunit.input.KeyLearnPolicy
import com.andrerinas.openheadunit.utils.AppLog

interface CarKeyReceiver {

    companion object {
        /** The source of a car key. A vendor route names itself as `carkey-<vendor>`. */
        const val SOURCE = "carkey"
        const val DEBUG_KEY_ACTION = "com.andrerinas.openheadunit.DEBUG_KEY"

        @JvmStatic
        fun newDefaultReceivers(): Array<CarKeyReceiver> {
            return arrayOf(
                CarKeyBroadcastReceiver(),
                CarBydReceiver(),
                CarFYTReceiver(),
                CarRoadroverReceiver(),
                CarDynavinReceiver(),
                CarDongfengReceiver(),
                CarTopwayReceiver(),
                CarQfReceiver(),
                OlaVolumeReceiver(),
            )
        }
    }

    val isSupported: Boolean

    val isSUNeeded: Boolean

    /**
     * Whether this receiver requires an active Android Auto projection session to be registered.
     * Receivers that aggressively grab hardware focus (e.g., FYT binding IPC and setting sys.carlink.type=2)
     * MUST be session-scoped so factory apps retain steering wheel control while OpenHU is idle.
     */
    val isSessionScoped: Boolean
        get() = false

    /** Whether the keymap screen may register this session-scoped receiver while no session runs. */
    val servesLearning: Boolean
        get() = true

    @Throws(Exception::class)
    fun register(context: Context)

    @Throws(Exception::class)
    fun unregister()


    /** Whether this unit runs the vendor this receiver serves. Absent, the receiver is never registered. */
    fun isSupportedOn(context: Context): Boolean = isSupported

    /**
     * Single key press or release: feeds the keymap screen, then goes to `CommManager.sendKey`.
     * [keyCode] is what the keymap screen sees; [sendCode] is what goes on.
     */
    fun handleKey(
        context: Context, keyCode: Int, isDown: Boolean, sendCode: Int = keyCode,
        source: String = SOURCE, downTime: Long? = null,
    ) {
        AppLog.d("CarKeyReceiver: Broadcasting key event: code=$keyCode, isDown=$isDown, src=$source")
        context.sendBroadcast(
            Intent(KeyIntent.action).apply {
                setPackage(context.packageName)
                putExtra(KeyLearnPolicy.EXTRA_ROUTE, source)
                putExtra(
                    KeyIntent.extraEvent,
                    KeyEvent(
                        if (isDown) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, keyCode,
                    ),
                )
            },
        )
        App.provide(context).commManager.sendKey(sendCode, isDown, downTime, source)
    }

    /** Full click (DOWN + UP). A [downTime] marks a new press, so fast clicks are not dropped as copies. */
    fun handleClick(
        context: Context, keyCode: Int, sendCode: Int = keyCode,
        source: String = SOURCE, downTime: Long? = null,
    ) {
        handleKey(context, keyCode, true, sendCode, source, downTime)
        handleKey(context, keyCode, false, sendCode, source, downTime)
    }

    /** Shows a vendor key's raw data on the keymap screen's tester, before it is mapped. */
    fun showRawKey(context: Context, action: String, keyCode: Int, vararg detail: Pair<String, String>) {
        context.sendBroadcast(
            Intent(DEBUG_KEY_ACTION).apply {
                setPackage(context.packageName)
                putExtra("action", action)
                putExtra("keyCode", keyCode)
                detail.forEach { (name, value) -> putExtra(name, value) }
            },
        )
    }

    fun toggleVoiceAssistant(context: Context) {
        App.provide(context).commManager.sendToggleVoiceAssistant()
    }
}
