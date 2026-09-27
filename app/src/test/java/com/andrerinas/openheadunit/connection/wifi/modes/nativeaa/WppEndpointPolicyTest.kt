package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.direct.GroupIdentityStability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WppEndpointPolicyTest {

    @Test
    fun `wifi direct withholds while its identity is unproven, even with the server listening`() {
        val decision = WppEndpointPolicy.decide(NativeStrategy.WIFI_DIRECT, 5299, GroupIdentityStability.UNPROVEN)
        assertTrue(decision is WppEndpointDecision.Withhold)
    }

    @Test
    fun `wifi direct withholds on a unit that re-addresses the group every create`() {
        val decision = WppEndpointPolicy.decide(NativeStrategy.WIFI_DIRECT, 5299, GroupIdentityStability.CHANGED)
        assertTrue(decision is WppEndpointDecision.Withhold)
        assertTrue((decision as WppEndpointDecision.Withhold).reason.contains("new address every time it comes up"))
    }

    @Test
    fun `wifi direct advertises once the identity has repeated`() {
        val decision = WppEndpointPolicy.decide(NativeStrategy.WIFI_DIRECT, 5299, GroupIdentityStability.STABLE)
        assertEquals(WppEndpointDecision.Advertise(5299), decision)
    }

    @Test
    fun `a not-measured verdict on wifi direct is not a pass`() {
        val decision = WppEndpointPolicy.decide(NativeStrategy.WIFI_DIRECT, 5299, GroupIdentityStability.NOT_MEASURED)
        assertTrue(decision is WppEndpointDecision.Withhold)
    }

    @Test
    fun `an access point on its own address advertises`() {
        val decision = WppEndpointPolicy.decide(NativeStrategy.HOTSPOT, 5299, GroupIdentityStability.STABLE)
        assertEquals(WppEndpointDecision.Advertise(5299), decision)
    }

    @Test
    fun `an access point that randomises its address is refused like a group that does`() {
        // Android has randomised soft AP addresses since 10, so the hotspot is no longer exempt.
        val decision = WppEndpointPolicy.decide(NativeStrategy.HOTSPOT, 5299, GroupIdentityStability.CHANGED)
        assertTrue(decision is WppEndpointDecision.Withhold)
        assertTrue((decision as WppEndpointDecision.Withhold).reason.contains("access point"))
    }

    @Test
    fun `hotspot withholds when nothing is listening`() {
        val decision = WppEndpointPolicy.decide(NativeStrategy.HOTSPOT, null, GroupIdentityStability.STABLE)
        assertTrue(decision is WppEndpointDecision.Withhold)
    }

    @Test
    fun `a stable wifi direct group with nothing listening still withholds`() {
        val decision = WppEndpointPolicy.decide(NativeStrategy.WIFI_DIRECT, null, GroupIdentityStability.STABLE)
        assertTrue(decision is WppEndpointDecision.Withhold)
    }

    @Test
    fun `the refusals say different things and each says something`() {
        val unproven = WppEndpointPolicy.decide(NativeStrategy.WIFI_DIRECT, 5299, GroupIdentityStability.UNPROVEN)
        val changed = WppEndpointPolicy.decide(NativeStrategy.WIFI_DIRECT, 5299, GroupIdentityStability.CHANGED)
        val notListening = WppEndpointPolicy.decide(NativeStrategy.HOTSPOT, null, GroupIdentityStability.STABLE)
        val reasons = listOf(unproven, changed, notListening).map { (it as WppEndpointDecision.Withhold).reason }
        assertTrue(reasons.all { it.isNotBlank() })
        assertEquals(3, reasons.toSet().size)
    }

    @Test
    fun `every identity refusal says how to clear an endpoint the phone already holds`() {
        // Measured: withholding stops us creating a record and does nothing to one Android Auto is
        // already carrying, which it dials until it is forgotten.
        for (identity in listOf(GroupIdentityStability.UNPROVEN, GroupIdentityStability.CHANGED)) {
            val reason = (WppEndpointPolicy.decide(NativeStrategy.WIFI_DIRECT, 5299, identity)
                as WppEndpointDecision.Withhold).reason
            assertTrue(identity.name, reason.contains("forget this head unit on the phone"))
        }
    }

    @Test
    fun `the credentials are kept only where our address will still be ours next time`() {
        assertTrue(WppEndpointPolicy.keepsNetwork(GroupIdentityStability.STABLE))
        for (identity in GroupIdentityStability.values().filter { it != GroupIdentityStability.STABLE }) {
            assertFalse(identity.name, WppEndpointPolicy.keepsNetwork(identity))
        }
    }

    @Test
    fun `keeping the network and advertising the endpoint answer the same question`() {
        // Field 5 and field 6 must never disagree: a phone told to keep a network it cannot find
        // again is the poisoning this whole policy exists to avoid.
        for (strategy in NativeStrategy.values()) {
            for (identity in GroupIdentityStability.values()) {
                val advertises = WppEndpointPolicy.decide(strategy, 5299, identity) is WppEndpointDecision.Advertise
                assertEquals("$strategy/$identity", WppEndpointPolicy.keepsNetwork(identity), advertises)
            }
        }
    }

    @Test
    fun `a silent server withholds the endpoint without making the network unkeepable`() {
        // The two are deliberately not the same test: whether anything is listening says nothing
        // about whether our address is the same one next time.
        val identity = GroupIdentityStability.STABLE
        assertTrue(WppEndpointPolicy.decide(NativeStrategy.WIFI_DIRECT, null, identity) is WppEndpointDecision.Withhold)
        assertTrue(WppEndpointPolicy.keepsNetwork(identity))
    }

    @Test
    fun `a port the server reports is advertised verbatim, not the default`() {
        val decision = WppEndpointPolicy.decide(NativeStrategy.HOTSPOT, 41234, GroupIdentityStability.STABLE)
        assertEquals(41234, (decision as WppEndpointDecision.Advertise).port)
    }
}
