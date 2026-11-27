package com.andrerinas.openheadunit.connection

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class ConnectionStateErrorTest {
    @Test
    fun `a repeated error with the same message is a new state value`() {
        val flow = MutableStateFlow<CommManager.ConnectionState>(CommManager.ConnectionState.Error("Android Auto is not installed"))
        val first = flow.value
        flow.value = CommManager.ConnectionState.Error("Android Auto is not installed")
        assertNotSame(first, flow.value)
    }

    @Test
    fun `logs keep the message`() {
        assertEquals("Error(message=boom)", CommManager.ConnectionState.Error("boom").toString())
    }
}
