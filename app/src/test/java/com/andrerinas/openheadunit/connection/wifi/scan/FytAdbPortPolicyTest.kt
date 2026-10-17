package com.andrerinas.openheadunit.connection.wifi.scan

import org.junit.Assert.*
import org.junit.Test

class FytAdbPortPolicyTest {
    @Test fun `missing reflection result and unrecognized properties are not treated as off`() {
        for (value in listOf(null, "garbage", "65536", "-2", "1;restart adbd"))
            assertFalse(FytAdbPortPolicy.valid(value))
        for (value in listOf("", "0", "-1", "1", "5555", "65535"))
            assertTrue(FytAdbPortPolicy.valid(value))
    }
    @Test fun `existing or persistent ports are reused without changing their settings`() {
        assertEquals(4567, FytAdbPortPolicy.existing("4567", "5555"))
        assertEquals(4567, FytAdbPortPolicy.existing("", "4567"))
        assertNull(FytAdbPortPolicy.existing("-1", "4567"))
        assertNull(FytAdbPortPolicy.existing("0", "4567"))
        assertNull(FytAdbPortPolicy.existing("", ""))
    }
    @Test fun `recovery preserves a later user port including conventional 5555`() {
        assertTrue(FytAdbPortPolicy.shouldRestore("43127", "-1", 43127, true))
        assertFalse(FytAdbPortPolicy.shouldRestore("5555", "-1", 43127, false))
        assertFalse(FytAdbPortPolicy.shouldRestore("4567", "-1", 43127, true))
    }
    @Test fun `restored property only retains ownership while our private listener remains`() {
        assertTrue(FytAdbPortPolicy.shouldRestore("-1", "-1", 43127, true))
        assertTrue(FytAdbPortPolicy.shouldRestore("", "-1", 43127, true))
        assertFalse(FytAdbPortPolicy.shouldRestore("-1", "-1", 43127, false))
        assertFalse(FytAdbPortPolicy.shouldRestore("", "-1", 43127, false))
    }
}
