package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.direct.GroupIdentityStability
import org.junit.Assert.*
import org.junit.Test

class WppCredentialStoreTest {
    private val network = NativeNetworkCredentials("DIRECT-AA", "12345678", "192.168.49.1", "02:11:22:33:44:55")

    @Test fun `replacement carries its own withdrawal generation and identity`() {
        val store = WppCredentialStore()
        store.publish(network)
        val old = store.current
        store.withdraw()
        store.publish(network.copy(identity = GroupIdentityStability.RENAMED))
        val fresh = store.current
        assertEquals(0L, old.withdrawals)
        assertEquals(GroupIdentityStability.UNPROVEN, old.credentials!!.identity)
        assertEquals(1L, fresh.withdrawals)
        assertEquals(GroupIdentityStability.RENAMED, fresh.credentials!!.identity)
    }

    @Test fun `withdrawal after selecting Type 3 remains visible to settling`() {
        val store = WppCredentialStore()
        store.publish(network)
        val sent = store.current
        store.withdraw()
        assertNull(store.current.credentials)
        assertNotEquals(sent.withdrawals, store.current.withdrawals)
        store.publish(network)
        assertNotEquals(sent.withdrawals, store.current.withdrawals)
    }

    @Test fun `ordinary redelivery does not withdraw the advertised network`() {
        val store = WppCredentialStore()
        store.publish(network)
        val sent = store.current
        store.publish(network.copy())
        assertEquals(sent, store.current)
    }
    @Test fun `replacement without a removal callback invalidates the previous handoff`() {
        for (replacement in listOf(network.copy(ssid = "DIRECT-BB"), network.copy(psk = "87654321"),
            network.copy(ip = "192.168.50.1"), network.copy(bssid = "02:11:22:33:44:66"))) {
            val store = WppCredentialStore()
            store.publish(network)
            val sent = store.current
            store.publish(replacement)
            assertEquals(sent.withdrawals + 1, store.current.withdrawals)
        }
    }

    @Test fun `identity reassessment and address spelling keep the network generation`() {
        val store = WppCredentialStore()
        store.publish(network.copy(bssid = "AA:BB:CC:DD:EE:FF"))
        val sent = store.current
        store.publish(network.copy(bssid = "aa:bb:cc:dd:ee:ff", identity = GroupIdentityStability.STABLE))
        assertEquals(sent.withdrawals, store.current.withdrawals)
        assertEquals(GroupIdentityStability.STABLE, store.current.credentials!!.identity)
    }
}
