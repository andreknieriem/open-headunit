package com.andrerinas.openheadunit.utils

import org.junit.Assert.*
import org.junit.Test

class CheckedLongTest {
    @Test fun `addition detects both signed edges without Android API24 methods`() {
        assertEquals(Long.MIN_VALUE, CheckedLong.add(Long.MIN_VALUE + 1, -1))
        assertEquals(Long.MAX_VALUE, CheckedLong.add(Long.MAX_VALUE - 1, 1))
        assertNull(CheckedLong.add(Long.MIN_VALUE, -1))
        assertNull(CheckedLong.add(Long.MAX_VALUE, 1))
        assertEquals(-1L, CheckedLong.add(Long.MAX_VALUE, Long.MIN_VALUE))
    }
    @Test fun `duration scaling is checked before multiplication`() {
        assertEquals(Long.MIN_VALUE, CheckedLong.scale(Long.MIN_VALUE, 1))
        assertNull(CheckedLong.scale(Long.MIN_VALUE, 1000))
        assertNull(CheckedLong.scale(Long.MAX_VALUE, 1000))
        assertEquals(-9_223_372_036_000_000_000L, CheckedLong.scale(-9_223_372_036L, 1_000_000_000))
    }
}
