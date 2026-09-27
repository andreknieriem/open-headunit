package com.andrerinas.openheadunit.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalBtPolicyTest {

    private val noNodes: (String) -> Boolean = { false }
    private val noProps: (String) -> String? = { null }

    private fun nodes(vararg present: String): (String) -> Boolean =
        { path -> present.contains(path) }

    private fun props(vararg pairs: Pair<String, String>): (String) -> String? =
        { key -> pairs.toMap()[key] }

    @Test
    fun `a unit with neither device node nor property is a normal built-in radio`() {
        assertNull(ExternalBtPolicy.detect(noNodes, noProps))
        assertFalse(ExternalBtPolicy.isExternal(noNodes, noProps))
    }

    @Test
    fun `either serial device node is enough`() {
        assertEquals("/dev/rf_serial exists", ExternalBtPolicy.detect(nodes("/dev/rf_serial"), noProps))
        assertEquals(
            "/dev/zj_bt_serial exists",
            ExternalBtPolicy.detect(nodes("/dev/zj_bt_serial"), noProps)
        )
    }

    @Test
    fun `either vendor property set to extra is enough`() {
        assertEquals(
            "rw.zlink.bt.type=extra",
            ExternalBtPolicy.detect(noNodes, props("rw.zlink.bt.type" to "extra"))
        )
        assertEquals(
            "rw.zj.bt.type=extra",
            ExternalBtPolicy.detect(noNodes, props("rw.zj.bt.type" to "extra"))
        )
    }

    @Test
    fun `properties naming any other topology do not fire`() {
        // "built_in" is the value the same properties carry on units where android.bluetooth is
        // the radio the phone is bonded to - the case this must not misreport.
        assertNull(ExternalBtPolicy.detect(noNodes, props("rw.zlink.bt.type" to "built_in")))
        assertNull(ExternalBtPolicy.detect(noNodes, props("rw.zj.bt.type" to "")))
        assertNull(ExternalBtPolicy.detect(noNodes, props("rw.zj.bt.type" to "internal")))
    }

    @Test
    fun `property values are trimmed and case-insensitive`() {
        // getprop output arrives with whatever whitespace the vendor's init script left in it.
        assertEquals(
            "rw.zj.bt.type=extra",
            ExternalBtPolicy.detect(noNodes, props("rw.zj.bt.type" to "  extra  "))
        )
        assertEquals(
            "rw.zlink.bt.type=EXTRA",
            ExternalBtPolicy.detect(noNodes, props("rw.zlink.bt.type" to "EXTRA"))
        )
    }

    @Test
    fun `device nodes are reported ahead of properties`() {
        // Both signals fire on the reference unit; the device node is the more concrete evidence
        // to put in a bug report, so it wins.
        assertEquals(
            "/dev/rf_serial exists",
            ExternalBtPolicy.detect(nodes("/dev/rf_serial"), props("rw.zj.bt.type" to "extra"))
        )
    }

    @Test
    fun `any single signal makes the unit external`() {
        assertTrue(ExternalBtPolicy.isExternal(nodes("/dev/zj_bt_serial"), noProps))
        assertTrue(ExternalBtPolicy.isExternal(noNodes, props("rw.zlink.bt.type" to "extra")))
    }

    @Test
    fun `a negative read is re-read until the vendor app sets its property`() {
        var reads = 0
        var value: String? = null
        val latch = ExternalBtPolicy.Latch { reads++; value }
        assertNull(latch.evidence())
        value = "rw.zj.bt.type=extra"
        assertEquals("rw.zj.bt.type=extra", latch.evidence())
        assertEquals(2, reads)
    }

    @Test
    fun `a positive read is held for the process`() {
        var reads = 0
        val latch = ExternalBtPolicy.Latch { reads++; if (reads == 1) "/dev/rf_serial exists" else null }
        assertEquals("/dev/rf_serial exists", latch.evidence())
        assertEquals("/dev/rf_serial exists", latch.evidence())
        assertEquals(1, reads)
    }
}
