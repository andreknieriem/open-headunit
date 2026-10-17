package com.andrerinas.openheadunit.connection.wifi.scan

import com.andrerinas.openheadunit.utils.adb.AdbProtocol
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class AdbHelloProbeTest {
    @Test fun `ADB authorization challenge and connected greeting identify daemon`() {
        val auth = AdbProtocol.generateAuth(AdbProtocol.AUTH_TYPE_TOKEN, ByteArray(20)).copyOf(24)
        assertTrue(AdbHelloProbe.validHeader(auth))
        assertTrue(AdbHelloProbe.validHeader(AdbProtocol.generateConnect().copyOf(24)))
    }
    @Test fun `reused HTTP port and malformed ADB header do not authorize daemon restart`() {
        assertFalse(AdbHelloProbe.validHeader("HTTP/1.1 200 OK\r\nServer: ".toByteArray()))
        val header = AdbProtocol.generateConnect().copyOf(24)
        header[20] = header[20].toInt().xor(1).toByte()
        assertFalse(AdbHelloProbe.validHeader(header))
        assertFalse(AdbHelloProbe.validHeader(header.copyOf(12)))
    }
    @Test fun `untrusted advertised length cannot masquerade as a valid greeting`() {
        val header = AdbProtocol.generateConnect().copyOf(24)
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(12, Int.MAX_VALUE)
        assertFalse(AdbHelloProbe.validHeader(header))
    }
}
