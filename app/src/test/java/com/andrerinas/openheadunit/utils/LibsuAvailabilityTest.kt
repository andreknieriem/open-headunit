package com.andrerinas.openheadunit.utils

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibsuAvailabilityTest {

    @Test
    fun `libsu is refused below API 19 and used from it`() {
        assertFalse(LibsuAvailability.usable(16))
        assertFalse(LibsuAvailability.usable(17))
        assertFalse(LibsuAvailability.usable(18))
        assertTrue(LibsuAvailability.usable(19))
        assertTrue(LibsuAvailability.usable(36))
    }

    @Test
    fun `every file that calls libsu asks for its API level first`() {
        // SUExecutor builds its libsu route only from API 21, so it is already gated.
        val exempt = setOf("SUExecutor.kt")
        val roots = listOf("src/main/java", "src/github/java", "src/playstore/java").map(::File)
        assertTrue("src/main/java not found from ${File(".").absolutePath}", roots.all { it.isDirectory })

        val sources = roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
        }
        assertTrue("only ${sources.size} sources scanned", sources.size > 200)

        val callers = sources.filter { it.readText().contains("import com.topjohnwu.superuser") }
        assertTrue("no libsu caller found, so this test checks nothing", callers.isNotEmpty())

        val ungated = callers
            .filterNot { it.name in exempt }
            .filterNot { it.readText().contains("LibsuAvailability.usable(") }
            .map { it.path }

        assertEquals(
            "ask LibsuAvailability.usable() before any Shell call:\n" + ungated.joinToString("\n"),
            emptyList<String>(),
            ungated
        )
    }
}
