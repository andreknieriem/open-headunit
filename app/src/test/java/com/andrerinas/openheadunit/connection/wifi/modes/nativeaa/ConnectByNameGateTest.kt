package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.ConnectByNameGate.Decision
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.ConnectByNameGate.Refusal
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NamedPhonePolicy.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConnectByNameGateTest {

    private fun decide(
        native: Boolean = true,
        readable: Boolean = true,
        result: Result = Result.Match("AA:00"),
        live: Boolean = false,
        projecting: String? = null,
        onResolve: () -> Unit = {},
    ) = ConnectByNameGate.decide(native, readable, { onResolve(); result }, live, projecting)

    @Test fun `not native refuses without resolving`() {
        var called = false
        assertEquals(Decision.Refuse(Refusal.NOT_NATIVE), decide(native = false, onResolve = { called = true }))
        assertFalse(called)
    }

    @Test fun `no permission refuses without resolving`() {
        var called = false
        assertEquals(Decision.Refuse(Refusal.NO_PERMISSION), decide(readable = false, onResolve = { called = true }))
        assertFalse(called)
    }

    @Test fun `each resolver refusal maps to its reason`() {
        assertEquals(Decision.Refuse(Refusal.NOT_FOUND), decide(result = Result.NotFound))
        assertEquals(Decision.Refuse(Refusal.AMBIGUOUS), decide(result = Result.Ambiguous(listOf("A", "B"))))
        assertEquals(Decision.Refuse(Refusal.NOT_A_PHONE), decide(result = Result.NotAPhone))
        assertEquals(Decision.Refuse(Refusal.NOT_PAIRED), decide(result = Result.NotPaired))
    }

    @Test fun `log texts are the ones the spec names`() {
        assertEquals("not Native AA", Refusal.NOT_NATIVE.logText)
        assertEquals("Bluetooth permission missing", Refusal.NO_PERMISSION.logText)
        assertEquals("no paired phone named", Refusal.NOT_FOUND.logText)
        assertEquals("more than one paired phone named", Refusal.AMBIGUOUS.logText)
        assertEquals("not a phone", Refusal.NOT_A_PHONE.logText)
        assertEquals("not paired", Refusal.NOT_PAIRED.logText)
    }

    @Test fun `a match with no session wakes`() {
        assertEquals(Decision.Wake("AA:00"), decide())
    }

    @Test fun `a live session with the same phone is already connected`() {
        assertEquals(Decision.AlreadyConnected, decide(live = true, projecting = "aa:00"))
    }

    @Test fun `a live session with another phone or an unknown one refuses`() {
        assertEquals(Decision.RefuseLiveSession, decide(live = true, projecting = "BB:00"))
        assertEquals(Decision.RefuseLiveSession, decide(live = true, projecting = null))
    }
    @Test fun `a phone in its handshake or join counts as live, named by its link`() {
        assertEquals(ConnectByNameGate.Live(true, "BB:00"), ConnectByNameGate.live(false, false, false, "AA:00", "BB:00"))
    }

    @Test fun `a connecting socket counts as live with no phone known`() {
        assertEquals(ConnectByNameGate.Live(true, null), ConnectByNameGate.live(false, true, false, "AA:00", null))
    }

    @Test fun `a connected wireless session is named by the last handshake`() {
        assertEquals(ConnectByNameGate.Live(true, "AA:00"), ConnectByNameGate.live(true, true, true, "AA:00", "BB:00"))
    }

    @Test fun `a connected wired session names no phone`() {
        assertEquals(ConnectByNameGate.Live(true, null), ConnectByNameGate.live(true, true, false, "AA:00", null))
    }

    @Test fun `nothing up and no handshake is not live`() {
        assertEquals(ConnectByNameGate.Live(false, null), ConnectByNameGate.live(false, false, false, "AA:00", null))
    }

    @Test fun `the phone in its own handshake is already connected, another is refused`() {
        val same = ConnectByNameGate.live(false, false, false, null, "aa:00")
        assertEquals(Decision.AlreadyConnected, decide(live = same.live, projecting = same.mac))
        val other = ConnectByNameGate.live(false, false, false, null, "BB:00")
        assertEquals(Decision.RefuseLiveSession, decide(live = other.live, projecting = other.mac))
    }
}
