package com.andrerinas.openheadunit.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Captures written before the rename (HUR was Headunit Revived) sit in the same directory as the
 * current ones, and rotation has to account for them: this is a rename, not a new directory that
 * the file and size budget never visits.
 */
class LogFileNamingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun newCapturesAreNamedWithTheCurrentPrefix() {
        val file = LogFilesHelper.createTimestampedLogFile(tempFolder.newFolder("logs"))

        assertTrue(file.name, file.name.startsWith("OHU_Log_"))
    }

    @Test
    fun rotationCountsCapturesWrittenUnderTheOldPrefix() {
        val dir = tempFolder.newFolder("logs")
        val legacy = (0 until 6).map { "HUR_Log_legacy_$it.txt" }
        val current = (0 until 6).map { "OHU_Log_current_$it.txt" }
        // Every legacy capture predates every current one, so keeping the budget means dropping
        // across both prefixes rather than only among the six that carry the current one.
        (legacy + current).forEachIndexed { index, name ->
            File(dir, name).apply {
                writeText("line\n")
                setLastModified(1_000_000_000_000L + index * 1_000L)
            }
        }

        LogFilesHelper.rotateLogs(dir)

        val remaining = dir.listFiles()!!.map { it.name }
        assertFalse("oldest capture survived: legacy names are outside rotation", remaining.contains(legacy[0]))
        assertTrue("rotation dropped a capture it had just written", remaining.containsAll(current))
    }
}
