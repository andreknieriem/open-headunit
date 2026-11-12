package com.andrerinas.openheadunit.maintenance

import com.andrerinas.openheadunit.maintenance.DistanceSourcePolicy.Access
import com.andrerinas.openheadunit.maintenance.DistanceSourcePolicy.Coverage
import com.andrerinas.openheadunit.maintenance.DistanceSourcePolicy.Shown
import com.andrerinas.openheadunit.maintenance.DistanceSourcePolicy.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistanceSourcePolicyTest {

    @Test
    fun `the box counts while it delivers and the GPS once it goes silent`() {
        assertEquals(Source.GPS, DistanceSourcePolicy.active(0, 50_000))
        assertEquals(Source.CAN_BOX, DistanceSourcePolicy.active(40_000, 50_000))
        assertEquals(Source.CAN_BOX, DistanceSourcePolicy.active(40_000, 40_000 + DistanceSourcePolicy.BOX_SILENT_MS))
        assertEquals(Source.GPS, DistanceSourcePolicy.active(40_000, 40_001 + DistanceSourcePolicy.BOX_SILENT_MS))
    }

    @Test
    fun `the first fix waits a while for the box to say whether it covered the gap`() {
        assertEquals(Coverage.NOT_COVERED, DistanceSourcePolicy.gapCoverage(0, null, 10_000))
        assertEquals(Coverage.PENDING, DistanceSourcePolicy.gapCoverage(1_000, null, 10_000))
        assertEquals(Coverage.NOT_COVERED,
            DistanceSourcePolicy.gapCoverage(1_000, null, 1_000 + DistanceSourcePolicy.BOX_FIRST_READING_WAIT_MS))
        assertEquals(Coverage.COVERED, DistanceSourcePolicy.gapCoverage(1_000, true, 2_000))
        assertEquals(Coverage.NOT_COVERED, DistanceSourcePolicy.gapCoverage(1_000, false, 2_000))
        // Answered before the reader stopped: the answer stands.
        assertEquals(Coverage.COVERED, DistanceSourcePolicy.gapCoverage(0, true, 2_000))
    }

    @Test
    fun `access needs a box, an Android that allows it, the permission and a fresh process`() {
        assertEquals(Access.NO_BOX, DistanceSourcePolicy.access(27, false, true, true))
        assertEquals(Access.UNSUPPORTED_ANDROID, DistanceSourcePolicy.access(19, true, true, true))
        assertEquals(Access.UNSUPPORTED_ANDROID, DistanceSourcePolicy.access(33, true, true, true))
        assertEquals(Access.NEEDS_PERMISSION, DistanceSourcePolicy.access(27, true, false, false))
        assertEquals(Access.NEEDS_RESTART, DistanceSourcePolicy.access(27, true, true, false))
        assertEquals(Access.READY, DistanceSourcePolicy.access(21, true, true, true))
        assertEquals(Access.READY, DistanceSourcePolicy.access(32, true, true, true))
    }

    @Test
    fun `the screen says which source counts and what the box is missing`() {
        assertEquals(Shown.GPS, DistanceSourcePolicy.shown(Access.NO_BOX, Source.GPS))
        assertEquals(Shown.GPS, DistanceSourcePolicy.shown(Access.UNSUPPORTED_ANDROID, Source.GPS))
        assertEquals(Shown.BOX_NEEDS_ACCESS, DistanceSourcePolicy.shown(Access.NEEDS_PERMISSION, Source.GPS))
        assertEquals(Shown.BOX_NEEDS_RESTART, DistanceSourcePolicy.shown(Access.NEEDS_RESTART, Source.GPS))
        assertEquals(Shown.BOX, DistanceSourcePolicy.shown(Access.READY, Source.CAN_BOX))
        assertEquals(Shown.BOX_SILENT, DistanceSourcePolicy.shown(Access.READY, Source.GPS))
    }

    @Test
    fun `the log group is read from the process status`() {
        val status = "Name:\tapp\nUid:\t10088\t10088\t10088\t10088\nGroups:\t1007 3003 9997 50088\nVmPeak:\t1 kB\n"
        assertTrue(DistanceSourcePolicy.inLogGroup(status))
        assertFalse(DistanceSourcePolicy.inLogGroup("Groups:\t3003 9997 50088\n"))
        assertFalse(DistanceSourcePolicy.inLogGroup("Groups:\t\n"))
        assertFalse(DistanceSourcePolicy.inLogGroup("Name:\tapp\n"))
        assertFalse(DistanceSourcePolicy.inLogGroup("Groups:\t10070\n"))
    }
}
