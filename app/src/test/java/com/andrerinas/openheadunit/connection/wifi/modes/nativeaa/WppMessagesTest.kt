package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.aap.protocol.proto.Wireless
import com.andrerinas.openheadunit.connection.wifi.direct.GroupIdentityStability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The field numbers here were read out of Android Auto 17.5's own dex. They are asserted rather
 * than trusted because a wrong one produces a message the phone parses and ignores: no error, no
 * log line, just a handshake that quietly falls back to the route that no longer has a trigger.
 */
class WppMessagesTest {

    private val carInfo = WppMessages.carInfo(
        vehicleMake = "Open", vehicleModel = "Headunit", vehicleYear = "2026",
        vehicleId = "id-1", headUnitMake = "OHU", headUnitModel = "Revived"
    )

    @Test
    fun `the version request announces 4 2, clearing both the 4 1 gate and its allowlist`() {
        // Below 4.1 the phone skips the TCP endpoint entirely; at exactly 4.1 it also requires the
        // make to be on an allowlist we are not on.
        assertEquals(4, WppHandshakeSession.WPP_VERSION_MAJOR)
        assertEquals(2, WppHandshakeSession.WPP_VERSION_MINOR)
    }

    @Test
    fun `the TCP endpoint rides on field 6 of the version request`() {
        val request = WppMessages.versionRequest(carInfo, WppMessages.endpoint("192.168.49.1", 5299))
        val parsed = Wireless.WifiVersionRequest.parseFrom(request.toByteArray())

        assertTrue(parsed.hasWppInfo())
        assertEquals("192.168.49.1", parsed.wppInfo.ipAddress)
        assertEquals(5299, parsed.wppInfo.port)
    }

    @Test
    fun `a blank address becomes the gateway form rather than an empty string`() {
        // The version request goes out before the credentials resolve. The phone reads an absent
        // address as "" and fails later at socket creation; "0.0.0.0" tells it to dial the gateway,
        // which on a network we host is us.
        val parsed = Wireless.WifiVersionRequest.parseFrom(
            WppMessages.versionRequest(carInfo, WppMessages.endpoint("", 5299)).toByteArray()
        )
        assertEquals("0.0.0.0", parsed.wppInfo.ipAddress)
    }

    @Test
    fun `both endpoint fields are always set, because neither is checked for presence`() {
        // The phone copies ip and port out without reading their has-bits, so an omitted one
        // becomes "" or 0 and fails at socket creation instead of being reported.
        val parsed = Wireless.WifiVersionRequest.parseFrom(
            WppMessages.versionRequest(carInfo, WppMessages.endpoint("10.0.0.1", 5299)).toByteArray()
        )
        assertTrue(parsed.wppInfo.hasIpAddress())
        assertTrue(parsed.wppInfo.hasPort())
    }

    @Test
    fun `the version request carries our identity on field 5`() {
        val parsed = Wireless.WifiVersionRequest.parseFrom(
            WppMessages.versionRequest(carInfo, WppMessages.endpoint("10.0.0.1", 5299)).toByteArray()
        )
        assertTrue(parsed.hasCarInfo())
        assertEquals("Open", parsed.carInfo.make)
        assertEquals("Headunit", parsed.carInfo.model)
        assertEquals("OHU", parsed.carInfo.headUnitMake)
    }

    @Test
    fun `no body type is announced, on any model name`() {
        // Field 9 is a real int32 in the schema the phone parses, but nothing recovers what it
        // carries, so it stays declared for reading captures and unset on the wire.
        fun carInfoFor(model: String) = WppMessages.carInfo(
            "Open", model, "2026", "id", "OHU", "Revived"
        )

        listOf("Pickup Truck", "bigtruck", "Headunit").forEach {
            assertFalse(it, carInfoFor(it).hasBodyType())
        }
    }

    @Test
    fun `the start request stays three fields, as 17 5 declares it`() {
        // It carries no endpoint and no access point info; adding either would be encoding another
        // project's mislabelled 17.4 recovery.
        val parsed = Wireless.WifiStartRequest.parseFrom(
            WppMessages.startRequest("192.168.49.1", 5288).toByteArray()
        )
        assertEquals("192.168.49.1", parsed.ipAddress)
        assertEquals(5288, parsed.port)
        assertEquals(0, parsed.status)
        assertEquals(3, parsed.allFields.size)
    }

    @Test
    fun `credentials go out with all five fields, including an empty bssid`() {
        val parsed = Wireless.WifiInfoResponse.parseFrom(
            WppMessages.infoResponse(
                "ssid", "key", null, GroupIdentityStability.STABLE
            ).toByteArray()
        )
        assertTrue(parsed.hasBssid())
        assertEquals("", parsed.bssid)
        assertEquals(Wireless.SecurityMode.WPA2_PERSONAL, parsed.securityMode)
    }

    @Test
    fun `a network the phone can keep is announced static`() {
        // Its address is the interface's own, so it is the same one next time.
        val parsed = Wireless.WifiInfoResponse.parseFrom(
            WppMessages.infoResponse(
                "ssid", "key", "aa:bb:cc:dd:ee:ff", GroupIdentityStability.STABLE
            ).toByteArray()
        )
        assertEquals(Wireless.AccessPointType.STATIC, parsed.accessPointType)
        assertEquals("aa:bb:cc:dd:ee:ff", parsed.bssid)
    }

    @Test
    fun `a group whose address has repeated is announced static`() {
        val parsed = Wireless.WifiInfoResponse.parseFrom(
            WppMessages.infoResponse(
                "ssid", "key", "aa:bb:cc:dd:ee:ff", GroupIdentityStability.STABLE
            ).toByteArray()
        )
        assertEquals(Wireless.AccessPointType.STATIC, parsed.accessPointType)
    }

    @Test
    fun `a group whose address moves is announced dynamic, so the phone keeps nothing`() {
        // DYNAMIC is the phone's own signal not to persist the credentials: it logs "Won't persist
        // Wifi Configuration ... since access point is DYNAMIC" and comes back over Bluetooth.
        for (identity in GroupIdentityStability.values().filter { it != GroupIdentityStability.STABLE }) {
            val parsed = Wireless.WifiInfoResponse.parseFrom(
                WppMessages.infoResponse(
                    "ssid", "key", "aa:bb:cc:dd:ee:ff", identity
                ).toByteArray()
            )
            assertEquals(identity.name, Wireless.AccessPointType.DYNAMIC, parsed.accessPointType)
        }
    }

    @Test
    fun `a withheld endpoint leaves field 6 off without touching the rest`() {
        // WppEndpointPolicy withholds on a network the phone would later fail to find. The version
        // and identity still go out: what changes is only whether the phone stores somewhere to dial.
        val parsed = Wireless.WifiVersionRequest.parseFrom(
            WppMessages.versionRequest(carInfo, null).toByteArray()
        )
        assertFalse(parsed.hasWppInfo())
        assertTrue(parsed.hasCarInfo())
        assertEquals(WppHandshakeSession.WPP_VERSION_MAJOR, parsed.major)
        assertEquals(WppHandshakeSession.WPP_VERSION_MINOR, parsed.minor)
    }

    @Test
    fun `the rejection carries the one reason the phone acts on, and nothing else`() {
        // The phone verifies this field against {0,1,2} and throws on anything else, including its
        // own UNKNOWN. Only this value withdraws the endpoint instead of crashing its dispatcher.
        val parsed = Wireless.WifiConnectionRejection.parseFrom(
            WppMessages.connectionRejection().toByteArray()
        )
        assertTrue(parsed.hasReason())
        assertEquals(
            Wireless.ConnectionRejectionReason.CONNECTION_REJECTION_REASON_INVALID_SETUP_TOKEN,
            parsed.reason
        )
        assertEquals(1, parsed.allFields.size)
    }

    @Test
    fun `the rejection's reason is the number the phone reads, not its ordinal position`() {
        // 2 on the wire. A wrong number fails the phone's verifier and lands on UNKNOWN, which
        // throws inside its dispatcher whatever the transport.
        assertEquals(
            2,
            Wireless.ConnectionRejectionReason.CONNECTION_REJECTION_REASON_INVALID_SETUP_TOKEN.number
        )
        assertEquals(
            1,
            Wireless.ConnectionRejectionReason.CONNECTION_REJECTION_REASON_MOBILE_DEVICE_ID_NOT_FOUND.number
        )
        assertEquals(
            0,
            Wireless.ConnectionRejectionReason.CONNECTION_REJECTION_REASON_UNKNOWN.number
        )
    }

    @Test
    fun `the rejection is type 10 and the setup info is type 11`() {
        assertEquals(10, WppMessageType.CONNECTION_REJECTION)
        assertEquals(11, WppMessageType.SETUP_INFO)
    }

    @Test
    fun `the version request is well under the frame's length field`() {
        // The framing header carries a uint16 length and refuses anything larger, loudly.
        val size = WppMessages.versionRequest(carInfo, WppMessages.endpoint("255.255.255.255", 65535)).toByteArray().size
        assertFalse(size > WppFraming.MAX_PAYLOAD_SIZE)
    }

    @Test
    fun `the join status parses a hint on field 2`() {
        // Hand-built wire bytes, so the field number is pinned independently of the generated code:
        // field 1 varint 0, field 2 length-delimited "no route".
        val bytes = byteArrayOf(0x08, 0x00, 0x12, 0x08) +
            "no route".toByteArray(Charsets.US_ASCII)
        val parsed = Wireless.WifiConnectStatus.parseFrom(bytes)
        assertEquals(0, parsed.status)
        assertTrue(parsed.hasErrorMessageHint())
        assertEquals("no route", parsed.errorMessageHint)
    }

    @Test
    fun `the start response parses a port on field 2 without disturbing field 3`() {
        // field 1 "1.2.3.4", field 2 varint 5288, field 3 varint 0.
        val bytes = byteArrayOf(0x0A, 0x07) + "1.2.3.4".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x10, 0xA8.toByte(), 0x29, 0x18, 0x00)
        val parsed = Wireless.WifiStartResponse.parseFrom(bytes)
        assertEquals("1.2.3.4", parsed.ipAddress)
        assertEquals(5288, parsed.port)
        assertEquals(0, parsed.status)
    }

    @Test
    fun `the version response parses the device info on field 6`() {
        // field 6 length-delimited, holding field 1 "abc" and field 2 "xyz".
        val inner = byteArrayOf(0x0A, 0x03) + "abc".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x12, 0x03) + "xyz".toByteArray(Charsets.US_ASCII)
        val bytes = byteArrayOf(0x32, inner.size.toByte()) + inner
        val parsed = Wireless.WifiVersionResponse.parseFrom(bytes)
        assertTrue(parsed.hasDeviceInfo())
        assertEquals("abc", parsed.deviceInfo.deviceId)
        assertEquals("xyz", parsed.deviceInfo.connectivityLifetimeId)
    }
}
