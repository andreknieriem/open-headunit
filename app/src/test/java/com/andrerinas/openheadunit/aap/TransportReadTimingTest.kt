package com.andrerinas.openheadunit.aap

import org.junit.Assert.*
import org.junit.Test

class TransportReadTimingTest {
    @Test fun `idle header waiting cannot consume the slow processing diagnostic budget`() {
        assertFalse(TransportReadTiming.isProcessingSlow(0, 2))
        assertTrue(TransportReadTiming.isProcessingSlow(60, 2))
        assertTrue(TransportReadTiming.isProcessingSlow(0, 75))
    }
}
