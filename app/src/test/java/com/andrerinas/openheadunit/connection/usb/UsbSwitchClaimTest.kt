package com.andrerinas.openheadunit.connection.usb

import com.andrerinas.openheadunit.connection.ConnectionArbiter
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy
import com.andrerinas.openheadunit.utils.AppLog
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** The guard the attach fallback reads, owned by the switch's exact arbiter claim. */
class UsbSwitchClaimTest {
    private val oldClock = ConnectionArbiter.clock
    private val oldLogger = AppLog.LOGGER
    private val owners = mutableListOf<ConnectionArbiter.Claim>()

    @Before fun prepare() {
        ConnectionArbiter.reset()
        ConnectionArbiter.clock = { 1000L }
        AppLog.LOGGER = object : AppLog.Logger { override fun println(priority: Int, tag: String, msg: String) = Unit }
    }

    @After fun restore() {
        owners.forEach(UsbSwitchClaim::release)
        ConnectionArbiter.reset()
        ConnectionArbiter.clock = oldClock
        AppLog.LOGGER = oldLogger
    }

    private fun owner() = checkNotNull(ConnectionArbiter.claim(ConnectionPriorityPolicy.Tier.USB,
        ConnectionPriorityPolicy.Owner.USB, "switch")).also(owners::add)

    @Test fun `nothing is claimed until something stakes one`() {
        assertFalse(UsbSwitchClaim.isLive())
    }

    @Test fun `a staked claim is live and a released one is not`() {
        val owner = owner()
        assertTrue(UsbSwitchClaim.stake(owner))
        assertTrue(UsbSwitchClaim.isLive())
        UsbSwitchClaim.release(owner)
        assertFalse(UsbSwitchClaim.isLive())
    }

    @Test fun `release is idempotent`() {
        val owner = owner()
        UsbSwitchClaim.release(owner)
        UsbSwitchClaim.release(owner)
        assertFalse(UsbSwitchClaim.isLive())
    }

    @Test fun `a second stake by one owner still needs only one release`() {
        val owner = owner()
        UsbSwitchClaim.stake(owner)
        UsbSwitchClaim.stake(owner)
        UsbSwitchClaim.release(owner)
        assertFalse(UsbSwitchClaim.isLive())
    }

    @Test fun `a completed switch moves the count once`() {
        val owner = owner()
        UsbSwitchClaim.stake(owner)
        val before = UsbSwitchClaim.switches()
        UsbSwitchClaim.noteSwitched(owner)
        UsbSwitchClaim.noteSwitched(owner)
        assertEquals(before + 1, UsbSwitchClaim.switches())
    }

    @Test fun `a stake alone does not move the count`() {
        val owner = owner()
        val before = UsbSwitchClaim.switches()
        UsbSwitchClaim.stake(owner)
        UsbSwitchClaim.release(owner)
        assertEquals(before, UsbSwitchClaim.switches())
    }

    @Test fun `old finally and completion leave the replacement guard owned`() {
        val old = owner()
        UsbSwitchClaim.stake(old)
        val current = owner()
        UsbSwitchClaim.stake(current)
        UsbSwitchClaim.release(old)
        UsbSwitchClaim.release(old)
        assertTrue(UsbSwitchClaim.isLive())
        UsbSwitchClaim.release(current)
        assertFalse(UsbSwitchClaim.isLive())
    }

    @Test fun `a replaced owner neither refreshes nor counts the new switch`() {
        val old = owner()
        UsbSwitchClaim.stake(old)
        val current = owner()
        UsbSwitchClaim.stake(current)
        val before = UsbSwitchClaim.switches()
        assertFalse(UsbSwitchClaim.stake(old))
        UsbSwitchClaim.noteSwitched(old)
        assertEquals(before, UsbSwitchClaim.switches())
        UsbSwitchClaim.noteSwitched(current)
        assertEquals(before + 1, UsbSwitchClaim.switches())
    }
}
