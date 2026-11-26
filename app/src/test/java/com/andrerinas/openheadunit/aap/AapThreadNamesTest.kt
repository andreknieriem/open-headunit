package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AapThreadNamesTest {
    private val all = listOf(AapThreadNames.VIDEO, AapThreadNames.SEND, AapThreadNames.POLL)

    @Test
    fun everyNameFitsTheKernelLimit() {
        all.forEach { assertTrue("$it too long", it.length <= 15) }
    }

    @Test
    fun namesAreDistinct() {
        assertEquals(3, all.toSet().size)
    }

    @Test
    fun pollKeepsTheSuffixTheHarnessInjectsOn() {
        assertTrue(AapThreadNames.POLL.endsWith("::Poll"))
    }
}
