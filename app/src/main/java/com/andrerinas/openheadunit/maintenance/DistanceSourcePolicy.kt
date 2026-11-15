package com.andrerinas.openheadunit.maintenance

/**
 * Which source counts the distance, and whether the CAN box can be read at all.
 *
 * The box wins whenever it is delivering, because it counts like the dashboard and keeps counting
 * with the app closed. The GPS counts only while the box is silent, so the two never add the same
 * stretch (and what the GPS adds meanwhile is taken off the box's next difference, see
 * [CanBoxDistancePolicy]).
 */
object DistanceSourcePolicy {

    enum class Source { GPS, CAN_BOX }

    /** The box repeats its counters about every second; this long without one, it is silent. */
    const val BOX_SILENT_MS = 10_000L

    /** How long the first GPS fix of a run waits for the box to say whether it covered the gap. */
    const val BOX_FIRST_READING_WAIT_MS = 30_000L

    /** Linux group that may read every app's log lines; Android gives it with READ_LOGS. */
    const val LOG_GROUP_ID = 1007

    /** The log reader needs logd's logcat, which arrived with Android 5. */
    const val FIRST_SDK_WITH_LOG_ACCESS = 21

    /** Android 13 asks the user on every log read, so the box is not read from there on. */
    const val LAST_SDK_WITH_LOG_ACCESS = 32

    fun active(lastBoxReadingElapsedMs: Long, nowElapsedMs: Long): Source =
        if (lastBoxReadingElapsedMs > 0 && nowElapsedMs - lastBoxReadingElapsedMs <= BOX_SILENT_MS) {
            Source.CAN_BOX
        } else {
            Source.GPS
        }

    enum class Coverage {
        /** The box measured the drive since the last run: nothing went uncounted. */
        COVERED,
        /** No box, or it could not measure it: compare positions as without a box. */
        NOT_COVERED,
        /** The box is starting: wait for its first reading. */
        PENDING,
    }

    /**
     * Whether the time the app was closed is already counted, which decides if the first GPS fix
     * should look for driving the app missed.
     *
     * @param boxStartedElapsedMs when the box reader started in this run, 0 when it is not running.
     * @param firstReadingCovered null until the box's first reading of this run was handled.
     */
    fun gapCoverage(boxStartedElapsedMs: Long, firstReadingCovered: Boolean?, nowElapsedMs: Long): Coverage = when {
        firstReadingCovered == true -> Coverage.COVERED
        firstReadingCovered == false -> Coverage.NOT_COVERED
        boxStartedElapsedMs <= 0 -> Coverage.NOT_COVERED
        nowElapsedMs - boxStartedElapsedMs < BOX_FIRST_READING_WAIT_MS -> Coverage.PENDING
        else -> Coverage.NOT_COVERED
    }

    enum class Access {
        /** Not a unit with a supported box: the GPS counts. */
        NO_BOX,
        /** A supported unit on an Android where the app cannot read the log quietly. */
        UNSUPPORTED_ANDROID,
        /** The box is there but reading it needs the log permission, granted once over ADB. */
        NEEDS_PERMISSION,
        /** Granted, but this process started before the grant: it takes effect on the next start. */
        NEEDS_RESTART,
        READY,
    }

    fun access(sdk: Int, boxUnit: Boolean, permissionGranted: Boolean, inLogGroup: Boolean): Access = when {
        !boxUnit -> Access.NO_BOX
        sdk < FIRST_SDK_WITH_LOG_ACCESS || sdk > LAST_SDK_WITH_LOG_ACCESS -> Access.UNSUPPORTED_ANDROID
        !permissionGranted -> Access.NEEDS_PERMISSION
        !inLogGroup -> Access.NEEDS_RESTART
        else -> Access.READY
    }

    /** What the Maintenance screen says about where the distance comes from. */
    enum class Shown { GPS, BOX, BOX_SILENT, BOX_NEEDS_ACCESS, BOX_NEEDS_RESTART }

    fun shown(access: Access, source: Source): Shown = when (access) {
        Access.NO_BOX, Access.UNSUPPORTED_ANDROID -> Shown.GPS
        Access.NEEDS_PERMISSION -> Shown.BOX_NEEDS_ACCESS
        Access.NEEDS_RESTART -> Shown.BOX_NEEDS_RESTART
        Access.READY -> if (source == Source.CAN_BOX) Shown.BOX else Shown.BOX_SILENT
    }

    /** Whether the "Groups:" line of /proc/self/status lists the log group. */
    fun inLogGroup(procStatus: String): Boolean {
        val line = procStatus.lineSequence().firstOrNull { it.startsWith("Groups:") } ?: return false
        return line.substring("Groups:".length).trim().split(' ', '\t')
            .any { it.toIntOrNull() == LOG_GROUP_ID }
    }
}
