package com.andrerinas.openheadunit.connection.carplaynative.lockdown

import com.andrerinas.openheadunit.connection.carplaynative.codec.PlistValue
import com.andrerinas.openheadunit.connection.carplaynative.lockdown.LockdownSession.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LockdownSessionTest {

    private var made = 0

    private fun record(host: String) = PairRecord(host, "BUID", "aa:bb", byteArrayOf(1), byteArrayOf(2), byteArrayOf(3), byteArrayOf(4), byteArrayOf(5), byteArrayOf(6))

    private fun session(saved: PairRecord? = null) = LockdownSession("OHU", "BUID", saved) { _, _ -> made++; record("NEW-$made") }

    private fun send(actions: List<Action>): Action.Send = actions.filterIsInstance<Action.Send>().single()

    private fun Action.Send.name(): String = (request["Request"] as PlistValue.Text).value

    private fun ok(vararg entries: Pair<String, PlistValue>) = PlistValue.Dict(entries.toList())

    private fun error(code: String) = ok("Error" to PlistValue.Text(code))

    private fun value(v: PlistValue) = ok("Value" to v)

    /** Drives a fresh session to the Pair request and returns the actions the Pair reply gets. */
    private fun toPair(s: LockdownSession, now: Long = 0L): Action.Send {
        assertTrue(s.start().single() is Action.Connect)
        assertEquals("SetValue", send(s.onConnected()).name())
        assertEquals("GetValue", send(s.onReply(ok(), now)).name())
        send(s.onReply(value(PlistValue.Data(byteArrayOf(9))), now))
        return send(s.onReply(value(PlistValue.Text("aa:bb")), now))
    }

    @Test
    fun `a fresh pairing asks in order, then pairs, saves, and starts the session and the carkit service`() {
        val s = session()
        assertTrue(s.start().single() is Action.Connect)
        val untrusted = send(s.onConnected())
        assertEquals(listOf("Label", "Request", "Key", "Value"), untrusted.request.entries.map { it.first })
        assertEquals(PlistValue.Text("UntrustedHostBUID"), untrusted.request["Key"])
        assertEquals(PlistValue.Text("BUID"), untrusted.request["Value"])
        assertFalse(untrusted.secure)
        val key = send(s.onReply(ok(), 0))
        assertEquals(PlistValue.Text("DevicePublicKey"), key.request["Key"])
        val wifi = send(s.onReply(value(PlistValue.Data(byteArrayOf(9))), 0))
        assertEquals(PlistValue.Text("WiFiAddress"), wifi.request["Key"])
        val pair = send(s.onReply(value(PlistValue.Text("aa:bb")), 0))
        assertEquals("Pair", pair.name())

        val done = s.onReply(ok(), 0)
        assertEquals("NEW-1", done.filterIsInstance<Action.SaveRecord>().single().record.hostId)
        assertTrue(done.last() is Action.Connect)
        assertFalse(s.usingSavedRecord)

        val session = send(s.onConnected())
        assertEquals(listOf("Label", "Request", "HostID", "SystemBUID"), session.request.entries.map { it.first })
        assertEquals(PlistValue.Text("NEW-1"), session.request["HostID"])
        val tls = s.onReply(ok("EnableSessionSSL" to PlistValue.Bool(true)), 0).single() as Action.StartTls
        assertEquals("NEW-1", tls.record.hostId)

        val service = send(s.onTlsReady())
        assertTrue(service.secure)
        assertEquals(listOf("Request", "Service"), service.request.entries.map { it.first })
        assertEquals(PlistValue.Text("com.apple.carkit.service"), service.request["Service"])
        val open = s.onReply(ok("Port" to PlistValue.Integer(49152), "EnableServiceSSL" to PlistValue.Bool(true)), 0).single() as Action.OpenService
        assertEquals(49152, open.port)
        assertTrue(open.tls)
        assertTrue(s.finished)
    }

    @Test
    fun `the Pair request carries the keys xcertplay sends`() {
        val pair = toPair(session())
        assertEquals(listOf("Label", "PairRecord", "Request", "ProtocolVersion", "PairingOptions"), pair.request.entries.map { it.first })
        assertEquals(PlistValue.Text("2"), pair.request["ProtocolVersion"])
        assertEquals(PlistValue.dict("ExtendedPairingErrors" to PlistValue.Bool(true)), pair.request["PairingOptions"])
        val nested = pair.request["PairRecord"] as PlistValue.Dict
        assertEquals(listOf("DeviceCertificate", "HostCertificate", "HostID", "RootCertificate", "SystemBUID"), nested.entries.map { it.first })
    }

    @Test
    fun `the Pair request survives the XML plist codec the wire uses`() {
        val request = toPair(session()).request
        assertEquals(request, com.andrerinas.openheadunit.connection.carplaynative.codec.XmlPlist.parse(com.andrerinas.openheadunit.connection.carplaynative.codec.XmlPlist.encode(request)))
    }

    @Test
    fun `a saved record skips every pairing request`() {
        val saved = record("SAVED")
        val s = session(saved)
        assertTrue(s.usingSavedRecord)
        assertTrue(s.start().single() is Action.Connect)
        val request = send(s.onConnected())
        assertEquals("StartSession", request.name())
        assertEquals(PlistValue.Text("SAVED"), request.request["HostID"])
        assertEquals(0, made)
        val tls = s.onReply(ok("EnableSessionSSL" to PlistValue.Bool(true)), 0).single() as Action.StartTls
        assertSame(saved, tls.record)
        assertTrue(s.usingSavedRecord)
    }

    @Test
    fun `the Trust wait logs once, retries after one second on a new connection and keeps the same record`() {
        val s = session()
        toPair(s)
        val first = s.onReply(error("PairingDialogResponsePending"), 1_000)
        assertEquals(listOf("CarPlayNative: waiting for Trust on the iPhone"), first.filterIsInstance<Action.Log>().map { it.line })
        assertTrue(s.tick(1_999).isEmpty())
        assertTrue(s.tick(2_000).single() is Action.Connect)
        assertEquals("SetValue", send(s.onConnected()).name())
        val again = send(s.onReply(ok(), 2_100))
        assertEquals("Pair", again.name())
        assertEquals(1, made)
        val second = s.onReply(error("PairingDialogResponsePending"), 2_200)
        assertTrue(second.none { it is Action.Log })
        assertTrue(s.tick(3_200).single() is Action.Connect)
    }

    @Test
    fun `the Trust wait ends at 60 seconds from the first pending answer`() {
        val s = session()
        toPair(s)
        s.onReply(error("PairingDialogResponsePending"), 5_000)
        assertTrue(s.tick(64_999).single() is Action.Connect)
        val fail = s.tick(65_000).single() as Action.Fail
        assertEquals("CarPlayNative: Trust was not given on the iPhone in 60 s", fail.reason)
        assertTrue(s.finished)
        assertTrue(s.tick(70_000).isEmpty())
    }

    @Test
    fun `a stalled reconnect cannot outlast the Trust bound`() {
        val s = session()
        toPair(s)
        s.onReply(error("PairingDialogResponsePending"), 0)
        assertTrue(s.tick(1_000).single() is Action.Connect)
        s.onConnected()
        assertTrue(s.tick(59_999).isEmpty())
        assertTrue(s.tick(60_000).single() is Action.Fail)
    }

    @Test
    fun `a pending answer after 60 seconds ends the wait at once`() {
        val s = session()
        toPair(s)
        s.onReply(error("PairingDialogResponsePending"), 0)
        s.tick(1_000)
        s.onConnected()
        s.onReply(ok(), 1_100)
        assertTrue(s.onReply(error("PairingDialogResponsePending"), 60_000).single() is Action.Fail)
    }

    @Test
    fun `a refusal on the phone ends the session with its own line`() {
        fun failure(code: String): String {
            val s = session()
            toPair(s)
            return (s.onReply(error(code), 0).single() as Action.Fail).reason
        }
        assertEquals("CarPlayNative: Trust was denied on the iPhone", failure("UserDeniedPairing"))
        assertEquals("CarPlayNative: the iPhone is locked, unlock it to pair", failure("PasswordProtected"))
        assertEquals("CarPlayNative: lockdown Pair failed (SomethingElse)", failure("SomethingElse"))
    }

    @Test
    fun `a rejected saved record is cleared and pairs once, not twice`() {
        val s = session(record("OLD"))
        s.start()
        s.onConnected()
        val actions = s.onReply(error("InvalidHostID"), 0)
        assertTrue(actions[0] is Action.ClearRecord)
        assertEquals("CarPlayNative: saved pair record rejected (InvalidHostID); pairing again", (actions[1] as Action.Log).line)
        assertTrue(actions[2] is Action.Connect)
        assertFalse(s.usingSavedRecord)
        assertEquals("SetValue", send(s.onConnected()).name())
        send(s.onReply(ok(), 0))
        send(s.onReply(value(PlistValue.Data(byteArrayOf(9))), 0))
        assertEquals("Pair", send(s.onReply(value(PlistValue.Text("w")), 0)).name())
        s.onReply(ok(), 0)
        assertEquals("StartSession", send(s.onConnected()).name())
        val second = s.onReply(error("InvalidHostID"), 0)
        assertEquals("CarPlayNative: lockdown StartSession failed (InvalidHostID)", (second.single() as Action.Fail).reason)
        assertEquals(1, made)
    }

    @Test
    fun `InvalidPairRecord also re-pairs, and other errors on a saved record do not`() {
        val a = session(record("OLD")).also { it.start(); it.onConnected() }
        assertTrue(a.onReply(error("InvalidPairRecord"), 0)[0] is Action.ClearRecord)
        val b = session(record("OLD")).also { it.start(); it.onConnected() }
        assertEquals("CarPlayNative: lockdown StartSession failed (PasswordProtected)", (b.onReply(error("PasswordProtected"), 0).single() as Action.Fail).reason)
    }

    @Test
    fun `a record the session just made is never cleared on a rejection`() {
        val s = session()
        toPair(s)
        s.onReply(ok(), 0)
        s.onConnected()
        val out = s.onReply(error("InvalidHostID"), 0)
        assertTrue(out.single() is Action.Fail)
    }

    @Test
    fun `a session without TLS, without a valid port or with a bad error type fails`() {
        fun saved() = session(record("S")).also { it.start(); it.onConnected() }
        assertTrue(saved().onReply(ok("EnableSessionSSL" to PlistValue.Bool(false)), 0).single() is Action.Fail)
        assertTrue(saved().onReply(ok(), 0).single() is Action.Fail)
        val s = saved()
        s.onReply(ok("EnableSessionSSL" to PlistValue.Bool(true)), 0)
        s.onTlsReady()
        assertTrue(s.onReply(ok("Port" to PlistValue.Integer(70000)), 0).single() is Action.Fail)
        val t = saved()
        t.onReply(ok("EnableSessionSSL" to PlistValue.Bool(true)), 0)
        t.onTlsReady()
        assertTrue(t.onReply(ok(), 0).single() is Action.Fail)
    }

    @Test
    fun `an unspecified EnableServiceSSL means plain`() {
        val s = session(record("S")).also { it.start(); it.onConnected() }
        s.onReply(ok("EnableSessionSSL" to PlistValue.Bool(true)), 0)
        s.onTlsReady()
        val open = s.onReply(ok("Port" to PlistValue.Integer(1)), 0).single() as Action.OpenService
        assertFalse(open.tls)
    }

    @Test
    fun `the error code reads text, and an integer through its ErrorString`() {
        assertNull(LockdownSession.errorCode(ok()))
        assertEquals("X", LockdownSession.errorCode(error("X")))
        assertEquals("Boom", LockdownSession.errorCode(ok("Error" to PlistValue.Integer(5), "ErrorString" to PlistValue.Text("Boom"))))
        assertEquals("5", LockdownSession.errorCode(ok("Error" to PlistValue.Integer(5))))
        assertEquals("BadErrorType", LockdownSession.errorCode(ok("Error" to PlistValue.Bool(true))))
    }

    @Test
    fun `a reply in the wrong phase is ignored and a session starts once`() {
        val s = session()
        assertTrue(s.onReply(ok(), 0).isEmpty())
        assertTrue(s.onConnected().isEmpty())
        assertTrue(s.onTlsReady().isEmpty())
        s.start()
        try {
            s.start()
            throw AssertionError("a second start was accepted")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun `no log or failure line carries the host id`() {
        val s = session(record("SECRET-HOST"))
        s.start()
        s.onConnected()
        val lines = s.onReply(error("InvalidHostID"), 0).filterIsInstance<Action.Log>().map { it.line } +
            (s.onConnected().let { s.onReply(ok(), 0); s.onReply(ok(), 0) }.filterIsInstance<Action.Log>().map { it.line })
        assertTrue(lines.none { it.contains("SECRET-HOST") })
    }

    @Test
    fun `a device key the record cannot use fails the session with no record bytes`() {
        val s = LockdownSession("OHU", "BUID", null) { _, _ -> throw IllegalArgumentException("SECRET-KEY") }
        s.start()
        s.onConnected()
        s.onReply(ok(), 0)
        s.onReply(value(PlistValue.Data(byteArrayOf(9))), 0)
        val fail = s.onReply(value(PlistValue.Text("aa:bb")), 0).single() as Action.Fail
        assertEquals("CarPlayNative: lockdown pair record could not be made", fail.reason)
        assertTrue(s.finished)
    }
}
