package com.andrerinas.openheadunit.main

import com.andrerinas.openheadunit.main.HomePrimaryPolicy.Connection
import com.andrerinas.openheadunit.main.HomePrimaryPolicy.Input
import com.andrerinas.openheadunit.main.HomePrimaryPolicy.Status
import org.junit.Assert.assertEquals
import org.junit.Test

class HomePrimaryPolicyTest {

    private fun input(
        chosen: Set<Connection> = emptySet(),
        lastConnection: Connection? = null,
        usbPhoneDetected: Boolean = false,
        sessionActive: Boolean = false,
        reconnectTarget: Connection? = null,
        wifiScanning: Boolean = false,
    ) = Input(chosen, lastConnection, usbPhoneDetected, sessionActive, reconnectTarget, wifiScanning)

    @Test
    fun `nothing chosen and no history starts with USB and keeps the rest on the bar`() {
        val decision = HomePrimaryPolicy.decide(input())
        assertEquals(Connection.USB, decision.primary)
        assertEquals(Status.READY, decision.status)
        assertEquals(listOf(Connection.WIFI, Connection.SELF), decision.barConnections)
    }

    @Test
    fun `the last connection is the main button when nothing is plugged in`() {
        val decision = HomePrimaryPolicy.decide(input(lastConnection = Connection.WIFI))
        assertEquals(Connection.WIFI, decision.primary)
        assertEquals(listOf(Connection.USB, Connection.SELF), decision.barConnections)
    }

    @Test
    fun `a phone on the cable wins over a wireless history`() {
        val decision = HomePrimaryPolicy.decide(
            input(lastConnection = Connection.WIFI, usbPhoneDetected = true)
        )
        assertEquals(Connection.USB, decision.primary)
        assertEquals(Status.PHONE_DETECTED, decision.status)
    }

    @Test
    fun `a phone on the cable is ignored when USB is not a chosen mode`() {
        val decision = HomePrimaryPolicy.decide(
            input(chosen = setOf(Connection.WIFI), usbPhoneDetected = true)
        )
        assertEquals(Connection.WIFI, decision.primary)
        assertEquals(Status.READY, decision.status)
    }

    @Test
    fun `connections the user did not choose never reach the bar`() {
        val decision = HomePrimaryPolicy.decide(input(chosen = setOf(Connection.USB)))
        assertEquals(Connection.USB, decision.primary)
        assertEquals(emptyList<Connection>(), decision.barConnections)
    }

    @Test
    fun `a last connection that is no longer chosen falls back to the first chosen one`() {
        val decision = HomePrimaryPolicy.decide(
            input(chosen = setOf(Connection.WIFI, Connection.SELF), lastConnection = Connection.USB)
        )
        assertEquals(Connection.WIFI, decision.primary)
        assertEquals(listOf(Connection.SELF), decision.barConnections)
    }

    @Test
    fun `Self Mode alone is the main button`() {
        val decision = HomePrimaryPolicy.decide(input(chosen = setOf(Connection.SELF)))
        assertEquals(Connection.SELF, decision.primary)
        assertEquals(Status.READY, decision.status)
    }

    @Test
    fun `an active session always offers the way back to Android Auto`() {
        for (primary in Connection.values()) {
            val decision = HomePrimaryPolicy.decide(
                input(chosen = setOf(primary), sessionActive = true, usbPhoneDetected = true, wifiScanning = true)
            )
            assertEquals(Status.ACTIVE_SESSION, decision.status)
        }
    }

    @Test
    fun `the countdown names the connection it is about to try`() {
        val decision = HomePrimaryPolicy.decide(
            input(lastConnection = Connection.USB, usbPhoneDetected = true, reconnectTarget = Connection.WIFI)
        )
        assertEquals(Connection.WIFI, decision.primary)
        assertEquals(Status.RECONNECTING, decision.status)
    }

    @Test
    fun `a countdown towards a mode that is not chosen leaves every chosen one on the bar`() {
        val decision = HomePrimaryPolicy.decide(
            input(chosen = setOf(Connection.USB), reconnectTarget = Connection.SELF)
        )
        assertEquals(Connection.SELF, decision.primary)
        assertEquals(listOf(Connection.USB), decision.barConnections)
    }

    @Test
    fun `searching only shows on the wireless button`() {
        assertEquals(
            Status.SEARCHING,
            HomePrimaryPolicy.decide(input(lastConnection = Connection.WIFI, wifiScanning = true)).status
        )
        assertEquals(
            Status.READY,
            HomePrimaryPolicy.decide(input(lastConnection = Connection.USB, wifiScanning = true)).status
        )
    }
}
