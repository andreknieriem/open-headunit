package com.andrerinas.openheadunit.connection.wifi.direct

data class ServerP2pBadge(val groupAvailable: Boolean = false, val deviceName: String? = null)

/** Display-only hysteresis. Discovery must still discard a missing network immediately. */
class ServerP2pStatus {
    var badge = ServerP2pBadge()
        private set
    private var sequence = 0L
    private var applied = 0L
    private var missingSince: Long? = null

    fun request(): Long = ++sequence
    fun invalidateRequests() { applied = ++sequence }

    fun localName(name: String?) {
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { badge = badge.copy(deviceName = it) }
    }

    fun group(request: Long, available: Boolean, now: Long): Boolean {
        if (request <= applied) return false
        applied = request
        if (available) {
            missingSince = null
            badge = badge.copy(groupAvailable = true)
        } else {
            val since = missingSince
            if (since == null) missingSince = now
            else if (now - since >= 2000) badge = badge.copy(groupAvailable = false)
        }
        return true
    }

    fun clearGroup() {
        applied = ++sequence
        missingSince = null
        badge = badge.copy(groupAvailable = false)
    }

    fun stop() { clearGroup(); badge = ServerP2pBadge() }
}

/** Rename success, refusal and timeout may race, but only one may proceed to create a group. */
class ServerP2pRenameGate {
    private var finished = false
    fun complete(): Boolean {
        if (finished) return false
        finished = true
        return true
    }
}
