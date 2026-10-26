package com.andrerinas.openheadunit.utils

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogFilesHelper {

    const val LOG_FILE_PREFIX = "OHU_Log_"

    /**
     * Prefixes written by earlier names of the project. Rotation matches them too, so captures a
     * unit took before it upgraded still count against the file and size budget instead of sitting
     * in the directory forever, outside it.
     */
    val LEGACY_LOG_FILE_PREFIXES = listOf("HUR_Log_")
    private const val LOG_FILE_SUFFIX = ".txt"
    const val DEFAULT_MAX_LOG_FILES = 10
    const val DEFAULT_MAX_TOTAL_SIZE = 50L * 1024 * 1024 // 50 MB

    fun resolveLogDirectory(
        context: Context,
        settings: Settings? = null,
        allowInternalFallback: Boolean = true
    ): File? {
        val location = settings?.logLocation ?: try {
            Settings(context).logLocation
        } catch (_: Exception) {
            Settings.LogLocation.DEFAULT
        }

        if (location == Settings.LogLocation.DOWNLOADS) {
            val downloadsDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: @Suppress("DEPRECATION") Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            } else {
                @Suppress("DEPRECATION")
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            }

            if (downloadsDir != null) {
                val openHeadunitDir = File(downloadsDir, "OpenHeadunitLogs")
                if (openHeadunitDir.exists() || openHeadunitDir.mkdirs()) {
                    return openHeadunitDir
                }
                if (downloadsDir.exists() || downloadsDir.mkdirs()) {
                    return downloadsDir
                }
            }
        }
        return context.getExternalFilesDir(null) ?: if (allowInternalFallback) context.filesDir else null
    }

    fun rotateLogs(
        logDir: File,
        prefix: String = LOG_FILE_PREFIX,
        maxFiles: Int = DEFAULT_MAX_LOG_FILES,
        maxTotalSizeBytes: Long = DEFAULT_MAX_TOTAL_SIZE
    ) {
        val files = logDir.listFiles { _, name ->
            name.startsWith(prefix) || (prefix == LOG_FILE_PREFIX && LEGACY_LOG_FILE_PREFIXES.any { name.startsWith(it) })
        }
            ?.sortedBy { it.lastModified() }
            ?.toMutableList() ?: return

        // Clean up any empty 0-byte log files left behind by interrupted or failed captures
        val iterator = files.iterator()
        while (iterator.hasNext()) {
            val file = iterator.next()
            if (file.length() == 0L) {
                file.delete()
                iterator.remove()
            }
        }

        while (files.size >= maxFiles) {
            files.removeAt(0).delete()
        }

        var totalSize = files.sumOf { it.length() }
        while (totalSize > maxTotalSizeBytes && files.isNotEmpty()) {
            val oldest = files.removeAt(0)
            totalSize -= oldest.length()
            oldest.delete()
        }
    }

    fun createTimestampedLogFile(logDir: File, prefix: String = LOG_FILE_PREFIX): File {
        ensureDirectory(logDir)

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        var candidate = File(logDir, "$prefix$timeStamp$LOG_FILE_SUFFIX")
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(logDir, "${prefix}${timeStamp}_$suffix$LOG_FILE_SUFFIX")
            suffix++
        }
        return candidate
    }

    fun ensureDirectory(logDir: File) {
        if (!logDir.exists()) logDir.mkdirs()
    }
}

