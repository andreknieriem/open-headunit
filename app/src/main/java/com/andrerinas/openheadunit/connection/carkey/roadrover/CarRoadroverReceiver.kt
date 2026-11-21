package com.andrerinas.openheadunit.connection.carkey.roadrover

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.connection.carkey.CarKeyReceiver
import com.andrerinas.openheadunit.input.RoadroverKey
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Wheel media keys on Roadrover / Autochips AC8317 units, from the car info service's broadcast.
 * Registered only where that service is installed. Volume and the other keys arrive as key events.
 */
class CarRoadroverReceiver : BroadcastReceiver(), CarKeyReceiver {

    private var context: Context? = null

    override val isSupported = true
    override val isSUNeeded = false

    override fun isSupportedOn(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(RoadroverKey.CARINFO_PACKAGE, 0)
    }.isSuccess

    override fun register(context: Context) {
        // Exported because the sender is the vendor's service, as with the other OEM key actions.
        ContextCompat.registerReceiver(
            context, this, IntentFilter(RoadroverKey.ACTION), ContextCompat.RECEIVER_EXPORTED,
        )
        this.context = context
        AppLog.i("CarKeyReceiver: Listening for Roadrover media keys on ${RoadroverKey.ACTION}")
    }

    override fun unregister() {
        val registeredOn = context ?: return
        context = null
        registeredOn.unregisterReceiver(this)
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != RoadroverKey.ACTION) return
        val word = intent.getStringExtra(RoadroverKey.EXTRA_ACTION) ?: return
        val event = RoadroverKey.eventFor(word)
        AppLog.i("CarKeyReceiver: Roadrover media key action=$word -> ${event?.keyCode ?: "not mapped"}")
        showRawKey(context, RoadroverKey.ACTION, event?.keyCode ?: -1, RoadroverKey.EXTRA_ACTION to word)
        if (event == null) return

        // No press identity: the firmware may also hand this key to the media session, and the copy must merge.
        when (event.edge) {
            RoadroverKey.Edge.CLICK -> handleClick(context, event.keyCode, source = ROUTE)
            RoadroverKey.Edge.DOWN -> handleKey(context, event.keyCode, true, source = ROUTE)
            RoadroverKey.Edge.UP -> handleKey(context, event.keyCode, false, source = ROUTE)
        }
    }

    private companion object {
        const val ROUTE = "carkey-roadrover"
    }
}
