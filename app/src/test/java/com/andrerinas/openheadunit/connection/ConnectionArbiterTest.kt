package com.andrerinas.openheadunit.connection

import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy.Owner
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy.Tier
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
    fun `acknowledging restored wireless consumes its debt even during a newer claim`() {
        val usb = must(ConnectionArbiter.claim(Tier.USB, Owner.USB, "USB session"))
        ConnectionArbiter.release(usb, sessionFormed = true)
        val wpp = must(ConnectionArbiter.claim(Tier.WIRELESS_HANDSHAKE, Owner.WIRELESS_STACK, "WPP reconnect"))
        assertTrue(ConnectionArbiter.tryRearmWireless())
        assertTrue(ConnectionArbiter.holds(wpp))
        ConnectionArbiter.release(wpp, sessionFormed = true)
        ConnectionArbiter.sessionEnded(wirelessAlreadyRearmed = false, userExit = false)
        assertTrue(givenBack.isEmpty())
    }

    @Test
    fun `a USB claim refuses rearm without erasing its wireless debt`() {
        val usb = must(ConnectionArbiter.claim(Tier.USB, Owner.USB, "new USB"))
        assertFalse(ConnectionArbiter.tryRearmWireless())
        assertTrue(ConnectionArbiter.holds(usb))
        ConnectionArbiter.release(usb, sessionFormed = true)
        ConnectionArbiter.sessionEnded(wirelessAlreadyRearmed = false, userExit = false)
        assertEquals(listOf(true to false), givenBack)
    }

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
    fun `a dropped debt gives nothing back`() {
        val wireless = must(ConnectionArbiter.claim(Tier.WIRELESS_HANDSHAKE, Owner.WIRELESS_STACK, "wireless"))
        ConnectionArbiter.holdUsbCheck()
        ConnectionArbiter.dropUsbDebt()
        ConnectionArbiter.release(wireless, sessionFormed = false)
        advance(60_000)
        assertEquals(emptyList<Pair<Boolean, Boolean>>(), givenBack)
    }

    @Test
    fun `a dropped USB debt still gives the stood-down wireless back`() {
        val usb = must(ConnectionArbiter.claim(Tier.USB, Owner.USB, "USB"))
        ConnectionArbiter.dropUsbDebt()
        ConnectionArbiter.release(usb, sessionFormed = false)
        advance(60_000)
        assertEquals(listOf(true to false), givenBack)
    }

    @Test
    fun `a debt after the drop is owed again`() {
        val first = must(ConnectionArbiter.claim(Tier.WIRELESS_HANDSHAKE, Owner.WIRELESS_STACK, "wireless"))
        ConnectionArbiter.dropUsbDebt()
        ConnectionArbiter.holdUsbCheck()
        ConnectionArbiter.release(first, sessionFormed = false)
        advance(60_000)
        assertEquals(listOf(false to true), givenBack)
    }

    private fun beginBox(aaLive: Boolean = false) = ConnectionArbiter.beginBoxSession("box", aaLive)

    @Test
    fun `every Android Auto claim site is refused while a box session is live`() {
        val old = must(ConnectionArbiter.claim(Tier.USB, Owner.USB, "old USB"))
        advance(ConnectionPriorityPolicy.STALE_CLAIM_MS + 1)
        assertTrue(beginBox())
        val sites = listOf(
            Triple(Tier.USB, Owner.USB, "UsbAttachedActivity"),
            Triple(Tier.USER, Owner.USB, "UsbAttachedActivity user"),
            Triple(Tier.USB, Owner.USB, "UsbLauncherManager.beginAttempt"),
            Triple(Tier.USER, Owner.USB, "CommManager.claimUsb"),
            Triple(Tier.USER, Owner.WIRELESS_STACK, "CommManager.claimSocket"),
            Triple(Tier.USER, Owner.MANUAL, "CommManager.claimSocket manual"),
            Triple(Tier.WIRELESS_HANDSHAKE, Owner.WIRELESS_STACK, "NativeAaHandshakeManager.handleHandshake"),
        )
        for ((tier, owner, what) in sites) assertNull(what, ConnectionArbiter.claim(tier, owner, what))
        assertNull(ConnectionArbiter.claim(Tier.USB, Owner.USB, "CommManager.reconnectUsb", expectedHolder = old))
        ConnectionArbiter.endBoxSession()
        for ((tier, owner, what) in sites) {
            val claim = must(ConnectionArbiter.claim(tier, owner, what))
            ConnectionArbiter.release(claim, sessionFormed = false)
        }
    }

    @Test
    fun `a box session older than 90 seconds still refuses claims`() {
        assertTrue(beginBox())
        advance(120_000)
        assertNull(ConnectionArbiter.claim(Tier.USER, Owner.USB, "USB"))
    }

    @Test
    fun `a refused claim during a box session records no USB debt`() {
        assertTrue(beginBox())
        assertNull(ConnectionArbiter.claim(Tier.USB, Owner.USB, "USB"))
        ConnectionArbiter.endBoxSession()
        advance(60_000)
        assertFalse(givenBack.any { it.second })
    }

    @Test
    fun `a user request does not bring wireless back during a box session`() {
        assertTrue(beginBox())
        assertTrue(ConnectionArbiter.refusesBackground(userRequested = true))
        assertTrue(ConnectionArbiter.refusesBackground(userRequested = false))
        assertFalse(ConnectionArbiter.tryRearmWireless())
    }

    @Test
    fun `yield to user ends nothing during a box session`() {
        assertTrue(beginBox())
        ConnectionArbiter.yieldToUser("WiFi button")
        ConnectionArbiter.endBoxSession()
        assertEquals(listOf(true to false), givenBack)
    }

    @Test
    fun `a box session is refused beside a live Android Auto session`() {
        assertFalse(beginBox(aaLive = true))
        assertTrue(wirelessUp)
    }

    @Test
    fun `a second box session while one is live is refused and stands nothing down`() {
        assertTrue(beginBox())
        wirelessUp = true
        assertFalse(beginBox())
        assertTrue(wirelessUp)
    }

    @Test
    fun `a box session is refused beside a fresh holder and accepted beside a stale one`() {
        val usb = must(ConnectionArbiter.claim(Tier.USB, Owner.USB, "USB"))
        assertFalse(beginBox())
        advance(ConnectionPriorityPolicy.STALE_CLAIM_MS)
        assertTrue(beginBox())
        ConnectionArbiter.release(usb, sessionFormed = false)
    }

    @Test
    fun `ending a box session that stood wireless down gives it back once`() {
        assertTrue(beginBox())
        assertFalse(wirelessUp)
        ConnectionArbiter.endBoxSession()
        ConnectionArbiter.endBoxSession()
        assertEquals(listOf(true to false), givenBack)
    }

    @Test
    fun `ending a box session that stood nothing down gives nothing back`() {
        wirelessUp = false
        assertTrue(beginBox())
        ConnectionArbiter.endBoxSession()
        assertTrue(givenBack.isEmpty())
    }
}
