package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.direct.GroupIdentityStability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WppTcpServePolicyTest {

    @Test
    fun `an advertised endpoint is one we serve`() {
        assertTrue(WppTcpServePolicy.servesDial(WppEndpointDecision.Advertise(5299)))
    }

    @Test
    fun `a withheld endpoint is one we refuse`() {
        assertFalse(WppTcpServePolicy.servesDial(WppEndpointDecision.Withhold("because")))
    }

    /**
     * The property that matters: serving and advertising are the same decision. A unit that is not
     * safe to be remembered by is not safe to answer, because answering hands out the same name.
     */
    @Test
    fun `serving is the exact complement of withholding, over every real input`() {
        for (strategy in NativeStrategy.values()) {
            for (identity in GroupIdentityStability.values()) {
                for (port in listOf(null, 5299)) {
                    val decision = WppEndpointPolicy.decide(strategy, port, identity)
                    assertEquals(
                        "$strategy/$identity/port=$port",
                        decision is WppEndpointDecision.Advertise,
                        WppTcpServePolicy.servesDial(decision)
                    )
                }
            }
        }
    }

    @Test
    fun `the unit that measured the wedge is refused, and says why`() {
        // D-SAM: below API 29, so the platform names the group and renames it every create.
        val decision = WppEndpointPolicy.decide(
            NativeStrategy.WIFI_DIRECT, 5299, GroupIdentityStability.RENAMED
        )
        assertFalse(WppTcpServePolicy.servesDial(decision))
        assertTrue(WppTcpServePolicy.refusalReason(decision).contains("forget this head unit on the phone"))
    }

    @Test
    fun `our own access point is served while its address is its own`() {
        // It used to be served whatever the verdict said. Android has randomised soft AP addresses
        // since 10, and one that moves strands the phone exactly as a moving group address does.
        val own = WppEndpointPolicy.decide(NativeStrategy.HOTSPOT, 5299, GroupIdentityStability.STABLE)
        assertTrue(WppTcpServePolicy.servesDial(own))
        for (identity in GroupIdentityStability.values().filter { it != GroupIdentityStability.STABLE }) {
            val moving = WppEndpointPolicy.decide(NativeStrategy.HOTSPOT, 5299, identity)
            assertFalse(identity.name, WppTcpServePolicy.servesDial(moving))
        }
    }

    @Test
    fun `every refusal about the phone is told to it while Bluetooth can take it instead`() {
        for (identity in GroupIdentityStability.values()) {
            val decision = WppEndpointPolicy.decide(NativeStrategy.WIFI_DIRECT, 5299, identity)
            if (WppTcpServePolicy.servesDial(decision)) continue
            assertTrue("$identity", WppTcpServePolicy.rejectsDial(decision, canRunRfcomm = true))
        }
    }

    @Test
    fun `a server that is not listening says nothing about the phone`() {
        // stop() drops the listening port, so a dial already past TLS decided the endpoint was
        // withheld and answered a healthy phone with a rejection and the main-screen record. Only
        // on a STABLE unit is the port the reason; on any other the identity answers first, and
        // that refusal is about the phone's record and still worth telling it.
        for (strategy in NativeStrategy.values()) {
            val decision = WppEndpointPolicy.decide(strategy, null, GroupIdentityStability.STABLE)
            assertFalse(strategy.name, WppTcpServePolicy.blamesStaleEndpoint(decision))
            assertFalse(strategy.name, WppTcpServePolicy.rejectsDial(decision, canRunRfcomm = true))
        }
    }

    @Test
    fun `a stale endpoint is still blamed when we are listening and it is the phone's record`() {
        val decision = WppEndpointPolicy.decide(
            NativeStrategy.WIFI_DIRECT, 5299, GroupIdentityStability.CHANGED
        )
        assertTrue(WppTcpServePolicy.blamesStaleEndpoint(decision))
    }

    @Test
    fun `nothing is put on the wire while projection is up`() {
        // A version request would drop the live session, and so would anything else on this socket.
        val decision = WppEndpointPolicy.decide(
            NativeStrategy.WIFI_DIRECT, 5299, GroupIdentityStability.CHANGED
        )
        assertTrue(WppTcpServePolicy.rejectsDial(decision, canRunRfcomm = true, projectionUp = false))
        assertFalse(WppTcpServePolicy.rejectsDial(decision, canRunRfcomm = true, projectionUp = true))
    }

    @Test
    fun `a refusal is swallowed when there is no Bluetooth route to fall back to`() {
        // Withdrawing the endpoint from a phone whose only way back is closed strands it: the
        // stall it has is at least a stall it can retry out of.
        val decision = WppEndpointPolicy.decide(
            NativeStrategy.WIFI_DIRECT, 5299, GroupIdentityStability.CHANGED
        )
        assertFalse(WppTcpServePolicy.rejectsDial(decision, canRunRfcomm = false))
    }

    @Test
    fun `a dial we serve is never rejected, whatever the Bluetooth listeners are doing`() {
        val decision = WppEndpointPolicy.decide(
            NativeStrategy.HOTSPOT, 5299, GroupIdentityStability.STABLE
        )
        assertTrue(WppTcpServePolicy.servesDial(decision))
        assertFalse(WppTcpServePolicy.rejectsDial(decision, canRunRfcomm = true))
        assertFalse(WppTcpServePolicy.rejectsDial(decision, canRunRfcomm = false))
    }

    @Test
    fun `a server that is not listening is not served either`() {
        val decision = WppEndpointPolicy.decide(NativeStrategy.HOTSPOT, null, GroupIdentityStability.STABLE)
        assertFalse(WppTcpServePolicy.servesDial(decision))
    }
}
