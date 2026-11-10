package com.andrerinas.openheadunit.utils

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Below API 19 a socket is not a Closeable, so Kotlin's `use` on one throws
 * IncompatibleClassChangeError there. The JVM cannot show that, so this scans the sources instead.
 */
class SocketCloseUsageTest {

    @Test
    fun `no source file closes a socket through Kotlin use`() {
        val roots = listOf("src/main/java", "src/github/java", "src/playstore/java").map(::File)
        assertTrue("src/main/java not found from ${File(".").absolutePath}", roots.all { it.isDirectory })

        val sources = roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }
        }
        // A wrong working directory would otherwise pass on an empty scan.
        assertTrue("only ${sources.size} sources scanned", sources.size > 200)

        val offenders = sources.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> SOCKET_USE.containsMatchIn(line) }
                .map { (index, _) -> "${file.path}:${index + 1}" }
        }

        assertEquals(
            "use useSocket from SocketClose.kt instead:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders
        )
    }

    private companion object {
        val SOCKET_USE = Regex("""\b(Server|Datagram)?Socket\s*\([^)]*\)\s*\.use\s*\{""")
    }
}
