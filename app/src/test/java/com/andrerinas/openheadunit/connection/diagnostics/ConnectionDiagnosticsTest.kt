package com.andrerinas.openheadunit.connection.diagnostics

import com.andrerinas.openheadunit.aap.AapTransport
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.BindException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

class ConnectionDiagnosticsTest {

    private var fakeClockMs = 1_000L

    @Before
    fun setUp() {
        fakeClockMs = 1_000L
        ConnectionDiagnosticsTracker.clock = { fakeClockMs }
        ConnectionDiagnosticsTracker.resetForTest()
    }

    @Test
    fun `all 9 required error codes carry expected stage description and troubleshooting suggestions`() {
        val expectedStages = mapOf(
            ConnectionErrorCode.PHONE_NOT_FOUND to ErrorStage.DISCOVERY_AND_CONNECTIVITY,
            ConnectionErrorCode.PORT_UNREACHABLE to ErrorStage.DISCOVERY_AND_CONNECTIVITY,
            ConnectionErrorCode.HEADUNIT_SERVER_NOT_RUNNING to ErrorStage.DISCOVERY_AND_CONNECTIVITY,
            ConnectionErrorCode.WIFI_CLIENT_ISOLATION to ErrorStage.DISCOVERY_AND_CONNECTIVITY,
            ConnectionErrorCode.SOCKET_TIMEOUT to ErrorStage.TRANSPORT,
            ConnectionErrorCode.TLS_HANDSHAKE_FAILED to ErrorStage.TRANSPORT,
            ConnectionErrorCode.AAP_NEGOTIATION_FAILED to ErrorStage.PROTOCOL,
            ConnectionErrorCode.DECODER_INIT_FAILED to ErrorStage.MEDIA_PIPELINE,
            ConnectionErrorCode.VIDEO_FIRST_FRAME_TIMEOUT to ErrorStage.MEDIA_PIPELINE,
        )

        assertEquals(9, ConnectionErrorCode.entries.size)
        for ((code, stage) in expectedStages) {
            assertEquals(stage, code.stage)
            assertTrue(code.userMessage.isNotBlank())
            assertTrue(code.shortMessage.isNotBlank())
            assertTrue("Expected troubleshooting suggestions for $code", code.troubleshootingSuggestions.isNotEmpty())
            assertTrue(code.formattedToastText().startsWith("[${code.name}]"))
            assertTrue(code.formattedDialogMessage().contains("Troubleshooting:"))
        }
    }

    @Test
    fun `classifier maps discovery transport protocol and media failures to exact ConnectionErrorCode`() {
        assertEquals(
            ConnectionErrorCode.PHONE_NOT_FOUND,
            ConnectionErrorClassifier.resolveCode(rawCause = "phone_not_found")
        )

        assertEquals(
            ConnectionErrorCode.PORT_UNREACHABLE,
            ConnectionErrorClassifier.resolveCode(
                throwable = ConnectException("Connection refused"),
                endpoint = "192.168.43.1:5289",
            )
        )

        assertEquals(
            ConnectionErrorCode.HEADUNIT_SERVER_NOT_RUNNING,
            ConnectionErrorClassifier.resolveCode(
                throwable = ConnectException("Connection refused"),
                endpoint = "192.168.43.1:5277",
            )
        )

        assertEquals(
            ConnectionErrorCode.HEADUNIT_SERVER_NOT_RUNNING,
            ConnectionErrorClassifier.resolveCode(
                throwable = BindException("Address already in use"),
                endpoint = "0.0.0.0:5288",
            )
        )

        assertEquals(
            ConnectionErrorCode.WIFI_CLIENT_ISOLATION,
            ConnectionErrorClassifier.resolveCode(
                throwable = NoRouteToHostException("EHOSTUNREACH (No route to host)"),
                wifiConnected = true,
                sameSubnet = true,
            )
        )

        assertEquals(
            ConnectionErrorCode.SOCKET_TIMEOUT,
            ConnectionErrorClassifier.resolveCode(
                throwable = SocketTimeoutException("connect timed out"),
                endpoint = "192.168.43.1:5277",
            )
        )

        assertEquals(
            ConnectionErrorCode.TLS_HANDSHAKE_FAILED,
            ConnectionErrorClassifier.resolveCode(
                handshakeFailure = AapTransport.HandshakeFailure.SSL,
                throwable = SSLHandshakeException(" Handshake failed "),
            )
        )

        assertEquals(
            ConnectionErrorCode.AAP_NEGOTIATION_FAILED,
            ConnectionErrorClassifier.resolveCode(
                rawCause = "handshake_failed",
                handshakeFailure = AapTransport.HandshakeFailure.OTHER,
            )
        )

        assertEquals(
            ConnectionErrorCode.DECODER_INIT_FAILED,
            ConnectionErrorClassifier.resolveCode(
                rawCause = "Start reading failed: MediaCodec configure failed",
            )
        )

        assertEquals(
            ConnectionErrorCode.VIDEO_FIRST_FRAME_TIMEOUT,
            ConnectionErrorClassifier.resolveCode(
                rawCause = "video_first_frame_timeout",
                reachedHandshake = true,
                renderedAnyFrame = false,
            )
        )
    }

    @Test
    fun `DiagnosticsExporter formats report matching GitHub issue specification`() {
        val snapshot = DiagnosticsSnapshot(
            appVersion = "1.2.3",
            platform = "Android 15",
            connectionMode = "Wireless",
            connectionState = "Failed",
            discoveryStatus = "Success",
            transportStatus = "Connected",
            tlsStatus = "Failed",
            aapNegotiationStatus = "Not Started",
            errorCode = ConnectionErrorCode.TLS_HANDSHAKE_FAILED,
            errorMessage = "TLS handshake with phone failed",
            wifiConnected = true,
            sameSubnet = true,
            clientIsolationDetected = false,
            decoderState = "Not Started",
            connectionDurationSeconds = 4.8,
        )

        val formatted = DiagnosticsExporter.format(snapshot)
        val expected = """
            ----------------------------------------
            Open HeadUnit Diagnostics
            ----------------------------------------
            App Version: 1.2.3
            Platform: Android 15
            Connection Mode: Wireless

            Connection State: Failed
            Discovery: Success
            Transport: Connected
            TLS: Failed
            AAP Negotiation: Not Started

            Error Code: TLS_HANDSHAKE_FAILED
            Error Message: TLS handshake with phone failed

            Session ID: [redacted]

            Network:
            - Wi‑Fi Connected: Yes
            - Same Subnet: Yes
            - Client Isolation Detected: No
            - Local Address: [redacted]
            - Remote Address: [redacted]

            Decoder State: Not Started
            Connection Duration: 4.8 s
            ----------------------------------------
        """.trimIndent()

        assertEquals(expected, formatted)
    }

    @Test
    fun `DiagnosticsSanitizer scrubs IPs MACs SSIDs serials phone numbers emails tokens certs and session IDs`() {
        val raw = """
            Connecting from 192.168.49.1:5288 to 192.168.49.105:5277 over fe80::1ff:fe23:4567:890a
            BT MAC AA:BB:CC:DD:EE:FF BSSID=11-22-33-44-55-66 ssid="MyCarHotspot"
            serial=R58M42XXXXX session_id=9f8e7d6c-5b4a-3210
            user=driver.name@example.com phone=+1 (555) 234-5678
            Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.secret
            oauth=ya29.a0AfH6SMBx
            -----BEGIN CERTIFICATE-----
            MIIDXTCCAkWgAwIBAgIJALa8
            -----END CERTIFICATE-----
        """.trimIndent()

        val sanitized = DiagnosticsSanitizer.sanitizeText(raw)

        assertFalse(sanitized.contains("192.168.49.1"))
        assertFalse(sanitized.contains("192.168.49.105"))
        assertFalse(sanitized.contains("fe80::1ff"))
        assertFalse(sanitized.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(sanitized.contains("11-22-33-44-55-66"))
        assertFalse(sanitized.contains("MyCarHotspot"))
        assertFalse(sanitized.contains("R58M42XXXXX"))
        assertFalse(sanitized.contains("9f8e7d6c-5b4a-3210"))
        assertFalse(sanitized.contains("driver.name@example.com"))
        assertFalse(sanitized.contains("555) 234-5678"))
        assertFalse(sanitized.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"))
        assertFalse(sanitized.contains("ya29.a0AfH6SMBx"))
        assertFalse(sanitized.contains("MIIDXTCCAkWgAwIBAgIJALa8"))
        assertTrue(sanitized.contains("[redacted]"))
    }

    @Test
    fun `ConnectionDiagnosticsTracker tracks stage progression and records stage-specific failure`() {
        ConnectionDiagnosticsTracker.beginAttempt("Wireless")
        ConnectionDiagnosticsTracker.noteNetworkContext(wifiConnected = true, sameSubnet = true)
        ConnectionDiagnosticsTracker.noteTransportConnected("Wireless")
        fakeClockMs += 4_800L

        val classified = ConnectionDiagnosticsTracker.recordError(
            rawCause = "tls_handshake_failed",
            handshakeFailure = AapTransport.HandshakeFailure.SSL,
        )
        assertEquals(ConnectionErrorCode.TLS_HANDSHAKE_FAILED, classified.code)

        val snapshot = ConnectionDiagnosticsTracker.currentSnapshot()
        assertEquals("Failed", snapshot.connectionState)
        assertEquals("Success", snapshot.discoveryStatus)
        assertEquals("Connected", snapshot.transportStatus)
        assertEquals("Failed", snapshot.tlsStatus)
        assertEquals("Not Started", snapshot.aapNegotiationStatus)
        assertEquals("Not Started", snapshot.decoderState)
        assertEquals(4.8, snapshot.connectionDurationSeconds, 0.01)
    }
}
