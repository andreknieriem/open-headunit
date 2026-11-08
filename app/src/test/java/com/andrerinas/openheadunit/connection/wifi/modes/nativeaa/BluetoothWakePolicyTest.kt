package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class BluetoothWakePolicyTest {

    // --- the targets themselves, pinned ---

    @Test
    fun `the poke targets are the assigned audio gateway service classes`() {
        assertEquals("0000111f-0000-1000-8000-00805f9b34fb", BluetoothWakePolicy.HFP_AG_UUID.toString())
        assertEquals("00001112-0000-1000-8000-00805f9b34fb", BluetoothWakePolicy.HSP_AG_UUID.toString())
    }

    /**
     * The poke is load-bearing on some head units — they never connect without it — so the list may
     * never be emptied to disable it. Standing a poke down is [BluetoothWakePolicy.wakeDecision]'s
     * decision, taken per attempt, and not this list's.
     */
    @Test
    fun `hands-free is tried first, headset second, and neither is ever dropped`() {
        assertEquals(
            listOf(BluetoothWakePolicy.HFP_AG_UUID, BluetoothWakePolicy.HSP_AG_UUID),
            BluetoothWakePolicy.POKE_TARGETS
        )
    }

    @Test
    fun `targets are named for a log reader`() {
        assertEquals("HFP-AG", BluetoothWakePolicy.profileName(BluetoothWakePolicy.HFP_AG_UUID))
        assertEquals("HSP-AG", BluetoothWakePolicy.profileName(BluetoothWakePolicy.HSP_AG_UUID))
    }

    @Test
    fun `an unknown uuid still prints as itself`() {
        val other = java.util.UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66")
        assertEquals(other.toString(), BluetoothWakePolicy.profileName(other))
    }

    // --- the guard: never poke a link we would destroy ---

    private val CONNECTED = BluetoothWakePolicy.HandsFreeLink.CONNECTED
    private val ABSENT = BluetoothWakePolicy.HandsFreeLink.ABSENT
    private val LINK_UNREADABLE = BluetoothWakePolicy.HandsFreeLink.UNREADABLE

    private fun decide(
        client: BluetoothWakePolicy.HandsFreeLink,
        gateway: BluetoothWakePolicy.HandsFreeLink = ABSENT,
        target: BluetoothWakePolicy.TargetLink = BluetoothWakePolicy.TargetLink.UNREADABLE,
        anotherPhones: Boolean = false
    ) = BluetoothWakePolicy.wakeDecision(client, gateway, target, anotherPhones)

    /**
     * The measured failure: a poke that connects takes the phone's one hands-free slot, this unit's
     * own client is dropped 4 ms later, and it does not come back without a Bluetooth adapter cycle.
     */
    @Test
    fun `a live client link to a phone that may be the target is never poked`() {
        val decision = decide(client = CONNECTED, target = BluetoothWakePolicy.TargetLink.CONNECTED)
        assertFalse(decision.poke)
        assertEquals(BluetoothWakePolicy.WakeReason.TARGET_CONNECTED, decision.reason)
    }

    @Test
    fun `no hands-free link means there is nothing to destroy, so poke`() {
        val decision = decide(client = ABSENT, gateway = ABSENT)
        assertTrue(decision.poke)
        assertEquals(BluetoothWakePolicy.WakeReason.NO_LINK, decision.reason)
    }

    /**
     * An adapter that will not report its profiles must not silently disable a mechanism some head
     * units cannot connect without. Those units keep today's behaviour.
     */
    @Test
    fun `an unreadable adapter still pokes`() {
        assertTrue(decide(client = LINK_UNREADABLE, gateway = LINK_UNREADABLE).poke)
        assertTrue(decide(client = LINK_UNREADABLE, gateway = ABSENT).poke)
    }

    @Test
    fun `the three-valued profile read maps onto the three states`() {
        assertEquals(CONNECTED, BluetoothWakePolicy.HandsFreeLink.of(true))
        assertEquals(ABSENT, BluetoothWakePolicy.HandsFreeLink.of(false))
        assertEquals(LINK_UNREADABLE, BluetoothWakePolicy.HandsFreeLink.of(null))
    }

    @Test
    fun `the target's own connection read maps onto three states`() {
        assertEquals(BluetoothWakePolicy.TargetLink.CONNECTED, BluetoothWakePolicy.TargetLink.of(true))
        assertEquals(BluetoothWakePolicy.TargetLink.ABSENT, BluetoothWakePolicy.TargetLink.of(false))
        assertEquals(BluetoothWakePolicy.TargetLink.UNREADABLE, BluetoothWakePolicy.TargetLink.of(null))
    }

    /**
     * The driver-switch case. The hands-free read is adapter-wide, so during a switch it reports the
     * phone being left; standing the incoming phone's wake down for it stranded the whole switch.
     */
    @Test
    fun `a hands-free link that is another phone's does not suppress the poke`() {
        val decision = decide(client = CONNECTED, target = BluetoothWakePolicy.TargetLink.CONNECTED, anotherPhones = true)
        assertTrue(decision.poke)
        assertEquals(BluetoothWakePolicy.WakeReason.SWITCH_TARGET, decision.reason)
    }

    /**
     * The car's case: a head unit that is itself a phone serves the car's kit as the audio gateway.
     * A phone is never on the far end of that role, so the target cannot be the link's holder.
     */
    @Test
    fun `a gateway-role link to the car does not stand the poke down`() {
        for (target in BluetoothWakePolicy.TargetLink.entries) {
            val decision = decide(client = ABSENT, gateway = CONNECTED, target = target)
            assertTrue("target $target", decision.poke)
            assertEquals(BluetoothWakePolicy.WakeReason.GATEWAY_ONLY, decision.reason)
        }
    }

    /** Only a positive "no client link" reading names the gateway as the sole holder. */
    @Test
    fun `an unreadable client role beside a gateway link is judged by the target`() {
        assertFalse(decide(client = LINK_UNREADABLE, gateway = CONNECTED, target = BluetoothWakePolicy.TargetLink.CONNECTED).poke)
        assertTrue(decide(client = LINK_UNREADABLE, gateway = CONNECTED, target = BluetoothWakePolicy.TargetLink.ABSENT).poke)
        assertFalse(decide(client = LINK_UNREADABLE, gateway = CONNECTED, target = BluetoothWakePolicy.TargetLink.UNREADABLE).poke)
    }

    @Test
    fun `a target with no connection at all cannot hold the link, so poke`() {
        val decision = decide(client = CONNECTED, target = BluetoothWakePolicy.TargetLink.ABSENT)
        assertTrue(decision.poke)
        assertEquals(BluetoothWakePolicy.WakeReason.TARGET_ABSENT, decision.reason)
    }

    /** A null from the hidden isConnected() is not absence; the link may still be the target's. */
    @Test
    fun `an unreadable target behind our own client link still stands down`() {
        val decision = decide(client = CONNECTED, target = BluetoothWakePolicy.TargetLink.UNREADABLE)
        assertFalse(decision.poke)
        assertEquals(BluetoothWakePolicy.WakeReason.TARGET_UNREADABLE, decision.reason)
    }

    /** The two stand-down reasons are the only ones; a new reason has to choose out loud. */
    @Test
    fun `only a possibly-own client link ever suppresses the poke`() {
        val suppressed = mutableSetOf<BluetoothWakePolicy.WakeReason>()
        for (client in BluetoothWakePolicy.HandsFreeLink.entries)
            for (gateway in BluetoothWakePolicy.HandsFreeLink.entries)
                for (target in BluetoothWakePolicy.TargetLink.entries)
                    for (another in listOf(true, false)) {
                        val decision = decide(client, gateway, target, another)
                        if (!decision.poke) {
                            suppressed.add(decision.reason)
                            assertTrue("$client/$gateway/$target", client != ABSENT && !another)
                        }
                    }
        assertEquals(
            setOf(BluetoothWakePolicy.WakeReason.TARGET_CONNECTED, BluetoothWakePolicy.WakeReason.TARGET_UNREADABLE),
            suppressed
        )
    }

    // --- pairing: strict about poking, lenient about forgetting ---

    private val BONDED = BluetoothWakePolicy.BondReading.BONDED
    private val NOT_BONDED = BluetoothWakePolicy.BondReading.NOT_BONDED
    private val MALFORMED = BluetoothWakePolicy.BondReading.MALFORMED
    private val UNREADABLE = BluetoothWakePolicy.BondReading.UNREADABLE

    @Test
    fun `only a confirmed pairing may be poked`() {
        assertTrue(BluetoothWakePolicy.mayPoke(BONDED))
        for (reading in BluetoothWakePolicy.BondReading.values().filterNot { it == BONDED }) {
            assertFalse("$reading should not be poked", BluetoothWakePolicy.mayPoke(reading))
        }
    }

    /**
     * The one that matters: `getBondState()` answers BOND_NONE when the Bluetooth service is
     * unavailable, so an adapter that is off looks exactly like a phone the user unpaired. Forgetting
     * is permanent and written through to device-protected storage, so it needs a real answer.
     */
    @Test
    fun `an unreadable state is never forgotten`() {
        assertFalse(BluetoothWakePolicy.shouldForget(UNREADABLE))
    }

    @Test
    fun `a paired device is never forgotten`() {
        assertFalse(BluetoothWakePolicy.shouldForget(BONDED))
    }

    @Test
    fun `a positive not-paired answer is forgotten`() {
        assertTrue(BluetoothWakePolicy.shouldForget(NOT_BONDED))
    }

    /** An address that is not a Bluetooth address can never become one. */
    @Test
    fun `a malformed address is forgotten`() {
        assertTrue(BluetoothWakePolicy.shouldForget(MALFORMED))
    }

    /**
     * The asymmetry is the design: refusing to poke costs a retry seconds later, forgetting costs
     * the user their configured device with nothing to restore it. So nothing may ever be forgotten
     * that was also considered pokeable, and the two rules must never both be lenient.
     */
    @Test
    fun `nothing pokeable is ever forgotten`() {
        for (reading in BluetoothWakePolicy.BondReading.values()) {
            assertFalse(
                "$reading was both poked and forgotten",
                BluetoothWakePolicy.mayPoke(reading) && BluetoothWakePolicy.shouldForget(reading)
            )
        }
    }

    @Test
    fun `only the hands-free record can carry a service level connection`() {
        // Headset speaks AT+CKPD, not BRSF or CIND, so walking it would error its way to a false
        // "established" and then poll a channel that cannot answer.
        assertTrue(BluetoothWakePolicy.carriesServiceLevelConnection(BluetoothWakePolicy.HFP_AG_UUID))
        assertFalse(BluetoothWakePolicy.carriesServiceLevelConnection(BluetoothWakePolicy.HSP_AG_UUID))
        assertFalse(
            BluetoothWakePolicy.carriesServiceLevelConnection(
                UUID.fromString("0000110b-0000-1000-8000-00805f9b34fb")
            )
        )
    }

    @Test
    fun `a link that is not the target's is recorded against this unit`() {
        // Both mean a link is up and it belongs to somebody else. The poke still goes out; what is
        // recorded is that this unit has no hands-free left to give the phone it is waking.
        for (reason in listOf(
            BluetoothWakePolicy.WakeReason.TARGET_ABSENT,
            BluetoothWakePolicy.WakeReason.SWITCH_TARGET,
        )) {
            assertEquals(
                reason.name,
                BluetoothWakePolicy.ForeignLink.RAISE,
                BluetoothWakePolicy.foreignHandsFreeLink(reason)
            )
        }
    }

    @Test
    fun `a target whose Bluetooth is off still records it`() {
        // The case the record is written before the socket for: a bonded phone with its radio off
        // reads as absent, reaches TARGET_ABSENT, and only then does connect() fail.
        assertEquals(
            BluetoothWakePolicy.ForeignLink.RAISE,
            BluetoothWakePolicy.foreignHandsFreeLink(
                BluetoothWakePolicy.wakeDecision(
                    clientRoleLink = BluetoothWakePolicy.HandsFreeLink.CONNECTED,
                    gatewayRoleLink = BluetoothWakePolicy.HandsFreeLink.ABSENT,
                    targetLink = BluetoothWakePolicy.TargetLink.ABSENT
                ).reason
            )
        )
    }

    @Test
    fun `every positive reading about the link retires the record`() {
        for (reason in listOf(
            BluetoothWakePolicy.WakeReason.NO_LINK,
            BluetoothWakePolicy.WakeReason.GATEWAY_ONLY,
            BluetoothWakePolicy.WakeReason.TARGET_CONNECTED,
        )) {
            assertEquals(
                reason.name,
                BluetoothWakePolicy.ForeignLink.CLEAR,
                BluetoothWakePolicy.foreignHandsFreeLink(reason)
            )
        }
    }

    @Test
    fun `an unreadable target neither raises nor retires`() {
        // A question that could not be asked is not a question answered, the same rule the poke
        // guard itself uses: an unreadable target stands the poke down rather than claiming absence.
        assertEquals(
            BluetoothWakePolicy.ForeignLink.UNCHANGED,
            BluetoothWakePolicy.foreignHandsFreeLink(BluetoothWakePolicy.WakeReason.TARGET_UNREADABLE)
        )
    }

    @Test
    fun `only a target that holds the link stands down as a held link`() {
        assertEquals(BluetoothWakePolicy.WakeOutcome.HELD_LINK,
            BluetoothWakePolicy.standDownOutcome(BluetoothWakePolicy.WakeReason.TARGET_CONNECTED))
        for (reason in BluetoothWakePolicy.WakeReason.values()) {
            if (reason == BluetoothWakePolicy.WakeReason.TARGET_CONNECTED) continue
            assertEquals(BluetoothWakePolicy.WakeOutcome.STOOD_DOWN, BluetoothWakePolicy.standDownOutcome(reason))
        }
    }
}
