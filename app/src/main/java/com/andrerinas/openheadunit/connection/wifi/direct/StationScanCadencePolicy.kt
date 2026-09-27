package com.andrerinas.openheadunit.connection.wifi.direct

/**
 * Turns the times this unit's WiFi scanned into one line a reporter can attach to an issue.
 *
 * A scan takes a single radio off the group's channel for tens to hundreds of milliseconds per
 * channel, and nothing in the framework suppresses one for a projection session. That is the
 * standing theory behind the periodic outages, and it has never been testable: `LogExporter` scopes
 * logcat to this app's own process, so no `WifiScanner` or `WifiConnectivityManager` line can ever
 * reach a reporter log at any level. A broadcast we receive ourselves is the only way the evidence
 * gets into one.
 *
 * A summary rather than a line per scan, so a unit whose scans are the problem cannot flood the log
 * it is meant to be diagnosed from. Pure, so the arithmetic is a unit test.
 */
object StationScanCadencePolicy {

    /** How often to summarise. Matches the throughput instruments so windows line up by eye. */
    const val WINDOW_MS = 30_000L

    /** How many earlier scans a line places in time, so a flood still prints one bounded line. */
    const val MAX_PLACED = 10

    /**
     * One line about the scans in this window, or null when there were none.
     *
     * @param scanTimesMs when each scan result landed, oldest first; the last is the one that
     *   prints the line, so the others are placed relative to it
     * @param windowMs how long the window actually was, which is not exactly [WINDOW_MS]
     * @param station what the bring-up did to this unit's own association. A disconnected station
     *   is the state that scans, so a cadence read without it attributes nothing; the deciding
     *   lines rotate out of a head unit's buffer long before a stutter is noticed.
     */
    fun summarise(
        scanTimesMs: List<Long>,
        windowMs: Long,
        station: StationStandDownOutcome = StationStandDownOutcome.UNKNOWN
    ): String? {
        if (scanTimesMs.isEmpty()) return null

        val gaps = scanTimesMs.zipWithNext { a, b -> b - a }
        val cadence = when {
            gaps.isEmpty() -> "one scan, so no cadence yet"
            else -> {
                val sorted = gaps.sorted()
                val median = sorted[sorted.size / 2]
                "every ${format(median)}s (shortest ${format(sorted.first())}s, " +
                    "longest ${format(sorted.last())}s)" + placed(scanTimesMs)
            }
        }
        val whose = station.scanClause?.let { " $it" } ?: ""
        return "station scans: ${scanTimesMs.size} in ${windowMs}ms, $cadence. Each one takes the " +
            "radio off the group's channel.$whose"
    }

    // Only the line's own scan carries a log timestamp; a stutter is matched to the others by these.
    private fun placed(scanTimesMs: List<Long>): String {
        val last = scanTimesMs.last()
        val earlier = scanTimesMs.dropLast(1).takeLast(MAX_PLACED)
        return ", the earlier ones ${earlier.joinToString("s, ") { format(last - it) }}s before this line"
    }

    private fun format(ms: Long): String = String.format(java.util.Locale.US, "%.1f", ms / 1000.0)
}
