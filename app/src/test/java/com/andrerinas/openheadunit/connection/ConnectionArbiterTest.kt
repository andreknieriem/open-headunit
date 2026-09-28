package com.andrerinas.openheadunit.connection

import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy.Owner
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy.Tier
import com.andrerinas.openheadunit.connection.wifi.ServerP2pAttempt
import com.andrerinas.openheadunit.utils.AppLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConnectionArbiterTest {

    private var now = 1_000L
    private var wirelessUp = true
    private val givenBack = mutableListOf<Pair<Boolean, Boolean>>()
    private val timers = mutableListOf<Pair<Long, () -> Unit>>()
    private var savedLogger: AppLog.Logger? = null

    private val actions = object : ConnectionArbiter.Actions {
        override fun preempt(loser: ConnectionArbiter.Claim) = Unit
        override fun standDownWireless(by: ConnectionArbiter.Claim): Boolean {
            val was = wirelessUp
            wirelessUp = false
            return was
        }
        override fun giveBack(wireless: Boolean, usb: Boolean) {
            givenBack += wireless to usb
            if (wireless) wirelessUp = true
        }
        override fun schedule(delayMs: Long, block: () -> Unit) {
            timers += (now + delayMs) to block
        }
    }

    @Before
    fun setUp() {
        savedLogger = AppLog.LOGGER
        AppLog.LOGGER = object : AppLog.Logger {
            override fun println(priority: Int, tag: String, msg: String) = Unit
        }
        ConnectionArbiter.reset()
        ConnectionArbiter.clock = { now }
        ConnectionArbiter.actions = actions
    }

    @After
    fun tearDown() {
        ConnectionArbiter.actions = null
        ConnectionArbiter.reset()
        savedLogger?.let { AppLog.LOGGER = it }
    }

    /** Moves the clock and fires every timer that has come due, in order. */
    private fun advance(ms: Long) {
        now += ms
        while (true) {
            val due = timers.filter { it.first <= now }.minByOrNull { it.first } ?: return
            timers.remove(due)
            due.second()
        }
    }

    private fun usbTry(failAfterMs: Long) {
        val claim = must(ConnectionArbiter.claim(Tier.USB, Owner.USB, "USB"))
        advance(failAfterMs)
        ConnectionArbiter.release(claim, sessionFormed = false)
    }

    private fun <T> must(value: T?): T { assertNotNull(value); return value!! }

    @Test
    fun `a USB retry inside the quiet window keeps wireless down`() {
        usbTry(failAfterMs = 10_000)
        advance(5_500)
        assertTrue(ConnectionArbiter.refusesBackground(userRequested = false))
        usbTry(failAfterMs = 10_000)
        assertTrue(givenBack.isEmpty())
        assertFalse(wirelessUp)
    }

    @Test
    fun `USB going quiet gives wireless back once`() {
        usbTry(failAfterMs = 10_000)
        advance(ConnectionPriorityPolicy.USB_QUIET_MS)
        assertEquals(listOf(true to false), givenBack)
        advance(ConnectionPriorityPolicy.USB_EPISODE_BUDGET_MS)
        assertEquals(1, givenBack.size)
    }

    @Test
    fun `a session formed gives nothing back until it ends`() {
        val claim = must(ConnectionArbiter.claim(Tier.USB, Owner.USB, "USB"))
        ConnectionArbiter.release(claim, sessionFormed = true)
        advance(ConnectionPriorityPolicy.USB_EPISODE_BUDGET_MS)
        assertTrue(givenBack.isEmpty())
        ConnectionArbiter.sessionEnded(wirelessAlreadyRearmed = false, userExit = false)
        assertEquals(listOf(true to false), givenBack)
    }

    @Test
    fun `a plug-in that fails for its whole budget gives wireless back and stops taking it`() {
        repeat(5) {
            usbTry(failAfterMs = 10_000)
            advance(3_000)
        }
        assertTrue(ConnectionArbiter.usbEpisodeSpent())
        assertEquals(listOf(true to false), givenBack)
        assertTrue(wirelessUp)

        usbTry(failAfterMs = 10_000)
        assertTrue(wirelessUp)
        assertFalse(ConnectionArbiter.refusesBackground(userRequested = false))
    }

    @Test
    fun `a spent plug-in ends once USB has been quiet`() {
        repeat(5) {
            usbTry(failAfterMs = 10_000)
            advance(3_000)
        }
        advance(ConnectionPriorityPolicy.USB_QUIET_MS)
        assertFalse(ConnectionArbiter.usbEpisodeSpent())

        usbTry(failAfterMs = 1_000)
        assertFalse(wirelessUp)
    }

    @Test
    fun `a user connect preempting USB gives USB back when it fails`() {
        ConnectionArbiter.claim(Tier.USB, Owner.USB, "USB")
        val user = must(ConnectionArbiter.claim(Tier.USER, Owner.MANUAL, "192.0.2.1:5277"))
        ConnectionArbiter.release(user, sessionFormed = false)
        assertEquals(listOf(true to true), givenBack)
    }

    @Test
    fun `server P2P handshake respects a USB claim`() {
        val usb = must(ConnectionArbiter.claim(Tier.USB, Owner.USB, "USB"))
        assertNull(ConnectionArbiter.claim(Tier.WIRELESS_HANDSHAKE, Owner.WIRELESS_STACK, "Auto/P2P"))
        assertTrue(ConnectionArbiter.holds(usb))
    }

    @Test
    fun `failed server P2P handshake releases its claim and keeps recovery armed`() {
        val attempt = ServerP2pAttempt().apply { begin(true) }
        val p2p = must(ConnectionArbiter.claim(Tier.WIRELESS_HANDSHAKE, Owner.WIRELESS_STACK, "Auto/P2P"))
        ConnectionArbiter.release(p2p, sessionFormed = false)

        assertFalse(ConnectionArbiter.holds(p2p))
        assertFalse(attempt.honorKillOnFailure)
        assertFalse(attempt.failureIsUserExit)
        assertNotNull(ConnectionArbiter.claim(Tier.WIRELESS_HANDSHAKE, Owner.WIRELESS_STACK, "Auto/P2P retry"))
    }

    @Test
    fun `late server P2P release does not release a manual connection that preempted it`() {
        val p2p = must(ConnectionArbiter.claim(Tier.WIRELESS_HANDSHAKE, Owner.WIRELESS_STACK, "Auto/P2P"))
        val user = must(ConnectionArbiter.claim(Tier.USER, Owner.MANUAL, "manual connection"))
        ConnectionArbiter.release(p2p, sessionFormed = false)
        assertTrue(ConnectionArbiter.holds(user))
    }
}
