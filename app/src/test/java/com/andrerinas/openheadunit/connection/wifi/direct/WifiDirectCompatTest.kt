package com.andrerinas.openheadunit.connection.wifi.direct

import android.app.Application
import android.net.wifi.p2p.WifiP2pGroup
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 30])
class WifiDirectCompatTest {

    @Test
    fun `hidden and public getters preserve real IDs for identity and purge consumers`() {
        val group = WifiP2pGroup()
        val setter = WifiP2pGroup::class.java.getMethod("setNetworkId", Int::class.javaPrimitiveType)
        for (id in listOf(-2, -1, 0, 7)) {
            setter.invoke(group, id)
            val actual = WifiDirectCompat.getGroupNetworkId(group)
            assertEquals(id, actual)
            assertEquals(id >= 0, P2pGroupIdentityPolicy.isPersistentNetworkId(actual))
        }
    }
}
