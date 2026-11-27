package com.andrerinas.openheadunit.connection.carplaynative.lockdown

import com.andrerinas.openheadunit.connection.carplaynative.usb.FakeIphone
import com.andrerinas.openheadunit.connection.carplaynative.usb.IphoneUsbException
import com.andrerinas.openheadunit.connection.carplaynative.usb.IphoneUsbLink
import com.andrerinas.openheadunit.connection.carplaynative.usb.UsbmuxHost
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Both ends use a Conscrypt provider: the JDK parser refuses the empty names of lockdown certificates. The whole lockdown path against the fake iPhone: usbmux, pairing, a TLS session and the carkit service. */
class LockdownClientTest {
    @get:Rule val folder = TemporaryFolder()
    private val realClock: () -> Long = { System.nanoTime() / 1_000_000 }
    private var now = 0L
    private val phone = FakeIphone()
    private lateinit var lockdown: FakeLockdown
    private lateinit var link: IphoneUsbLink
    private lateinit var host: UsbmuxHost
    private lateinit var store: PairRecordStore

    @Before fun setUp() {
        lockdown = FakeLockdown(phone, realClock)
        host = UsbmuxHost({ link.write(it) }, realClock)
        link = IphoneUsbLink(phone, realClock, { host.onBytes(it) }, { host.onLinkEnded(it) })
        link.start()
        host.begin(2000)
        store = PairRecordStore(folder.newFolder("pair"))
    }

    @After fun tearDown() {
        host.close()
        link.close()
        assertTrue("every fake iPhone thread ended", phone.joinThreads(4000))
        assertEquals("no stream stays open on the iPhone", 0, phone.openStreams.get())
    }

    private fun client(stepMs: Long = 3000) = LockdownClient(host, store, { now }, { now += it }, stepMs, engineFor = { LockdownTlsEngineFactory.create(it, FakeLockdown.conscrypt) })

    private fun pairedStream(pendingPairs: Int = 0): ByteArray {
        lockdown.pendingPairs = pendingPairs
        val stream = client().open(SERIAL, "ohu", { false })
        stream.send(byteArrayOf(1, 2, 3))
        val echoed = stream.recv(16, 3000)!!
        stream.close()
        return echoed
    }

    @Test fun aNewiPhonePairsWaitsForTrustThenOpensTheCarkitService() {
        assertArrayEquals(byteArrayOf(1, 2, 3), pairedStream(pendingPairs = 2))
        assertEquals(
            listOf("SetValue", "GetValue", "GetValue", "Pair", "SetValue", "Pair", "SetValue", "Pair", "StartSession", "StartService"),
            lockdown.requests.toList(),
        )
        assertEquals(3, lockdown.pairRequests.get())
        assertTrue("two one-second waits on the fake clock", now >= 2000)
        assertNotNull(store.load(SERIAL))
        assertEquals(1, lockdown.sessionTlsHandshakes.get())
        assertEquals(1, lockdown.serviceTlsHandshakes.get())
    }

    @Test fun aSavedRecordIsReusedAndNoPairGoesOut() {
        pairedStream()
        lockdown.requests.clear()
        val before = lockdown.pairRequests.get()
        assertArrayEquals(byteArrayOf(1, 2, 3), pairedStream())
        assertEquals(before, lockdown.pairRequests.get())
        assertEquals(listOf("StartSession", "StartService"), lockdown.requests.toList())
    }

    @Test fun aRecordTheIphoneRejectsIsReplacedByAFreshPair() {
        pairedStream()
        val old = store.load(SERIAL)!!
        lockdown.knownHostId = "SOMEONE-ELSE"
        lockdown.requests.clear()
        assertArrayEquals(byteArrayOf(1, 2, 3), pairedStream())
        assertTrue("the first StartSession failed, then it paired", lockdown.requests.toList().let { "Pair" in it && it.first() == "StartSession" })
        val fresh = store.load(SERIAL)!!
        assertFalse(old.hostId == fresh.hostId)
        assertEquals(fresh.hostId, lockdown.knownHostId)
    }

    @Test fun trustThatNeverComesEndsAtSixtySecondsAndClosesEverything() {
        lockdown.pendingPairs = -1
        try { client().open(SERIAL, "ohu", { false }); fail() } catch (e: IphoneUsbException.Protocol) {
            assertEquals("CarPlayNative: Trust was not given on the iPhone in 60 s", e.message)
        }
        assertTrue(now >= 60_000)
        assertNull(store.load(SERIAL))
    }

    @Test fun anIphoneThatStopsAnsweringFailsTheStepAtItsTimeout() {
        lockdown.neverAnswer = true
        val started = System.nanoTime()
        try { client(stepMs = 300).open(SERIAL, "ohu", { false }); fail() } catch (_: IphoneUsbException.TimedOut) { }
        assertTrue("the step timeout applies to the request", (System.nanoTime() - started) / 1_000_000 < 2500)
    }

    @Test fun aCancelEndsTheRunAtOnce() {
        try { client().open(SERIAL, "ohu", { true }); fail() } catch (e: IphoneUsbException.Protocol) {
            assertEquals("CarPlayNative: lockdown cancelled", e.message)
        }
    }

    @Test fun aServiceEngineThatCannotBeMadeLeavesNoServiceStreamOpen() {
        pairedStream()
        var engines = 0
        val failing = LockdownClient(host, store, { now }, { now += it }, 3000, engineFor = {
            if (++engines == 2) throw java.security.GeneralSecurityException("no engine")
            LockdownTlsEngineFactory.create(it, FakeLockdown.conscrypt)
        })
        try { failing.open(SERIAL, "ohu", { false }); fail() } catch (_: IphoneUsbException.Protocol) { }
        val deadline = System.nanoTime() + 1_000_000_000L
        while (phone.openStreams.get() > 0 && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals("the service stream was closed, not left to its timeout", 0, phone.openStreams.get())
    }

    @Test fun aSecondOpenWorksAfterTheFirstClosed() {
        assertArrayEquals(byteArrayOf(1, 2, 3), pairedStream())
        assertArrayEquals(byteArrayOf(1, 2, 3), pairedStream())
    }

    private companion object { const val SERIAL = "TESTSERIAL" }
}
