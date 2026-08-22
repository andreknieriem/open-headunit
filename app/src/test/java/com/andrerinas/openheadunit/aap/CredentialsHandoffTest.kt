package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CredentialsHandoffTest {

    private fun creds(ssid: String = "HeadUnitAP") =
        NativeNetworkCredentials(ssid, "passphrase", "192.168.43.1", "00:08:22:37:d1:25")

    @Test
    fun `a listener registered first is called on publish`() {
        val handoff = CredentialsHandoff()
        val seen = mutableListOf<NativeNetworkCredentials>()
        handoff.setListener { seen.add(it) }

        assertTrue(handoff.publish(creds()))

        assertEquals(listOf(creds()), seen)
    }

    @Test
    fun `a listener registered afterwards is given what was published before it existed`() {
        val handoff = CredentialsHandoff()
        val seen = mutableListOf<NativeNetworkCredentials>()

        // The ordering that cost a connection: the transport resolved while the service that owns
        // the listener was still starting.
        assertFalse(handoff.publish(creds()))
        handoff.setListener { seen.add(it) }

        assertEquals(listOf(creds()), seen)
    }

    @Test
    fun `each value is delivered exactly once, whichever order it arrived in`() {
        val handoff = CredentialsHandoff()
        val seen = mutableListOf<NativeNetworkCredentials>()

        handoff.publish(creds("early"))
        handoff.setListener { seen.add(it) }
        handoff.publish(creds("late"))

        assertEquals(listOf(creds("early"), creds("late")), seen)
    }

    @Test
    fun `a second listener does not replay a value the first already took`() {
        val handoff = CredentialsHandoff()
        val first = mutableListOf<NativeNetworkCredentials>()
        val second = mutableListOf<NativeNetworkCredentials>()

        handoff.publish(creds())
        handoff.setListener { first.add(it) }
        handoff.setListener { second.add(it) }

        assertEquals(listOf(creds()), first)
        assertEquals(emptyList<NativeNetworkCredentials>(), second)
    }

    @Test
    fun `only the newest latched value survives`() {
        val handoff = CredentialsHandoff()
        val seen = mutableListOf<NativeNetworkCredentials>()

        // An access point that came up, went down and came back describes one network by the time
        // anyone asks, not three.
        handoff.publish(creds("first"))
        handoff.publish(creds("second"))
        handoff.publish(creds("third"))
        handoff.setListener { seen.add(it) }

        assertEquals(listOf(creds("third")), seen)
    }

    @Test
    fun `clear drops a latched value but keeps the listener`() {
        val handoff = CredentialsHandoff()
        val seen = mutableListOf<NativeNetworkCredentials>()
        handoff.setListener { seen.add(it) }

        // stop() on the transport: the network these describe is going away, but the service holds
        // its listener across every stop and re-arm.
        handoff.publish(creds("stale"))
        seen.clear()
        handoff.clear()
        assertTrue(handoff.publish(creds("fresh")))

        assertEquals(listOf(creds("fresh")), seen)
    }

    @Test
    fun `a value latched before stop is not replayed to the next run`() {
        val handoff = CredentialsHandoff()
        val seen = mutableListOf<NativeNetworkCredentials>()

        assertFalse(handoff.publish(creds("stale")))
        handoff.clear()
        handoff.setListener { seen.add(it) }

        assertEquals(emptyList<NativeNetworkCredentials>(), seen)
    }

    @Test
    fun `a publish racing a registration is delivered exactly once`() {
        // The two callers really are on different threads: the resolve loop runs on IO and the
        // registration on the main thread, which is the whole reason this class exists.
        repeat(200) {
            val handoff = CredentialsHandoff()
            val seen = java.util.Collections.synchronizedList(mutableListOf<NativeNetworkCredentials>())
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)

            val publisher = Thread {
                start.await()
                handoff.publish(creds())
                done.countDown()
            }
            val registrar = Thread {
                start.await()
                handoff.setListener { seen.add(it) }
                done.countDown()
            }
            publisher.start()
            registrar.start()
            start.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))

            assertEquals("delivered ${seen.size} times on iteration $it", 1, seen.size)
        }
    }
}
