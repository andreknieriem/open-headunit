package com.andrerinas.openheadunit.utils

import com.andrerinas.openheadunit.utils.DisplayTargetPolicy.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayTargetPolicyTest {

    private fun display(
        id: Int,
        name: String = "display $id",
        presentation: Boolean = true,
        usable: Boolean = true,
    ) = DisplayTargetPolicy.DisplayInfo(
        displayId = id,
        name = name,
        widthPx = 1024,
        heightPx = 600,
        densityDpi = 160,
        isPresentation = presentation,
        isUsable = usable,
    )

    private val builtIn = display(0, name = "Built-in Screen", presentation = false)

    @Test
    fun `the built-in display is never a candidate`() {
        assertEquals(emptyList<Int>(), DisplayTargetPolicy.candidates(listOf(builtIn)).map { it.displayId })
    }

    @Test
    fun `an unusable display is not offered`() {
        val displays = listOf(builtIn, display(2, usable = false))
        assertEquals(emptyList<Int>(), DisplayTargetPolicy.candidates(displays).map { it.displayId })
    }

    @Test
    fun `presentation displays sort ahead of the rest, then by id`() {
        val displays = listOf(
            builtIn,
            display(5, presentation = false),
            display(4, presentation = true),
            display(3, presentation = false),
        )
        assertEquals(listOf(4, 3, 5), DisplayTargetPolicy.candidates(displays).map { it.displayId })
    }

    @Test
    fun `DEFAULT answers the built-in display even with a panel attached`() {
        val choice = DisplayTargetPolicy.choose(Mode.DEFAULT, 2, listOf(builtIn, display(2)))
        assertEquals(DisplayTargetPolicy.DEFAULT_DISPLAY_ID, choice.displayId)
        assertTrue(choice.isDefault)
    }

    @Test
    fun `SECONDARY uses the pinned display when it is attached`() {
        val displays = listOf(builtIn, display(2), display(3))
        assertEquals(3, DisplayTargetPolicy.choose(Mode.SECONDARY, 3, displays).displayId)
    }

    @Test
    fun `a pinned display that was unplugged falls back to another panel, not to a failure`() {
        val choice = DisplayTargetPolicy.choose(Mode.SECONDARY, 9, listOf(builtIn, display(2)))
        assertEquals(2, choice.displayId)
        assertTrue(choice.reason.contains("9"))
    }

    @Test
    fun `every mode falls back to the built-in display when nothing is attached`() {
        for (mode in Mode.values()) {
            val choice = DisplayTargetPolicy.choose(mode, 2, listOf(builtIn))
            assertEquals(DisplayTargetPolicy.DEFAULT_DISPLAY_ID, choice.displayId)
        }
    }

    @Test
    fun `AUTO ignores the pinned id and takes whatever is attached`() {
        val displays = listOf(builtIn, display(7))
        assertEquals(7, DisplayTargetPolicy.choose(Mode.AUTO, 2, displays).displayId)
    }

    @Test
    fun `a stored mode outside the enum reads as the built-in display`() {
        assertEquals(Mode.DEFAULT, Mode.of(-1))
        assertEquals(Mode.DEFAULT, Mode.of(7))
        assertEquals(Mode.SECONDARY, Mode.of(1))
    }

    @Test
    fun `a session loses its display only when that display is gone`() {
        val displays = listOf(builtIn, display(2))
        assertFalse(DisplayTargetPolicy.lostTargetDisplay(2, displays))
        assertTrue(DisplayTargetPolicy.lostTargetDisplay(3, displays))
        assertTrue(DisplayTargetPolicy.lostTargetDisplay(2, listOf(builtIn, display(2, usable = false))))
    }

    @Test
    fun `the built-in display is never reported as lost`() {
        assertFalse(DisplayTargetPolicy.lostTargetDisplay(0, emptyList()))
    }
}
