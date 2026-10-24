package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/**
 * The advertised network and its withdrawal generation are one publication. Type 3 captures
 * this pair before writing; a later withdrawal still ends settling, even if the replacement
 * happens to reuse the same SSID. No socket I/O runs under this store's monitor.
 */
internal class WppCredentialStore {
    data class Snapshot(val credentials: NativeNetworkCredentials?, val withdrawals: Long)

    @Volatile
    var current = Snapshot(null, 0L)
        private set

    @Synchronized
    fun publish(credentials: NativeNetworkCredentials) {
        val previous = current
        // Some platforms publish a replacement without a preceding removal callback. Treat a
        // changed join target as retiring the old one, but not an identity-policy update.
        val replaced = previous.credentials?.let { !it.sameNetworkAs(credentials) } == true
        current = Snapshot(credentials, previous.withdrawals + if (replaced) 1 else 0)
    }

    @Synchronized
    fun withdraw() {
        current = Snapshot(null, current.withdrawals + 1)
    }
}
