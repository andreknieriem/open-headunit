package com.andrerinas.openheadunit.utils

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.media.AudioManager

/**
 * Prints which Bluetooth profiles this unit holds at session start and whenever that changes, and
 * tags each quiet window with them. Diagnostic only; see [BluetoothLinkStatePolicy].
 */
class BluetoothLinkMonitor(private val context: Context) {

    // Resolved once a session: the adapter lookup can reflect, and this is read on the poll thread.
    @Volatile private var adapter: BluetoothAdapter? = null
    @Volatile private var adapterResolved = false
    private var last: BluetoothLinkStatePolicy.Snapshot? = null

    @Synchronized
    fun onSessionStart() {
        adapter = try {
            BluetoothHelper.getBluetoothAdapter(context)
        } catch (e: Exception) {
            null
        }
        adapterResolved = adapter != null
        last = null
        val snapshot = read()
        last = snapshot
        AppLog.i("BluetoothLinkMonitor: at session start: %s", BluetoothLinkStatePolicy.describe(snapshot))
    }

    /** Called once per rate window; prints only when the profiles moved. */
    @Synchronized
    fun onWindow() {
        noteIfChanged(read())
    }

    /** The current reading for a quiet line, printing a change line first if it moved. */
    @Synchronized
    fun describeNow(): String {
        val snapshot = read()
        noteIfChanged(snapshot)
        return BluetoothLinkStatePolicy.describe(snapshot)
    }

    private fun noteIfChanged(snapshot: BluetoothLinkStatePolicy.Snapshot) {
        if (!BluetoothLinkStatePolicy.changed(last, snapshot)) return
        last = snapshot
        AppLog.i("BluetoothLinkMonitor: changed: %s", BluetoothLinkStatePolicy.describe(snapshot))
    }

    private fun read(): BluetoothLinkStatePolicy.Snapshot {
        val audioMode = try {
            (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.mode
        } catch (e: Exception) {
            null
        }
        val resolved = adapter
        if (!adapterResolved || resolved == null) {
            return BluetoothLinkStatePolicy.Snapshot(null, emptyMap(), audioMode)
        }
        val on = try { resolved.isEnabled } catch (e: Exception) { null }
        if (on == false) return BluetoothLinkStatePolicy.Snapshot(false, emptyMap(), audioMode)
        val states = BluetoothLinkStatePolicy.PROFILES.associate { (name, profile) ->
            // SecurityException without BLUETOOTH_CONNECT, or a stack that rejects a hidden role.
            name to try { resolved.getProfileConnectionState(profile) } catch (e: Exception) { null }
        }
        return BluetoothLinkStatePolicy.Snapshot(true, states, audioMode)
    }
}
