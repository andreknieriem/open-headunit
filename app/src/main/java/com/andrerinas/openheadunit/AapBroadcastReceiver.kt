package com.andrerinas.openheadunit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.IntentFilter
import android.view.KeyEvent
import com.andrerinas.openheadunit.aap.AapProjectionActivity
import com.andrerinas.openheadunit.aap.protocol.messages.LocationUpdateEvent
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.contract.KeyIntent
import com.andrerinas.openheadunit.contract.LocationUpdateIntent
import com.andrerinas.openheadunit.contract.MediaKeyIntent
import com.andrerinas.openheadunit.contract.ProjectionActivityRequest
import android.os.UserManager
import android.os.Build

class AapBroadcastReceiver : BroadcastReceiver() {

    companion object {
        val filter: IntentFilter by lazy {
            val filter = IntentFilter()
            filter.addAction(LocationUpdateIntent.action)
            filter.addAction(MediaKeyIntent.action)
            filter.addAction(ProjectionActivityRequest.action)
            filter
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val isLocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && 
                      !(context.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked
        
        if (isLocked) return

        val component = App.provide(context)
        if (intent.action == LocationUpdateIntent.action) {
            val location = LocationUpdateIntent.extractLocation(intent)

            // Feed the single source of truth for geofence / night-by-area evaluation.
            com.andrerinas.openheadunit.location.LocationHolder.update(location)

            if (component.settings.useGpsForNavigation) {
                component.commManager.send(LocationUpdateEvent(location))
            }

            if (location.latitude != 0.0 && location.longitude != 0.0) {
                // Only on change: a parked head unit delivers the same coordinates every few
                // seconds, and each write rewrites the whole preferences file. Same position in,
                // nothing to store.
                val stored = component.settings.lastKnownLocation
                if (stored.latitude != location.latitude || stored.longitude != location.longitude) {
                    component.settings.lastKnownLocation = location
                }
            }
        } else if (intent.action == MediaKeyIntent.action) {
            val event: KeyEvent? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(KeyIntent.extraEvent, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(KeyIntent.extraEvent)
            }
            event?.let {
                component.commManager.sendKey(
                    it.keyCode, it.action == KeyEvent.ACTION_DOWN, it.downTime, "media-key-intent"
                )
            }
        } else if (intent.action == ProjectionActivityRequest.action){
            if (component.commManager.connectionState.value is CommManager.ConnectionState.TransportStarted) {
                val aapIntent = Intent(context, AapProjectionActivity::class.java)
                aapIntent.putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                aapIntent.flags = FLAG_ACTIVITY_NEW_TASK
                context.startActivity(aapIntent)
            }
        }
    }
}

