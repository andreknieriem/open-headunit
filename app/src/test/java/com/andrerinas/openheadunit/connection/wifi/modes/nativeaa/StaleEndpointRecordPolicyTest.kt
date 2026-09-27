package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleEndpointRecordPolicyTest {

    @Test
    fun `a landing with no dial beside it retires the record`() {
        assertTrue(StaleEndpointRecordPolicy.retiredByHandshake(refusedADialSinceLastLanding = false))
    }

    @Test
    fun `a landing with a dial refused beside it leaves the record standing`() {
        // Measured: the phone runs its dial loop and its Bluetooth handshake at the same time, so
        // this landing says nothing about whether it has let the endpoint go.
        assertFalse(StaleEndpointRecordPolicy.retiredByHandshake(refusedADialSinceLastLanding = true))
    }

    @Test
    fun `the landing after a refused one retires it, because the dialling stopped`() {
        assertFalse(StaleEndpointRecordPolicy.retiredByHandshake(refusedADialSinceLastLanding = true))
        assertTrue(StaleEndpointRecordPolicy.retiredByHandshake(refusedADialSinceLastLanding = false))
    }

    @Test
    fun `a unit that never refused a dial retires nothing it did not raise`() {
        // Harmless rather than meaningless: clearing a record that is not standing is a no-op, and
        // this is the ordinary case on every unit that has never poisoned a phone.
        assertTrue(StaleEndpointRecordPolicy.retiredByHandshake(refusedADialSinceLastLanding = false))
    }

    @Test
    fun `a refusal is the only thing that holds the record through a landing`() {
        for (refused in listOf(true, false)) {
            assertTrue(
                "refused=$refused",
                StaleEndpointRecordPolicy.retiredByHandshake(refused) == !refused
            )
        }
    }

    @Test
    fun `a hotspot landing never retires it, because the old address can never be refused`() {
        for (refused in listOf(true, false)) {
            assertFalse(
                "refused=$refused",
                StaleEndpointRecordPolicy.retiredByHandshake(refused, onHotspot = true)
            )
        }
    }
}
