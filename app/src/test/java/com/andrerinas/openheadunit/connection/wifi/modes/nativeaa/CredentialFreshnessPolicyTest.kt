package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.CredentialFreshnessPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class CredentialFreshnessPolicyTest {

    private val captured = NativeNetworkCredentials(
        ssid = "DIRECT-VQ-Navegadortz2",
        psk = "abcd1234",
        ip = "192.168.49.1",
        bssid = "7A:3E:20:5A:12:2F",
    )

    @Test
    fun `an unchanged network is sent as it was captured`() {
        // A distinct instance carrying the same values: the group was never touched.
        val live = NativeNetworkCredentials(captured.ssid, captured.psk, captured.ip, captured.bssid)
        assertEquals(Action.SEND_AS_CAPTURED, CredentialFreshnessPolicy.decide(captured, live))
    }

    @Test
    fun `a network that was taken down is not named at all`() {
        assertEquals(Action.ABORT, CredentialFreshnessPolicy.decide(captured, null))
    }

    @Test
    fun `a moved endpoint makes the whole exchange stale`() {
        // Type 1 already told the phone where to dial, so a new address cannot be patched in here.
        val live = captured.copy(ip = "192.168.49.5")
        assertEquals(Action.ABORT, CredentialFreshnessPolicy.decide(captured, live))
    }

    @Test
    fun `a replaced group is sent as it is now`() {
        assertEquals(Action.SEND_LIVE, CredentialFreshnessPolicy.decide(captured, captured.copy(ssid = "DIRECT-ET-Navegadortz2")))
        assertEquals(Action.SEND_LIVE, CredentialFreshnessPolicy.decide(captured, captured.copy(bssid = "7E:D6:68:AC:67:06")))
        assertEquals(Action.SEND_LIVE, CredentialFreshnessPolicy.decide(captured, captured.copy(psk = "wxyz9876")))
    }
    @Test
    fun `replacement must pass credential checks again`() {
        assertEquals(Action.ABORT, CredentialFreshnessPolicy.decide(captured, captured.copy(psk = "")))
        assertEquals(Action.ABORT, CredentialFreshnessPolicy.decide(captured, captured.copy(ssid = "  ")))
    }
    @Test fun `identity reassessment alone does not substitute the network`() {
        assertEquals(Action.SEND_AS_CAPTURED, CredentialFreshnessPolicy.decide(captured,
            captured.copy(identity = com.andrerinas.openheadunit.connection.wifi.direct.GroupIdentityStability.STABLE)))
    }

    private val advertised = captured.copy(identity = com.andrerinas.openheadunit.connection.wifi.direct.GroupIdentityStability.STABLE)

    @Test fun `a cold start whose Type 4 carried no endpoint takes whatever network becomes ready`() {
        assertEquals(true, CredentialFreshnessPolicy.keepsEndpoint(null, captured))
        assertEquals(Action.SEND_LIVE, CredentialFreshnessPolicy.decide(captured, captured.copy(ssid = "DIRECT-ET-Navegadortz2"), null))
    }

    @Test fun `the advertised network still kept matches its endpoint, whatever the BSSID case`() {
        val redelivered = advertised.copy(bssid = advertised.bssid.lowercase())
        assertEquals(true, CredentialFreshnessPolicy.keepsEndpoint(advertised, redelivered))
        assertEquals(Action.SEND_AS_CAPTURED, CredentialFreshnessPolicy.decide(advertised, redelivered, advertised))
    }

    @Test fun `a same-IP replacement is not sent under an advertised endpoint`() {
        val replaced = advertised.copy(ssid = "DIRECT-ET-Navegadortz2", bssid = "7E:D6:68:AC:67:06")
        assertEquals(false, CredentialFreshnessPolicy.keepsEndpoint(advertised, replaced))
        assertEquals(Action.ABORT, CredentialFreshnessPolicy.decide(advertised, replaced, advertised))
        // Even one judged stable: the endpoint went out for the other network.
        assertEquals(Action.ABORT, CredentialFreshnessPolicy.decide(advertised, replaced.copy(identity = advertised.identity), advertised))
    }

    @Test fun `a network replaced before Type 1 fails the advertised endpoint even on another IP`() {
        assertEquals(false, CredentialFreshnessPolicy.keepsEndpoint(advertised, advertised.copy(ssid = "DIRECT-ET-Navegadortz2", ip = "192.168.49.7")))
        assertEquals(false, CredentialFreshnessPolicy.keepsEndpoint(advertised, null))
    }

    @Test fun `the advertised network re-judged as not to be kept no longer matches its endpoint`() {
        val downgraded = advertised.copy(identity = com.andrerinas.openheadunit.connection.wifi.direct.GroupIdentityStability.UNPROVEN)
        assertEquals(false, CredentialFreshnessPolicy.keepsEndpoint(advertised, downgraded))
        assertEquals(Action.ABORT, CredentialFreshnessPolicy.decide(advertised, downgraded, advertised))
    }
}
