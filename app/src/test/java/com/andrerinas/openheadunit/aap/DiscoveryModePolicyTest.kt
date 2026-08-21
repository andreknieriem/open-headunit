package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryModePolicyTest {

    @Test
    fun `native AA never scans, whatever strategy happens to be stored`() {
        // helperConnectionStrategy keeps whatever mode 2 last left there, so mode 3 has to be
        // decided on the mode alone.
        for (strategy in 0..4) {
            assertFalse(DiscoveryModePolicy.usesNetworkDiscovery(3, strategy))
        }
    }

    @Test
    fun `headunit server scans in both its sub-modes, whatever strategy is stored`() {
        for (strategy in 0..4) {
            assertTrue(DiscoveryModePolicy.usesNetworkDiscovery(0, strategy))
            assertTrue(DiscoveryModePolicy.usesNetworkDiscovery(1, strategy))
        }
    }

    @Test
    fun `helper mode scans only where we go looking for the phone`() {
        assertTrue(DiscoveryModePolicy.usesNetworkDiscovery(2, 0))  // Common WiFi (NSD)
        assertFalse(DiscoveryModePolicy.usesNetworkDiscovery(2, 1)) // WiFi Direct
        assertFalse(DiscoveryModePolicy.usesNetworkDiscovery(2, 2)) // Google Nearby
        assertTrue(DiscoveryModePolicy.usesNetworkDiscovery(2, 3))  // Phone Hotspot (Host)
        assertTrue(DiscoveryModePolicy.usesNetworkDiscovery(2, 4))  // Headunit Hotspot (Passive)
    }

    @Test
    fun `the headunit server mode and the phone hotspot strategy are the same path`() {
        // The point of the object. These two look unrelated in the settings screen, so a defect in
        // the 5277 probe reads as two separate bug reports unless this is written down.
        assertTrue(DiscoveryModePolicy.usesNetworkDiscovery(1, 2))
        assertTrue(DiscoveryModePolicy.usesNetworkDiscovery(2, 3))
    }

    @Test
    fun `the routes that bring the phone to us are exactly the ones that skip discovery`() {
        // WiFi Direct and Nearby are the two helper strategies with an inbound connection, and
        // they are the two that must not scan. Stated as a complement so adding a strategy without
        // deciding which side it falls on fails here.
        for (strategy in 0..4) {
            val inbound = strategy == 1 || strategy == 2
            assertTrue(
                "strategy $strategy",
                DiscoveryModePolicy.usesNetworkDiscovery(2, strategy) != inbound
            )
        }
    }
}
