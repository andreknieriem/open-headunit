package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.DummyVpnPolicy.Owner
import com.andrerinas.openheadunit.aap.DummyVpnPolicy.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DummyVpnPolicyTest {

    // ---------------------------------------------------------------------
    // shouldStop
    // ---------------------------------------------------------------------

    @Test
    fun `the only teardowns that can stop the VPN are the ones named here`() {
        // The reported regression was a wireless re-init taking the VPN with it. There is no
        // Reason for that any more, and this asserts the set stays that small: a new member added
        // without a rule fails here before it can silently inherit one.
        assertEquals(
            setOf(
                Reason.SESSION_ENDED,
                Reason.SELF_MODE_NEVER_CONNECTED,
                Reason.SERVICE_DESTROYED,
            ),
            Reason.entries.toSet()
        )
    }

    @Test
    fun `a VPN we did not start is never stopped`() {
        for (reason in Reason.entries) {
            assertFalse(
                "$reason must not stop a VPN with no owner",
                DummyVpnPolicy.shouldStop(owner = null, reason = reason)
            )
        }
    }

    @Test
    fun `the self mode watchdog stops only a self mode VPN`() {
        // It fires 120s after Self Mode started with no phone. A session VPN is by definition
        // attached to a live connection, so the watchdog must never reach one.
        assertTrue(DummyVpnPolicy.shouldStop(Owner.SELF_MODE, Reason.SELF_MODE_NEVER_CONNECTED))
        assertFalse(DummyVpnPolicy.shouldStop(Owner.SESSION, Reason.SELF_MODE_NEVER_CONNECTED))
    }

    @Test
    fun `the end of a session stops either owner`() {
        for (owner in Owner.entries) {
            assertTrue(DummyVpnPolicy.shouldStop(owner, Reason.SESSION_ENDED))
        }
    }

    @Test
    fun `destroying the service stops anything we own`() {
        for (owner in Owner.entries) {
            assertTrue(DummyVpnPolicy.shouldStop(owner, Reason.SERVICE_DESTROYED))
        }
    }

    // ---------------------------------------------------------------------
    // shouldStartForSession
    // ---------------------------------------------------------------------

    private fun start(
        keepDuringSession: Boolean = true,
        nativeWirelessSession: Boolean = true,
        currentOwner: Owner? = null,
        selfMode: Boolean = false,
        vpnAvailable: Boolean = true,
        alreadyPrepared: Boolean = true,
    ) = DummyVpnPolicy.shouldStartForSession(
        keepDuringSession = keepDuringSession,
        nativeWirelessSession = nativeWirelessSession,
        currentOwner = currentOwner,
        selfMode = selfMode,
        vpnAvailable = vpnAvailable,
        alreadyPrepared = alreadyPrepared,
    )

    @Test
    fun `a session VPN starts only when it was asked for`() {
        assertTrue(start())
        assertFalse("the setting is the trigger", start(keepDuringSession = false))
        assertFalse("self mode brings its own", start(selfMode = true))
        assertFalse("no consent, no VPN", start(alreadyPrepared = false))
        assertFalse("not in this flavour", start(vpnAvailable = false))
        assertFalse("already up", start(currentOwner = Owner.SESSION))
        assertFalse("already up", start(currentOwner = Owner.SELF_MODE))
    }

    @Test
    fun `a session VPN never starts outside a native wireless session`() {
        // The toggle only renders inside the Native AA block, so a user who turns it on and then
        // switches connection mode keeps a preference they can no longer see. Without this gate
        // that preference would put a blackholing tun on a USB session.
        assertFalse(start(nativeWirelessSession = false))
        assertFalse(start(nativeWirelessSession = false, selfMode = true))
        assertFalse(start(nativeWirelessSession = false, alreadyPrepared = false))
    }
}
