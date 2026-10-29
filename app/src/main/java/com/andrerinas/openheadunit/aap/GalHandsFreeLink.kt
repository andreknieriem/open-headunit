package com.andrerinas.openheadunit.aap

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Read-only lookup on the Bluetooth worker; never connect, disconnect or request a bond. */
internal class GalHandsFreeLink {
    // No callback means no proxy handle exists to unbind. Do not accumulate another
    // pending profile binding on every phone retry; a new transport may try again.
    private var timedOut = false
    // The head-unit HFP client role, distinct from Android's public HEADSET gateway role.
    private val HEADSET_CLIENT = 16

    fun connectedPhones(context: Context, adapter: BluetoothAdapter): Set<String>? {
        if (timedOut) return null
        val applicationContext = context.applicationContext ?: context
        val lock = Any()
        val ready = CountDownLatch(1)
        var retired = false
        var connected = false
        var proxy: BluetoothProfile? = null
        fun release(profile: BluetoothProfile) {
            try { adapter.closeProfileProxy(HEADSET_CLIENT, profile) } catch (_: Exception) { }
        }
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, service: BluetoothProfile) {
                val discard = synchronized(lock) {
                    if (retired) true else { proxy = service; connected = true; false }
                }
                if (discard) release(service)
                ready.countDown()
            }
            override fun onServiceDisconnected(profile: Int) {
                synchronized(lock) { connected = false }
                // Retain the proxy until finally so a disconnect cannot lose the handle
                // needed to release the profile binding. Reading it may now fail/return empty.
                ready.countDown()
            }
        }
        try {
            if (!adapter.getProfileProxy(applicationContext, listener, HEADSET_CLIENT)) return null
            if (!ready.await(500, TimeUnit.MILLISECONDS)) {
                timedOut = true
                return null
            }
            val active = synchronized(lock) { if (connected) proxy else null } ?: return null
            return active.connectedDevices.map { it.address }.toSet()
        } finally {
            val active = synchronized(lock) { retired = true; proxy.also { proxy = null } }
            active?.let(::release)
            // A callback after timeout sees retired and releases its proxy itself.
        }
    }
}
