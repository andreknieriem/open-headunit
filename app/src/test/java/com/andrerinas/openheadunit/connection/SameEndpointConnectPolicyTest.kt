package com.andrerinas.openheadunit.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SameEndpointConnectPolicyTest {
    @Test fun sameEndpointInFlightJoins() {
        assertTrue(SameEndpointConnectPolicy.joins("192.168.1.5:5277", "192.168.1.5:5277"))
    }

    @Test fun differentEndpointProceeds() {
        assertFalse(SameEndpointConnectPolicy.joins("192.168.1.5:5277", "192.168.1.6:5277"))
    }

    @Test fun noClaimInFlightProceeds() {
        assertFalse(SameEndpointConnectPolicy.joins(null, "192.168.1.5:5277"))
    }

    @Test fun nullRequestProceeds() {
        assertFalse(SameEndpointConnectPolicy.joins("192.168.1.5:5277", null))
        assertFalse(SameEndpointConnectPolicy.joins(null, null))
    }

    @Test fun endpointSpellingIsTheSameForBothPaths() {
        assertEquals("192.168.1.5:5277", SameEndpointConnectPolicy.endpoint("192.168.1.5", 5277))
        assertEquals("fe80::1:5277", SameEndpointConnectPolicy.endpoint("fe80::1%wlan0", 5277))
        assertEquals("fe80::1:5277", SameEndpointConnectPolicy.endpoint("[fe80::1]", 5277))
    }
}
