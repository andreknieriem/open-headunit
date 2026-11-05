package com.andrerinas.openheadunit.app

import com.andrerinas.openheadunit.app.HiddenMenuCodes.Code
import com.andrerinas.openheadunit.app.HiddenMenuCodes.Opens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenMenuCodesTest {

    @Test
    fun `the reglink unit finds its code from the firmware`() {
        // What the check reads on a CMZX65-U1: device, platform and firmware.
        val found = HiddenMenuCodes.forUnit(listOf("alps k62v1_64_bsp", "mt6765", "CMZXR62N-U1_R6210_S4.26"))
        assertEquals(listOf("9569"), found.map { it.code })
        assertTrue(found.first().tested)
    }

    @Test
    fun `a unit that reports nothing known gets no code`() {
        assertEquals(emptyList<Code>(), HiddenMenuCodes.forUnit(listOf("google sdk_gphone64_x86_64", "ranchu", "")))
        assertEquals(emptyList<Code>(), HiddenMenuCodes.forUnit(listOf("", " ")))
    }

    @Test
    fun `tested codes come first when several match`() {
        val codes = listOf(
            Code("1111", "A", Opens.FACTORY_SETTINGS, tested = false, markers = listOf("ums512")),
            Code("2222", "B", Opens.ADB_MENU, tested = true, markers = listOf("ums512"))
        )
        assertEquals(listOf("2222", "1111"), HiddenMenuCodes.forUnit(listOf("UMS512"), codes).map { it.code })
    }

    @Test
    fun `search looks at the code, the units and the markers, ignoring case`() {
        assertEquals(listOf("9569"), HiddenMenuCodes.search("reglink").map { it.code })
        assertEquals(listOf("9569"), HiddenMenuCodes.search(" CMZX ").map { it.code })
        assertEquals(listOf("8888", "123456"), HiddenMenuCodes.search("topway").map { it.code })
        assertEquals(listOf("3368"), HiddenMenuCodes.search("336").map { it.code })
        assertEquals(emptyList<Code>(), HiddenMenuCodes.search("nothing like this"))
    }

    @Test
    fun `a blank search keeps the whole list`() {
        assertEquals(HiddenMenuCodes.ALL, HiddenMenuCodes.search(""))
        assertEquals(HiddenMenuCodes.ALL, HiddenMenuCodes.search("   "))
    }

    @Test
    fun `the list has no repeated codes and only the reglink one is marked tested`() {
        val codes = HiddenMenuCodes.ALL.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
        assertEquals(listOf("9569"), HiddenMenuCodes.ALL.filter { it.tested }.map { it.code })
        assertTrue(HiddenMenuCodes.ALL.all { c -> c.code.all { it.isDigit() } && c.markers.all { it == it.lowercase() } })
    }
}
