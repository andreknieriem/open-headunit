package com.andrerinas.openheadunit.connection.bluetooth.xyauto

object XyAutoMediaSession {
    data class Call(val owner: Any, val session: Any?)
    val calls = mutableListOf<Call>()
    fun update(context: Any, owner: Any, session: Any?) { calls += Call(owner, session) }
}
