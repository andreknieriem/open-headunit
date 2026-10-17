package com.andrerinas.openheadunit.connection.wifi.scan

/** O–Q shares its scan-manager gate with network requests, which can enable it again.
 * O/P also repeats stop/firmware-roaming cleanup for an unchanged disable request.
 * Use a five-minute compromise, measured from the last successful request, not every
 * UI refresh. This is not state observation and cannot prevent scans between requests. */
internal class ScanControlRenewal {
    private var leaseId: String? = null
    private var lastRequestMs = 0L

    fun applied(id: String, nowMs: Long) {
        leaseId = id
        lastRequestMs = nowMs
    }

    fun due(id: String, mode: Int, nowMs: Long): Boolean =
        mode == ScanControlPolicy.LEGACY_CONNECTIVITY && leaseId == id &&
            nowMs - lastRequestMs >= INTERVAL_MS

    companion object { const val INTERVAL_MS = 5 * 60_000L }
}
