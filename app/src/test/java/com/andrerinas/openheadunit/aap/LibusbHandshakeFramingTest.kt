package com.andrerinas.openheadunit.aap

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbEndpoint
import android.os.SystemClock
import com.andrerinas.openheadunit.connection.projection.LibusbProjectionConnection
import com.andrerinas.openheadunit.connection.projection.StandardUsbProjectionConnection
import com.andrerinas.openheadunit.connection.usb.UsbNative
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import java.nio.ByteBuffer

class LibusbHandshakeFramingTest {
    @Test fun `USB packet size comes from the published OUT endpoint`() = mockStatic(SystemClock::class.java).use {
        val connections = listOf(
            LibusbProjectionConnection(mock(UsbManager::class.java), mock(UsbDevice::class.java), com.andrerinas.openheadunit.connection.ConnectionAdmission.UNRESTRICTED),
            StandardUsbProjectionConnection(mock(UsbManager::class.java), mock(UsbDevice::class.java), com.andrerinas.openheadunit.connection.ConnectionAdmission.UNRESTRICTED))
        for (connection in connections) {
            assertEquals(0, connection.outMaxPacketSize)
            val field = connection.javaClass.getDeclaredField("endpointOut").apply { isAccessible = true }
            for (size in listOf(8, 64, 512, 1024)) {
                val endpoint = mock(UsbEndpoint::class.java)
                `when`(endpoint.maxPacketSize).thenReturn(size)
                field.set(connection, endpoint)
                assertEquals(size, connection.outMaxPacketSize)
            }
            field.set(connection, null)
            assertEquals(0, connection.outMaxPacketSize)
        }
    }
    @Test fun `positive JNI counts pass through USB leftovers and both handshake phases once`() =
        mockStatic(SystemClock::class.java).use {
            val stale = byteArrayOf(5, 9, 0, 3, -1, -1, -1, -1, 1, 2, 3)
            val version = byteArrayOf(0, 3, 0, 8, 0, 2, 0, 1, 0, 7, 0, 0)
            val tls = byteArrayOf(0, 3, 0, 5, 0, 3, 22, 3, 3)
            val stream = stale + version + tls
            for (split in 1 until stale.size + version.size) {
                val native = mock(UsbNative::class.java)
                val connection = LibusbProjectionConnection(mock(UsbManager::class.java), mock(UsbDevice::class.java), com.andrerinas.openheadunit.connection.ConnectionAdmission.UNRESTRICTED)
                connection.isConnected = true
                LibusbProjectionConnection::class.java.getDeclaredField("usbNative").apply {
                    isAccessible = true
                }.set(connection, native)
                var nativeReads = 0
                `when`(native.read(any(), anyInt())).thenAnswer { call ->
                    // Model the positive count JNI supplies, including preserved timeout bytes.
                    // This tests Kotlin buffering, not libusb timeout result mapping itself.
                    val from = if (nativeReads == 0) 0 else split
                    val to = if (nativeReads == 0) split else stream.size
                    assertTrue("unexpected reread of consumed data", nativeReads++ < 2)
                    call.getArgument<ByteBuffer>(0).put(stream, from, to - from)
                    to - from
                }
                val versionReader = HandshakeFrameReader({ 0L }, 100, skipEncryptedFrames = true) { out, length, timeout ->
                    connection.recvBlocking(out, length, timeout, false)
                }
                assertArrayEquals(version, versionReader.readFrame(1000))
                // The TLS phase gets a fresh strict reader but shares the USB leftover buffer.
                val tlsReader = HandshakeFrameReader({ 0L }, 100) { out, length, timeout ->
                    connection.recvBlocking(out, length, timeout, false)
                }
                assertArrayEquals(tls, tlsReader.readFrame(1000))
                assertEquals(2, nativeReads)
                assertTrue(connection.isConnected)
            }
        }
}
