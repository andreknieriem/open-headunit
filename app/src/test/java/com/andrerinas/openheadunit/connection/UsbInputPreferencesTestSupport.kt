package com.andrerinas.openheadunit.connection

import android.content.Context
import android.content.SharedPreferences
import org.mockito.Mockito.*

/** Platform storage for real USB input-policy calls; unrelated preference fixtures stay intact. */
internal fun <T : Context> T.withUsbInputPreferencesForTest(): T = apply {
    val values = mutableMapOf<String, Any?>()
    fun editor(): SharedPreferences.Editor {
        val changes = mutableMapOf<String, Any?>()
        var clear = false
        lateinit var result: SharedPreferences.Editor
        result = mock(SharedPreferences.Editor::class.java) { call ->
            when (call.method.name) {
                "putBoolean", "putInt", "putLong", "putFloat", "putString", "putStringSet" -> {
                    changes[call.getArgument(0)] = call.getArgument(1)
                    result
                }
                "remove" -> { changes[call.getArgument(0)] = null; result }
                "clear" -> { clear = true; result }
                "commit", "apply" -> {
                    synchronized(values) {
                        if (clear) values.clear()
                        changes.forEach { (key, value) ->
                            if (value == null) values.remove(key) else values[key] = value
                        }
                    }
                    if (call.method.name == "commit") true else null
                }
                else -> RETURNS_DEFAULTS.answer(call)
            }
        }
        return result
    }
    val prefs = mock(SharedPreferences::class.java) { call ->
        synchronized(values) {
            when (call.method.name) {
                "getBoolean", "getInt", "getLong", "getFloat", "getString", "getStringSet" ->
                    values[call.getArgument<String>(0)] ?: call.getArgument<Any?>(1)
                "contains" -> values.containsKey(call.getArgument<String>(0))
                "getAll" -> values.toMap()
                "edit" -> editor()
                else -> RETURNS_DEFAULTS.answer(call)
            }
        }
    }
    doReturn(this).`when`(this).createDeviceProtectedStorageContext()
    doReturn(prefs).`when`(this).getSharedPreferences(eq("usb-input"), anyInt())
}
