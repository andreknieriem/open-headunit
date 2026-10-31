package com.andrerinas.openheadunit.utils

import android.content.Context
import com.andrerinas.openheadunit.app.AutoStartCheckPolicy
import com.andrerinas.openheadunit.app.MakerWhitelistPolicy
import com.andrerinas.openheadunit.app.MakerWhitelistPolicy.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Adds the app to, or removes it from, a known maker's app lists in /system (#1122, phase three).
 * Each list is copied to the app's own storage first and written back if anything goes wrong, and
 * /system is always made read-only again before returning.
 */
object MakerWhitelist {

    enum class Result { DONE, NO_ROUTE, VERITY, READ_FAILED, REMOUNT_FAILED, WRITE_FAILED }

    private const val BACKUP_DIR = "maker-whitelist-backup"

    suspend fun apply(context: Context, format: Format): Result =
        change(context, format, "apply") { MakerWhitelistPolicy.withApp(it, context.packageName) }

    suspend fun remove(context: Context, format: Format): Result =
        change(context, format, "remove") { MakerWhitelistPolicy.withoutApp(it, context.packageName) }

    /** The commands [apply] runs, for people who want to see them before tapping. */
    fun describe(context: Context, format: Format): String = buildString {
        appendLine(MakerWhitelistPolicy.remountCommand(MakerWhitelistPolicy.MOUNT_POINTS.first(), true))
        for (file in format.files) appendLine("echo ${context.packageName} >> $file")
        append(MakerWhitelistPolicy.remountCommand(MakerWhitelistPolicy.MOUNT_POINTS.first(), false))
    }

    suspend fun reboot(context: Context) {
        AppLog.i("MakerWhitelist: restarting the head unit")
        OemAppManager.executeCommand(context, "reboot")
    }

    private suspend fun change(
        context: Context,
        format: Format,
        what: String,
        edit: (String) -> String?,
    ): Result = withContext(Dispatchers.IO) {
        OemAppManager.requestRoot()
        if (OemAppManager.checkAvailableBackend() == OemAppManager.ExecutionBackend.NONE) {
            return@withContext Result.NO_ROUTE
        }
        val verity = read(context, "getprop ro.boot.veritymode")
        if (MakerWhitelistPolicy.verityBlocksWrites(verity)) {
            AppLog.w("MakerWhitelist: $what refused, verity mode is '$verity'")
            return@withContext Result.VERITY
        }

        val read = format.files.associateWith { readList(context, it) }
        if (read.values.any { it == null }) {
            // Never write a list that could not be read: the edit would replace it with our line alone.
            AppLog.w("MakerWhitelist: $what refused, could not read ${read.filterValues { it == null }.keys}")
            return@withContext Result.READ_FAILED
        }
        val originals = read.mapValues { it.value.orEmpty() }
        val changes = originals.mapNotNull { (path, content) -> edit(content)?.let { path to it } }.toMap()
        if (changes.isEmpty()) {
            AppLog.i("MakerWhitelist: $what, nothing to change in the ${format.name} lists")
            return@withContext Result.DONE
        }
        saveBackups(context, originals)

        val mountPoint = remountWritable(context) ?: run {
            AppLog.w("MakerWhitelist: $what refused, /system could not be made writable")
            return@withContext Result.REMOUNT_FAILED
        }
        try {
            val written = changes.all { (path, content) -> write(context, path, content) }
            val confirmed = written && changes.all { (path, content) ->
                val now = readList(context, path)
                now != null && MakerWhitelistPolicy.hasApp(now, context.packageName) ==
                    MakerWhitelistPolicy.hasApp(content, context.packageName)
            }
            if (confirmed) {
                AppLog.i("MakerWhitelist: $what done in ${changes.keys}")
                Result.DONE
            } else {
                AppLog.w("MakerWhitelist: $what failed, putting the ${format.name} lists back")
                for (path in changes.keys) write(context, path, originals.getValue(path))
                Result.WRITE_FAILED
            }
        } finally {
            val code = OemAppManager.executeCommand(context, MakerWhitelistPolicy.remountCommand(mountPoint, false)).first
            AppLog.i("MakerWhitelist: $mountPoint read-only again, exit=$code")
        }
    }

    private suspend fun read(context: Context, command: String): String = AutoStartCheckPolicy.unbracket(
        OemAppManager.executeCommand(context, AutoStartCheckPolicy.bracket(command)).second
    )

    /** The list's content, or null when `cat` did not finish: the marker only prints after it did. */
    private suspend fun readList(context: Context, path: String): String? =
        MakerWhitelistPolicy.listContent(read(context, "cat '$path' && echo && echo ${MakerWhitelistPolicy.READ_OK}"))

    private suspend fun write(context: Context, path: String, content: String): Boolean {
        val command = MakerWhitelistPolicy.writeCommand(path, content) ?: run {
            AppLog.w("MakerWhitelist: $path holds the heredoc marker, left as it is")
            return false
        }
        return OemAppManager.executeCommand(context, command).first == 0
    }

    /** The mount point now writable, or null if none could be remounted. */
    private suspend fun remountWritable(context: Context): String? =
        MakerWhitelistPolicy.MOUNT_POINTS.firstOrNull { mountPoint ->
            OemAppManager.executeCommand(context, MakerWhitelistPolicy.remountCommand(mountPoint, true)).first == 0
        }

    // Written by the app itself, so they need no root to keep and survive until the app is removed.
    private fun saveBackups(context: Context, originals: Map<String, String>) {
        try {
            val dir = File(context.filesDir, BACKUP_DIR).apply { mkdirs() }
            for ((path, content) in originals) File(dir, File(path).name).writeText(content)
            AppLog.i("MakerWhitelist: backed up ${originals.size} list(s) to ${dir.absolutePath}")
        } catch (e: Exception) {
            AppLog.w("MakerWhitelist: backup failed: ${e.message}")
        }
    }
}
