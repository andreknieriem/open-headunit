package com.andrerinas.openheadunit.app

/**
 * The reading side of the auto-start check (#1122): turns what the read-only commands printed into
 * findings, and the findings into the report people paste into an issue. No Android types, so the
 * parsing is tested on the JVM.
 */
object AutoStartCheckPolicy {

    /**
     * Being the home app is not a verdict: the check is run because the app failed to come back,
     * and the units in #1122 kill it whether it is the home app or not. It stays a detail.
     */
    enum class Verdict { FORCE_STOPPED, BATTERY, UNKNOWN }

    data class ListFile(val path: String, val hasApp: Boolean)

    data class Findings(
        val appVersion: String,
        val route: String,
        val device: String,
        val android: String,
        val platform: String,
        val firmware: String,
        val isHomeApp: Boolean,
        /** Null below API 23, where there is no battery optimization to be exempt from. */
        val batteryExempt: Boolean?,
        /** Null below API 28. */
        val backgroundRestricted: Boolean?,
        /** Null when it could not be told. */
        val duraSpeed: Boolean?,
        val recentExits: List<String>,
        val forceStops: List<String>,
        val stoppedBy: List<String>,
        val listFiles: List<ListFile>,
    )

    /** Where a maker's app lists can live. Reglink keeps its three in /system/etc. */
    val LIST_DIRS = listOf("/system/etc", "/vendor/etc", "/oem", "/product/etc", "/odm/etc")

    /** File names that hint at a maker's keep-alive or kill list. Same pattern the issue asks people to run. */
    private val LIST_NAME = Regex("white|skip|kill|keep|qb|autorun|pwctl", RegexOption.IGNORE_CASE)

    /** Enough to cover the three Reglink lists and the odd extra without a wall of text. */
    const val MAX_LIST_FILES = 20
    const val MAX_LOG_LINES = 5

    /** How a force stop is named in [Findings.recentExits]. */
    const val FORCE_STOP_EXIT = "force-stop"

    private const val UPDATE_STOP = "pkg removed"

    private val FROM_PID = Regex("""from pid (\d+)""")

    /**
     * Lists [dir] one name per line. Through the unit's ADB the command can run in an interactive
     * shell, where `ls` sees a terminal and prints columns; the pipe makes it print one per line,
     * which toolbox on Android 4.x already does.
     */
    fun listCommand(dir: String): String = "ls $dir 2>/dev/null | cat"

    /** Full paths, one per line, kept when the file name looks like an app list. */
    fun listCandidates(paths: String): List<String> = paths.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("/") }
        .filter { LIST_NAME.containsMatchIn(it.substringAfterLast('/')) }
        .distinct()
        .take(MAX_LIST_FILES)
        .toList()

    /**
     * The log lines where the system force-stopped [packageName], newest last. ActivityManager logs
     * them as "Force stopping <package> appid=... user=0: from pid 1234", the events buffer as
     * am_force_stop.
     */
    fun forceStopLines(log: String, packageName: String): List<String> = log.lineSequence()
        .filter { it.contains(packageName) }
        .filter { it.contains("force stop", ignoreCase = true) || it.contains("am_force_stop") ||
            it.contains("forceStop", ignoreCase = true) || it.contains("force_stop", ignoreCase = true) }
        // Installing an update stops the app too ("Force stopping ... pkg removed"); that is not the unit.
        .filterNot { it.contains(UPDATE_STOP, ignoreCase = true) }
        .map { it.trim() }
        .toList()
        .takeLast(MAX_LOG_LINES)

    /** The pids that asked for a force stop, in the order they first appear. */
    fun callerPids(lines: List<String>): List<Int> = lines
        .mapNotNull { FROM_PID.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        .distinct()

    private const val BEGIN = "OHU_CHECK_BEGIN"
    private const val END = "OHU_CHECK_END"

    /**
     * Brackets [command] with markers. When the unit's ADB falls back to an interactive shell, the
     * prompt and the echoed command come back mixed into the output, and a command whose output has
     * no trailing newline (`/proc/<pid>/cmdline`) gets the next prompt glued onto it.
     */
    fun bracket(command: String): String = "echo $BEGIN; $command; echo; echo $END"

    /** What [bracket]ed output printed between the markers; the output as is when they are missing. */
    fun unbracket(output: String): String {
        val lines = output.lines()
        // The echoed command line also contains the marker, so only a line that is exactly it counts.
        val begin = lines.indexOfLast { it.trim() == BEGIN }
        if (begin < 0) return output
        val end = lines.subList(begin + 1, lines.size).indexOfFirst { it.trim() == END }
        val body = if (end < 0) lines.subList(begin + 1, lines.size) else lines.subList(begin + 1, begin + 1 + end)
        return body.joinToString("\n").trimEnd()
    }

    /** `/proc/<pid>/cmdline` separates arguments with NUL; the first one is the process name. */
    fun processName(cmdline: String): String? =
        cmdline.substringBefore('\u0000').trim().takeIf { it.isNotEmpty() }

    fun verdict(f: Findings): Verdict = when {
        f.forceStops.isNotEmpty() || f.stoppedBy.isNotEmpty() ||
            f.recentExits.any { it.contains(" $FORCE_STOP_EXIT") } -> Verdict.FORCE_STOPPED
        f.batteryExempt == false || f.backgroundRestricted == true -> Verdict.BATTERY
        else -> Verdict.UNKNOWN
    }

    /** Plain English on purpose: it is pasted into GitHub issues, like the exported log. */
    fun report(f: Findings): String = buildString {
        appendLine("Open Headunit auto-start check")
        appendLine("App: ${f.appVersion}")
        appendLine("Device: ${f.device}")
        appendLine("Android: ${f.android}")
        appendLine("Platform: ${f.platform}")
        appendLine("Firmware: ${f.firmware}")
        appendLine("Access: ${f.route}")
        appendLine("Home app: ${yesNo(f.isHomeApp)}")
        appendLine("Battery exempt: ${yesNo(f.batteryExempt)}")
        appendLine("Background restricted: ${yesNo(f.backgroundRestricted)}")
        appendLine("DuraSpeed installed: ${yesNo(f.duraSpeed)}")
        appendLine("Verdict: ${verdict(f).name.lowercase().replace('_', ' ')}")
        section("Recent exits", f.recentExits)
        section("Force stops in the log", f.forceStops)
        section("Stopped by", f.stoppedBy)
        section("Lists", f.listFiles.map { (if (it.hasApp) "[has app] " else "[no app] ") + it.path })
    }.trimEnd()

    private fun StringBuilder.section(title: String, lines: List<String>) {
        appendLine("$title:")
        if (lines.isEmpty()) appendLine("  none")
        // A loop, not forEach: on a List that resolves to java.lang.Iterable#forEach, API 24.
        for (line in lines) appendLine("  $line")
    }

    private fun yesNo(value: Boolean?): String = when (value) {
        true -> "yes"
        false -> "no"
        null -> "unknown"
    }
}
