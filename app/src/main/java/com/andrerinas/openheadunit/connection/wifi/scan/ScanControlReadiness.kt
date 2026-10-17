package com.andrerinas.openheadunit.connection.wifi.scan

/** Retry an idle session only when its prerequisites change, never just because time passed. */
internal class ScanControlReadiness {
    data class Snapshot(val mode: Int, val allowed: Boolean)
    private var observed: Snapshot? = null

    fun remember(current: Snapshot?) { observed = current }

    fun poll(current: Snapshot?, refresh: () -> Unit) {
        if (current == observed) return
        // Record first: a refused operation must not be repeated at each poll. Explicit Retry
        // still calls the controller directly; this observer only handles external changes.
        observed = current
        refresh()
    }
}
