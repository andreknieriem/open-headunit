package com.andrerinas.openheadunit.aap.bluetooth

import com.andrerinas.openheadunit.aap.bluetooth.BluetoothPairingSession.Bond
import com.andrerinas.openheadunit.aap.protocol.proto.Bluetooth
import com.andrerinas.openheadunit.aap.protocol.proto.Common.MessageStatus
import org.junit.Assert.*
import org.junit.Test

class BluetoothPairingSessionTest {
    private val phone = "12:34:56:78:9A:BC"
    private class Fake : BluetoothPairingSession.Port {
        var state = Bond.BONDED
        var time = 0L
        val replies = mutableListOf<Pair<MessageStatus, Boolean>>()
        val keys = mutableListOf<String>()
        var onBond: () -> Unit = {}
        override fun bond(address: String): Bond { onBond(); return state }
        override fun reply(status: MessageStatus, alreadyPaired: Boolean) { replies += status to alreadyPaired }
        override fun authentication(value: String) { keys += value }
        override fun now() = time
    }
    @Test fun pairedIsActualBondNotConnectionReadiness() {
        val p = Fake(); val s = BluetoothPairingSession(p)
        s.request(phone, 2); assertEquals(MessageStatus.STATUS_SUCCESS to true, p.replies.last())
        p.state = Bond.NONE; BluetoothPairingSession(p).request(phone, 2)
        assertEquals(MessageStatus.STATUS_BLUETOOTH_UNAVAILABLE to false, p.replies.last())
        p.state = Bond.BONDING; BluetoothPairingSession(p).request(phone, 2)
        assertEquals(MessageStatus.STATUS_BLUETOOTH_PAIRING_DELAYED to false, p.replies.last())
        assertFalse(p.replies.any { it == MessageStatus.STATUS_SUCCESS to false })
    }
    @Test fun unavailableDoesNotClaimSuccess() {
        val p = Fake(); p.state = Bond.UNAVAILABLE; val s = BluetoothPairingSession(p)
        s.request(phone, 2)
        assertEquals(MessageStatus.STATUS_BLUETOOTH_UNAVAILABLE to false, p.replies.single())
    }
    @Test fun addressAndMethodAreValidated() {
        val p = Fake(); val s = BluetoothPairingSession(p)
        for (bad in listOf("", "123456789ABC", "00:00:00:00:00:00", "02:00:00:00:00:00", "FF:FF:FF:FF:FF:FF")) {
            s.request(bad, 2); assertEquals(MessageStatus.STATUS_BLUETOOTH_INVALID_ADDRESS, p.replies.last().first)
        }
        for (method in listOf(-1, 0, 1, 3, 4, 99)) {
            s.request(phone, method); assertEquals(MessageStatus.STATUS_BLUETOOTH_INVALID_PAIRING_METHOD, p.replies.last().first)
        }
    }
    @Test fun comparisonRetainsLeadingZeroesAndIgnoresDuplicates() {
        val p = Fake(); val s = BluetoothPairingSession(p); s.request(phone.lowercase(), 2)
        p.state = Bond.BONDING
        s.comparison(phone, 42); s.comparison(phone, 42)
        assertEquals(listOf("000042"), p.keys)
    }
    @Test fun keysNeedSamePhoneLiveRequestAndPositiveAdmission() {
        val p = Fake(); val s = BluetoothPairingSession(p)
        s.comparison(phone, 1); p.state = Bond.UNAVAILABLE; s.request(phone, 2); s.comparison(phone, 2)
        p.state = Bond.BONDED; s.request(phone, 2)
        s.comparison("22:34:56:78:9A:BC", 3); s.comparison(phone, -1); s.comparison(phone, 1000000)
        assertTrue(p.keys.isEmpty())
    }
    @Test fun differentPhoneCannotStealPendingAuthentication() {
        val p = Fake(); val s = BluetoothPairingSession(p)
        s.request(phone, 2); s.request("22:34:56:78:9A:BC", 2)
        assertEquals(MessageStatus.STATUS_BLUETOOTH_PAIRING_DELAYED, p.replies.last().first)
        p.state = Bond.BONDING; s.comparison(phone, 12); assertEquals(listOf("000012"), p.keys)
    }
    @Test fun duplicateRequestDoesNotExtendDeadline() {
        val p = Fake(); val s = BluetoothPairingSession(p); s.request(phone, 2)
        p.time = 119999; s.request(phone, 2)
        p.time = 120000; p.state = Bond.BONDING; s.comparison(phone, 55)
        assertTrue(p.keys.isEmpty())
        p.state = Bond.BONDED; s.request(phone, 2); s.comparison(phone, 55); assertEquals(listOf("000055"), p.keys)
    }
    @Test fun retryWhoseBondQueryCrossesDeadlineOpensFreshWindowOnlyForConfirmedBond() {
        for (bond in Bond.entries) {
            val p = Fake(); val s = BluetoothPairingSession(p); s.request(phone, 2)
            p.time = 119000
            p.state = bond
            p.onBond = { p.time = 121000 }
            s.request(phone, 2)
            s.comparison(phone, 42)
            if (bond == Bond.BONDED) {
                assertEquals(MessageStatus.STATUS_SUCCESS to true, p.replies.last())
                assertEquals(listOf("000042"), p.keys)
                p.time = 241000; s.comparison(phone, 43)
                assertEquals(1, p.keys.size)
            } else {
                assertNotEquals(MessageStatus.STATUS_SUCCESS, p.replies.last().first)
                assertTrue(p.keys.isEmpty())
            }
        }
    }
    @Test fun closeAndAuthenticationFailureRetireKeys() {
        val p = Fake(); val s = BluetoothPairingSession(p); s.request(phone, 2)
        s.authenticationResult(-15); p.state = Bond.BONDING; s.comparison(phone, 1)
        assertTrue(p.keys.isEmpty())
        p.state = Bond.BONDED; s.request(phone, 2); s.close(); s.comparison(phone, 2); s.request(phone, 2)
        assertTrue(p.keys.isEmpty()); assertEquals(2, p.replies.size)
    }
    @Test fun authenticationSuccessIsNotBondCompletion() {
        val p = Fake(); val s = BluetoothPairingSession(p); s.request(phone, 2)
        s.authenticationResult(0); p.state = Bond.BONDING; s.comparison(phone, 7)
        assertEquals(listOf("000007"), p.keys)
        s.bondChanged(phone, Bond.BONDED); s.comparison(phone, 8)
        assertEquals(1, p.keys.size)
    }
    @Test fun falseRequiredFieldAndSignedStatusRoundTrip() {
        val message = Bluetooth.PairingResponse.newBuilder().setStatus(-11).setAlreadyPaired(false).build()
        val decoded = Bluetooth.PairingResponse.parseFrom(message.toByteArray())
        assertTrue(decoded.hasAlreadyPaired()); assertFalse(decoded.alreadyPaired); assertEquals(-11, decoded.status)
        assertFalse(Bluetooth.PairingResponse.newBuilder().setStatus(0).buildPartial().isInitialized)
    }
    @Test fun senderWireExamplesDecode() {
        val request = Bluetooth.PairingRequest.newBuilder().setPhoneAddress(phone).setPairingMethod(2).build()
        assertEquals(request, Bluetooth.PairingRequest.parseFrom(request.toByteArray()))
        val auth = Bluetooth.AuthenticationData.newBuilder().setAuthData("000042").build()
        assertArrayEquals(byteArrayOf(0x0a,6,48,48,48,48,52,50), auth.toByteArray())
        assertEquals(0, Bluetooth.AuthenticationResult.parseFrom(byteArrayOf(8,0)).status)
    }
    @Test fun phoneForgotUsButLocalBondStillExists() {
        for (challengeState in listOf(Bond.BONDED, Bond.BONDING, Bond.NONE)) {
            val p = Fake(); p.state = Bond.BONDED
            val s = BluetoothPairingSession(p); s.request(phone, 2)
            assertEquals(MessageStatus.STATUS_SUCCESS to true, p.replies.single())
            p.state = challengeState; s.comparison(phone, 0)
            assertEquals(listOf("000000"), p.keys)
        }
    }
    @Test fun unrelatedBondEventAndExpiredAttempt() {
        val p = Fake(); val s = BluetoothPairingSession(p); s.request(phone, 2)
        val other = "22:34:56:78:9A:BC"
        s.bondChanged(other, Bond.NONE); s.comparison(phone, 9)
        assertEquals(listOf("000009"), p.keys)
        p.time = 120000; s.request(other, 2); s.comparison(other, 10)
        assertEquals(listOf("000009", "000010"), p.keys)
    }

    @Test fun staleKeyRemovalKeepsWindowButFailedAttemptClosesIt() {
        val p = Fake(); p.state = Bond.BONDED
        val s = BluetoothPairingSession(p); s.request(phone, 2)
        p.state = Bond.NONE; s.bondChanged(phone, Bond.NONE, Bond.BONDED)
        s.comparison(phone, 42); assertEquals(listOf("000042"), p.keys)
        s.bondChanged(phone, Bond.NONE, Bond.BONDING)
        s.comparison(phone, 43); assertEquals(1, p.keys.size)
    }

    @Test fun retryDuringOldKeyCleanupKeepsTheAdmittedWindow() {
        for (state in listOf(Bond.UNAVAILABLE, Bond.NONE, Bond.BONDING)) {
            val p = Fake(); val s = BluetoothPairingSession(p); s.request(phone, 2)
            p.state = state; s.request(phone, 2)
            assertEquals(listOf(MessageStatus.STATUS_SUCCESS to true), p.replies)
            s.comparison(phone, 17)
            assertEquals(listOf("000017"), p.keys)
        }
    }
    @Test fun staleOrSupersededRequestNeverRepliesAfterBondQuery() {
        val p = Fake(); val s = BluetoothPairingSession(p)
        p.time = BluetoothPairingSession.TIMEOUT_MS
        s.request(phone, 2, receivedAt = 0)
        s.request(phone, 2, receivedAt = p.time) { false }
        assertTrue(p.replies.isEmpty()); s.comparison(phone, 1); assertTrue(p.keys.isEmpty())
    }
}
