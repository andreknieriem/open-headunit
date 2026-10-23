package com.andrerinas.openheadunit.connection.broker

import android.content.Context

class NativeBTBrokerStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { current ->
        if (current.contains(KEY_ADDRESS) || current.contains(KEY_NAME)) return@also
        for (legacyName in LEGACY_PREFS) {
            val legacy = context.applicationContext.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
            val address = legacy.getString(KEY_ADDRESS, null)
            val name = legacy.getString(KEY_NAME, null)
            if (address == null && name == null) continue
            current.edit().apply {
                if (address != null) putString(KEY_ADDRESS, address)
                if (name != null) putString(KEY_NAME, name)
            }.apply()
            legacy.edit().clear().apply()
            return@also
        }
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
        private val LEGACY_PREFS = listOf("AABroker", "esp32-broker")
        private const val KEY_ADDRESS = "address"
        private const val KEY_NAME = "name"
        private const val KEY_END_AA_ON_DISCONNECT = "end_aa_on_disconnect"
    }
}
