package com.andrerinas.openheadunit.connection

import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy.Owner
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy.Tier
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionPriorityPolicyTest {

    private fun decide(incoming: Tier, incomingOwner: Owner, holder: Tier?, holderOwner: Owner?, ageMs: Long = 1_000L) =
        ConnectionPriorityPolicy.decide(incoming, incomingOwner, holder, holderOwner, ageMs)

    @Test
    fun `nothing in flight lets any attempt proceed`() {
        for (tier in Tier.values()) {
            assertEquals(Verdict.PROCEED, decide(tier, Owner.USB, null, null))
        }
    }

    @Test
    fun `USB preempts a wireless handshake`() {
        assertEquals(Verdict.PREEMPT, decide(Tier.USB, Owner.USB, Tier.WIRELESS_HANDSHAKE, Owner.WIRELESS_STACK))
    }

    @Test
    fun `a user request preempts USB and a wireless handshake`() {
        assertEquals(Verdict.PREEMPT, decide(Tier.USER, Owner.MANUAL, Tier.USB, Owner.USB))
        assertEquals(Verdict.PREEMPT, decide(Tier.USER, Owner.MANUAL, Tier.WIRELESS_HANDSHAKE, Owner.WIRELESS_STACK))
    }

    @Test
    fun `a lower tier is refused while a higher one is in flight`() {
        assertEquals(Verdict.REFUSE, decide(Tier.WIRELESS_HANDSHAKE, Owner.WIRELESS_STACK, Tier.USB, Owner.USB))
        assertEquals(Verdict.REFUSE, decide(Tier.USB, Owner.USB, Tier.USER, Owner.MANUAL))
        assertEquals(Verdict.REFUSE, decide(Tier.WIRELESS_HANDSHAKE, Owner.WIRELESS_STACK, Tier.USER, Owner.MANUAL))
    }

    @Test
    fun `the same tier from another owner is refused, first come first served`() {
        assertEquals(Verdict.REFUSE, decide(Tier.USB, Owner.USB, Tier.USB, Owner.MANUAL))
        assertEquals(Verdict.REFUSE, decide(Tier.WIRELESS_HANDSHAKE, Owner.MANUAL, Tier.WIRELESS_HANDSHAKE, Owner.WIRELESS_STACK))
    }

    @Test
    fun `the newest user request wins over an older one`() {
        assertEquals(Verdict.PREEMPT, decide(Tier.USER, Owner.USB, Tier.USER, Owner.MANUAL))
    }

    @Test
    fun `one owner's claims hand over whatever their tiers`() {
        assertEquals(Verdict.PROCEED, decide(Tier.WIRELESS_HANDSHAKE, Owner.USB, Tier.USER, Owner.USB))
        assertEquals(Verdict.PROCEED, decide(Tier.WIRELESS_HANDSHAKE, Owner.WIRELESS_STACK, Tier.WIRELESS_HANDSHAKE, Owner.WIRELESS_STACK))
    }

    @Test
    fun `a stale holder no longer holds anything off`() {
        assertEquals(Verdict.PROCEED, decide(Tier.WIRELESS_HANDSHAKE, Owner.WIRELESS_STACK, Tier.USER, Owner.MANUAL,
            ConnectionPriorityPolicy.STALE_CLAIM_MS))
        assertEquals(Verdict.REFUSE, decide(Tier.WIRELESS_HANDSHAKE, Owner.WIRELESS_STACK, Tier.USER, Owner.MANUAL,
            ConnectionPriorityPolicy.STALE_CLAIM_MS - 1))
    }

    @Test
    fun `only claims from outside the wireless stack stand it down`() {
        assertTrue(ConnectionPriorityPolicy.standsDownWireless(Owner.USB))
        assertTrue(ConnectionPriorityPolicy.standsDownWireless(Owner.MANUAL))
        assertFalse(ConnectionPriorityPolicy.standsDownWireless(Owner.WIRELESS_STACK))
    }

    @Test
    fun `a background bring-up waits for an outside attempt unless the user asked`() {
        assertTrue(ConnectionPriorityPolicy.refusesBackground(outsideHolderInFlight = true, userRequested = false))
        assertFalse(ConnectionPriorityPolicy.refusesBackground(outsideHolderInFlight = true, userRequested = true))
        assertFalse(ConnectionPriorityPolicy.refusesBackground(outsideHolderInFlight = false, userRequested = false))
    }

    @Test
    fun `a spent USB plug-in stops standing wireless down`() {
        assertFalse(ConnectionPriorityPolicy.standsDownWireless(Owner.USB, usbEpisodeSpent = true))
        assertTrue(ConnectionPriorityPolicy.standsDownWireless(Owner.MANUAL, usbEpisodeSpent = true))
    }

    @Test
    fun `a USB plug-in is spent only once its budget has run`() {
        assertFalse(ConnectionPriorityPolicy.usbEpisodeSpent(ConnectionPriorityPolicy.USB_EPISODE_BUDGET_MS - 1))
        assertTrue(ConnectionPriorityPolicy.usbEpisodeSpent(ConnectionPriorityPolicy.USB_EPISODE_BUDGET_MS))
    }

    @Test
    fun `only USB waits out a quiet window before giving back`() {
        assertEquals(ConnectionPriorityPolicy.USB_QUIET_MS, ConnectionPriorityPolicy.giveBackDelayMs(Owner.USB))
        assertEquals(0L, ConnectionPriorityPolicy.giveBackDelayMs(Owner.MANUAL))
        assertEquals(0L, ConnectionPriorityPolicy.giveBackDelayMs(Owner.WIRELESS_STACK))
    }
}
