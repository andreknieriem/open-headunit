package com.andrerinas.openheadunit.connection.wifi.scan

/** Version is the actual Android SDK, never a head-unit marketing version. */
internal object ScanControlPolicy {
    const val UNSUPPORTED = 0
    const val AUTOJOIN = 1
    const val HOTSPOT_SCAN = 2
    fun wirelessTransport(live: Boolean, wireless: Boolean, loopback: Boolean) = live && wireless && !loopback
    fun mode(sdk: Int, hotspot: Boolean, stationOff: Boolean, nativeHost: Boolean = true): Int = when {
        !nativeHost -> UNSUPPORTED
        sdk >= 33 -> AUTOJOIN
        sdk >= 26 && hotspot && stationOff -> HOTSPOT_SCAN
        else -> UNSUPPORTED
    }
    fun discardAfterBoot(mode: Int, previousBoot: Int, currentBoot: Int): Boolean {
        if (mode != AUTOJOIN) return false // Scan-always is persistent, including across OTAs.
        require(previousBoot >= 0 && currentBoot >= 0) // An unreadable boot ID is not a reboot.
        return previousBoot != currentBoot
    }
    fun shouldRestore(current: Int, original: Int) = original == 1 && current == 0
}

/** Every read/write is authoritative; a successful Binder transaction alone is not success. */
internal class ScanControlLease(private val read: (Int) -> Int, private val write: (Int, Int) -> Unit) {
    fun apply(mode: Int, original: Int, acquired: () -> Unit = {}): Boolean {
        require(original == 0 || original == 1)
        if (read(mode) != original) return false
        acquired()
        if (original == 1) write(mode, 0)
        return read(mode) == 0
    }
    fun restore(mode: Int, original: Int): Boolean {
        require(original == 0 || original == 1)
        val current = read(mode)
        if (current !in 0..1) return false
        // A user re-enabling discovery wins. We never turn their new setting off on teardown.
        if (ScanControlPolicy.shouldRestore(current, original)) write(mode, original)
        return read(mode) == original || current == 1
    }
}

/** Receipts live with the daemon: app-death rollback must not replay over a later user change. */
internal class ReleasedScanLeases {
    private val ids = LinkedHashSet<String>()
    fun contains(id: String) = id in ids
    fun record(id: String) {
        ids.add(id)
        if (ids.size > 32) ids.remove(ids.first())
    }
}
