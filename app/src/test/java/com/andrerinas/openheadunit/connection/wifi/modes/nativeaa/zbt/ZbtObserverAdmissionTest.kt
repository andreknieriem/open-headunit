package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

import android.os.SystemClock
import com.andrerinas.openheadunit.utils.AppLog
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito

/** One OEM client slot is shared by route discovery and the settings diagnostic. */
class ZbtObserverAdmissionTest {
    private lateinit var clock: MockedStatic<SystemClock>
    private var elapsed = 100_000L
    private var logger: AppLog.Logger? = null
    private val openings = mutableListOf<ZbtChannelOpening>()

    @Before fun setup() {
        clock = Mockito.mockStatic(SystemClock::class.java)
        clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { elapsed }
        logger = AppLog.LOGGER
        AppLog.LOGGER = object : AppLog.Logger {
            override fun println(priority: Int, tag: String, msg: String) = Unit
        }
        ZbtDaemonReachability.forget()
    }

    @After fun cleanup() {
        openings.forEach { it.close(); ZbtDaemonReachability.releaseObserver(it) }
        ZbtDaemonReachability.forget()
        clock.close()
        logger?.let { AppLog.LOGGER = it }
    }

    private fun observer(diagnostic: Boolean = false) =
        ZbtDaemonReachability.observerOpening({ true }, diagnostic)!!.also { openings.add(it) }

    @Test fun `manual diagnostic never connects beside another observer`() {
        for (diagnostic in listOf(false, true)) {
            val old = observer(diagnostic)
            val verdict = ZbtProbe.run(socketFactory = { error("second observer created a socket") })
            assertTrue(verdict.contains("Another Bluetooth module check"))
            assertFalse(old.wasCancelled())
            old.close()
            ZbtDaemonReachability.releaseObserver(old)
        }
    }

    @Test fun `discovery retires diagnostic but waits until its caller releases the slot`() {
        val old = observer(diagnostic = true)
        var closes = 0
        old.attach(Closeable {
            closes++
            // Re-entry must not be a second admission while old shutdown is in progress.
            assertThrows(ZbtChannelOpening.ObserverBusy::class.java) {
                ZbtDaemonReachability.observerOpening({ true }, diagnostic = true)
            }
        })
        assertThrows(ZbtChannelOpening.ObserverBusy::class.java) { observer() }
        assertTrue(old.wasCancelled())
        assertEquals(1, closes)
        assertThrows(ZbtChannelOpening.ObserverBusy::class.java) { observer() }
        ZbtDaemonReachability.releaseObserver(old)
        val replacement = observer()
        assertFalse(replacement.wasCancelled())
        // A repeated old finally cannot release the replacement.
        ZbtDaemonReachability.releaseObserver(old)
        assertThrows(ZbtChannelOpening.ObserverBusy::class.java) { observer() }
    }

    @Test fun `busy discovery is bounded and never cached as refusal`() {
        val old = observer(diagnostic = true)
        for (identity in listOf(false, true)) {
            val started = elapsed
            assertFalse(ZbtDaemonReachability.resolve(
                requireIdentity = identity, sleep = { elapsed += it },
                // The passive identity reader joins the registry in its follow-up PR.
                // Exercise resolution's shared admission contract without a real socket here.
                identityDial = { observer(); error("occupied identity slot admitted a second observer") }))
            assertEquals(10_000L, elapsed - started)
            assertNull(ZbtDaemonReachability.cached(requireIdentity = identity))
            assertTrue(old.wasCancelled())
        }
    }

    @Test fun `retiring diagnostic release allows the same discovery to finish`() {
        val old = observer(diagnostic = true)
        var attempts = 0
        val result = ZbtDaemonReachability.resolve(
            dial = {
                attempts++
                val opening = observer()
                opening.close()
                ZbtDaemonReachability.releaseObserver(opening)
                true
            },
            sleep = { elapsed += it; ZbtDaemonReachability.releaseObserver(old) },
        )
        assertTrue(result)
        assertEquals(2, attempts)
        assertTrue(old.wasCancelled())
        assertEquals(true, ZbtDaemonReachability.cached())
    }

    @Test fun `carrier still preempts the single observer and blocks replacement`() {
        val old = observer(diagnostic = true)
        var closed = false
        old.attach(Closeable { closed = true })
        val carrier = ZbtDaemonReachability.claimCarrier()
        try {
            assertTrue(closed)
            assertTrue(old.wasCancelled())
            assertNull(ZbtDaemonReachability.observerOpening { true })
        } finally { carrier.close() }
    }
    @Test fun `observer retirement does not hold the client registry across shutdown`() {
        val old = observer(diagnostic = true)
        var registryAvailable = false
        old.attach(Closeable {
            val accessed = CountDownLatch(1)
            val worker = Thread { ZbtDaemonReachability.carrierWantsClient(); accessed.countDown() }
            worker.start()
            registryAvailable = accessed.await(2, TimeUnit.SECONDS)
            worker.join(2_000)
        })
        assertThrows(ZbtChannelOpening.ObserverBusy::class.java) { observer() }
        assertTrue(registryAvailable)
    }

    @Test fun `cancelled discovery stops waiting for another observer without a cached failure`() {
        observer()
        var running = true
        var waits = 0
        assertFalse(ZbtDaemonReachability.resolve(
            keepTrying = { running }, sleep = { waits++; running = false }))
        assertEquals(1, waits)
        assertNull(ZbtDaemonReachability.cached())
    }

}
