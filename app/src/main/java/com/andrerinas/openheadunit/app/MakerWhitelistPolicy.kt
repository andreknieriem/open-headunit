package com.andrerinas.openheadunit.app

/**
 * Phase three of #1122: adding the app to the maker's own app lists in /system, for list formats
 * that are known. The rules live here so they are tested on the JVM; nothing in this file runs a
 * command or touches a file.
 */
object MakerWhitelistPolicy {

    /** A firmware's lists: plain text, one package per line. */
    data class Format(val name: String, val files: List<String>)

    enum class Status { NOT_APPLIED, PARTLY_APPLIED, APPLIED }

    /** Autorun on boot, allowed in the background, and survives suspend. All three are needed. */
    val REGLINK = Format(
        name = "Reglink",
        files = listOf(
            "/system/etc/reglink_autorun_whitelist.txt",
            "/system/etc/reglink_background_app_whitelist.txt",
            "/system/etc/suspend_kill_whitelist.txt",
        ),
    )

    val KNOWN = listOf(REGLINK)

    /**
     * Verity modes where a write to /system can leave the unit unable to boot. "enforcing" fails
     * the read of a changed block, "eio" returns an I/O error; "logging" only records it.
     */
    private val BLOCKING_VERITY = setOf("enforcing", "eio")

    /** Tried in order: /system on its own partition (Reglink, Android 8), then system-as-root. */
    val MOUNT_POINTS = listOf("/system", "/")

    const val HEREDOC_END = "OHU_EOF"
    const val READ_OK = "OHU_READ_OK"

    /** The known format whose files are all present, if any. */
    fun detect(existing: Collection<String>): Format? = KNOWN.firstOrNull { existing.containsAll(it.files) }

    fun status(format: Format, filesWithApp: Collection<String>): Status {
        val count = format.files.count { it in filesWithApp }
        return when (count) {
            0 -> Status.NOT_APPLIED
            format.files.size -> Status.APPLIED
            else -> Status.PARTLY_APPLIED
        }
    }

    /**
     * What `cat <list> && echo && echo READ_OK` printed, without the marker and with one trailing
     * newline, or null when the marker is missing because `cat` failed.
     */
    fun listContent(output: String): String? {
        val lines = output.lines().dropLastWhile { it.isBlank() }
        if (lines.lastOrNull()?.trim() != READ_OK) return null
        val body = lines.dropLast(1).joinToString("\n").trimEnd('\n', ' ')
        return if (body.isEmpty()) "" else "$body\n"
    }

    fun verityBlocksWrites(verityMode: String): Boolean = verityMode.trim().lowercase() in BLOCKING_VERITY

    fun hasApp(content: String, packageName: String): Boolean =
        content.lineSequence().any { it.trim() == packageName }

    /** [content] with the package as its own last line, or null when it is already there. */
    fun withApp(content: String, packageName: String): String? {
        if (hasApp(content, packageName)) return null
        val base = if (content.isEmpty() || content.endsWith("\n")) content else "$content\n"
        return "$base$packageName\n"
    }

    /** [content] without the package's line, every other line untouched, or null when it is not there. */
    fun withoutApp(content: String, packageName: String): String? {
        if (!hasApp(content, packageName)) return null
        val kept = content.lines().filterNot { it.trim() == packageName }
        return kept.joinToString("\n").trimEnd('\n').let { if (it.isEmpty()) "" else "$it\n" }
    }

    /**
     * Rewrites [path] in place, so it keeps its owner, mode and SELinux label. Null when the
     * content holds the end marker on a line of its own and the heredoc would end early.
     */
    fun writeCommand(path: String, content: String): String? {
        if (content.lineSequence().any { it == HEREDOC_END }) return null
        val body = if (content.isEmpty() || content.endsWith("\n")) content else "$content\n"
        return "cat > '$path' <<'$HEREDOC_END'\n$body$HEREDOC_END"
    }

    fun remountCommand(mountPoint: String, writable: Boolean): String =
        "mount -o ${if (writable) "rw" else "ro"},remount $mountPoint"
}
