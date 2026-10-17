package com.andrerinas.openheadunit.connection.wifi.scan

/** Version is the actual Android SDK, never a head-unit marketing version. */
internal object ScanControlPolicy {
    const val UNSUPPORTED = 0
    const val AUTOJOIN = 1
    const val HOTSPOT_SCAN = 2
    const val LEGACY_AUTOJOIN = 3
    const val LEGACY_CONNECTIVITY = 4
    const val UNKNOWN = -1
    fun hasReadback(mode: Int) = mode == AUTOJOIN || mode == HOTSPOT_SCAN
    fun validSnapshot(mode: Int, value: Int) = when (mode) {
        AUTOJOIN, HOTSPOT_SCAN -> value in 0..1
        LEGACY_AUTOJOIN, LEGACY_CONNECTIVITY -> value == UNKNOWN
        else -> false
    }
    fun wirelessTransport(live: Boolean, wireless: Boolean, loopback: Boolean) = live && wireless && !loopback
    fun mode(sdk: Int, hotspot: Boolean, stationOff: Boolean, nativeHost: Boolean = true): Int = when {
        !nativeHost -> UNSUPPORTED
        sdk >= 33 -> AUTOJOIN
        sdk >= 26 && hotspot && stationOff -> HOTSPOT_SCAN
        sdk in 30..32 && !hotspot -> LEGACY_AUTOJOIN
        sdk in 26..29 && !hotspot -> LEGACY_CONNECTIVITY
        else -> UNSUPPORTED
    }
    fun discardAfterBoot(mode: Int, previousBoot: Int, currentBoot: Int): Boolean {
        if (mode == HOTSPOT_SCAN) return false // Scan-always is persistent, including across OTAs.
        require(previousBoot >= 0 && currentBoot >= 0) // An unreadable boot ID is not a reboot.
        return previousBoot != currentBoot
    }
    fun shouldRestore(current: Int, original: Int) = original == 1 && current == 0
}

/** Read back where Android exposes state. Legacy Direct setters have no getter: their
 * documented fallback enables searches on release, rather than inventing an original value. */
internal class ScanControlLease(private val read: (Int) -> Int, private val write: (Int, Int) -> Unit) {
    fun apply(mode: Int, original: Int, acquired: () -> Unit = {}): Boolean {
        require(ScanControlPolicy.validSnapshot(mode, original))
        if (!ScanControlPolicy.hasReadback(mode)) {
            acquired()
            write(mode, 0)
            return true // Setter accepted; not proof of observed radio/scan state.
        }
        if (read(mode) != original) return false
        acquired()
        if (original == 1) write(mode, 0)
        return read(mode) == 0
    }
    fun renew(mode: Int) {
        require(mode == ScanControlPolicy.LEGACY_CONNECTIVITY)
        write(mode, 0)
    }
    fun restore(mode: Int, original: Int): Boolean {
        require(ScanControlPolicy.validSnapshot(mode, original))
        if (!ScanControlPolicy.hasReadback(mode)) {
            write(mode, 1)
            return true // Explicit legacy fallback, not restoration of a measured snapshot.
        }
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
