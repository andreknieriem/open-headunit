package com.andrerinas.openheadunit.connection.wifi.scan

/** Records the observer's boundary calls without changing the host's Wi-Fi or invoking Shizuku. */
object WifiScanControl {
    data class Call(val owner: Any, val connection: Any? = null, val hotspot: Boolean? = null)
    val calls = mutableListOf<Call>()
    fun session(owner: Any, connection: Any, hotspot: Boolean) {
        calls.add(Call(owner, connection, hotspot))
    }
    fun end(owner: Any) { calls.add(Call(owner)) }
}
