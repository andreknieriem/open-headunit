package com.andrerinas.openheadunit.connection.broker

import android.content.Context

class NativeBTBrokerStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Master switch for the BT Broker path. Defaults to off. Off keeps the saved device
     * but blocks scan, pairing, BLE session, and Native AA broker transport.
     */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    var deviceAddress: String?
        get() = prefs.getString(KEY_ADDRESS, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().putString(KEY_ADDRESS, value?.trim()?.uppercase()).apply()
        }

    var deviceName: String?
        get() = prefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().putString(KEY_NAME, value).apply()
        }

    /** When the BLE link stays down, end the Android Auto session. */
    var endAaOnDisconnect: Boolean
        get() = prefs.getBoolean(KEY_END_AA_ON_DISCONNECT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_END_AA_ON_DISCONNECT, value).apply()
        }

    fun clear() {
        prefs.edit().remove(KEY_ADDRESS).remove(KEY_NAME).apply()
    }

    companion object {
        private const val PREFS = "NativeBTBroker"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ADDRESS = "address"
        private const val KEY_NAME = "name"
        private const val KEY_END_AA_ON_DISCONNECT = "end_aa_on_disconnect"
    }
}
